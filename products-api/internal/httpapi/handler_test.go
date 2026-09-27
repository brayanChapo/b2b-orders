package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"b2b-orders/products-api/internal/catalog"
	"b2b-orders/products-api/internal/product"
)

var discardLogger = slog.New(slog.NewTextHandler(io.Discard, nil))

type testOptions struct {
	catalog Catalog
	timeout time.Duration
	faults  bool
	health  *Health
}

func newHandler(t *testing.T, o testOptions) http.Handler {
	t.Helper()
	if o.catalog == nil {
		c, err := catalog.NewSeeded()
		if err != nil {
			t.Fatalf("semilla inválida: %v", err)
		}
		o.catalog = c
	}
	if o.timeout == 0 {
		o.timeout = time.Second
	}
	if o.health == nil {
		o.health = NewHealth()
	}
	return NewHandler(Options{
		Catalog: o.catalog, Health: o.health, Logger: discardLogger,
		RequestTimeout: o.timeout, FaultInjectionEnabled: o.faults,
	})
}

func do(t *testing.T, h http.Handler, method, target string, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, target, nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func decodeError(t *testing.T, rec *httptest.ResponseRecorder) errorBody {
	t.Helper()
	var body errorBody
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("cuerpo de error no es JSON válido: %v\n%s", err, rec.Body.String())
	}
	if body.Status != rec.Code {
		t.Errorf("status en el cuerpo = %d, código HTTP = %d", body.Status, rec.Code)
	}
	if body.Timestamp == "" || body.Message == "" {
		t.Errorf("faltan campos obligatorios: %+v", body)
	}
	return body
}

type blockingCatalog struct{ observed chan error }

func (b *blockingCatalog) Find(ctx context.Context, _ product.ID, _ product.Market) (product.Product, error) {
	<-ctx.Done()
	b.observed <- ctx.Err()
	return product.Product{}, ctx.Err()
}

type failingCatalog struct{ err error }

func (f failingCatalog) Find(context.Context, product.ID, product.Market) (product.Product, error) {
	return product.Product{}, f.err
}

type panickingCatalog struct{}

func (panickingCatalog) Find(context.Context, product.ID, product.Market) (product.Product, error) {
	panic("boom")
}

// --- GET /products/{productId} -------------------------------------------------

func TestGetProductOK(t *testing.T) {
	rec := do(t, newHandler(t, testOptions{}), http.MethodGet, "/products/PRD-001?market=MX", nil)

	if rec.Code != http.StatusOK {
		t.Fatalf("código = %d, cuerpo = %s", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q", ct)
	}
	var got productResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("JSON inválido: %v", err)
	}
	want := productResponse{ProductID: "PRD-001", Name: "Bebida 600 ml", SKU: "BEB-600-PET", Status: "ACTIVE", TaxCategory: "STANDARD"}
	if got != want {
		t.Fatalf("respuesta = %+v, se esperaba %+v", got, want)
	}
	if rec.Header().Get("X-Trace-Id") == "" {
		t.Error("falta X-Trace-Id")
	}
}

func TestGetProductNotFound(t *testing.T) {
	h := newHandler(t, testOptions{})
	for _, target := range []string{
		"/products/PRD-999?market=MX", // no existe
		"/products/PRD-005?market=MX", // existe, pero no se vende en MX
	} {
		rec := do(t, h, http.MethodGet, target, nil)
		if rec.Code != http.StatusNotFound {
			t.Fatalf("%s: código = %d", target, rec.Code)
		}
		if body := decodeError(t, rec); body.Code != codeProductNotFound {
			t.Fatalf("%s: code = %s", target, body.Code)
		}
	}
}

func TestGetProductDiscontinuedIsReturned(t *testing.T) {
	// DISCONTINUED es un dato de negocio, no un error: decidir qué hacer es del consumidor.
	rec := do(t, newHandler(t, testOptions{}), http.MethodGet, "/products/PRD-006?market=MX", nil)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"status":"DISCONTINUED"`) {
		t.Fatalf("código = %d, cuerpo = %s", rec.Code, rec.Body.String())
	}
}

func TestGetProductValidation(t *testing.T) {
	h := newHandler(t, testOptions{})
	tests := []struct {
		name       string
		target     string
		wantFields []string
	}{
		{"falta market", "/products/PRD-001", []string{"market"}},
		{"market vacío", "/products/PRD-001?market=", []string{"market"}},
		{"market no soportado", "/products/PRD-001?market=AR", []string{"market"}},
		{"market en minúsculas", "/products/PRD-001?market=mx", []string{"market"}},
		{"productId con caracteres inválidos", "/products/PRD_001?market=MX", []string{"productId"}},
		{"productId demasiado largo", "/products/" + strings.Repeat("A", 65) + "?market=MX", []string{"productId"}},
		{"ambos inválidos a la vez", "/products/PRD%20001?market=XX", []string{"productId", "market"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rec := do(t, h, http.MethodGet, tt.target, nil)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("código = %d, cuerpo = %s", rec.Code, rec.Body.String())
			}
			body := decodeError(t, rec)
			if body.Code != codeInvalidParameter {
				t.Fatalf("code = %s", body.Code)
			}
			if len(body.Details) != len(tt.wantFields) {
				t.Fatalf("details = %+v, se esperaban campos %v", body.Details, tt.wantFields)
			}
			for i, f := range tt.wantFields {
				if body.Details[i].Field != f {
					t.Errorf("details[%d].field = %s, se esperaba %s", i, body.Details[i].Field, f)
				}
			}
		})
	}
}

// --- rutas y métodos -------------------------------------------------------------

func TestUnknownRoutesAndMethodsUseErrorContract(t *testing.T) {
	h := newHandler(t, testOptions{})

	rec := do(t, h, http.MethodPost, "/products/PRD-001?market=MX", nil)
	if rec.Code != http.StatusMethodNotAllowed || rec.Header().Get("Allow") == "" {
		t.Fatalf("POST: código = %d, Allow = %q", rec.Code, rec.Header().Get("Allow"))
	}
	if body := decodeError(t, rec); body.Code != codeMethodNotAllowed {
		t.Fatalf("POST: code = %s", body.Code)
	}

	for _, target := range []string{"/", "/products", "/products/PRD-001/extra", "/clients/CLI-1"} {
		rec := do(t, h, http.MethodGet, target, nil)
		if rec.Code != http.StatusNotFound {
			t.Fatalf("%s: código = %d", target, rec.Code)
		}
		if body := decodeError(t, rec); body.Code != codeRouteNotFound {
			t.Fatalf("%s: code = %s", target, body.Code)
		}
	}
}

// --- trazabilidad -------------------------------------------------------------------

func TestTraceIDPropagation(t *testing.T) {
	h := newHandler(t, testOptions{})
	const traceID = "4bf92f3577b34da6a3ce929d0e0e4736"

	rec := do(t, h, http.MethodGet, "/products/PRD-999?market=MX", map[string]string{
		"traceparent": "00-" + traceID + "-00f067aa0ba902b7-01",
	})
	if got := decodeError(t, rec).TraceID; got != traceID {
		t.Errorf("traceId del cuerpo = %q, se esperaba el de traceparent", got)
	}
	if got := rec.Header().Get("X-Trace-Id"); got != traceID {
		t.Errorf("X-Trace-Id = %q", got)
	}

	rec = do(t, h, http.MethodGet, "/products/PRD-999?market=MX", map[string]string{"X-Request-Id": "req-123"})
	if got := decodeError(t, rec).TraceID; got != "req-123" {
		t.Errorf("sin traceparent debe usarse X-Request-Id, se obtuvo %q", got)
	}

	rec = do(t, h, http.MethodGet, "/products/PRD-999?market=MX", map[string]string{"X-Request-Id": "<script>"})
	if got := decodeError(t, rec).TraceID; got == "<script>" || got == "" {
		t.Errorf("un X-Request-Id inválido debe reemplazarse, se obtuvo %q", got)
	}
}

// --- contexto: deadline y cancelación ---------------------------------------------

func TestDeadlineReachesCatalog(t *testing.T) {
	stub := &blockingCatalog{observed: make(chan error, 1)}
	h := newHandler(t, testOptions{catalog: stub, timeout: 20 * time.Millisecond})

	rec := do(t, h, http.MethodGet, "/products/PRD-001?market=MX", nil)

	if err := <-stub.observed; !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("el catálogo observó %v, se esperaba DeadlineExceeded", err)
	}
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("código = %d", rec.Code)
	}
	if body := decodeError(t, rec); body.Code != codeRequestTimeout {
		t.Fatalf("code = %s", body.Code)
	}
}

func TestClientCancellationReachesCatalog(t *testing.T) {
	stub := &blockingCatalog{observed: make(chan error, 1)}
	h := newHandler(t, testOptions{catalog: stub, timeout: time.Minute})

	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest(http.MethodGet, "/products/PRD-001?market=MX", nil).WithContext(ctx)
	rec := httptest.NewRecorder()

	done := make(chan struct{})
	go func() { h.ServeHTTP(rec, req); close(done) }()
	cancel() // el cliente se desconecta
	<-done

	if err := <-stub.observed; !errors.Is(err, context.Canceled) {
		t.Fatalf("el catálogo observó %v, se esperaba Canceled", err)
	}
	if rec.Body.Len() != 0 {
		t.Fatalf("no debe escribirse respuesta a un cliente que canceló: %s", rec.Body.String())
	}
}

// --- errores internos -----------------------------------------------------------------

func TestInternalErrorsDoNotLeakDetails(t *testing.T) {
	h := newHandler(t, testOptions{catalog: failingCatalog{err: errors.New("connection refused to db-primary:5432")}})
	rec := do(t, h, http.MethodGet, "/products/PRD-001?market=MX", nil)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("código = %d", rec.Code)
	}
	if strings.Contains(rec.Body.String(), "db-primary") {
		t.Fatalf("el error interno se filtró al cliente: %s", rec.Body.String())
	}
	if body := decodeError(t, rec); body.Code != codeInternalError {
		t.Fatalf("code = %s", body.Code)
	}
}

func TestPanicIsRecovered(t *testing.T) {
	rec := do(t, newHandler(t, testOptions{catalog: panickingCatalog{}}), http.MethodGet, "/products/PRD-001?market=MX", nil)
	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("código = %d", rec.Code)
	}
	if body := decodeError(t, rec); body.Code != codeInternalError {
		t.Fatalf("code = %s", body.Code)
	}
}

// --- /health -----------------------------------------------------------------------------

func TestHealth(t *testing.T) {
	health := NewHealth()
	h := newHandler(t, testOptions{health: health})

	rec := do(t, h, http.MethodGet, "/health", nil)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"UP"`) {
		t.Fatalf("código = %d, cuerpo = %s", rec.Code, rec.Body.String())
	}

	health.MarkShuttingDown()
	rec = do(t, h, http.MethodGet, "/health", nil)
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), `"DOWN"`) {
		t.Fatalf("durante el apagado: código = %d, cuerpo = %s", rec.Code, rec.Body.String())
	}
}

// --- inyección de fallos -----------------------------------------------------------------

func TestFaultInjectionByHeader(t *testing.T) {
	h := newHandler(t, testOptions{faults: true, timeout: 20 * time.Millisecond})
	tests := []struct {
		fault    string
		wantCode int
		wantBody string
	}{
		{"429", http.StatusTooManyRequests, codeRateLimited},
		{"500", http.StatusInternalServerError, codeInternalError},
		{"502", http.StatusBadGateway, codeBadGateway},
		{"503", http.StatusServiceUnavailable, codeServiceUnavailable},
		{"timeout", http.StatusServiceUnavailable, codeRequestTimeout},
	}
	for _, tt := range tests {
		t.Run(tt.fault, func(t *testing.T) {
			rec := do(t, h, http.MethodGet, "/products/PRD-001?market=MX", map[string]string{"X-Fault": tt.fault})
			if rec.Code != tt.wantCode {
				t.Fatalf("código = %d, se esperaba %d", rec.Code, tt.wantCode)
			}
			if body := decodeError(t, rec); body.Code != tt.wantBody {
				t.Fatalf("code = %s", body.Code)
			}
			if tt.fault == "429" && rec.Header().Get("Retry-After") == "" {
				t.Error("429 debe incluir Retry-After")
			}
		})
	}
}

func TestFaultInjectionByReservedID(t *testing.T) {
	h := newHandler(t, testOptions{faults: true})
	tests := map[string]int{
		"PRD-FAIL-400": http.StatusBadRequest,
		"PRD-FAIL-429": http.StatusTooManyRequests,
		"PRD-FAIL-503": http.StatusServiceUnavailable,
		"PRD-FAIL-XYZ": http.StatusNotFound, // tipo desconocido: se comporta como producto inexistente
	}
	for id, want := range tests {
		rec := do(t, h, http.MethodGet, "/products/"+id+"?market=MX", nil)
		if rec.Code != want {
			t.Errorf("%s: código = %d, se esperaba %d", id, rec.Code, want)
		}
	}
}

func TestFaultInjectionDisabledByDefault(t *testing.T) {
	h := newHandler(t, testOptions{faults: false})

	rec := do(t, h, http.MethodGet, "/products/PRD-001?market=MX", map[string]string{"X-Fault": "503"})
	if rec.Code != http.StatusOK {
		t.Fatalf("con la inyección deshabilitada el header debe ignorarse; código = %d", rec.Code)
	}
	rec = do(t, h, http.MethodGet, "/products/PRD-FAIL-503?market=MX", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("con la inyección deshabilitada un ID reservado es un producto inexistente; código = %d", rec.Code)
	}
}

func TestFaultInjectionNeverAffectsHealth(t *testing.T) {
	h := newHandler(t, testOptions{faults: true})
	rec := do(t, h, http.MethodGet, "/health", map[string]string{"X-Fault": "503"})
	if rec.Code != http.StatusOK {
		t.Fatalf("/health no debe fallar por inyección; código = %d", rec.Code)
	}
}
