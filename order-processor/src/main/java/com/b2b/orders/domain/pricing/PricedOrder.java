package com.b2b.orders.domain.pricing;

import java.util.List;

public record PricedOrder(List<PricedLine> lines, OrderTotals totals) {

    public PricedOrder {
        lines = List.copyOf(lines);
    }
}
