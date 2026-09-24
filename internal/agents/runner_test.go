package agents

import (
	"context"
	"testing"
)

type scriptedModel struct{ calls int }

func (m *scriptedModel) Next(_ context.Context, _ AgentConfig, messages []Message) (Decision, error) {
	m.calls++
	if m.calls == 1 {
		if len(messages) != 3 || messages[0].Content != "earlier" || messages[2].Content != "now" {
			panic("session history was not passed to the model")
		}
		return Decision{Kind: "tool_call", Tool: &ToolCall{Name: "echo", Arguments: []byte("hello")}}, nil
	}
	if len(messages) != 5 || messages[4].Role != "tool" || messages[4].Content != "hello" {
		panic("tool result was not passed to the model")
	}
	return Decision{Kind: "final", Message: "done"}, nil
}

type echoTool struct{}

func (echoTool) Execute(_ context.Context, arguments []byte) (string, error) {
	return string(arguments), nil
}

func TestLoopRunnerPassesHistoryAndToolResult(t *testing.T) {
	model := &scriptedModel{}
	runner := LoopRunner{Model: model, Tools: map[string]Tool{"echo": echoTool{}}}
	session := Session{History: []Message{{Role: "user", Content: "earlier"}, {Role: "assistant", Content: "reply"}}}
	var events []string
	output, err := runner.Run(context.Background(), session, "now", func(kind string, _ map[string]any) {
		events = append(events, kind)
	})
	if err != nil || output != "done" {
		t.Fatalf("Run() = %q, %v", output, err)
	}
	if model.calls != 2 || len(events) != 3 || events[0] != "tool.call.started" || events[2] != "message.delta" {
		t.Fatalf("unexpected model calls or events: calls=%d events=%v", model.calls, events)
	}
}
