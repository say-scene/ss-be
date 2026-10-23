package com.sayscene;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/v1/conversation")
public class ConversationController {
    public record Message(String id, String text, String role, int revision, String mode) {}
    public record Turn(String sessionId, String requestId, int expectedVersion, Message message, boolean edit) {}
    public record Pending(Turn turn, String token, long expiresAt) {}
    public record Snapshot(String sessionId, String scenarioId, int version, List<Message> messages, String lastRequestId, Pending pending) {}
    public record State(Snapshot active, List<String> closed) {}
    public record Start(String sessionId, String scenarioId, String expectedSessionId) {}
    public record End(String sessionId) {}
    private final ConversationStore store;
    private final ObjectMapper json;
    private final InferenceController inference;
    private final boolean secure;
    private final long ttl, leaseMillis;
    public ConversationController(ConversationStore store, ObjectMapper json, InferenceController inference,
            @Value("${conversation.cookie-secure:true}") boolean secure,
            @Value("${conversation.ttl-seconds:86400}") long ttl,
            @Value("${inference.timeout-seconds:180}") long timeout) {
        this.store = store; this.json = json; this.inference = inference; this.secure = secure; this.ttl = ttl;
        this.leaseMillis = (timeout + 30) * 1000;
    }
    private boolean uuid(String id) { try { return id != null && UUID.fromString(id).toString().equals(id); } catch (Exception e) { return false; } }
    private State decode(String value) { try { return json.readValue(value, State.class); } catch (Exception e) { throw new IllegalStateException("invalid session state"); } }
    private String encode(State state) { try { return json.writeValueAsString(state); } catch (Exception e) { throw new IllegalStateException("invalid session state"); } }
    private State read(String owner) {
        if (!uuid(owner)) throw new ResponseStatusException(UNAUTHORIZED, "SESSION_REQUIRED");
        String value = store.read(owner);
        if (value == null) throw new ResponseStatusException(UNAUTHORIZED, "SESSION_EXPIRED");
        return decode(value);
    }
    private State change(String owner, UnaryOperator<State> action) {
        if (!uuid(owner)) throw new ResponseStatusException(UNAUTHORIZED, "SESSION_REQUIRED");
        for (int i = 0; i < 12; i++) {
            String raw = store.read(owner);
            if (raw == null) throw new ResponseStatusException(UNAUTHORIZED, "SESSION_EXPIRED");
            State next = action.apply(decode(raw));
            if (store.replace(owner, raw, encode(next))) return next;
        }
        throw new ResponseStatusException(CONFLICT, "STATE_CHANGED");
    }
    @GetMapping
    public State current(@CookieValue(name="sayscene_session", required=false) String owner, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String raw = uuid(owner) ? store.read(owner) : null;
        if (raw != null) return decode(raw);
        String id = UUID.randomUUID().toString();
        State state = new State(null, List.of());
        if (!store.replace(id, null, encode(state))) throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        response.addHeader("Set-Cookie", ResponseCookie.from("sayscene_session", id).httpOnly(true).secure(secure)
            .sameSite("Lax").path("/api/v1/conversation").maxAge(ttl).build().toString());
        return state;
    }
    @PostMapping("/start")
    public State start(@CookieValue(name="sayscene_session", required=false) String owner, @RequestBody Start body) {
        if (!uuid(body.sessionId()) || !InferenceController.supportsScenario(body.scenarioId())) throw new ResponseStatusException(BAD_REQUEST);
        return change(owner, state -> {
            if (state.active() != null && state.active().sessionId().equals(body.sessionId())) return state;
            if (state.closed().contains(body.sessionId())) throw new ResponseStatusException(CONFLICT, "CONVERSATION_CLOSED");
            if (!Objects.equals(state.active() == null ? null : state.active().sessionId(), body.expectedSessionId())) throw new ResponseStatusException(CONFLICT, "STATE_CHANGED");
            var closed = new ArrayList<>(state.closed());
            if (state.active() != null) closed.add(state.active().sessionId());
            return new State(new Snapshot(body.sessionId(), body.scenarioId(), 0, List.of(), null, null), closed);
        });
    }
    @PostMapping("/restore")
    public State restore(@CookieValue(name="sayscene_session", required=false) String owner, @RequestBody Snapshot snapshot) {
        validate(snapshot);
        return change(owner, state -> {
            if (state.active() != null) return state;
            if (state.closed().contains(snapshot.sessionId())) throw new ResponseStatusException(CONFLICT, "CONVERSATION_CLOSED");
            return new State(new Snapshot(snapshot.sessionId(), snapshot.scenarioId(), snapshot.version(), snapshot.messages(), snapshot.lastRequestId(), null), state.closed());
        });
    }
    private void validate(Snapshot s) {
        if (s == null || !uuid(s.sessionId()) || !InferenceController.supportsScenario(s.scenarioId()) || s.version() < 0 || s.messages() == null
                || (!s.messages().isEmpty() && s.messages().size() % 2 != 1)) throw new ResponseStatusException(BAD_REQUEST);
        var ids = new HashSet<String>();
        for (int i = 0; i < s.messages().size(); i++) {
            Message m = s.messages().get(i);
            if (m == null || !uuid(m.id()) || !ids.add(m.id()) || m.revision() < 1 || m.text() == null || m.text().isBlank()
                || !(i % 2 == 0 ? "assistant" : "user").equals(m.role())) throw new ResponseStatusException(BAD_REQUEST);
        }
    }
    private Snapshot active(State state, String id) {
        if (state.active() == null || !state.active().sessionId().equals(id)) throw new ResponseStatusException(CONFLICT, "CONVERSATION_CLOSED");
        return state.active();
    }
    private List<Message> history(Snapshot s, Turn turn) {
        var messages = new ArrayList<>(s.messages());
        var m = turn.message();
        if (m == null) { if (!messages.isEmpty() || turn.edit()) throw new ResponseStatusException(BAD_REQUEST); return messages; }
        if (!uuid(m.id()) || !"user".equals(m.role()) || m.text() == null || m.text().isBlank()) throw new ResponseStatusException(BAD_REQUEST);
        if (turn.edit()) {
            if (messages.size() < 3) throw new ResponseStatusException(BAD_REQUEST);
            Message last = messages.get(messages.size() - 2);
            if (!last.id().equals(m.id()) || m.revision() != last.revision() + 1) throw new ResponseStatusException(CONFLICT, "INVALID_REVISION");
            messages.remove(messages.size() - 1); messages.remove(messages.size() - 1);
        } else if (messages.isEmpty() || m.revision() != 1 || messages.stream().anyMatch(x -> x.id().equals(m.id()))) throw new ResponseStatusException(BAD_REQUEST);
        messages.add(new Message(m.id(), m.text().trim(), "user", m.revision(), null)); return messages;
    }
    @PostMapping("/turn")
    public ResponseEntity<?> turn(@CookieValue(name="sayscene_session", required=false) String owner, @RequestBody Turn body) {
        if (!uuid(body.requestId())) throw new ResponseStatusException(BAD_REQUEST);
        String token = UUID.randomUUID().toString();
        State reserved = change(owner, state -> {
            Snapshot s = active(state, body.sessionId());
            if (body.requestId().equals(s.lastRequestId())) return state;
            if (s.version() != body.expectedVersion()) throw new ResponseStatusException(CONFLICT, "STATE_CHANGED");
            if (s.pending() != null && (!s.pending().turn().equals(body) || s.pending().expiresAt() > System.currentTimeMillis())) throw new ResponseStatusException(CONFLICT, "TURN_IN_PROGRESS");
            history(s, body);
            return new State(new Snapshot(s.sessionId(), s.scenarioId(), s.version(), s.messages(), s.lastRequestId(),
                new Pending(body, token, System.currentTimeMillis() + leaseMillis)), state.closed());
        });
        Snapshot snapshot = reserved.active();
        if (body.requestId().equals(snapshot.lastRequestId())) return ResponseEntity.ok(reserved);
        List<Message> history = history(snapshot, body);
        ResponseEntity<?> result = history.isEmpty() ? inference.cafe(snapshot.scenarioId()) : inference.reply(new InferenceController.ChatRequest(snapshot.scenarioId(),
            history.stream().map(m -> new InferenceController.ChatMessage(m.role(), m.text())).toList()));
        if (!result.getStatusCode().is2xxSuccessful()) {
            change(owner, state -> {
                Snapshot s = active(state, body.sessionId());
                if (s.pending() == null || !token.equals(s.pending().token())) return state;
                return new State(new Snapshot(s.sessionId(), s.scenarioId(), s.version(), s.messages(), s.lastRequestId(),
                    new Pending(body, token, 0)), state.closed());
            });
            return result;
        }
        var output = (Map<?, ?>) result.getBody();
        history.add(new Message(UUID.randomUUID().toString(), output.get("text").toString(), "assistant", 1, output.get("mode").toString()));
        State completed = change(owner, state -> {
            Snapshot s = active(state, body.sessionId());
            if (s.pending() == null || !token.equals(s.pending().token())) throw new ResponseStatusException(CONFLICT, "STALE_RESPONSE");
            return new State(new Snapshot(s.sessionId(), s.scenarioId(), s.version() + 1, history, body.requestId(), null), state.closed());
        });
        return ResponseEntity.ok(completed);
    }
    @PostMapping("/end")
    public State end(@CookieValue(name="sayscene_session", required=false) String owner, @RequestBody End body) {
        if (!uuid(body.sessionId())) throw new ResponseStatusException(BAD_REQUEST);
        return change(owner, state -> {
            if (state.closed().contains(body.sessionId())) return state;
            active(state, body.sessionId());
            var closed = new ArrayList<>(state.closed()); closed.add(body.sessionId());
            // 대화 텍스트는 제거하고 종료 표식만 유지해 오래된 복원 요청을 차단한다.
            return new State(null, closed);
        });
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("code", "CONVERSATION_STORE_UNAVAILABLE")); }
}
