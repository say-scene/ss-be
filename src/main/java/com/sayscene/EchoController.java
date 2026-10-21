package com.sayscene;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class EchoController {
    public record EchoRequest(String message) {}
    // 추론·저장 없이 FE ↔ BE HTTP 왕복만 확인하는 데모용 API.
    @PostMapping("/api/v1/demo/echo")
    public ResponseEntity<?> echo(@RequestBody EchoRequest input) {
        if (input.message() == null || input.message().isBlank() || input.message().length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_MESSAGE"));
        }
        return ResponseEntity.ok(Map.of("message", input.message(), "source", "Spring Boot",
            "receivedAt", Instant.now().toString()));
    }
}
