package io.github.yangjjiso.redyagents.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.yangjjiso.redyagents.core.AgentConfig;
import io.github.yangjjiso.redyagents.core.AgentException;
import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.Session;
import io.github.yangjjiso.redyagents.core.SessionItem;
import io.github.yangjjiso.redyagents.core.Turn;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agents/sessions")
public class AgentsController {
    private final AgentService service;
    private final SseStreams streams;

    public AgentsController(AgentService service, SseStreams streams) {
        this.service = service;
        this.streams = streams;
    }

    public record CreateSessionRequest(AgentConfig agent, String input) {}

    public record StartTurnRequest(String input) {}

    public record InputTextPart(String type, String text) {}

    public record InputMessage(String role, List<InputTextPart> content) {}

    public record InputEvent(String type, List<InputMessage> input,
                             @JsonProperty("turn_id") String turnId,
                             @JsonProperty("call_id") String callId,
                             Boolean success, String output, String error) {}

    public record SubmitEventsRequest(List<InputEvent> events) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Session> createSession(@RequestBody CreateSessionRequest request) {
        if (request == null || request.agent() == null) {
            throw AgentException.invalid();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createSession(request.agent(), request.input()));
    }

    @GetMapping("/{sessionID}")
    public Session getSession(@PathVariable String sessionID) {
        return service.getSession(sessionID);
    }

    @PostMapping(path = "/{sessionID}/turns", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Turn> startTurn(@PathVariable String sessionID, @RequestBody StartTurnRequest request) {
        if (request == null) {
            throw AgentException.invalid();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startTurn(sessionID, request.input()));
    }

    @GetMapping("/{sessionID}/turns")
    public Map<String, Object> listTurns(@PathVariable String sessionID) {
        List<Turn> turns = service.listTurns(sessionID);
        return page(turns, turns.isEmpty() ? null : turns.get(0).id(),
                turns.isEmpty() ? null : turns.get(turns.size() - 1).id());
    }

    @GetMapping("/{sessionID}/items")
    public Map<String, Object> listItems(@PathVariable String sessionID) {
        List<SessionItem> items = service.items(sessionID);
        return page(items, items.isEmpty() ? null : items.get(0).id(),
                items.isEmpty() ? null : items.get(items.size() - 1).id());
    }

    @GetMapping("/{sessionID}/turns/{turnID}")
    public Turn getTurn(@PathVariable String sessionID, @PathVariable String turnID) {
        return service.getTurn(sessionID, turnID);
    }

    @PostMapping("/{sessionID}/turns/{turnID}/cancel")
    public Turn cancelTurn(@PathVariable String sessionID, @PathVariable String turnID) {
        return service.cancelTurn(sessionID, turnID);
    }

    @PostMapping(path = "/{sessionID}/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> submitEvents(@PathVariable String sessionID,
                                             @RequestBody SubmitEventsRequest request) {
        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw AgentException.invalid();
        }
        for (InputEvent event : request.events()) {
            validateEvent(event);
        }
        for (InputEvent event : request.events()) {
            switch (event.type()) {
                case "agent.session.input.message" -> service.submitMessage(sessionID,
                        event.input().get(0).content().get(0).text());
                case "agent.session.input.tool_result" -> service.submitToolResult(sessionID,
                        event.turnId(), event.callId(), event.success(), event.output(), event.error());
                case "agent.session.input.cancel" -> service.cancelActiveTurn(sessionID);
                default -> throw AgentException.invalid();
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/{sessionID}/events")
    public ResponseEntity<?> events(
            @PathVariable String sessionID,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        long cursor = parseCursor(after == null || after.isEmpty() ? lastEventId : after);
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(streams.open(sessionID, cursor));
        }
        return ResponseEntity.ok(Map.of("events", service.eventsAfter(sessionID, cursor)));
    }

    private static long parseCursor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            long value = Long.parseLong(raw);
            if (value >= 0 && raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Report malformed or out-of-range cursors through the API error envelope.
        }
        throw AgentException.invalid();
    }

    private static void validateEvent(InputEvent event) {
        if (event == null || event.type() == null) {
            throw AgentException.invalid();
        }
        switch (event.type()) {
            case "agent.session.input.message" -> {
                if (event.input() == null || event.input().size() != 1) {
                    throw AgentException.invalid();
                }
                InputMessage message = event.input().get(0);
                if (message == null || !"user".equals(message.role())
                        || message.content() == null || message.content().size() != 1) {
                    throw AgentException.invalid();
                }
                InputTextPart content = message.content().get(0);
                if (content == null || !"input_text".equals(content.type())
                        || blank(content.text())) {
                    throw AgentException.invalid();
                }
            }
            case "agent.session.input.tool_result" -> {
                if (blank(event.turnId()) || blank(event.callId()) || event.success() == null
                        || (event.success() && event.output() == null)
                        || (!event.success() && blank(event.error()))) {
                    throw AgentException.invalid();
                }
            }
            case "agent.session.input.cancel" -> { }
            default -> throw AgentException.invalid();
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static Map<String, Object> page(List<?> data, String firstId, String lastId) {
        Map<String, Object> page = new java.util.LinkedHashMap<>();
        page.put("data", data);
        page.put("first_id", firstId);
        page.put("last_id", lastId);
        page.put("has_more", false);
        return page;
    }
}
