package com.sayscene;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class EchoTest {
    @Test void returnsSubmittedTextFromBackend() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new EchoController()).build();
        mvc.perform(post("/api/v1/demo/echo").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"안녕 SayScene\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("message").value("안녕 SayScene"))
            .andExpect(jsonPath("source").value("Spring Boot"))
            .andExpect(jsonPath("receivedAt").isNotEmpty());
    }
    @Test void rejectsBlankAndOversizedInput() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new EchoController()).build();
        for (String text : new String[]{" ", "x".repeat(201)}) {
            mvc.perform(post("/api/v1/demo/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + text + "\"}"))
                .andExpect(status().isBadRequest());
        }
    }
}
