package com.sayscene;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.util.*;

final class OpeningLines {
    private static final Map<String, List<String>> LINES = load();
    private static Map<String, List<String>> load() {
        try (var input = new ClassPathResource("prompts/openings.json").getInputStream()) {
            Map<String, List<String>> lines = new ObjectMapper().readValue(input, new TypeReference<>() {});
            lines.forEach((id, values) -> {
                if (values.size() != 10 || new HashSet<>(values).size() != 10 || values.stream().anyMatch(String::isBlank))
                    throw new IllegalStateException("첫 인사 후보를 확인해주세요: " + id);
            });
            return lines;
        } catch (IOException e) { throw new IllegalStateException("첫 인사 파일을 읽지 못했습니다", e); }
    }
    static String pick(String scenarioId, String sessionId) {
        var lines = LINES.get(scenarioId);
        if (lines == null) throw new IllegalArgumentException("지원하지 않는 시나리오");
        // 새 대화의 무작위 UUID를 사용하므로 재시도에서도 같은 후보를 선택한다.
        return lines.get(Math.floorMod(UUID.fromString(sessionId).hashCode(), lines.size()));
    }
}
