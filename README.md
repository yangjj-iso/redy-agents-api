# Redy Agents API

Java 17 / Spring Boot Agents API starter. It provides asynchronous turns, cancellation, JSON event replay, and Server-Sent Events (SSE). A replaceable model and tool loop sits behind the HTTP API.

The bundled `demo` model makes no network calls and returns `Demo response: <input>`. This keeps the API runnable without credentials while a real model adapter is being developed.

## Run

Requirements: Java 17+ and Maven 3.6.3+.

```sh
mvn test
mvn spring-boot:run
```

The server listens on `127.0.0.1:8080` by default. Set `REDY_HOST` and `REDY_PORT` to change that address.

```sh
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agent":{"name":"example","model":"demo","instructions":"Be concise."}}'

curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/turns \
  -H 'Content-Type: application/json' \
  -d '{"input":"Hello"}'

curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/turns/TURN_ID
curl -sS 'http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events?after=0'
curl -N -H 'Accept: text/event-stream' http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events
```

`POST /turns` returns `202` with a running turn. Poll the turn or subscribe to events until it becomes `completed`, `failed`, or `cancelled`. Each session can run one turn at a time. To cancel, `POST /v1/agents/sessions/{sessionID}/turns/{turnID}/cancel`; the turn stays `cancelling` and the session remains busy until its worker exits. Event sequence numbers are per session. SSE clients can reconnect with `Last-Event-ID` or `?after=<sequence>`; the query parameter takes precedence.

## Context budget

An agent can set `context_window_tokens` (default `4096`) and `max_output_tokens` (default `512`) when creating a session. The prompt budget is `context_window_tokens - max_output_tokens - 128`; the default is `3456`. Both values must be positive, and the window must leave a positive prompt budget after the fixed 128-token reserve. The session response includes the effective values.

Before every model call, `LoopRunner` fits the model's input into that budget. If prior turns do not fit, it creates a role-labeled extractive summary of older messages and trims the model-input view as needed. It emits `context.compacted` only when it actually summarizes or trims; the event data includes `step`, `estimated_prompt_tokens`, `prompt_budget_tokens`, and `omitted_messages`. A successful turn saves the compacted model context in the session for the next turn; original completed turns remain available as records. All of this state is in memory and disappears on restart. If the latest user input alone cannot fit, the asynchronous turn becomes `failed`; `POST /turns` still returns `202` first.

The bundled estimator uses UTF-8 byte counts plus message overhead, not the model's exact token count. `max_output_tokens` is currently a prompt reserve; the demo model does not enforce a generation limit. The bundled extractive summary is a demo fallback, not the model- or server-generated semantic compaction used by Codex. A provider integration can inject its own tokenizer and compressor.

## Tool failures and replay

A tool can throw `ToolFailure` for a recoverable problem. The runner reports it to the model as a tool message so the model can correct its arguments or choose another action. An ordinary, unclassified exception fails the turn. A failed call with the same tool name and arguments cannot repeat indefinitely: one re-execution is allowed only when the tool declares itself idempotent and the failure is marked `retryable`. Blocked repeats also become tool feedback without executing again; the run's step limit bounds continued repeats.

When a model repeats a `callId`, the runner replays that call's result or error within the current turn without executing the tool again. This replay state is not durable across turns or process restarts. A tool can implement `validateArguments` and `validateResult` to reject domain-invalid inputs or results; without those checks, the runner cannot tell whether a technically successful result string is correct for the task. The default server registers no tools, and the `demo` model does not request any. Side-effecting tools need their own durable idempotency records to prevent duplicate effects after a crash.

The full HTTP contract is in [`api/openapi.yaml`](api/openapi.yaml).

## Code layout

| Path | Responsibility |
| --- | --- |
| `src/main/java/io/github/yangjjiso/redyagents/core` | Session/turn state, event log, model and tool loop |
| `src/main/java/io/github/yangjjiso/redyagents/web` | HTTP endpoints, JSON errors, SSE |
| `src/test/java` | API and lifecycle tests |
| `api/openapi.yaml` | API contract |

The core's `Model` and `Tool` interfaces are the extension points for providers and actions. `LoopRunner` passes a budgeted view of conversation history and tool results into the model, and enforces a step limit. The HTTP layer does not depend on a particular provider.

## Scope

This is an in-memory starter, not a production deployment. Sessions and events disappear when the process exits. It has no authentication, tenant isolation, durable job queue, provider integration, tool sandbox, exact tokenizer, or model-based context compressor. The default bind address is loopback; add those controls before exposing the server to other machines.
