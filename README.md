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

## API quickstart

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

## CubeSandbox environment

An optional CubeSandbox backend gives each `environment.type: "cube"` session its own remote microVM. A [CubeSandbox cluster](https://docs.cubesandbox.com/guide/quickstart) must run on a Linux KVM host; this Java service can connect to it from macOS or another machine. The chosen template must include envd for command and file operations. Configure the CubeAPI URL and either a CubeProxy address for [path routing](https://docs.cubesandbox.com/guide/https-and-domain#path-based-quick-access-no-dns--cert), or CubeSandbox's virtual-host DNS:

```sh
export REDY_CUBE_ENABLED=true
export CUBE_API_URL=http://cube-api-host:3000
export CUBE_TEMPLATE_ID=your-template-id
export CUBE_PROXY_NODE_IP=cube-proxy-host
export CUBE_PROXY_PORT_HTTP=80
mvn spring-boot:run
```

`CUBE_API_KEY` supplies a bearer token when CubeAPI authentication is configured. If `CUBE_PROXY_NODE_IP` is omitted, set `CUBE_SANDBOX_DOMAIN` and route `49983-<sandbox-id>.<domain>` through CubeProxy. `CUBE_PROXY_SCHEME` selects `http` or `https`; `CUBE_REQUEST_TIMEOUT` controls request timeouts. `CUBE_SANDBOX_IDLE_SECONDS` defaults to `300`. New sandboxes request pause on timeout and auto-resume; command and file operations reconnect before use. Internet egress is denied by default using `network.denyOut`; set `CUBE_ALLOW_INTERNET_ACCESS=true` only when the session needs it. The client does not enable CubeSandbox's private inbound traffic mode: CubeAPI returns its traffic token only at creation, and durable token storage is not implemented. Restrict CubeProxy and CubeAPI access at the cluster boundary. Protect this service before exposing it outside a trusted network.

The environment variables map to `redy.cube.*` Spring properties, so deployments and tests can override them through Spring configuration. `CUBE_REQUEST_TIMEOUT` accepts a duration such as `30s`; a bare number is interpreted as seconds.

```sh
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agent":{"name":"example","model":"demo"},"environment":{"type":"cube"}}'

curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/environment/pause
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/environment/resume
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/environment/kill
```

The session response includes `environment.type`, `sandbox_id`, `template_id`, and `status`. The sandbox ID survives a service restart and is reused across turns. Pause, resume, and kill require no active turn; paused or killed environments reject new turns. A failed lifecycle request can leave a durable `pausing`, `resuming`, or `killing` status. Retry that same endpoint to reconcile it. Killing a sandbox ends its compute and ephemeral files while leaving the session history readable.

When enabled, the harness registers `sandbox.shell` (`{"command":"pwd","timeout_ms":30000}`), `sandbox.read_file` (`{"path":"/workspace/note.txt"}`), and `sandbox.write_file` (`{"path":"/workspace/note.txt","content":"hello"}`). These tools route only to the session's CubeSandbox. Shell returns `exit_code`, stdout, and stderr even when the command exits nonzero; the model decides what to do next. File tools handle UTF-8 text up to 1 MiB. The bundled `demo` model never asks for tools, so a future model adapter must emit these tool calls to use them during a turn. There is no direct public shell endpoint.

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

The full HTTP contract is in [`api/openapi.yaml`](api/openapi.yaml). The [architecture guide](docs/architecture.md) shows the boundaries and request flow.

## Code layout

| Path | Responsibility |
| --- | --- |
| `src/main/java/io/github/yangjjiso/redyagents/config` | Spring configuration and deployment settings |
| `src/main/java/io/github/yangjjiso/redyagents/core` | Session/turn state, snapshot store, model and tool interfaces, and harness loop |
| `src/main/java/io/github/yangjjiso/redyagents/cube` | CubeSandbox adapter, tools, CubeAPI, CubeProxy, and envd Connect protocol |
| `src/main/java/io/github/yangjjiso/redyagents/web` | HTTP endpoints, JSON errors, SSE |
| `src/test/java` | API and lifecycle tests |
| `api/openapi.yaml` | API contract |

The core's `Model` and `Tool` interfaces are the extension points for providers and actions. `LoopRunner` passes a budgeted view of conversation history and tool results into the model, and enforces a step limit. The HTTP layer does not depend on a particular provider.

## Scope

This is a demo-model starter and an Agents API-style subset, not a complete Codex harness or a production deployment. It has no authentication, tenant isolation, model provider integration, MCP, subagents, programmatic JavaScript tool calling, exact tokenizer, or model-based context compressor. The CubeSandbox integration has protocol-level mock tests but has not been verified against a live cluster in this environment. Creating a sandbox and saving its first session snapshot are separate operations; a process crash between them can leave an orphan sandbox. The local file store provides restart recovery but is not a distributed job queue. The default bind address is loopback; add the missing controls before exposing the server to other machines.
