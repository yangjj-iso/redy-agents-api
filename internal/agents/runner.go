package agents

import (
	"context"
	"errors"
	"fmt"
)

// Runner is the replaceable Harness boundary. A production implementation
// supplies model turns, tool dispatch, budgets, and sandbox execution.
type Runner interface {
	Run(ctx context.Context, session Session, input string, emit func(eventType string, data map[string]any)) (string, error)
}

var ErrModelNotConfigured = errors.New("model adapter is not configured")

type Message struct {
	Role    string
	Content string
	Tool    string
}

type ToolCall struct {
	Name      string
	Arguments []byte
}

type Decision struct {
	Kind    string // "final" or "tool_call"
	Message string
	Tool    *ToolCall
}

type Model interface {
	Next(ctx context.Context, agent AgentConfig, messages []Message) (Decision, error)
}

type Tool interface {
	Execute(ctx context.Context, arguments []byte) (string, error)
}

// LoopRunner contains the minimal model/tool loop. Durable tool claims,
// approvals, and external function callbacks belong in later adapters.
type LoopRunner struct {
	Model    Model
	Tools    map[string]Tool
	MaxSteps int
}

func (r LoopRunner) Run(ctx context.Context, session Session, input string, emit func(string, map[string]any)) (string, error) {
	if r.Model == nil {
		return "", ErrModelNotConfigured
	}
	limit := r.MaxSteps
	if limit <= 0 {
		limit = 8
	}
	messages := append(append([]Message(nil), session.History...), Message{Role: "user", Content: input})
	for step := 0; step < limit; step++ {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		decision, err := r.Model.Next(ctx, session.Agent, append([]Message(nil), messages...))
		if err != nil {
			return "", err
		}
		switch decision.Kind {
		case "final":
			emit("message.delta", map[string]any{"text": decision.Message})
			return decision.Message, nil
		case "tool_call":
			if decision.Tool == nil || decision.Tool.Name == "" {
				return "", errors.New("model returned an invalid tool call")
			}
			tool := r.Tools[decision.Tool.Name]
			if tool == nil {
				return "", fmt.Errorf("unknown tool %q", decision.Tool.Name)
			}
			emit("tool.call.started", map[string]any{"name": decision.Tool.Name})
			result, err := tool.Execute(ctx, decision.Tool.Arguments)
			if err != nil {
				emit("tool.call.failed", map[string]any{"name": decision.Tool.Name, "error": err.Error()})
				return "", err
			}
			emit("tool.call.completed", map[string]any{"name": decision.Tool.Name})
			messages = append(messages,
				Message{Role: "assistant", Content: decision.Message},
				Message{Role: "tool", Tool: decision.Tool.Name, Content: result},
			)
		default:
			return "", fmt.Errorf("unknown model decision %q", decision.Kind)
		}
	}
	return "", errors.New("run exceeded max steps")
}

// DemoModel exercises the API lifecycle without making a model request.
// It deliberately accepts only model="demo".
type DemoModel struct{}

func (DemoModel) Next(ctx context.Context, agent AgentConfig, messages []Message) (Decision, error) {
	if agent.Model != "demo" {
		return Decision{}, ErrModelNotConfigured
	}
	if err := ctx.Err(); err != nil {
		return Decision{}, err
	}
	return Decision{Kind: "final", Message: "Demo response: " + messages[len(messages)-1].Content}, nil
}
