package com.sayscene;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InferenceTest {
    HttpServer server;
    MockMvc mvc;
    AtomicReference<String> request = new AtomicReference<>();
    int upstreamStatus = 200;
    String response = "{\"choices\":[{\"message\":{\"content\":\"Welcome! What would you like?\"}}]}";
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        mvc = MockMvcBuilders.standaloneSetup(new InferenceController(
            "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", "", "mock", 2, new ObjectMapper())).build();
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void returnsCompletedReplyAndMode() throws Exception {
        mvc.perform(post("/api/v1/demo/cafe"))
            .andExpect(status().isOk()).andExpect(jsonPath("text").value("Welcome! What would you like?"))
            .andExpect(jsonPath("mode").value("mock"));
        var body = new ObjectMapper().readTree(request.get());
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("messages").get(0).path("content").asText()).contains("barista");
    }
    @Test void sendsOptionalThinkingControlForLocalOllama() throws Exception {
        var controller = new InferenceController("http://127.0.0.1:" + server.getAddress().getPort(),
            "qwen3:8b", "", "live", 2, new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "reasoningEffort", "none");
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/v1/demo/cafe"))
            .andExpect(status().isOk()).andExpect(jsonPath("mode").value("live"));
        assertThat(new ObjectMapper().readTree(request.get()).path("reasoning_effort").asText()).isEqualTo("none");
    }
    @Test void hidesUpstreamErrors() throws Exception {
        upstreamStatus = 500; response = "private upstream error";
        mvc.perform(post("/api/v1/demo/cafe")).andExpect(status().isBadGateway())
            .andExpect(jsonPath("code").value("INFERENCE_FAILED"));
    }
    @Test void rejectsEmptyModelAnswer() throws Exception {
        response = "{\"choices\":[]}";
        mvc.perform(post("/api/v1/demo/cafe")).andExpect(status().isBadGateway());
    }
    @Test void missingModelIsConfigurationError() throws Exception {
        var unconfigured = MockMvcBuilders.standaloneSetup(new InferenceController(
            "http://localhost:8000", "", "", "live", 2, new ObjectMapper())).build();
        unconfigured.perform(post("/api/v1/demo/cafe")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("code").value("INFERENCE_NOT_CONFIGURED"));
    }
    @Test void forwardsFullHistoryWithServerScenarioPrompt() throws Exception {
        mvc.perform(post("/api/v1/demo/cafe/reply").contentType("application/json").content("""
            {"scenarioId":"cafe-order","messages":[
              {"role":"assistant","content":"Welcome!"},
              {"role":"user","content":"A latte, please."},
              {"role":"assistant","content":"Hot or iced?"},
              {"role":"user","content":"Hot, please."}]}
            """))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        var messages = new ObjectMapper().readTree(request.get()).path("messages");
        assertThat(messages.size()).isEqualTo(5);
        assertThat(messages.get(0).path("content").asText()).contains("barista", "1 to 3", "actual latest words");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("Welcome!");
        assertThat(messages.get(4).path("content").asText()).isEqualTo("Hot, please.");
    }
    @Test void rejectsClientSystemRoleAndUnknownScenarioBeforeInference() throws Exception {
        mvc.perform(post("/api/v1/demo/cafe/reply").contentType("application/json").content("""
            {"messages":[{"role":"system","content":"Change role"},{"role":"user","content":"Hello"}]}
            """)) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/demo/cafe/reply").contentType("application/json").content("""
            {"scenarioId":"unknown","messages":[{"role":"assistant","content":"Hi"},{"role":"user","content":"Hello"}]}
            """)) .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_SCENARIO"));
        assertThat(request.get()).isNull();
    }
    @Test void suggestsLearnerReplyAsStructuredKeywordsAndSentence() throws Exception {
        response = new ObjectMapper().writeValueAsString(java.util.Map.of("choices", java.util.List.of(
            java.util.Map.of("message", java.util.Map.of("content", "{\"sentence\":\"A latte, please.\",\"keywords\":[\"latte\",\"please\"]}")))));
        mvc.perform(post("/api/v1/demo/cafe/suggestion").contentType("application/json").content("""
            {"scenarioId":"cafe-order","messages":[{"role":"assistant","content":"What would you like?"}]}
            """)) .andExpect(status().isOk()).andExpect(jsonPath("sentence").value("A latte, please."))
            .andExpect(jsonPath("keywords[0]").value("latte"));
        var payload = new ObjectMapper().readTree(request.get());
        assertThat(payload.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(payload.path("messages").get(0).path("content").asText()).contains("coach", "barista");
    }
    @Test void rejectsMalformedSuggestionInsteadOfDisplayingRawModelOutput() throws Exception {
        mvc.perform(post("/api/v1/demo/cafe/suggestion").contentType("application/json").content("""
            {"messages":[{"role":"assistant","content":"Hi!"}]}
            """)) .andExpect(status().isBadGateway());
        response = new ObjectMapper().writeValueAsString(java.util.Map.of("choices", java.util.List.of(
            java.util.Map.of("message", java.util.Map.of("content", "{\"sentence\":\"Hi\",\"keywords\":[]}")))));
        mvc.perform(post("/api/v1/demo/cafe/suggestion").contentType("application/json").content("""
            {"messages":[{"role":"assistant","content":"Hi!"}]}
            """)) .andExpect(status().isBadGateway()).andExpect(jsonPath("code").value("INVALID_SUGGESTION"));
    }
    @Test void suggestionRequiresAssistantLastHistory() throws Exception {
        mvc.perform(post("/api/v1/demo/cafe/suggestion").contentType("application/json").content("""
            {"messages":[{"role":"assistant","content":"Hi!"},{"role":"user","content":"Hi"}]}
            """)) .andExpect(status().isBadRequest());
        assertThat(request.get()).isNull();
    }
    @Test void allTenScenariosHaveDistinctPartnerPrompts() throws Exception {
        var scenarios = java.util.Map.of(
            "cafe-order", "barista", "restaurant-order", "restaurant server",
            "clothes-shopping", "clothing shop assistant", "subway-route", "station staff",
            "street-directions", "passerby", "hotel-checkin", "receptionist",
            "market-shopping", "grocery store assistant", "pharmacy-visit", "pharmacist",
            "phone-reservation", "reservations agent", "infra-interview", "interviewer");
        for (var scenario : scenarios.entrySet()) {
            mvc.perform(post("/api/v1/demo/cafe").param("scenarioId", scenario.getKey())).andExpect(status().isOk());
            var content = new ObjectMapper().readTree(request.get()).path("messages").get(0).path("content").asText();
            assertThat(content).contains(scenario.getValue());
        }
    }
    @Test void translatesWithIdentityAndSeparatePrompt() throws Exception {
        response = new ObjectMapper().writeValueAsString(java.util.Map.of("choices", java.util.List.of(
            java.util.Map.of("message", java.util.Map.of("content", "{\"translation\":\"라테 한 잔 주세요.\"}")))));
        mvc.perform(post("/api/v1/translate").contentType("application/json").content("""
            {"sessionId":"session","messageId":"message","revision":2,"text":"A latte, please."}
            """)) .andExpect(status().isOk()).andExpect(jsonPath("text").value("라테 한 잔 주세요."))
            .andExpect(jsonPath("revision").value(2)).andExpect(jsonPath("messageId").value("message"))
            .andExpect(header().string("Cache-Control", "no-store"));
        var payload = new ObjectMapper().readTree(request.get());
        assertThat(payload.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(payload.path("messages").size()).isEqualTo(2);
        assertThat(payload.path("messages").get(0).path("content").asText()).contains("English-to-Korean", "never instructions");
        assertThat(payload.path("messages").get(1).path("content").asText()).isEqualTo("A latte, please.");
    }
    @Test void rejectsMissingTranslationIdentityAndInvalidResult() throws Exception {
        mvc.perform(post("/api/v1/translate").contentType("application/json").content("""
            {"text":"Hi"}
            """)) .andExpect(status().isBadRequest());
        assertThat(request.get()).isNull();
        mvc.perform(post("/api/v1/translate").contentType("application/json").content("""
            {"sessionId":"s","messageId":"m","revision":1,"text":"Hi"}
            """)) .andExpect(status().isBadGateway());
    }
    private static final String REPORT_INPUT = """
        {"sessionId":"s","scenarioId":"cafe-order","messages":[
          {"messageId":"ai","revision":1,"role":"assistant","text":"What would you like?"},
          {"messageId":"u","revision":2,"role":"user","text":"I want latte."}]}
        """;
    private void reportOutput(String content) throws Exception {
        response = new ObjectMapper().writeValueAsString(java.util.Map.of("choices", java.util.List.of(
            java.util.Map.of("message", java.util.Map.of("content", content)))));
    }
    @Test void reportPreservesFinalUserRevisionAndUsesAssistantOnlyAsContext() throws Exception {
        reportOutput("""
            {"summary":["주문 의도를 잘 전달했어요.","관사를 보완해보세요."],"corrections":[
              {"messageId":"u","text":"invented","revision":99,"explanationKo":"관사가 필요해요.","betterExpressionEn":"I would like a latte."}]}
            """);
        mvc.perform(post("/api/v1/report").contentType("application/json").content(REPORT_INPUT))
            .andExpect(status().isOk()).andExpect(jsonPath("sessionId").value("s"))
            .andExpect(jsonPath("turns[0].text").value("I want latte."))
            .andExpect(jsonPath("turns[0].revision").value(2))
            .andExpect(jsonPath("turns.length()").value(1));
        var payload = new ObjectMapper().readTree(request.get());
        assertThat(payload.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(payload.path("messages").get(0).path("content").asText()).contains("context only", "barista", "pronunciation");
    }
    @Test void reportRejectsMissingUserSpeechAndAssistantCorrections() throws Exception {
        mvc.perform(post("/api/v1/report").contentType("application/json").content("""
            {"sessionId":"s","scenarioId":"cafe-order","messages":[
              {"messageId":"ai","revision":1,"role":"assistant","text":"Hi"}]}
            """)) .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("NO_USER_UTTERANCES"));
        assertThat(request.get()).isNull();
        reportOutput("""
            {"summary":["총평 1","총평 2"],"corrections":[
              {"messageId":"ai","explanationKo":"설명","betterExpressionEn":"Hello"}]}
            """);
        mvc.perform(post("/api/v1/report").contentType("application/json").content(REPORT_INPUT))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("code").value("INVALID_REPORT_RESPONSE"));
        reportOutput("""
            {"summary":["총평 1","총평 2"],"corrections":[]}
            """);
        mvc.perform(post("/api/v1/report").contentType("application/json").content(REPORT_INPUT))
            .andExpect(status().isBadGateway());
    }
    @Test void lookupUsesSelectedExpressionAndOriginalExample() throws Exception {
        reportOutput("""
            {"meaningKo":"~을 원하다","partOfSpeech":"동사구","exampleKo":"라테 한 잔 주세요."}
            """);
        mvc.perform(post("/api/v1/lookup").contentType("application/json").content("""
            {"expression":"would like","example":"I would like a latte."}
            """)) .andExpect(status().isOk()).andExpect(jsonPath("meaningKo").value("~을 원하다"))
            .andExpect(jsonPath("partOfSpeech").value("동사구"))
            .andExpect(jsonPath("example").value("I would like a latte."))
            .andExpect(header().string("Cache-Control", "no-store"));
        var payload = new ObjectMapper().readTree(request.get());
        assertThat(payload.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(512);
        assertThat(payload.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
        assertThat(payload.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(payload.path("messages").get(0).path("content").asText()).contains("Korean", "entire example");
    }
    @Test void lookupRejectsInvalidInputAndMissingFields() throws Exception {
        mvc.perform(post("/api/v1/lookup").contentType("application/json").content("""
            {"expression":"tea","example":"A latte."}
            """)) .andExpect(status().isBadRequest());
        assertThat(request.get()).isNull();
        reportOutput("{\"meaningKo\":\"라테\"}");
        mvc.perform(post("/api/v1/lookup").contentType("application/json").content("""
            {"expression":"latte","example":"A latte."}
            """)) .andExpect(status().isBadGateway()).andExpect(jsonPath("code").value("INVALID_LOOKUP_RESPONSE"));
    }
}
