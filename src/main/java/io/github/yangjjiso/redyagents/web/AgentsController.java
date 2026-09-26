package io.github.yangjjiso.redyagents.web;

import io.github.yangjjiso.redyagents.core.AgentConfig;
import io.github.yangjjiso.redyagents.core.AgentException;
import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.Session;
import io.github.yangjjiso.redyagents.core.Turn;
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

    public record CreateSessionRequest(AgentConfig agent) {}

    public record StartTurnRequest(String input) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Session> createSession(@RequestBody CreateSessionRequest request) {
        if (request == null || request.agent() == null) {
            throw AgentException.invalid();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSession(request.agent()));
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

    @GetMapping("/{sessionID}/turns/{turnID}")
    public Turn getTurn(@PathVariable String sessionID, @PathVariable String turnID) {
        return service.getTurn(sessionID, turnID);
    }

    @PostMapping("/{sessionID}/turns/{turnID}/cancel")
    public Turn cancelTurn(@PathVariable String sessionID, @PathVariable String turnID) {
        return service.cancelTurn(sessionID, turnID);
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
}
