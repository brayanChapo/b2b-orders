package httpapi

import (
	"context"
	"errors"
	"log/slog"
	"net/http"

	"b2b-orders/products-api/internal/product"
)

type Catalog interface {
	Find(ctx context.Context, id product.ID, market product.Market) (product.Product, error)
}

type productResponse struct {
	ProductID   string `json:"productId"`
	Name        string `json:"name"`
	SKU         string `json:"sku"`
	Status      string `json:"status"`
	TaxCategory string `json:"taxCategory"`
}

func toResponse(p product.Product) productResponse {
	return productResponse{
		ProductID:   string(p.ID),
		Name:        p.Name,
		SKU:         p.SKU,
		Status:      string(p.Status),
		TaxCategory: string(p.TaxCategory),
	}
}

type productsHandler struct {
	catalog Catalog
	logger  *slog.Logger
}

func (h *productsHandler) get(w http.ResponseWriter, r *http.Request) {
	rawID := r.PathValue("productId")
	rawMarket := r.URL.Query().Get("market")

	var issues []fieldIssue
	id, err := product.ParseID(rawID)
	if err != nil {
		issues = append(issues, fieldIssue{Field: "productId", Issue: "must be 1-64 characters: letters, digits or '-'"})
	}
	market, err := product.ParseMarket(rawMarket)
	if err != nil {
		issue := "must be one of MX, CO, PE"
		if rawMarket == "" {
			issue = "is required"
		}
		issues = append(issues, fieldIssue{Field: "market", Issue: issue})
	}
	if len(issues) > 0 {
		writeError(w, r, http.StatusBadRequest, codeInvalidParameter, "Invalid request parameters", issues...)
		return
	}

	p, err := h.catalog.Find(r.Context(), id, market)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, toResponse(p))
	case errors.Is(err, product.ErrNotFound):
		writeError(w, r, http.StatusNotFound, codeProductNotFound,
			"Product "+string(id)+" not found in market "+string(market))
	case errors.Is(err, context.Canceled):
		// El cliente se fue: no hay a quién responder.
	case errors.Is(err, context.DeadlineExceeded):
		writeError(w, r, http.StatusServiceUnavailable, codeRequestTimeout, "Request timed out")
	default:
		h.logger.Error("error consultando el catálogo", "error", err, "trace_id", traceIDFrom(r.Context()))
		writeError(w, r, http.StatusInternalServerError, codeInternalError, "Internal server error")
	}
}
