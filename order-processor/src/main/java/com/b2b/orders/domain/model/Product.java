package com.b2b.orders.domain.model;

import java.util.Objects;

public record Product(
        ProductId id,
        String name,
        String sku,
        ProductStatus status,
        TaxCategory taxCategory) {

    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(taxCategory, "taxCategory");
    }

    public boolean isActive() {
        return status == ProductStatus.ACTIVE;
    }
}
