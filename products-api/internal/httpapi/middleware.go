package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"time"
)

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.ResponseWriter.Write(b)
}

func withTrace(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := resolveTraceID(r.Header.Get("traceparent"), r.Header.Get("X-Request-Id"))
		w.Header().Set("X-Trace-Id", id)
		next.ServeHTTP(w, r.WithContext(withTraceID(r.Context(), id)))
	})
}

func withAccessLog(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)

		status := rec.status
		if status == 0 {
			status = 499
		}
		level := slog.LevelInfo
		if r.URL.Path == "/health" {
			level = slog.LevelDebug
		}
		if status >= 500 {
			level = slog.LevelError
		}
		logger.LogAttrs(r.Context(), level, "http_request",
			slog.String("method", r.Method),
			slog.String("path", r.URL.Path),
			slog.String("market", r.URL.Query().Get("market")),
			slog.Int("status", status),
			slog.Int64("duration_ms", time.Since(start).Milliseconds()),
			slog.String("trace_id", traceIDFrom(r.Context())),
		)
	})
}

func withDeadline(timeout time.Duration, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), timeout)
		defer cancel()
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func withRecover(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				if v == http.ErrAbortHandler {
					panic(v)
				}
				logger.Error("panic en handler", "panic", v, "trace_id", traceIDFrom(r.Context()))
				writeError(w, r, http.StatusInternalServerError, codeInternalError, "Internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}
