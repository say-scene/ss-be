package com.sayscene;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestController
public class SttController {
    private final String baseUrl;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    public SttController(@Value("${stt.base-url:http://127.0.0.1:8001}") String baseUrl,
                         @Value("${stt.timeout-seconds:120}") int seconds, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.timeout = Duration.ofSeconds(seconds); this.json = json;
    }
    @PostMapping(value="/api/v1/stt", consumes="multipart/form-data")
    public ResponseEntity<?> transcribe(@RequestParam("audio") MultipartFile audio) {
        String requestId = UUID.randomUUID().toString();
        if (audio.isEmpty()) return error(400,"EMPTY_AUDIO",requestId);
        if (audio.getSize() > 2097152) return error(413,"AUDIO_TOO_LARGE",requestId);
        String type = Optional.ofNullable(audio.getContentType()).orElse("").split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!Set.of("audio/webm","audio/mp4","audio/ogg","audio/wav","audio/x-wav").contains(type))
            return error(415,"UNSUPPORTED_AUDIO",requestId);
        try {
            var payload = Map.of("inputs", List.of(Map.of("name","AUDIO","shape",List.of(1),
                "datatype","BYTES","data",List.of(Base64.getEncoder().encodeToString(audio.getBytes())))));
            var request = HttpRequest.newBuilder(URI.create(baseUrl+"/v2/models/whisper_stt/infer"))
                .timeout(timeout).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response = client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()==422) return error(422,"INVALID_AUDIO",requestId);
            if (response.statusCode()==503) return error(503,"STT_BUSY",requestId);
            if (response.statusCode()!=200) return error(502,"STT_FAILED",requestId);
            var outputs=json.readTree(response.body()).path("outputs");
            for (var output: outputs) {
                if (!output.path("name").asText().equals("TEXT")) continue;
                var text=output.path("data").path(0);
                if (!text.isTextual()) break;
                if (text.asText().isBlank()) return error(422,"NO_SPEECH",requestId);
                return ResponseEntity.ok(Map.of("text",text.asText().trim(),"requestId",requestId));
            }
            return error(502,"INVALID_STT_RESPONSE",requestId);
        } catch (HttpTimeoutException e) { return error(504,"STT_TIMEOUT",requestId);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return error(503,"STT_INTERRUPTED",requestId);
        } catch (Exception e) { return error(502,"STT_FAILED",requestId); }
    }
    private ResponseEntity<?> error(int status,String code,String id) {
        return ResponseEntity.status(status).body(Map.of("code",code,"requestId",id));
    }
}

@RestControllerAdvice(assignableTypes=SttController.class)
class SttUploadErrors {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("code","AUDIO_TOO_LARGE"));
    }
}
