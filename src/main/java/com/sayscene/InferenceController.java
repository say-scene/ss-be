package com.sayscene;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
    public ResponseEntity<?> cafe() {
        String requestId = UUID.randomUUID().toString();
        if (model.isBlank()) return error(503, "INFERENCE_NOT_CONFIGURED", requestId);
        try {
            var payload = Map.of("model", model, "stream", false, "max_tokens", 128,
                "messages", List.of(
                    Map.of("role", "system", "content", "You are a friendly cafe barista. Greet a customer in one short English sentence and ask what they would like to order. Return only the spoken sentence."),
                    Map.of("role", "user", "content", "A customer has just entered the cafe. Start the conversation.")));
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
            return ResponseEntity.ok(Map.of("text", content.asText().trim(), "mode", mode, "requestId", requestId));
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
