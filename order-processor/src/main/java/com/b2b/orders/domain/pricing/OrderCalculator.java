package com.b2b.orders.domain.pricing;

import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.OrderItem;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;

public final class OrderCalculator {

    private final TaxPolicy taxPolicy;
    private final DiscountPolicy discountPolicy;

    public OrderCalculator(TaxPolicy taxPolicy, DiscountPolicy discountPolicy) {
        this.taxPolicy = taxPolicy;
        this.discountPolicy = discountPolicy;
    }

    /**
     * @throws IllegalStateException si falta un producto: el pedido debe ser elegible.
     */
    public PricedOrder price(OrderRequest order, Client client, Map<ProductId, Product> products) {
        var lines = new ArrayList<PricedLine>(order.items().size());
        for (OrderItem item : order.items()) {
            Product product = products.get(item.productId());
            if (product == null) {
                throw new IllegalStateException("Producto no disponible para calcular: " + item.productId());
            }
            lines.add(priceLine(order, client, item, product));
        }
        return new PricedOrder(lines, OrderTotals.of(lines));
    }

    private PricedLine priceLine(OrderRequest order, Client client, OrderItem item, Product product) {
        BigDecimal discountRate = discountPolicy.rateFor(client.segment(), item.quantity());
        BigDecimal taxRate = taxPolicy.rateFor(order.market(), product.taxCategory(), client.taxRegime());

        BigDecimal gross = Money.round(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
        BigDecimal discount = Money.round(gross.multiply(discountRate));
        BigDecimal net = gross.subtract(discount);
        BigDecimal tax = Money.round(net.multiply(taxRate));
        BigDecimal total = net.add(tax);

        return new PricedLine(
                product.id(), product.name(), product.sku(), product.taxCategory(),
                item.quantity(), item.unitPrice(), discountRate, taxRate,
                gross, discount, net, tax, total);
    }
}
