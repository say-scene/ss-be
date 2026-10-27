package com.sayscene;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InferenceController {
    private static final Logger log = LoggerFactory.getLogger(InferenceController.class);
    private final HttpClient client;
    private final ObjectMapper json;
    private final String baseUrl, model, apiKey, mode;
    private final Duration timeout;
    @Value("${inference.reasoning-effort:}")
    private String reasoningEffort = "";

    public InferenceController(@Value("${inference.base-url}") String baseUrl,
            @Value("${inference.model}") String model,
            @Value("${inference.api-key}") String apiKey,
            @Value("${inference.mode}") String mode,
            @Value("${inference.timeout-seconds}") int timeoutSeconds, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model; this.apiKey = apiKey; this.json = json;
        if (!Set.of("mock", "live").contains(mode)) throw new IllegalArgumentException("잘못된 inference.mode");
        this.mode = mode;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }

    // 상태를 만들지 않는 연결 확인 전용 API. 정식 대화 시작 API와 분리한다.
    @PostMapping("/api/v1/demo/cafe")
    public ResponseEntity<?> cafe(@RequestParam(defaultValue = "cafe-order") String scenarioId) {
        var scenario = SCENARIOS.get(scenarioId);
        if (scenario == null) return error(400, "INVALID_SCENARIO", UUID.randomUUID().toString());
        return complete(List.of(
            Map.of("role", "system", "content", systemPrompt(scenario)),
            Map.of("role", "user", "content", scenario.opening())));
    }

    ResponseEntity<?> opening(String scenarioId, String sessionId) {
        return ResponseEntity.ok().body(Map.of("text", OpeningLines.pick(scenarioId, sessionId), "mode", "preset"));
    }

    private static String promptResource(String name) {
        try (var stream = new ClassPathResource("prompts/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) { throw new IllegalStateException("프롬프트 파일을 읽지 못했습니다: " + name, e); }
    }
    private record Scenario(String prompt, String opening) {}
    private static final String COMMON_PROMPT = promptResource("conversation.txt");
    // 새로운 장소·상황은 시나리오 프롬프트와 시작 지시를 등록한다.
    private static final Map<String, Scenario> SCENARIOS = Map.ofEntries(
        Map.entry("cafe-order", new Scenario(promptResource("cafe-order.txt"), "Greet the customer and ask what drink they would like.")),
        Map.entry("restaurant-order", new Scenario(promptResource("restaurant-order.txt"), "Welcome the diner and ask what kind of food they would like.")),
        Map.entry("clothes-shopping", new Scenario(promptResource("clothes-shopping.txt"), "Welcome the shopper and ask what clothing they are looking for.")),
        Map.entry("subway-route", new Scenario(promptResource("subway-route.txt"), "Ask the traveler which station they want to reach.")),
        Map.entry("street-directions", new Scenario(promptResource("street-directions.txt"), "Ask the visitor where they are trying to go.")),
        Map.entry("hotel-checkin", new Scenario(promptResource("hotel-checkin.txt"), "Welcome the guest and ask whether they have a reservation.")),
        Map.entry("market-shopping", new Scenario(promptResource("market-shopping.txt"), "Greet the shopper and ask if they need help finding anything.")),
        Map.entry("pharmacy-visit", new Scenario(promptResource("pharmacy-visit.txt"), "Greet the customer and ask how you can help. Do not diagnose or give specific drugs or dosages; this is language practice.")),
        Map.entry("phone-reservation", new Scenario(promptResource("phone-reservation.txt"), "Answer the call and ask which date the caller would like to book.")),
        Map.entry("infra-interview", new Scenario(promptResource("infra-interview.txt"), "Welcome the candidate and ask them to briefly introduce their infrastructure experience.")));
    static boolean supportsScenario(String id) { return id != null && SCENARIOS.containsKey(id); }
    private static String systemPrompt(Scenario scenario) { return COMMON_PROMPT + "\n\n" + scenario.prompt(); }

    public record ChatMessage(String role, String content) {}
    public record ChatRequest(String scenarioId, List<ChatMessage> messages) {}

    // 로컬 시연용: 전체 이력을 요청으로 전달한다. Redis 세션 저장·복원은 후속 단계다.
    @PostMapping("/api/v1/demo/cafe/reply")
    public ResponseEntity<?> reply(@RequestBody ChatRequest body) {
        return conversation(body, false);
    }

    @PostMapping("/api/v1/demo/cafe/suggestion")
    public ResponseEntity<?> suggestion(@RequestBody ChatRequest body) {
        return conversation(body, true);
    }

    public record TranslationRequest(String sessionId, String messageId, Integer revision, String text) {}

    @PostMapping("/api/v1/translate")
    public ResponseEntity<?> translate(@RequestBody TranslationRequest body) {
        if (body == null || body.sessionId() == null || body.sessionId().isBlank()
                || body.messageId() == null || body.messageId().isBlank()
                || body.revision() == null || body.revision() < 1
                || body.text() == null || body.text().isBlank() || body.text().length() > 4000)
            return error(400, "INVALID_TRANSLATION", UUID.randomUUID().toString());
        var result = complete(List.of(
            Map.of("role", "system", "content", promptResource("translation.txt")),
            Map.of("role", "user", "content", body.text().trim())), false, true);
        if (!result.getStatusCode().is2xxSuccessful()) return result;
        var response = new HashMap<String, Object>();
        ((Map<?, ?>) result.getBody()).forEach((key, value) -> response.put(key.toString(), value));
        response.put("sessionId", body.sessionId());
        response.put("messageId", body.messageId());
        response.put("revision", body.revision());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }

    public record LookupRequest(String expression, String example) {}

    @PostMapping("/api/v1/lookup")
    public ResponseEntity<?> lookup(@RequestBody LookupRequest body) {
        String requestId = UUID.randomUUID().toString();
        if (body == null || body.expression() == null || body.expression().isBlank()
                || body.example() == null || body.example().isBlank()
                || !body.example().toLowerCase(Locale.ROOT).contains(body.expression().trim().toLowerCase(Locale.ROOT)))
            return error(400, "INVALID_LOOKUP", requestId);
        try {
            // 구조화 생성 경로를 공유하되 리포트의 발화 평가 프롬프트는 사용하지 않는다.
            var result = complete(List.of(
                Map.of("role", "system", "content", promptResource("lookup.txt")),
                Map.of("role", "user", "content", json.writeValueAsString(body))), false, false, true);
            if (!result.getStatusCode().is2xxSuccessful()) return result;
            var response = (Map<?, ?>) result.getBody();
            requestId = response.get("requestId").toString();
            var details = json.readTree(response.get("text").toString());
            var output = new HashMap<String, Object>();
            for (var field : List.of("meaningKo", "partOfSpeech", "exampleKo")) {
                if (!details.path(field).isTextual() || details.path(field).asText().isBlank())
                    return error(502, "INVALID_LOOKUP_RESPONSE", requestId);
                output.put(field, details.path(field).asText().trim());
            }
            output.put("expression", body.expression()); output.put("example", body.example());
            output.put("requestId", requestId);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(output);
        } catch (Exception e) {
            log.warn("lookup_failed requestId={} type={}", requestId, e.getClass().getSimpleName());
            return error(502, "INVALID_LOOKUP_RESPONSE", requestId);
        }
    }

    public record ReportMessage(String messageId, Integer revision, String role, String text) {}
    public record ReportRequest(String sessionId, String scenarioId, List<ReportMessage> messages) {}

    @PostMapping("/api/v1/report")
    public ResponseEntity<?> report(@RequestBody ReportRequest body) {
        String requestId = UUID.randomUUID().toString();
        if (body == null || body.sessionId() == null || body.sessionId().isBlank()
                || body.scenarioId() == null || !SCENARIOS.containsKey(body.scenarioId())
                || body.messages() == null || body.messages().isEmpty())
            return error(400, "INVALID_REPORT", requestId);
        var ids = new HashSet<String>();
        var users = new ArrayList<ReportMessage>();
        for (int i = 0; i < body.messages().size(); i++) {
            var message = body.messages().get(i);
            if (message == null || message.messageId() == null || message.messageId().isBlank()
                    || !ids.add(message.messageId()) || message.revision() == null || message.revision() < 1
                    || !(i % 2 == 0 ? "assistant" : "user").equals(message.role())
                    || message.text() == null || message.text().isBlank())
                return error(400, "INVALID_REPORT", requestId);
            if ("user".equals(message.role())) users.add(message);
        }
        if (users.isEmpty()) return error(400, "NO_USER_UTTERANCES", requestId);
        try {
            var result = complete(List.of(
                Map.of("role", "system", "content", promptResource("report.txt")
                    + "\nPractice scenario:\n" + SCENARIOS.get(body.scenarioId()).prompt()),
                Map.of("role", "user", "content", json.writeValueAsString(Map.of(
                    "conversation", body.messages(),
                    "outputTemplate", Map.of("summary", List.of("", ""), "corrections", users.stream().map(u -> Map.of(
                        "messageId", u.messageId(), "explanationKo", "", "betterExpressionEn", "")).toList()))))), false, false, true);
            if (!result.getStatusCode().is2xxSuccessful()) return result;
            var response = (Map<?, ?>) result.getBody();
            requestId = response.get("requestId").toString();
            var review = json.readTree(response.get("text").toString());
            var summary = review.path("summary");
            var corrections = review.path("corrections");
            if (!summary.isArray() || summary.size() < 2 || summary.size() > 3
                    || !corrections.isArray() || corrections.size() != users.size())
                return error(502, "INVALID_REPORT_RESPONSE", requestId);
            var lines = new ArrayList<String>();
            for (var line : summary) {
                if (!line.isTextual() || line.asText().isBlank()) return error(502, "INVALID_REPORT_RESPONSE", requestId);
                lines.add(line.asText().trim());
            }
            var turns = new ArrayList<Map<String, Object>>();
            var correctedIds = new HashSet<String>();
            for (var correction : corrections) {
                var user = users.stream().filter(u -> u.messageId().equals(correction.path("messageId").asText())).findFirst();
                if (user.isEmpty() || !correctedIds.add(user.get().messageId())
                        || !correction.path("explanationKo").isTextual() || correction.path("explanationKo").asText().isBlank()
                        || !correction.path("betterExpressionEn").isTextual() || correction.path("betterExpressionEn").asText().isBlank())
                    return error(502, "INVALID_REPORT_RESPONSE", requestId);
                var source = user.get();
                turns.add(Map.of("messageId", source.messageId(), "revision", source.revision(), "text", source.text(),
                    "explanationKo", correction.path("explanationKo").asText().trim(),
                    "betterExpressionEn", correction.path("betterExpressionEn").asText().trim()));
            }
            // 원문과 revision은 모델 출력이 아닌 확정 스냅샷에서 가져온다.
            turns.sort(Comparator.comparingInt(t -> users.stream().map(ReportMessage::messageId).toList().indexOf(t.get("messageId"))));
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of(
                "sessionId", body.sessionId(), "summary", lines, "turns", turns, "requestId", requestId));
        } catch (Exception e) {
            log.warn("report_failed requestId={} type={}", requestId, e.getClass().getSimpleName());
            return error(502, "INVALID_REPORT_RESPONSE", requestId);
        }
    }

    private ResponseEntity<?> conversation(ChatRequest body, boolean suggestion) {
        String requestId = UUID.randomUUID().toString();
        if (body == null || body.messages() == null || body.messages().isEmpty()
                || body.messages().size() % 2 != (suggestion ? 1 : 0)) return error(400, "INVALID_HISTORY", requestId);
        var scenario = SCENARIOS.get(body.scenarioId() == null ? "cafe-order" : body.scenarioId());
        if (scenario == null) return error(400, "INVALID_SCENARIO", requestId);
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", suggestion
            ? promptResource("suggestion.txt") + "\nPartner scenario (the learner is speaking to this partner, NOT playing this role):\n" + scenario.prompt()
            : systemPrompt(scenario)));
        for (int i = 0; i < body.messages().size(); i++) {
            var message = body.messages().get(i);
            String expected = i % 2 == 0 ? "assistant" : "user";
            if (message == null || !expected.equals(message.role()) || message.content() == null
                    || message.content().isBlank()) return error(400, "INVALID_HISTORY", requestId);
            messages.add(Map.of("role", expected, "content", message.content().trim()));
        }
        if (suggestion) messages.add(Map.of("role", "user", "content", "Suggest one possible learner reply to the partner's last line. Return the JSON object only."));
        return complete(messages, suggestion);
    }

    private ResponseEntity<?> complete(List<Map<String, String>> messages) {
        return complete(messages, false);
    }
    private ResponseEntity<?> complete(List<Map<String, String>> messages, boolean suggestion) {
        return complete(messages, suggestion, false);
    }
    private ResponseEntity<?> complete(List<Map<String, String>> messages, boolean suggestion, boolean translation) {
        return complete(messages, suggestion, translation, false);
    }
    private ResponseEntity<?> complete(List<Map<String, String>> messages, boolean suggestion, boolean translation, boolean structured) {
        String requestId = UUID.randomUUID().toString();
        if (model.isBlank()) return error(503, "INFERENCE_NOT_CONFIGURED", requestId);
        try {
            var payload = new HashMap<String, Object>(Map.of("model", model, "stream", false,
                "max_tokens", structured ? 4096 : translation ? 2048 : suggestion ? 256 : 128, "temperature", (translation || structured) ? 0.2 : 0.7, "top_p", 0.8, "messages", messages));
            if (suggestion || translation || structured) payload.put("response_format", Map.of("type", "json_object"));
            if (!reasoningEffort.isBlank()) payload.put("reasoning_effort", reasoningEffort);
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("inference_failed requestId={} status={}", requestId, response.statusCode());
                return error(502, "INFERENCE_FAILED", requestId);
            }
            var content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) return error(502, "INVALID_INFERENCE_RESPONSE", requestId);
            if (structured && "length".equals(json.readTree(response.body()).path("choices").path(0).path("finish_reason").asText()))
                return error(502, "INCOMPLETE_STRUCTURED_RESPONSE", requestId);
            if (translation) {
                var translated = json.readTree(content.asText()).path("translation");
                var finish = json.readTree(response.body()).path("choices").path(0).path("finish_reason").asText();
                if (!translated.isTextual() || translated.asText().isBlank() || "length".equals(finish))
                    return error(502, "INVALID_TRANSLATION_RESPONSE", requestId);
                return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of(
                    "text", translated.asText().trim(), "mode", mode, "requestId", requestId));
            }
            if (suggestion) {
                var hint = json.readTree(content.asText());
                var sentence = hint.path("sentence");
                var keywords = hint.path("keywords");
                if (!sentence.isTextual() || sentence.asText().isBlank() || sentence.asText().length() > 500
                        || !keywords.isArray() || keywords.isEmpty() || keywords.size() > 5)
                    return error(502, "INVALID_SUGGESTION", requestId);
                var words = new ArrayList<String>();
                for (var keyword : keywords) {
                    if (!keyword.isTextual() || keyword.asText().isBlank() || keyword.asText().length() > 80)
                        return error(502, "INVALID_SUGGESTION", requestId);
                    words.add(keyword.asText().trim());
                }
                return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of(
                    "sentence", sentence.asText().trim(), "keywords", words, "mode", mode, "requestId", requestId));
            }
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("text", content.asText().trim(), "mode", mode, "requestId", requestId));
        } catch (HttpTimeoutException e) {
            log.warn("inference_timeout requestId={}", requestId);
            return error(504, "INFERENCE_TIMEOUT", requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(503, "INFERENCE_INTERRUPTED", requestId);
        } catch (Exception e) {
            // 예외 본문에는 모델 응답·접속 정보가 포함될 수 있어 종류만 기록한다.
            log.warn("inference_failed requestId={} type={}", requestId, e.getClass().getSimpleName());
            return error(502, "INFERENCE_FAILED", requestId);
        }
    }
    private ResponseEntity<?> error(int status, String code, String requestId) {
        return ResponseEntity.status(status).body(Map.of("code", code, "requestId", requestId));
    }
}
