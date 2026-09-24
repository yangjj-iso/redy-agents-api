package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"redy-agents-api/internal/agents"
)

type Handler struct{ service *agents.Service }

func NewHandler(service *agents.Service) http.Handler {
	h := &Handler{service: service}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /v1/agents/sessions", h.createSession)
	mux.HandleFunc("GET /v1/agents/sessions/{sessionID}", h.getSession)
	mux.HandleFunc("POST /v1/agents/sessions/{sessionID}/turns", h.startTurn)
	mux.HandleFunc("GET /v1/agents/sessions/{sessionID}/turns/{turnID}", h.getTurn)
	mux.HandleFunc("POST /v1/agents/sessions/{sessionID}/turns/{turnID}/cancel", h.cancelTurn)
	mux.HandleFunc("GET /v1/agents/sessions/{sessionID}/events", h.events)
	return mux
}

func (h *Handler) createSession(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Agent agents.AgentConfig `json:"agent"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	session, err := h.service.CreateSession(body.Agent)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, session)
}

func (h *Handler) getSession(w http.ResponseWriter, r *http.Request) {
	session, err := h.service.GetSession(r.PathValue("sessionID"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, session)
}

func (h *Handler) startTurn(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Input string `json:"input"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	turn, err := h.service.StartTurn(r.PathValue("sessionID"), body.Input)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusAccepted, turn)
}

func (h *Handler) getTurn(w http.ResponseWriter, r *http.Request) {
	turn, err := h.service.GetTurn(r.PathValue("sessionID"), r.PathValue("turnID"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, turn)
}

func (h *Handler) cancelTurn(w http.ResponseWriter, r *http.Request) {
	turn, err := h.service.CancelTurn(r.PathValue("sessionID"), r.PathValue("turnID"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, turn)
}

func (h *Handler) events(w http.ResponseWriter, r *http.Request) {
	rawAfter := r.URL.Query().Get("after")
	if rawAfter == "" {
		rawAfter = r.Header.Get("Last-Event-ID")
	}
	var after uint64
	if rawAfter != "" {
		parsed, err := strconv.ParseUint(rawAfter, 10, 64)
		if err != nil {
			writeError(w, agents.ErrInvalid)
			return
		}
		after = parsed
	}
	sessionID := r.PathValue("sessionID")
	events, changed, err := h.service.EventsAfter(sessionID, after)
	if err != nil {
		writeError(w, err)
		return
	}
	if !strings.Contains(r.Header.Get("Accept"), "text/event-stream") {
		writeJSON(w, http.StatusOK, map[string]any{"events": events})
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "streaming unavailable"})
		return
	}
	heartbeat := time.NewTicker(15 * time.Second)
	defer heartbeat.Stop()
	for {
		for _, event := range events {
			encoded, err := json.Marshal(event)
			if err != nil {
				return
			}
			if _, err := fmt.Fprintf(w, "id: %d\nevent: %s\ndata: %s\n\n", event.Sequence, event.Type, encoded); err != nil {
				return
			}
			after = event.Sequence
		}
		flusher.Flush()
		select {
		case <-r.Context().Done():
			return
		case <-changed:
		case <-heartbeat.C:
			if _, err := io.WriteString(w, ": heartbeat\n\n"); err != nil {
				return
			}
			flusher.Flush()
		}
		events, changed, err = h.service.EventsAfter(sessionID, after)
		if err != nil {
			return
		}
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		writeJSON(w, http.StatusUnsupportedMediaType, map[string]any{"error": map[string]string{"code": "unsupported_media_type", "message": "Content-Type must be application/json"}})
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		writeError(w, agents.ErrInvalid)
		return false
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		writeError(w, agents.ErrInvalid)
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, err error) {
	status, code, message := http.StatusInternalServerError, "internal_error", "internal error"
	switch {
	case errors.Is(err, agents.ErrNotFound):
		status, code, message = http.StatusNotFound, "not_found", err.Error()
	case errors.Is(err, agents.ErrConflict):
		status, code, message = http.StatusConflict, "conflict", err.Error()
	case errors.Is(err, agents.ErrInvalid):
		status, code, message = http.StatusBadRequest, "invalid_request", err.Error()
	}
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "message": message}})
}
