package httpapi

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"time"
)

const (
	codeInvalidParameter   = "INVALID_PARAMETER"
	codeProductNotFound    = "PRODUCT_NOT_FOUND"
	codeRouteNotFound      = "ROUTE_NOT_FOUND"
	codeMethodNotAllowed   = "METHOD_NOT_ALLOWED"
	codeRateLimited        = "RATE_LIMITED"
	codeInternalError      = "INTERNAL_ERROR"
	codeBadGateway         = "BAD_GATEWAY"
	codeServiceUnavailable = "SERVICE_UNAVAILABLE"
	codeRequestTimeout     = "REQUEST_TIMEOUT"
)

type fieldIssue struct {
	Field string `json:"field"`
	Issue string `json:"issue"`
}

type errorBody struct {
	Code      string       `json:"code"`
	Message   string       `json:"message"`
	Status    int          `json:"status"`
	TraceID   string       `json:"traceId,omitempty"`
	Timestamp string       `json:"timestamp"`
	Details   []fieldIssue `json:"details,omitempty"`
}

var now = func() time.Time { return time.Now().UTC() }

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("no se pudo escribir la respuesta", "error", err)
	}
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code, message string, details ...fieldIssue) {
	writeJSON(w, status, errorBody{
		Code:      code,
		Message:   message,
		Status:    status,
		TraceID:   traceIDFrom(r.Context()),
		Timestamp: now().Format(time.RFC3339),
		Details:   details,
	})
}
