package product

import (
	"errors"
	"fmt"
	"regexp"
)

var (
	ErrNotFound          = errors.New("product not found")
	ErrInvalidID         = errors.New("invalid product id")
	ErrUnsupportedMarket = errors.New("unsupported market")
)

type ID string

var idPattern = regexp.MustCompile(`^[A-Za-z0-9-]{1,64}$`)

func ParseID(raw string) (ID, error) {
	if !idPattern.MatchString(raw) {
		return "", fmt.Errorf("%w: must match %s", ErrInvalidID, idPattern.String())
	}
	return ID(raw), nil
}

type Market string

// Mercados soportados en v1.
const (
	MarketMX Market = "MX"
	MarketCO Market = "CO"
	MarketPE Market = "PE"
)

func Markets() []Market { return []Market{MarketMX, MarketCO, MarketPE} }

func ParseMarket(raw string) (Market, error) {
	for _, m := range Markets() {
		if Market(raw) == m {
			return m, nil
		}
	}
	return "", fmt.Errorf("%w: must be one of MX, CO, PE", ErrUnsupportedMarket)
}

type Status string

// Estados posibles.
const (
	StatusActive       Status = "ACTIVE"
	StatusDiscontinued Status = "DISCONTINUED"
)

type TaxCategory string

// Categorías fiscales posibles.
const (
	TaxStandard TaxCategory = "STANDARD"
	TaxReduced  TaxCategory = "REDUCED"
	TaxExempt   TaxCategory = "EXEMPT"
)

type Product struct {
	ID          ID
	Name        string
	SKU         string
	Market      Market
	Status      Status
	TaxCategory TaxCategory
}
