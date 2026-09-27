package httpapi

import (
	"net/http"
	"sync/atomic"
)

type Health struct {
	shuttingDown atomic.Bool
}

func NewHealth() *Health { return &Health{} }

func (h *Health) MarkShuttingDown() { h.shuttingDown.Store(true) }

type healthBody struct {
	Status string `json:"status"`
}

func (h *Health) ServeHTTP(w http.ResponseWriter, _ *http.Request) {
	if h.shuttingDown.Load() {
		writeJSON(w, http.StatusServiceUnavailable, healthBody{Status: "DOWN"})
		return
	}
	writeJSON(w, http.StatusOK, healthBody{Status: "UP"})
}
