package product

import (
	"errors"
	"strings"
	"testing"
)

func TestParseID(t *testing.T) {
	tests := []struct {
		name    string
		raw     string
		wantErr bool
	}{
		{name: "formato del contrato", raw: "PRD-001"},
		{name: "solo alfanumérico", raw: "ABC123"},
		{name: "longitud máxima", raw: strings.Repeat("A", 64)},
		{name: "vacío", raw: "", wantErr: true},
		{name: "excede longitud", raw: strings.Repeat("A", 65), wantErr: true},
		{name: "caracteres no permitidos", raw: "PRD_001", wantErr: true},
		{name: "intento de path traversal", raw: "../etc", wantErr: true},
		{name: "espacios", raw: "PRD 001", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			id, err := ParseID(tt.raw)
			if tt.wantErr {
				if !errors.Is(err, ErrInvalidID) {
					t.Fatalf("ParseID(%q) error = %v, se esperaba ErrInvalidID", tt.raw, err)
				}
				return
			}
			if err != nil {
				t.Fatalf("ParseID(%q) error inesperado: %v", tt.raw, err)
			}
			if string(id) != tt.raw {
				t.Fatalf("ParseID(%q) = %q", tt.raw, id)
			}
		})
	}
}

func TestParseMarket(t *testing.T) {
	tests := []struct {
		raw     string
		want    Market
		wantErr bool
	}{
		{raw: "MX", want: MarketMX},
		{raw: "CO", want: MarketCO},
		{raw: "PE", want: MarketPE},
		{raw: "", wantErr: true},
		{raw: "AR", wantErr: true},
		{raw: "mx", wantErr: true}, // estricto: el contrato define mayúsculas
		{raw: " MX", wantErr: true},
	}
	for _, tt := range tests {
		t.Run("market="+tt.raw, func(t *testing.T) {
			got, err := ParseMarket(tt.raw)
			if tt.wantErr {
				if !errors.Is(err, ErrUnsupportedMarket) {
					t.Fatalf("ParseMarket(%q) error = %v, se esperaba ErrUnsupportedMarket", tt.raw, err)
				}
				return
			}
			if err != nil || got != tt.want {
				t.Fatalf("ParseMarket(%q) = %q, %v; se esperaba %q", tt.raw, got, err, tt.want)
			}
		})
	}
}
