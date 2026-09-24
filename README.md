# Redy Agents API

A small, standalone Agents API scaffold in Go. The HTTP layer manages sessions, asynchronous turns, cancellation, and replayable events. The runner owns the agent loop; model and tool implementations are replaceable interfaces.

## Run locally

Requires Go 1.23 or newer. The server listens on `127.0.0.1:8080` by default.

```sh
go run ./cmd/redy-api
```

Set `REDY_ADDR` to choose a different listen address. The default runner uses `DemoModel`; create an agent with `model: "demo"`. It makes no network calls and returns `Demo response: <input>`.

```sh
curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agent":{"name":"example","model":"demo","instructions":"Be concise."}}'

curl -sS -X POST http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/turns \
  -H 'Content-Type: application/json' \
  -d '{"input":"Hello"}'

curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/turns/TURN_ID
curl -sS http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events?after=0
curl -N -H 'Accept: text/event-stream' http://127.0.0.1:8080/v1/agents/sessions/SESSION_ID/events
```

`POST /turns` returns `202` immediately. Poll the turn or subscribe to events until it reaches `completed`, `failed`, or `cancelled`. Event `sequence` values are per session. For SSE reconnects, send `Last-Event-ID` or `?after=<sequence>`. To cancel, `POST /v1/agents/sessions/{sessionID}/turns/{turnID}/cancel`. It first enters `cancelling`, then `cancelled` when the runner exits. Only one turn runs per session at a time. Completed turns provide the conversation history for later turns in the same session.

## Structure

| Path | Responsibility |
| --- | --- |
| `cmd/redy-api` | Process startup, HTTP server, graceful shutdown |
| `internal/httpapi` | Versioned HTTP routes, JSON validation, SSE |
| `internal/agents/service.go` | Session/turn lifecycle and event log |
| `internal/agents/runner.go` | Agent loop and `Runner`, `Model`, `Tool` interfaces |
| `api/openapi.yaml` | HTTP contract |

Replace `DemoModel` in `cmd/redy-api/main.go` with a model adapter implementing `agents.Model`. Register tools with `LoopRunner.Tools`. For a production service, replace the in-memory state/event log behind `Service` with durable storage and a job executor, and add authentication, tenant isolation, limits, observability, and a tool sandbox. The current code has no provider integration, durable state, tool sandbox, or authentication. It binds only to loopback unless `REDY_ADDR` is changed.

## Verify

```sh
go test ./...
go test -race ./...
```
