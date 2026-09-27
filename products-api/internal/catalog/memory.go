package catalog

import (
	"context"
	"fmt"

	"b2b-orders/products-api/internal/product"
)

type Listing struct {
	Market      product.Market
	Status      product.Status
	TaxCategory product.TaxCategory
}

type Entry struct {
	ID       product.ID
	Name     string
	SKU      string
	Listings []Listing
}

type key struct {
	id     product.ID
	market product.Market
}

type Memory struct {
	items map[key]product.Product
}

func NewMemory(entries []Entry) (*Memory, error) {
	items := make(map[key]product.Product)
	for _, e := range entries {
		if _, err := product.ParseID(string(e.ID)); err != nil {
			return nil, fmt.Errorf("seed %q: %w", e.ID, err)
		}
		for _, l := range e.Listings {
			k := key{id: e.ID, market: l.Market}
			if _, dup := items[k]; dup {
				return nil, fmt.Errorf("seed %q: duplicated market %s", e.ID, l.Market)
			}
			items[k] = product.Product{
				ID:          e.ID,
				Name:        e.Name,
				SKU:         e.SKU,
				Market:      l.Market,
				Status:      l.Status,
				TaxCategory: l.TaxCategory,
			}
		}
	}
	return &Memory{items: items}, nil
}

func NewSeeded() (*Memory, error) {
	return NewMemory(Seed())
}

func (m *Memory) Find(ctx context.Context, id product.ID, market product.Market) (product.Product, error) {
	if err := ctx.Err(); err != nil {
		return product.Product{}, err
	}
	p, ok := m.items[key{id: id, market: market}]
	if !ok {
		return product.Product{}, product.ErrNotFound
	}
	return p, nil
}

func (m *Memory) Len() int { return len(m.items) }
