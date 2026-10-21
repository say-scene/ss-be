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
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("messages").get(0).path("content").asText()).contains("barista");
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
}
