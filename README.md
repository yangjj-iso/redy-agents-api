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

The full HTTP contract is in [`api/openapi.yaml`](api/openapi.yaml).

## Code layout

| Path | Responsibility |
| --- | --- |
| `src/main/java/io/github/yangjjiso/redyagents/core` | Session/turn state, event log, model and tool loop |
| `src/main/java/io/github/yangjjiso/redyagents/web` | HTTP endpoints, JSON errors, SSE |
| `src/test/java` | API and lifecycle tests |
| `api/openapi.yaml` | API contract |

The core's `Model` and `Tool` interfaces are the extension points for providers and actions. `LoopRunner` passes conversation history and tool results back into the model, and enforces a step limit. The HTTP layer does not depend on a particular provider.

## Scope

This is an in-memory starter, not a production deployment. Sessions and events disappear when the process exits. It has no authentication, tenant isolation, durable job queue, provider integration, tool sandbox, or context compaction. The default bind address is loopback; add those controls before exposing the server to other machines.
