package com.sayscene;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SttTest {
    HttpServer server; MockMvc mvc;
    AtomicReference<String> body = new AtomicReference<>();
    String output = "I'd like a latte, please.";
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/models/whisper_stt/infer", e -> {
            body.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = ("{\"outputs\":[{\"name\":\"TEXT\",\"data\":[\""+output+"\"]}]}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200,data.length); e.getResponseBody().write(data); e.close();
        });server.start();
        mvc=MockMvcBuilders.standaloneSetup(new SttController("http://127.0.0.1:"+server.getAddress().getPort(),2,new ObjectMapper())).build();
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void forwardsAudioAndReturnsTranscript() throws Exception {
        mvc.perform(multipart("/api/v1/stt").file(new MockMultipartFile("audio","voice.webm","audio/webm",new byte[]{1,2,3})))
            .andExpect(status().isOk()).andExpect(jsonPath("text").value(output));
        var audio=new ObjectMapper().readTree(body.get()).path("inputs").get(0).path("data").get(0).asText();
        assertThat(Base64.getDecoder().decode(audio)).containsExactly((byte)1,(byte)2,(byte)3);
    }
    @Test void rejectsUnsupportedEmptyAndLargeUploads() throws Exception {
        mvc.perform(multipart("/api/v1/stt").file(new MockMultipartFile("audio","x","text/plain",new byte[]{1}))).andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/v1/stt").file(new MockMultipartFile("audio","x","audio/webm",new byte[0]))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/stt").file(new MockMultipartFile("audio","x","audio/webm",new byte[2097153]))).andExpect(status().isPayloadTooLarge());
        assertThat(body.get()).isNull();
    }
    @Test void silenceDoesNotCreateAnUtterance() throws Exception {
        output="";
        mvc.perform(multipart("/api/v1/stt").file(new MockMultipartFile("audio","x","audio/mp4",new byte[]{1})))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("NO_SPEECH"));
    }
}
