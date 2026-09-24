package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"redy-agents-api/internal/agents"
	"redy-agents-api/internal/httpapi"
)

func main() {
	addr := os.Getenv("REDY_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	service := agents.NewService(agents.LoopRunner{Model: agents.DemoModel{}})
	server := &http.Server{
		Addr: addr, Handler: httpapi.NewHandler(service),
		ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			log.Printf("shutdown error: %v", err)
		}
	}()
	log.Printf("Redy Agents API listening on %s (demo runner)", addr)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
