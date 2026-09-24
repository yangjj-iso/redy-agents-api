package agents

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"strings"
	"sync"
	"time"
)

var (
	ErrNotFound = errors.New("resource not found")
	ErrConflict = errors.New("session already has an active turn")
	ErrInvalid  = errors.New("invalid request")
)

type sessionState struct {
	session Session
	turns   map[string]Turn
	order   []string
	events  []Event
	change  chan struct{}
	cancel  context.CancelFunc
}

// Service owns the in-memory session state for this scaffold. The state and
// event log are intentionally behind this boundary so durable storage can
// replace them without changing the HTTP contract or Runner.
type Service struct {
	mu       sync.Mutex
	sessions map[string]*sessionState
	runner   Runner
}

func NewService(runner Runner) *Service {
	return &Service{sessions: make(map[string]*sessionState), runner: runner}
}

func newID(prefix string) string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		panic(err) // crypto/rand failure means safe IDs cannot be issued
	}
	return prefix + hex.EncodeToString(bytes[:])
}

func (s *Service) CreateSession(agent AgentConfig) (Session, error) {
	if strings.TrimSpace(agent.Name) == "" || strings.TrimSpace(agent.Model) == "" {
		return Session{}, ErrInvalid
	}
	now := time.Now().UTC()
	session := Session{
		ID: newID("sess_"), Agent: agent, Status: "idle",
		CreatedAt: now, UpdatedAt: now,
	}
	s.mu.Lock()
	s.sessions[session.ID] = &sessionState{session: session, turns: make(map[string]Turn), change: make(chan struct{})}
	s.mu.Unlock()
	return session, nil
}

func (s *Service) GetSession(id string) (Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.sessions[id]
	if state == nil {
		return Session{}, ErrNotFound
	}
	return state.session, nil
}

func (s *Service) StartTurn(sessionID, input string) (Turn, error) {
	if strings.TrimSpace(input) == "" {
		return Turn{}, ErrInvalid
	}
	s.mu.Lock()
	state := s.sessions[sessionID]
	if state == nil {
		s.mu.Unlock()
		return Turn{}, ErrNotFound
	}
	if state.session.ActiveTurnID != "" {
		s.mu.Unlock()
		return Turn{}, ErrConflict
	}
	now := time.Now().UTC()
	turn := Turn{ID: newID("turn_"), SessionID: sessionID, Input: input, Status: "running", CreatedAt: now}
	ctx, cancel := context.WithCancel(context.Background())
	state.cancel = cancel
	state.turns[turn.ID] = turn
	state.order = append(state.order, turn.ID)
	state.session.Status = "running"
	state.session.ActiveTurnID = turn.ID
	state.session.UpdatedAt = now
	s.appendEvent(state, Event{Type: "turn.started", SessionID: sessionID, TurnID: turn.ID})
	session := state.session
	for _, turnID := range state.order[:len(state.order)-1] {
		previous := state.turns[turnID]
		if previous.Status == "completed" {
			session.History = append(session.History,
				Message{Role: "user", Content: previous.Input},
				Message{Role: "assistant", Content: previous.Output},
			)
		}
	}
	s.mu.Unlock()

	go s.run(ctx, session, turn)
	return turn, nil
}

func (s *Service) run(ctx context.Context, session Session, turn Turn) {
	defer func() {
		if recovered := recover(); recovered != nil {
			s.finishTurn(turn, "failed", "", "runner panic")
		}
	}()
	output, err := s.runner.Run(ctx, session, turn.Input, func(eventType string, data map[string]any) {
		s.mu.Lock()
		defer s.mu.Unlock()
		state := s.sessions[session.ID]
		if state != nil && state.turns[turn.ID].Status == "running" {
			s.appendEvent(state, Event{Type: eventType, SessionID: session.ID, TurnID: turn.ID, Data: data})
		}
	})
	if err != nil {
		if errors.Is(err, context.Canceled) {
			s.finishTurn(turn, "cancelled", "", "")
		} else {
			s.finishTurn(turn, "failed", "", err.Error())
		}
		return
	}
	s.finishTurn(turn, "completed", output, "")
}

func (s *Service) finishTurn(turn Turn, status, output, errorText string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.sessions[turn.SessionID]
	current := state.turns[turn.ID]
	if current.Status != "running" && current.Status != "cancelling" {
		return
	}
	if current.Status == "cancelling" {
		status, output, errorText = "cancelled", "", ""
	}
	now := time.Now().UTC()
	current.Status, current.Output, current.Error, current.CompletedAt = status, output, errorText, &now
	state.turns[turn.ID] = current
	state.session.Status = "idle"
	state.session.ActiveTurnID = ""
	state.session.UpdatedAt = now
	state.cancel = nil
	s.appendEvent(state, Event{Type: "turn." + status, SessionID: turn.SessionID, TurnID: turn.ID})
}

func (s *Service) GetTurn(sessionID, turnID string) (Turn, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.sessions[sessionID]
	if state == nil {
		return Turn{}, ErrNotFound
	}
	turn, ok := state.turns[turnID]
	if !ok {
		return Turn{}, ErrNotFound
	}
	return turn, nil
}

func (s *Service) CancelTurn(sessionID, turnID string) (Turn, error) {
	s.mu.Lock()
	state := s.sessions[sessionID]
	if state == nil {
		s.mu.Unlock()
		return Turn{}, ErrNotFound
	}
	turn, ok := state.turns[turnID]
	if !ok {
		s.mu.Unlock()
		return Turn{}, ErrNotFound
	}
	if turn.Status != "running" {
		s.mu.Unlock()
		return Turn{}, ErrConflict
	}
	turn.Status = "cancelling"
	state.turns[turnID] = turn
	state.session.UpdatedAt = time.Now().UTC()
	cancel := state.cancel
	s.appendEvent(state, Event{Type: "turn.cancelling", SessionID: sessionID, TurnID: turnID})
	s.mu.Unlock()
	cancel()
	return turn, nil
}

// EventsAfter returns a snapshot and a channel that closes when the next
// event arrives. Callers can subscribe before waiting without losing events.
func (s *Service) EventsAfter(sessionID string, after uint64) ([]Event, <-chan struct{}, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.sessions[sessionID]
	if state == nil {
		return nil, nil, ErrNotFound
	}
	events := make([]Event, 0)
	for _, event := range state.events {
		if event.Sequence > after {
			events = append(events, event)
		}
	}
	return events, state.change, nil
}

// appendEvent requires s.mu.
func (s *Service) appendEvent(state *sessionState, event Event) {
	event.Sequence = uint64(len(state.events) + 1)
	event.CreatedAt = time.Now().UTC()
	state.events = append(state.events, event)
	close(state.change)
	state.change = make(chan struct{})
}
