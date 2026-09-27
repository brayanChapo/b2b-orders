package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"
)

const reservedFaultPrefix = "PRD-FAIL-"

type fault struct {
	status  int
	code    string
	message string
	hang    bool
}

var faults = map[string]fault{
	"400":     {status: http.StatusBadRequest, code: codeInvalidParameter, message: "Fault injected: bad request"},
	"429":     {status: http.StatusTooManyRequests, code: codeRateLimited, message: "Fault injected: too many requests"},
	"500":     {status: http.StatusInternalServerError, code: codeInternalError, message: "Fault injected: internal error"},
	"502":     {status: http.StatusBadGateway, code: codeBadGateway, message: "Fault injected: bad gateway"},
	"503":     {status: http.StatusServiceUnavailable, code: codeServiceUnavailable, message: "Fault injected: service unavailable"},
	"timeout": {hang: true},
}

var headerFaults = map[string]bool{"429": true, "500": true, "502": true, "503": true, "timeout": true}

func lookupFault(r *http.Request) (fault, bool) {
	if h := r.Header.Get("X-Fault"); headerFaults[h] {
		return faults[h], true
	}
	if id := r.PathValue("productId"); strings.HasPrefix(id, reservedFaultPrefix) {
		f, ok := faults[strings.ToLower(strings.TrimPrefix(id, reservedFaultPrefix))]
		return f, ok
	}
	return fault{}, false
}

func withFaultInjection(enabled bool, next http.Handler) http.Handler {
	if !enabled {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f, ok := lookupFault(r)
		if !ok {
			next.ServeHTTP(w, r)
			return
		}
		if f.hang {
			<-r.Context().Done()
			if errors.Is(r.Context().Err(), context.DeadlineExceeded) {
				writeError(w, r, http.StatusServiceUnavailable, codeRequestTimeout, "Request timed out")
			}
			return
		}
		if f.status == http.StatusTooManyRequests {
			w.Header().Set("Retry-After", "1")
		}
		writeError(w, r, f.status, f.code, f.message)
	})
}
