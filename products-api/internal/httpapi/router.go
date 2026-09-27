package httpapi

import (
	"log/slog"
	"net/http"
	"time"
)

type Options struct {
	Catalog               Catalog
	Health                *Health
	Logger                *slog.Logger
	RequestTimeout        time.Duration
	FaultInjectionEnabled bool
}

// NewHandler construye el handler raíz del servicio.
func NewHandler(opts Options) http.Handler {
	products := &productsHandler{catalog: opts.Catalog, logger: opts.Logger}

	mux := http.NewServeMux()
	mux.Handle("GET /products/{productId}", withFaultInjection(opts.FaultInjectionEnabled, http.HandlerFunc(products.get)))
	mux.Handle("GET /health", opts.Health)

	mux.HandleFunc("/products/{productId}", methodNotAllowed)
	mux.HandleFunc("/health", methodNotAllowed)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, r, http.StatusNotFound, codeRouteNotFound, "Route not found")
	})

	var h http.Handler = mux
	h = withDeadline(opts.RequestTimeout, h)
	h = withRecover(opts.Logger, h)
	h = withAccessLog(opts.Logger, h)
	h = withTrace(h)
	return h
}

func methodNotAllowed(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Allow", "GET, HEAD")
	writeError(w, r, http.StatusMethodNotAllowed, codeMethodNotAllowed, "Method "+r.Method+" not allowed")
}
