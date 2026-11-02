package com.sayscene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous browser telemetry only; never forwards application payloads or request headers. */
@RestController
public class BrowserTelemetryController {
    private static final Logger log = LoggerFactory.getLogger(BrowserTelemetryController.class);
    static final int MAX_BYTES = 128 * 1024, MAX_SPANS = 128, REQUESTS_PER_MINUTE = 60;
    private static final Set<String> ACTIONS = Set.of("speech.turn", "conversation.start", "conversation.resume",
        "conversation.sync", "conversation.turn", "conversation.end", "conversation.reply", "translation",
        "suggestion", "lookup", "report", "demo.cafe", "demo.echo", "api.request");
    private static final Set<String> ROUTES = Set.of("/api/v1/stt", "/api/v1/conversation",
        "/api/v1/conversation/start", "/api/v1/conversation/restore", "/api/v1/conversation/turn",
        "/api/v1/conversation/end", "/api/v1/translate", "/api/v1/lookup", "/api/v1/report",
        "/api/v1/demo/cafe", "/api/v1/demo/cafe/reply", "/api/v1/demo/cafe/suggestion", "/api/v1/demo/echo");
    private static final Set<String> ERRORS = Set.of("request_failed", "aborted", "invalid_response");
    private final ObjectMapper json;
    private final URI endpoint;
    private final boolean disabled;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore inFlight = new Semaphore(4);
    private long windowStart = System.nanoTime();
    private int windowRequests;

    public BrowserTelemetryController(@Value("${browser-telemetry.otlp-endpoint:}") String endpoint, ObjectMapper json) {
        this.json = json;
        this.disabled = endpoint == null || endpoint.isBlank();
        this.endpoint = endpoint(endpoint);
        if (!disabled && this.endpoint == null) log.warn("browser_telemetry_configuration_invalid");
    }

    private static URI endpoint(String value) {
        try {
            URI uri = URI.create(value.trim());
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535)
                && "/v1/traces".equals(uri.getRawPath()) ? uri : null;
        } catch (RuntimeException e) { return null; }
    }

    private synchronized boolean admit() {
        long now = System.nanoTime();
        if (now - windowStart >= TimeUnit.MINUTES.toNanos(1)) { windowStart = now; windowRequests = 0; }
        if (windowRequests >= REQUESTS_PER_MINUTE) return false;
        windowRequests++;
        return true;
    }

    @PostMapping(value = "/api/telemetry/traces", consumes = "application/json")
    public ResponseEntity<Void> collect(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) return response(403);
        if (request.getHeader("Content-Encoding") != null) return response(415);
        if (endpoint == null) return response(disabled ? 204 : 503);
        if (request.getContentLengthLong() > MAX_BYTES) return response(413);
        if (!admit() || !inFlight.tryAcquire()) return response(429);
        CompletableFuture<HttpResponse<Void>> pending = null;
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) return response(413);
            JsonNode input;
            try (var parser = json.createParser(body)) {
                input = json.readTree(parser);
                require(parser.nextToken() == null);
            }
            byte[] safe = json.writeValueAsBytes(sanitize(input));
            var upstream = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(safe)).build();
            pending = client.sendAsync(upstream, HttpResponse.BodyHandlers.discarding());
            int status = pending.get(2, TimeUnit.SECONDS).statusCode();
            return response(status >= 200 && status < 300 ? 204 : 502);
        } catch (IllegalArgumentException | IOException e) {
            return response(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return response(503);
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            return response(503);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            inFlight.release();
        }
    }

    private static ResponseEntity<Void> response(int status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").build();
    }

    // Rebuild, rather than forward, untrusted OTLP. Unknown fields, events, links and errors are discarded.
    private ObjectNode sanitize(JsonNode input) {
        require(input != null && input.isObject());
        ArrayNode spans = json.createArrayNode();
        for (JsonNode resource : array(input.get("resourceSpans"), 8)) {
            for (JsonNode scope : array(resource.get("scopeSpans"), 8)) {
                for (JsonNode span : array(scope.get("spans"), MAX_SPANS)) {
                    require(spans.size() < MAX_SPANS);
                    spans.add(span(span));
                }
            }
        }
        require(!spans.isEmpty());
        ObjectNode result = json.createObjectNode();
        ObjectNode resource = result.putArray("resourceSpans").addObject();
        resource.putObject("resource").putArray("attributes").addObject().put("key", "service.name")
            .putObject("value").put("stringValue", "ss-fe");
        ObjectNode scope = resource.putArray("scopeSpans").addObject();
        scope.putObject("scope").put("name", "sayscene.browser");
        scope.set("spans", spans);
        return result;
    }

    private ObjectNode span(JsonNode input) {
        require(input.isObject());
        int kind = integer(input.get("kind"), 1, 3);
        String name = text(input.get("name"));
        require(kind == 1 && ACTIONS.contains(name) || kind == 3 && clientName(name));
        ObjectNode result = json.createObjectNode();
        result.put("traceId", identifier(input.get("traceId"), 32));
        result.put("spanId", identifier(input.get("spanId"), 16));
        if (input.has("parentSpanId") && !"".equals(input.get("parentSpanId").asText()))
            result.put("parentSpanId", identifier(input.get("parentSpanId"), 16));
        long start = nanos(input.get("startTimeUnixNano")), end = nanos(input.get("endTimeUnixNano"));
        long now = Instant.now().toEpochMilli() * 1_000_000;
        require(end >= start && end - start <= TimeUnit.MINUTES.toNanos(15)
            && start >= now - TimeUnit.DAYS.toNanos(1) && end <= now + TimeUnit.MINUTES.toNanos(5));
        result.put("name", name).put("kind", kind).put("startTimeUnixNano", Long.toString(start))
            .put("endTimeUnixNano", Long.toString(end));
        // Browser spans are locally sampled. Do not accept client-controlled W3C tracestate or flags.
        result.put("flags", 1);
        if (input.has("status")) {
            JsonNode code = input.path("status").get("code");
            result.putObject("status").put("code", code == null ? 0 : integer(code, 0, 2));
        }
        ArrayNode attrs = result.putArray("attributes");
        Set<String> seen = new HashSet<>();
        for (JsonNode attr : array(input.get("attributes"), 32)) {
            String key = text(attr.get("key"));
            JsonNode value = attr.path("value");
            if (!Set.of("session.id", "http.request.method", "http.route", "http.response.status_code", "error.type").contains(key)) continue;
            require(seen.add(key));
            ObjectNode safe = attrs.addObject().put("key", key).putObject("value");
            if ("http.response.status_code".equals(key)) {
                safe.put("intValue", Integer.toString(integerValue(value.get("intValue"), 100, 599)));
            } else {
                String item = text(value.get("stringValue"));
                require(switch (key) {
                    case "session.id" -> uuid(item);
                    case "http.request.method" -> Set.of("GET", "POST").contains(item);
                    case "http.route" -> ROUTES.contains(item);
                    case "error.type" -> ERRORS.contains(item);
                    default -> false;
                });
                safe.put("stringValue", item);
            }
        }
        require(seen.contains("session.id"));
        if (kind == 3) {
            String method = name.substring(0, name.indexOf(' ')), route = name.substring(name.indexOf(' ') + 1);
            require(seen.contains("http.request.method") && seen.contains("http.route"));
            for (JsonNode attr : attrs) {
                if ("http.request.method".equals(attr.path("key").asText())) require(method.equals(attr.path("value").path("stringValue").asText()));
                if ("http.route".equals(attr.path("key").asText())) require(route.equals(attr.path("value").path("stringValue").asText()));
            }
        }
        return result;
    }

    private static boolean clientName(String name) {
        int split = name.indexOf(' ');
        return split > 0 && Set.of("GET", "POST").contains(name.substring(0, split)) && ROUTES.contains(name.substring(split + 1));
    }
    private static ArrayNode array(JsonNode value, int max) {
        require(value != null && value.isArray() && value.size() > 0 && value.size() <= max);
        return (ArrayNode) value;
    }
    private static String text(JsonNode value) {
        require(value != null && value.isTextual());
        return value.textValue();
    }
    private static String identifier(JsonNode value, int length) {
        String id = text(value);
        require(id.length() == length && id.matches("[0-9a-fA-F]+") && !id.matches("0+"));
        return id.toLowerCase(Locale.ROOT);
    }
    private static long nanos(JsonNode value) {
        String text = text(value);
        require(text.matches("[1-9][0-9]{0,18}"));
        try { return Long.parseLong(text); } catch (NumberFormatException e) { throw new IllegalArgumentException(); }
    }
    private static int integer(JsonNode value, int min, int max) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt());
        int number = value.intValue();
        require(number >= min && number <= max);
        return number;
    }
    private static int integerValue(JsonNode value, int min, int max) {
        if (value != null && value.isTextual()) {
            require(value.textValue().matches("[0-9]{3}"));
            int number = Integer.parseInt(value.textValue());
            require(number >= min && number <= max);
            return number;
        }
        return integer(value, min, max);
    }
    private static boolean uuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException e) { return false; }
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(); }
}
