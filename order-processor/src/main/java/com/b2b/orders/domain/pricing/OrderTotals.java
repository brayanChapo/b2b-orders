package com.b2b.orders.domain.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

public record OrderTotals(
        BigDecimal grossSubtotal,
        BigDecimal discount,
        BigDecimal netSubtotal,
        BigDecimal tax,
        BigDecimal grandTotal) {

    public static OrderTotals of(List<PricedLine> lines) {
        return new OrderTotals(
                sum(lines, PricedLine::grossSubtotal),
                sum(lines, PricedLine::discount),
                sum(lines, PricedLine::netSubtotal),
                sum(lines, PricedLine::taxAmount),
                sum(lines, PricedLine::lineTotal));
    }

    private static BigDecimal sum(List<PricedLine> lines, Function<PricedLine, BigDecimal> amount) {
        // Sumar importes de 2 decimales produce 2 decimales exactos: no hay nada que redondear.
        return lines.stream().map(amount).reduce(Money.zero(), BigDecimal::add);
    }
}
