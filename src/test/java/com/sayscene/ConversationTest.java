package com.sayscene;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationTest {
    Map<String,String> data;
    ConversationController controller;
    InferenceController inference;
    String owner, session;
    ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() {
        data = new ConcurrentHashMap<>();
        var store = mock(ConversationStore.class);
        when(store.read(anyString())).thenAnswer(i -> data.get(i.getArgument(0)));
        when(store.replace(anyString(), nullable(String.class), anyString())).thenAnswer(i -> {
            synchronized(data) {
                String id = i.getArgument(0); String expected = i.getArgument(1);
                if (!Objects.equals(expected, data.get(id))) return false;
                data.put(id, i.getArgument(2)); return true;
            }
        });
        inference = mock(InferenceController.class);
        doReturn(ResponseEntity.ok(Map.of("text", "Hello!", "mode", "mock"))).when(inference).cafe(anyString());
        doReturn(ResponseEntity.ok(Map.of("text", "Anything else?", "mode", "mock"))).when(inference).reply(any());
        controller = new ConversationController(store, json, inference, true, 86400, 180);
        var response = new MockHttpServletResponse(); controller.current(null, response);
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax");
        owner = cookie.split("[=;]")[1]; session = UUID.randomUUID().toString();
        controller.start(owner, new ConversationController.Start(session, "cafe-order", null));
    }
    ConversationController.Turn opening() { return new ConversationController.Turn(session, UUID.randomUUID().toString(), 0, null, false); }
    ConversationController.Message user(String text) { return new ConversationController.Message(UUID.randomUUID().toString(), text, "user", 1, null); }
    @Test void issuedCookieIsRequiredAndUnknownCookieIsReplaced() {
        assertThatThrownBy(() -> controller.start(UUID.randomUUID().toString(), new ConversationController.Start(session,"cafe-order",null))).isInstanceOf(ResponseStatusException.class);
        var response = new MockHttpServletResponse(); controller.current(UUID.randomUUID().toString(), response);
        assertThat(response.getHeader("Set-Cookie")).isNotNull();
    }
    @Test void completedRequestsAreReusedAndEditsReplacePreviousTurn() {
        var start = opening(); controller.turn(owner, start); controller.turn(owner, start);
        verify(inference, times(1)).cafe("cafe-order");
        var user = user("A latte.");
        controller.turn(owner, new ConversationController.Turn(session, UUID.randomUUID().toString(), 1, user, false));
        var edited = new ConversationController.Message(user.id(), "Tea, please.", "user", 2, null);
        controller.turn(owner, new ConversationController.Turn(session, UUID.randomUUID().toString(), 2, edited, true));
        var snapshot = controller.current(owner, new MockHttpServletResponse()).active();
        assertThat(snapshot.messages()).hasSize(3);
        assertThat(snapshot.messages().get(1).text()).isEqualTo("Tea, please.");
        assertThat(snapshot.messages().get(1).revision()).isEqualTo(2);
        assertThat(snapshot.version()).isEqualTo(3);
    }
    @Test void inFlightRequestIsNotRepeatedAndEndFencesLateCompletion() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); finish.await(5, TimeUnit.SECONDS); return ResponseEntity.ok(Map.of("text","late","mode","mock")); }).when(inference).cafe(anyString());
        var turn = opening(); var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> controller.turn(owner, turn));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> controller.turn(owner, turn)).isInstanceOf(ResponseStatusException.class);
            controller.end(owner, new ConversationController.End(session)); finish.countDown();
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(controller.current(owner, new MockHttpServletResponse()).active()).isNull();
            verify(inference, times(1)).cafe(anyString());
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
    @Test void missingRedisCanRestoreButClosedConversationCannot() {
        controller.turn(owner, opening());
        var snapshot = controller.current(owner, new MockHttpServletResponse()).active();
        controller.end(owner, new ConversationController.End(session));
        assertThatThrownBy(() -> controller.restore(owner, snapshot)).isInstanceOf(ResponseStatusException.class);
        assertThat(data.get(owner)).doesNotContain("Hello!");
        data.clear(); var response = new MockHttpServletResponse(); controller.current(owner, response);
        String newOwner = response.getHeader("Set-Cookie").split("[=;]")[1];
        assertThat(controller.restore(newOwner, snapshot).active().messages()).hasSize(1);
    }
    @Test void newConversationRejectsStaleStartAndRestorationNeverOverridesRedis() {
        controller.turn(owner, opening());
        var snapshot = controller.current(owner, new MockHttpServletResponse()).active();
        String next = UUID.randomUUID().toString();
        controller.start(owner, new ConversationController.Start(next, "hotel-checkin", session));
        assertThat(controller.restore(owner, snapshot).active().sessionId()).isEqualTo(next);
        assertThatThrownBy(() -> controller.start(owner, new ConversationController.Start(UUID.randomUUID().toString(), "cafe-order", session))).isInstanceOf(ResponseStatusException.class);
    }
}
