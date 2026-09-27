package catalog

import "b2b-orders/products-api/internal/product"

func Seed() []Entry {
	const (
		mx = product.MarketMX
		co = product.MarketCO
		pe = product.MarketPE

		active       = product.StatusActive
		discontinued = product.StatusDiscontinued

		standard = product.TaxStandard
		reduced  = product.TaxReduced
		exempt   = product.TaxExempt
	)
	return []Entry{
		{ID: "PRD-001", Name: "Bebida 600 ml", SKU: "BEB-600-PET", Listings: []Listing{
			{mx, active, standard}, {co, active, standard}, {pe, active, standard},
		}},
		{ID: "PRD-002", Name: "Agua mineral 1 L", SKU: "AGU-1000-PET", Listings: []Listing{
			{mx, active, exempt}, {co, active, reduced}, {pe, active, standard},
		}},
		{ID: "PRD-003", Name: "Leche entera 1 L", SKU: "LEC-1000-TTR", Listings: []Listing{
			{mx, active, exempt}, {co, active, reduced}, {pe, active, reduced},
		}},
		{ID: "PRD-004", Name: "Galletas surtidas 500 g", SKU: "GAL-500-BOL", Listings: []Listing{
			{mx, active, standard}, {co, active, standard},
		}},
		{ID: "PRD-005", Name: "Café molido 250 g", SKU: "CAF-250-BOL", Listings: []Listing{
			{co, active, reduced}, {pe, active, standard},
		}},
		{ID: "PRD-006", Name: "Jugo de naranja 1 L", SKU: "JUG-1000-TTR", Listings: []Listing{
			{mx, discontinued, standard}, {pe, active, standard},
		}},
		{ID: "PRD-007", Name: "Arroz 5 kg", SKU: "ARR-5000-SAC", Listings: []Listing{
			{mx, active, exempt}, {co, active, exempt}, {pe, active, exempt},
		}},
		{ID: "PRD-008", Name: "Bebida energética 473 ml", SKU: "BEN-473-LAT", Listings: []Listing{
			{mx, active, standard}, {co, active, standard},
		}},
		{ID: "PRD-009", Name: "Aceite vegetal 1 L", SKU: "ACE-1000-PET", Listings: []Listing{
			{co, active, reduced}, {pe, discontinued, reduced},
		}},
		{ID: "PRD-010", Name: "Detergente 1 kg", SKU: "DET-1000-BOL", Listings: []Listing{
			{mx, active, standard}, {co, active, standard}, {pe, active, standard},
		}},
		{ID: "PRD-011", Name: "Papas fritas 150 g", SKU: "SNK-150-BOL", Listings: []Listing{
			{mx, discontinued, standard}, {pe, active, standard},
		}},
		{ID: "PRD-012", Name: "Atún en lata 170 g", SKU: "ATU-170-LAT", Listings: []Listing{
			{mx, active, reduced}, {co, active, standard}, {pe, active, reduced},
		}},
	}
}
