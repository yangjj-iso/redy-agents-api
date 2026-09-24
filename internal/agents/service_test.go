package agents

import (
	"context"
	"errors"
	"testing"
	"time"
)

type blockingRunner struct {
	started chan struct{}
	release chan struct{}
}

func (r blockingRunner) Run(ctx context.Context, _ Session, _ string, _ func(string, map[string]any)) (string, error) {
	close(r.started)
	<-r.release
	return "", ctx.Err()
}

func TestCancelKeepsSessionBusyUntilRunnerExits(t *testing.T) {
	runner := blockingRunner{started: make(chan struct{}), release: make(chan struct{})}
	service := NewService(runner)
	session, err := service.CreateSession(AgentConfig{Name: "test", Model: "demo"})
	if err != nil {
		t.Fatal(err)
	}
	turn, err := service.StartTurn(session.ID, "hello")
	if err != nil {
		t.Fatal(err)
	}
	<-runner.started
	if _, err := service.CancelTurn(session.ID, turn.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := service.StartTurn(session.ID, "second"); !errors.Is(err, ErrConflict) {
		t.Fatalf("expected a conflict while cancellation is pending, got %v", err)
	}
	close(runner.release)
	deadline := time.After(time.Second)
	for {
		current, err := service.GetTurn(session.ID, turn.ID)
		if err != nil {
			t.Fatal(err)
		}
		if current.Status == "cancelled" {
			break
		}
		select {
		case <-deadline:
			t.Fatalf("turn did not settle: %s", current.Status)
		case <-time.After(time.Millisecond):
		}
	}
	events, _, err := service.EventsAfter(session.ID, 1)
	if err != nil || len(events) != 2 || events[0].Type != "turn.cancelling" || events[1].Type != "turn.cancelled" {
		t.Fatalf("unexpected events after cursor 1: %v, %v", events, err)
	}
}
