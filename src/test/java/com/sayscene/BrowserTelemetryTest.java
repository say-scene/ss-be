package com.sayscene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BrowserTelemetryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> forwarded = new AtomicReference<>();
    private final AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;
    private BrowserTelemetryController controller;
    private MockMvc mvc;
    private volatile int upstreamStatus = 200;
    private volatile long delayMillis;
    private volatile boolean stallResponseBody;
    private volatile CountDownLatch entered, release;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/traces", exchange -> {
            accepted.incrementAndGet();
            forwarded.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.set(Map.copyOf(exchange.getRequestHeaders()));
            if (entered != null) entered.countDown();
            try {
                if (release != null) release.await(5, TimeUnit.SECONDS);
                boolean stall = stallResponseBody;
                if (stall) exchange.sendResponseHeaders(upstreamStatus, 1);
                if (delayMillis > 0) Thread.sleep(delayMillis);
                if (stall) exchange.getResponseBody().write(0);
                else {
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private");
                    exchange.sendResponseHeaders(upstreamStatus, -1);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        controller = new BrowserTelemetryController(endpoint(), json);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @AfterEach void stop() {
        if (release != null) release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces"; }

    private ObjectNode payload() {
        ObjectNode root = json.createObjectNode();
        ObjectNode resource = root.putArray("resourceSpans").addObject();
        resource.putObject("resource").putArray("attributes").addObject().put("key", "service.name")
            .putObject("value").put("stringValue", "untrusted-service");
        ObjectNode scope = resource.putArray("scopeSpans").addObject();
        scope.putObject("scope").put("name", "untrusted-scope");
        ObjectNode span = scope.putArray("spans").addObject();
        long now = Instant.now().toEpochMilli() * 1_000_000;
        span.put("traceId", "1".repeat(32)).put("spanId", "2".repeat(16)).put("name", "speech.turn")
            .put("kind", 1).put("startTimeUnixNano", Long.toString(now - 100_000_000))
            .put("endTimeUnixNano", Long.toString(now - 50_000_000));
        span.putArray("attributes").addObject().put("key", "session.id")
            .putObject("value").put("stringValue", "2d128c2e-6061-42ad-8d4c-97fc6a6738d9");
        return root;
    }
    private ObjectNode span(ObjectNode root) { return (ObjectNode) root.at("/resourceSpans/0/scopeSpans/0/spans/0"); }
    private ArrayNode spans(ObjectNode root) { return (ArrayNode) root.at("/resourceSpans/0/scopeSpans/0/spans"); }
    private void stringAttr(ObjectNode span, String key, String value) {
        ((ArrayNode) span.path("attributes")).addObject().put("key", key).putObject("value").put("stringValue", value);
    }
    private MockHttpServletRequest request(byte[] content) {
        var request = new MockHttpServletRequest("POST", "/api/telemetry/traces");
        request.setContentType("application/json");
        request.setContent(content);
        return request;
    }
    private int collect(ObjectNode body) throws Exception {
        return mvc.perform(post("/api/telemetry/traces").contentType("application/json")
            .content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus();
    }

    @Test void reconstructsAnonymousOtlpAndNeverForwardsPrivateFieldsOrRequestHeaders() throws Exception {
        ObjectNode body = payload(), span = span(body);
        span.put("parentSpanId", "3".repeat(16)).put("traceState", "private-session");
        span.putObject("status").put("code", 2).put("message", "private utterance");
        span.putArray("events").addObject().put("name", "private audio");
        span.putArray("links").addObject().put("traceId", "4".repeat(32));
        stringAttr(span, "sessionId", "real-session-secret");
        stringAttr(span, "http.request.body", "private utterance");
        body.put("endpoint", "http://127.0.0.1:1/private");
        mvc.perform(post("/api/telemetry/traces").contentType("application/json")
            .header("Cookie", "sayscene_session=real-session-secret")
            .header("Authorization", "Bearer private-token").header("traceparent", "private-context")
            .content(json.writeValueAsBytes(body)))
            .andExpect(status().isNoContent()).andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"));
        JsonNode safe = json.readTree(forwarded.get());
        assertThat(safe.at("/resourceSpans/0/resource/attributes/0/value/stringValue").asText()).isEqualTo("ss-fe");
        assertThat(safe.at("/resourceSpans/0/scopeSpans/0/scope/name").asText()).isEqualTo("sayscene.browser");
        JsonNode safeSpan = safe.at("/resourceSpans/0/scopeSpans/0/spans/0");
        assertThat(safeSpan.path("parentSpanId").asText()).isEqualTo("3".repeat(16));
        assertThat(safeSpan.path("status").path("code").asInt()).isEqualTo(2);
        assertThat(safeSpan.path("attributes").size()).isEqualTo(1);
        assertThat(forwarded.get()).doesNotContain("private", "real-session", "sessionId", "traceState", "events", "links");
        assertThat(headers.get().keySet()).noneMatch(key -> java.util.Set.of("cookie", "authorization", "traceparent").contains(key.toLowerCase()));
    }

    @Test void acceptsKnownClientSpansAndBothOtlpIntegerEncodings() throws Exception {
        ObjectNode body = payload(), span = span(body);
        span.put("kind", 3).put("name", "POST /api/v1/stt");
        stringAttr(span, "http.request.method", "POST");
        stringAttr(span, "http.route", "/api/v1/stt");
        ((ArrayNode) span.path("attributes")).addObject().put("key", "http.response.status_code")
            .putObject("value").put("intValue", "200");
        assertThat(collect(body)).isEqualTo(204);
        ((ObjectNode) span.at("/attributes/3/value")).put("intValue", 422);
        assertThat(collect(body)).isEqualTo(204);
        assertThat(forwarded.get()).contains("422");
    }

    @Test void rejectsInvalidIdsNamesKindsDatesAndMissingCorrelation() throws Exception {
        for (String id : new String[]{"0".repeat(32), "invalid", "x".repeat(32)}) {
            ObjectNode body = payload(); span(body).put("traceId", id);
            assertThat(collect(body)).isEqualTo(400);
        }
        ObjectNode body = payload(); span(body).put("name", "private utterance");
        assertThat(collect(body)).isEqualTo(400);
        body = payload(); span(body).put("kind", 2);
        assertThat(collect(body)).isEqualTo(400);
        body = payload(); span(body).put("startTimeUnixNano", "1");
        assertThat(collect(body)).isEqualTo(400);
        body = payload(); span(body).put("endTimeUnixNano", "9223372036854775808");
        assertThat(collect(body)).isEqualTo(400);
        body = payload(); span(body).putArray("attributes");
        assertThat(collect(body)).isEqualTo(400);
        assertThat(accepted.get()).isZero();
    }

    @Test void rejectsQueryBearingRoutesMismatchedClientNamesAndDuplicateAttributes() throws Exception {
        ObjectNode body = payload(), span = span(body);
        span.put("kind", 3).put("name", "POST /api/v1/stt");
        stringAttr(span, "http.request.method", "POST");
        stringAttr(span, "http.route", "/api/v1/stt?text=private");
        assertThat(collect(body)).isEqualTo(400);
        ((ObjectNode) span.at("/attributes/2/value")).put("stringValue", "/api/v1/translate");
        assertThat(collect(body)).isEqualTo(400);
        body = payload(); stringAttr(span(body), "session.id", "2d128c2e-6061-42ad-8d4c-97fc6a6738d9");
        assertThat(collect(body)).isEqualTo(400);
        body = payload(); stringAttr(span(body), "error.type", "private exception");
        assertThat(collect(body)).isEqualTo(400);
        assertThat(accepted.get()).isZero();
    }

    @Test void boundsSpanCountsAndRejectsMalformedOrTrailingJson() throws Exception {
        ObjectNode body = payload();
        ObjectNode template = span(body).deepCopy();
        for (int i = 0; i < BrowserTelemetryController.MAX_SPANS; i++) spans(body).add(template);
        assertThat(collect(body)).isEqualTo(400);
        for (String invalid : new String[]{"{", "null", "{}", "[]", json.writeValueAsString(payload()) + " {}"})
            mvc.perform(post("/api/telemetry/traces").contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest());
        assertThat(accepted.get()).isZero();
    }

    @Test void boundsDeclaredAndUnknownLengthBodyReads() throws Exception {
        byte[] oversized = new byte[BrowserTelemetryController.MAX_BYTES + 1];
        mvc.perform(post("/api/telemetry/traces").contentType("application/json").content(oversized))
            .andExpect(status().isPayloadTooLarge());
        var chunked = new MockHttpServletRequest("POST", "/api/telemetry/traces") {
            @Override public long getContentLengthLong() { return -1; }
        };
        chunked.setContent(oversized);
        assertThat(controller.collect(chunked).getStatusCode().value()).isEqualTo(413);
        assertThat(accepted.get()).isZero();
    }

    @Test void rejectsUnsupportedTransportAndCrossSiteBrowserRequests() throws Exception {
        String body = json.writeValueAsString(payload());
        mvc.perform(post("/api/telemetry/traces").contentType("application/x-protobuf").content(body))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/telemetry/traces").contentType("application/json").header("Content-Encoding", "gzip").content(body))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/telemetry/traces").contentType("application/json").header("Sec-Fetch-Site", "cross-site").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/telemetry/traces")).andExpect(status().isMethodNotAllowed());
        assertThat(accepted.get()).isZero();
    }

    @Test void blankDestinationDeliberatelyDisablesForwarding() throws Exception {
        for (String endpoint : new String[]{"", " \t\n"}) {
            var disabled = new BrowserTelemetryController(endpoint, json);
            assertThat(disabled.collect(request("not parsed".getBytes(StandardCharsets.UTF_8))).getStatusCode().value()).isEqualTo(204);
        }
        assertThat(accepted.get()).isZero();
    }

    @Test void invalidNonemptyDestinationIsUnavailableWithoutBreakingStartupOrContactingAnUpstream() throws Exception {
        for (String endpoint : new String[]{"not a URI", "file:///v1/traces", endpoint() + "?url=private", "http://user:secret@localhost/v1/traces"}) {
            var unavailable = new BrowserTelemetryController(endpoint, json);
            assertThat(unavailable.collect(request("not parsed".getBytes(StandardCharsets.UTF_8))).getStatusCode().value()).isEqualTo(503);
        }
        assertThat(accepted.get()).isZero();
    }

    @Test void invalidConfigurationLogsOneFixedDiagnosticWithoutItsSecretPerInstance() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BrowserTelemetryController.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var unavailable = new BrowserTelemetryController("http://user:secret@localhost/v1/traces", json);
            for (int i = 0; i < 2; i++)
                assertThat(unavailable.collect(request(new byte[0])).getStatusCode().value()).isEqualTo(503);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo("browser_telemetry_configuration_invalid");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void hidesCollectorFailuresAndDoesNotFollowRedirects() throws Exception {
        upstreamStatus = 500;
        assertThat(collect(payload())).isEqualTo(502);
        upstreamStatus = 302;
        assertThat(collect(payload())).isEqualTo(502);
        assertThat(accepted.get()).isEqualTo(2);
    }

    @Test void timesOutTheWholeUpstreamExchangeAndReleasesItsPermit() throws Exception {
        delayMillis = 3000; stallResponseBody = true;
        long start = System.nanoTime();
        assertThat(collect(payload())).isEqualTo(503);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2800);
        delayMillis = 0;
        assertThat(collect(payload())).isEqualTo(204);
    }

    @Test void capsConcurrentCollectorCallsAndReleasesAdmissionAfterCompletion() throws Exception {
        entered = new CountDownLatch(4); release = new CountDownLatch(1);
        byte[] body = json.writeValueAsBytes(payload());
        var calls = java.util.stream.IntStream.range(0, 4).mapToObj(i -> CompletableFuture.supplyAsync(
            () -> controller.collect(request(body)).getStatusCode().value(), executor)).toList();
        try {
            assertThat(entered.await(1500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(controller.collect(request(body)).getStatusCode().value()).isEqualTo(429);
        } finally { release.countDown(); }
        for (var call : calls) assertThat(call.get(2, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(controller.collect(request(body)).getStatusCode().value()).isEqualTo(204);
    }

    @Test void limitsAdmissionsPerInstanceWithoutAnUnboundedClientMap() throws Exception {
        for (int i = 0; i < BrowserTelemetryController.REQUESTS_PER_MINUTE; i++)
            mvc.perform(post("/api/telemetry/traces").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/telemetry/traces").contentType("application/json").content("{}"))
            .andExpect(status().isTooManyRequests()).andExpect(content().string(""));
        assertThat(accepted.get()).isZero();
    }
}
