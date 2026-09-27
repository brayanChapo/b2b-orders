package catalog

import (
	"context"
	"errors"
	"testing"

	"b2b-orders/products-api/internal/product"
)

func newSeeded(t *testing.T) *Memory {
	t.Helper()
	c, err := NewSeeded()
	if err != nil {
		t.Fatalf("la semilla debe ser válida: %v", err)
	}
	return c
}

func TestFind(t *testing.T) {
	c := newSeeded(t)
	ctx := context.Background()

	t.Run("producto existente en el mercado", func(t *testing.T) {
		p, err := c.Find(ctx, "PRD-001", product.MarketMX)
		if err != nil {
			t.Fatalf("error inesperado: %v", err)
		}
		if p.Name != "Bebida 600 ml" || p.SKU != "BEB-600-PET" || p.Market != product.MarketMX {
			t.Fatalf("producto inesperado: %+v", p)
		}
	})

	t.Run("producto que no se vende en ese mercado", func(t *testing.T) {
		_, err := c.Find(ctx, "PRD-005", product.MarketMX)
		if !errors.Is(err, product.ErrNotFound) {
			t.Fatalf("error = %v, se esperaba ErrNotFound", err)
		}
	})

	t.Run("producto inexistente", func(t *testing.T) {
		_, err := c.Find(ctx, "PRD-999", product.MarketPE)
		if !errors.Is(err, product.ErrNotFound) {
			t.Fatalf("error = %v, se esperaba ErrNotFound", err)
		}
	})

	t.Run("la categoría fiscal varía por mercado", func(t *testing.T) {
		mx, _ := c.Find(ctx, "PRD-002", product.MarketMX)
		co, _ := c.Find(ctx, "PRD-002", product.MarketCO)
		if mx.TaxCategory == co.TaxCategory {
			t.Fatalf("se esperaban categorías distintas, ambas %s", mx.TaxCategory)
		}
	})
}

func TestFindRespectsContext(t *testing.T) {
	c := newSeeded(t)

	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.Find(canceled, "PRD-001", product.MarketMX); !errors.Is(err, context.Canceled) {
		t.Fatalf("error = %v, se esperaba context.Canceled", err)
	}

	expired, cancel2 := context.WithTimeout(context.Background(), 0)
	defer cancel2()
	if _, err := c.Find(expired, "PRD-001", product.MarketMX); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v, se esperaba context.DeadlineExceeded", err)
	}
}

func TestNewMemoryRejectsInvalidSeed(t *testing.T) {
	_, err := NewMemory([]Entry{{ID: "bad id", Listings: []Listing{{product.MarketMX, product.StatusActive, product.TaxStandard}}}})
	if err == nil {
		t.Fatal("se esperaba error por ID inválido")
	}
	_, err = NewMemory([]Entry{{ID: "PRD-1", Listings: []Listing{
		{product.MarketMX, product.StatusActive, product.TaxStandard},
		{product.MarketMX, product.StatusDiscontinued, product.TaxStandard},
	}}})
	if err == nil {
		t.Fatal("se esperaba error por mercado duplicado")
	}
}

// El enunciado exige al menos 10 productos distribuidos entre los tres mercados.
func TestSeedCoversRequirements(t *testing.T) {
	entries := Seed()
	if len(entries) < 10 {
		t.Fatalf("la semilla tiene %d productos, se requieren al menos 10", len(entries))
	}
	perMarket := map[product.Market]int{}
	var discontinued int
	for _, e := range entries {
		for _, l := range e.Listings {
			perMarket[l.Market]++
			if l.Status == product.StatusDiscontinued {
				discontinued++
			}
		}
	}
	for _, m := range product.Markets() {
		if perMarket[m] < 3 {
			t.Errorf("mercado %s tiene %d productos, se esperaban al menos 3", m, perMarket[m])
		}
	}
	if discontinued == 0 {
		t.Error("la semilla debe incluir productos DISCONTINUED para probar rechazos")
	}
}

func TestSeedMatchesContractFixtures(t *testing.T) {
	c := newSeeded(t)
	required := []struct {
		id     product.ID
		market product.Market
		tax    product.TaxCategory
	}{
		{"PRD-001", product.MarketMX, product.TaxStandard},
		{"PRD-001", product.MarketPE, product.TaxStandard},
		{"PRD-008", product.MarketMX, product.TaxStandard},
		{"PRD-003", product.MarketCO, product.TaxReduced},
	}
	for _, r := range required {
		p, err := c.Find(context.Background(), r.id, r.market)
		if err != nil {
			t.Errorf("%s en %s: %v", r.id, r.market, err)
			continue
		}
		if p.Status != product.StatusActive || p.TaxCategory != r.tax {
			t.Errorf("%s en %s = %s/%s, se esperaba ACTIVE/%s", r.id, r.market, p.Status, p.TaxCategory, r.tax)
		}
	}
}
