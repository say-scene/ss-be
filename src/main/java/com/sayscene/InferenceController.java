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
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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

    private static String promptResource(String name) {
        try (var stream = new ClassPathResource("prompts/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) { throw new IllegalStateException("프롬프트 파일을 읽지 못했습니다: " + name, e); }
    }
    private record Scenario(String prompt, String opening) {}
    private static final String COMMON_PROMPT = promptResource("conversation.txt");
    // 새로운 장소·상황은 시나리오 프롬프트와 시작 지시를 등록한다.
    private static final Map<String, Scenario> SCENARIOS = Map.of("cafe-order", new Scenario(
        promptResource("cafe-order.txt"), "A customer has just entered the cafe. Greet them and ask what they would like to order."));
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

    private ResponseEntity<?> conversation(ChatRequest body, boolean suggestion) {
        String requestId = UUID.randomUUID().toString();
        if (body == null || body.messages() == null || body.messages().isEmpty()
                || body.messages().size() % 2 != (suggestion ? 1 : 0)) return error(400, "INVALID_HISTORY", requestId);
        var scenario = SCENARIOS.get(body.scenarioId() == null ? "cafe-order" : body.scenarioId());
        if (scenario == null) return error(400, "INVALID_SCENARIO", requestId);
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", suggestion
            ? promptResource("suggestion.txt") + "\nPartner scenario (the learner plays the customer, NOT this role):\n" + scenario.prompt()
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
        String requestId = UUID.randomUUID().toString();
        if (model.isBlank()) return error(503, "INFERENCE_NOT_CONFIGURED", requestId);
        try {
            var payload = new HashMap<String, Object>(Map.of("model", model, "stream", false,
                "max_tokens", suggestion ? 256 : 128, "temperature", 0.7, "top_p", 0.8, "messages", messages));
            if (suggestion) payload.put("response_format", Map.of("type", "json_object"));
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
