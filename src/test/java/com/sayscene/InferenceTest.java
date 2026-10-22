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
}
