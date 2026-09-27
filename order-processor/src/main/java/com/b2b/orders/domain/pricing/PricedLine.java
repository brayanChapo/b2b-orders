package com.b2b.orders.domain.pricing;

import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.model.TaxCategory;
import java.math.BigDecimal;

public record PricedLine(
        ProductId productId,
        String name,
        String sku,
        TaxCategory taxCategory,
        long quantity,
        BigDecimal unitPrice,
        BigDecimal discountRate,
        BigDecimal taxRate,
        BigDecimal grossSubtotal,
        BigDecimal discount,
        BigDecimal netSubtotal,
        BigDecimal taxAmount,
        BigDecimal lineTotal) {
}
