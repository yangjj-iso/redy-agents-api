# Redy Agents API

Java 17 / Spring Boot Agents API starter. It provides asynchronous turns, session input events, saved items, cancellation, and Server-Sent Events (SSE). A replaceable model and tool loop sits behind the HTTP API.

The bundled `demo` model makes no network calls and returns `Demo response: <input>`. This keeps the API runnable without credentials while a real model adapter is being developed.

## Run

Requirements: Java 17+ and Maven 3.6.3+.

```sh
mvn test
mvn spring-boot:run
```

The server listens on `127.0.0.1:8080` by default. Set `REDY_HOST` and `REDY_PORT` to change that address. Session state is saved in `./.redy-data` by default; set `REDY_DATA_DIR` or `redy.data-dir` to use another directory.

```sh
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agent":{"name":"example","model":"demo","instructions":"Be concise."},"input":"Hello"}'

curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"type":"agent.session.input.message","input":[{"role":"user","content":[{"type":"input_text","text":"Continue"}]}]}]}'

curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID
curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/turns
curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/items
curl -sS 'http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events?after=0'
curl -N -H 'Accept: text/event-stream' http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events
```

Creating a session with `input` starts its first turn. `POST /events` accepts `agent.session.input.message`, `agent.session.input.tool_result`, and `agent.session.input.cancel`. A message on an idle session starts a turn; a message during an active turn steers it. The text subset accepts one user message containing one `input_text` part per input event. The endpoint returns `202` with no body when input is accepted; inspect the session and turn for the outcome. Turn statuses are `queued`, `in_progress`, `waiting`, `completed`, `failed`, and `cancelled`; this implementation may briefly report `cancelling` while an active worker stops. A session can be `idle`, `in_progress`, `requires_action`, or `failed`. Session `idle` does not imply the preceding turn succeeded.

`GET /items` and `GET /turns` return all saved root-agent records in creation order using a `{ "data": [...], "first_id": ..., "last_id": ..., "has_more": false }` envelope. Pagination is not yet implemented. The earlier `POST /turns`, `GET /turns/{turnID}`, and `POST /turns/{turnID}/cancel` endpoints remain available. Event sequence numbers are per session. This implementation's SSE and JSON event endpoints can replay events using `Last-Event-ID` or `?after=<sequence>`; the query parameter takes precedence.

The event stream exposes the [Agents API session, turn, item, and output-text event names](https://developers.openai.com/api/docs/guides/agents-api/sessions/events) for the supported text and function subset. For example, `agent.session.turn.output_text.delta` has `item_id`, `output_index`, `content_index`, and `delta`; the matching `.done` has the complete `text`. Every event has a stable `event_id`. `agent.session.turn.completed`, `.failed`, and `.cancelled` include the terminal `turn`; `agent.session.requires_action` and `agent.session.idle` include a `session` snapshot. The demo model returns text in one chunk, so its delta is emitted after the model returns. Existing `turn.*`, `message.delta`, `tool.call.*`, and `context.compacted` events and the nested `data` field remain available for older clients. The replay cursor and extra envelope fields are local extensions; OpenAI's live stream does not replay missed events.

## Function results and recovery

When an external function call is pending, `GET /sessions/{sessionID}` returns `status: "requires_action"` and a `required_actions` entry with `type`, `turn_id`, `call_id`, `name`, and `arguments`. This follows the [Agents API function-result flow](https://developers.openai.com/api/docs/guides/agents-api/tools/functions). Submit a result with the same IDs:

```sh
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"type":"agent.session.input.tool_result","turn_id":"TURN_ID","call_id":"CALL_ID","success":true,"output":"{\"found\":true}"}]}'
```

For a tool error, use `"success":false` and an `"error"` string. After a disconnect, retrieve the session and inspect `required_actions` before executing a function. Save side-effecting results by session, turn, and call ID so a repeated submission does not repeat the side effect. The local session store keeps waiting actions across process restarts. A turn interrupted while running is marked `failed` on recovery because its external effects may be uncertain; the harness does not blindly replay it.

An active-turn message that has already reached a model call stays with that turn if it fails or is cancelled. A message accepted but not yet sent to the model is queued for another turn. The caller can inspect saved turns and items before deciding whether to resubmit any input whose effects are uncertain.

## Context budget

An agent can set `context_window_tokens` (default `4096`) and `max_output_tokens` (default `512`) when creating a session. The prompt budget is `context_window_tokens - max_output_tokens - 128`; the default is `3456`. Both values must be positive, and the window must leave a positive prompt budget after the fixed 128-token reserve. The session response includes the effective values.

Before every model call, `LoopRunner` fits the model's input into that budget. If prior turns do not fit, it creates a role-labeled extractive summary of older messages and trims the model-input view as needed. It emits `context.compacted` only when it actually summarizes or trims; the event data includes `step`, `estimated_prompt_tokens`, `prompt_budget_tokens`, and `omitted_messages`. A successful turn saves the compacted model context in the session for the next turn; original completed turns remain available as records. If the latest user input alone cannot fit, the asynchronous turn becomes `failed` after the request accepting that input returns.

The bundled estimator uses UTF-8 byte counts plus message overhead, not the model's exact token count. `max_output_tokens` is currently a prompt reserve; the demo model does not enforce a generation limit. The bundled extractive summary is a demo fallback, not the model- or server-generated semantic compaction used by Codex. A provider integration can inject its own tokenizer and compressor.

## Tool failures and replay

A tool can throw `ToolFailure` for a recoverable problem. The runner reports it to the model as a tool message so the model can correct its arguments or choose another action. An ordinary, unclassified exception fails the turn. A failed call with the same tool name and arguments cannot repeat indefinitely: one re-execution is allowed only when the tool declares itself idempotent and the failure is marked `retryable`. Blocked repeats also become tool feedback without executing again; the run's step limit bounds continued repeats.

When a model repeats a `callId`, the runner replays that call's result or error within the current turn without executing the tool again. This in-turn replay cache is separate from saved external function results. A tool can implement `validateArguments` and `validateResult` to reject domain-invalid inputs or results; without those checks, the runner cannot tell whether a technically successful result string is correct for the task. The `demo` model does not request tools. Side-effecting tools need their own durable idempotency records to prevent duplicate effects after a crash.

For local tools, the harness saves the function call, arguments, and generated `call_id` before dispatch. If execution ends without a tool result, cancellation or recovery adds a failed output item for reconciliation. The persisted record cannot prove whether an interrupted external side effect occurred.

The full HTTP contract is in [`api/openapi.yaml`](api/openapi.yaml).

## Code layout

| Path | Responsibility |
| --- | --- |
| `src/main/java/io/github/yangjjiso/redyagents/core` | Session/turn state, local store, event log, model and tool loop |
| `src/main/java/io/github/yangjjiso/redyagents/web` | HTTP endpoints, JSON errors, SSE |
| `src/test/java` | API and lifecycle tests |
| `api/openapi.yaml` | API contract |

The core's `Model` and `Tool` interfaces are the extension points for providers and actions. `LoopRunner` passes a budgeted view of conversation history and tool results into the model, and enforces a step limit. The HTTP layer does not depend on a particular provider.

## Scope

This is a demo-model starter and an Agents API-style subset, not a complete Codex harness or a production deployment. It has no authentication, tenant isolation, provider integration, sandbox, MCP, subagents, programmatic JavaScript tool calling, exact tokenizer, or model-based context compressor. The local file store provides restart recovery but is not a distributed job queue. The default bind address is loopback; add the missing controls before exposing the server to other machines.
