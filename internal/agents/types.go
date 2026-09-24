package agents

import "time"

type AgentConfig struct {
	Name         string `json:"name"`
	Model        string `json:"model"`
	Instructions string `json:"instructions"`
}

type Session struct {
	ID           string      `json:"id"`
	Agent        AgentConfig `json:"agent"`
	Status       string      `json:"status"`
	ActiveTurnID string      `json:"active_turn_id,omitempty"`
	CreatedAt    time.Time   `json:"created_at"`
	UpdatedAt    time.Time   `json:"updated_at"`
	History      []Message   `json:"-"`
}

type Turn struct {
	ID          string     `json:"id"`
	SessionID   string     `json:"session_id"`
	Input       string     `json:"input"`
	Status      string     `json:"status"`
	Output      string     `json:"output,omitempty"`
	Error       string     `json:"error,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
}

type Event struct {
	Sequence  uint64         `json:"sequence"`
	Type      string         `json:"type"`
	SessionID string         `json:"session_id"`
	TurnID    string         `json:"turn_id,omitempty"`
	CreatedAt time.Time      `json:"created_at"`
	Data      map[string]any `json:"data,omitempty"`
}
