package com.b2b.orders.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

public record OrderItem(ProductId productId, long quantity, BigDecimal unitPrice) {

    public OrderItem {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity debe ser mayor que cero");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice no puede ser negativo");
        }
    }
}
