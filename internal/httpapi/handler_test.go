package httpapi

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"redy-agents-api/internal/agents"
)

func TestSessionTurnAndEventReplay(t *testing.T) {
	server := httptest.NewServer(NewHandler(agents.NewService(agents.LoopRunner{Model: agents.DemoModel{}})))
	defer server.Close()
	post := func(path, body string, target any, expected int) {
		t.Helper()
		response, err := http.Post(server.URL+path, "application/json", bytes.NewBufferString(body))
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != expected {
			t.Fatalf("POST %s: status %d, want %d", path, response.StatusCode, expected)
		}
		if err := json.NewDecoder(response.Body).Decode(target); err != nil {
			t.Fatal(err)
		}
	}
	var session agents.Session
	post("/v1/agents/sessions", `{"agent":{"name":"demo","model":"demo"}}`, &session, http.StatusCreated)
	var turn agents.Turn
	post(fmt.Sprintf("/v1/agents/sessions/%s/turns", session.ID), `{"input":"hello"}`, &turn, http.StatusAccepted)
	deadline := time.Now().Add(time.Second)
	for {
		response, err := http.Get(fmt.Sprintf("%s/v1/agents/sessions/%s/turns/%s", server.URL, session.ID, turn.ID))
		if err != nil {
			t.Fatal(err)
		}
		err = json.NewDecoder(response.Body).Decode(&turn)
		response.Body.Close()
		if err != nil {
			t.Fatal(err)
		}
		if turn.Status == "completed" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("turn did not complete: %s", turn.Status)
		}
		time.Sleep(time.Millisecond)
	}
	if turn.Output != "Demo response: hello" {
		t.Fatalf("unexpected output %q", turn.Output)
	}
	response, err := http.Get(fmt.Sprintf("%s/v1/agents/sessions/%s/events?after=1", server.URL, session.ID))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var replay struct {
		Events []agents.Event `json:"events"`
	}
	if err := json.NewDecoder(response.Body).Decode(&replay); err != nil {
		t.Fatal(err)
	}
	if len(replay.Events) != 2 || replay.Events[0].Type != "message.delta" || replay.Events[1].Type != "turn.completed" {
		t.Fatalf("unexpected replay: %+v", replay.Events)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet,
		fmt.Sprintf("%s/v1/agents/sessions/%s/events?after=1", server.URL, session.ID), nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Accept", "text/event-stream")
	stream, err := server.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Body.Close()
	line, err := bufio.NewReader(stream.Body).ReadString('\n')
	if err != nil || line != "id: 2\n" {
		t.Fatalf("SSE replay first line = %q, %v", line, err)
	}
}
