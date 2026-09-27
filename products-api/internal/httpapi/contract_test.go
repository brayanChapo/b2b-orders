package httpapi

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"testing"
	"time"
)

const contractsDir = "../../../contracts"

func contractFile(t *testing.T, rel string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(contractsDir, rel))
	if err != nil {
		t.Skipf("contratos no disponibles (%v): se omite la verificación de contrato", err)
	}
	return data
}

func TestContractProductResponseMatchesExample(t *testing.T) {
	var example map[string]any
	if err := json.Unmarshal(contractFile(t, "http/examples/product-prd-001-mx.json"), &example); err != nil {
		t.Fatalf("ejemplo inválido: %v", err)
	}

	rec := do(t, newHandler(t, testOptions{}), http.MethodGet, "/products/PRD-001?market=MX", nil)
	var actual map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &actual); err != nil {
		t.Fatalf("respuesta inválida: %v", err)
	}
	if !reflect.DeepEqual(actual, example) {
		t.Fatalf("la respuesta no coincide con el ejemplo del contrato\nreal:     %v\ncontrato: %v", actual, example)
	}
}

func TestContractErrorResponses(t *testing.T) {
	var schema struct {
		Required   []string `json:"required"`
		Properties struct {
			Code struct {
				Pattern string `json:"pattern"`
			} `json:"code"`
		} `json:"properties"`
	}
	if err := json.Unmarshal(contractFile(t, "http/error.schema.json"), &schema); err != nil {
		t.Fatalf("esquema inválido: %v", err)
	}
	codePattern := regexp.MustCompile(schema.Properties.Code.Pattern)

	h := newHandler(t, testOptions{faults: true})
	cases := []struct {
		method, target string
		headers        map[string]string
	}{
		{http.MethodGet, "/products/PRD-999?market=MX", nil},
		{http.MethodGet, "/products/PRD-001?market=AR", nil},
		{http.MethodGet, "/products/PRD-001?market=MX", map[string]string{"X-Fault": "429"}},
		{http.MethodGet, "/products/PRD-001?market=MX", map[string]string{"X-Fault": "503"}},
		{http.MethodPost, "/products/PRD-001?market=MX", nil},
		{http.MethodGet, "/nope", nil},
	}
	for _, c := range cases {
		rec := do(t, h, c.method, c.target, c.headers)
		var body map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatalf("%s %s: cuerpo no es JSON: %v", c.method, c.target, err)
		}
		for _, field := range schema.Required {
			if _, ok := body[field]; !ok {
				t.Errorf("%s %s: falta el campo obligatorio %q", c.method, c.target, field)
			}
		}
		if code, _ := body["code"].(string); !codePattern.MatchString(code) {
			t.Errorf("%s %s: code %q no cumple %s", c.method, c.target, code, codePattern)
		}
		if status, _ := body["status"].(float64); int(status) != rec.Code {
			t.Errorf("%s %s: status %v distinto del código HTTP %d", c.method, c.target, body["status"], rec.Code)
		}
		if ts, _ := body["timestamp"].(string); !isRFC3339(ts) {
			t.Errorf("%s %s: timestamp %q no es date-time", c.method, c.target, ts)
		}
	}
}

func isRFC3339(s string) bool {
	_, err := time.Parse(time.RFC3339, s)
	return err == nil
}
