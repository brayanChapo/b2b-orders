package com.b2b.orders.domain;

import com.b2b.orders.domain.eligibility.EligibilityPolicy;
import com.b2b.orders.domain.eligibility.RejectionReason;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.pricing.OrderCalculator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OrderEvaluator {

    private final EligibilityPolicy eligibility;
    private final OrderCalculator calculator;

    public OrderEvaluator(EligibilityPolicy eligibility, OrderCalculator calculator) {
        this.eligibility = eligibility;
        this.calculator = calculator;
    }

    /** Rechazo por cliente (no existe, no está activo o es de otro mercado), si corresponde. */
    public Optional<OrderDecision.Rejected> screenClient(OrderRequest order, Optional<Client> client) {
        List<RejectionReason> reasons = eligibility.checkClient(order, client);
        return reasons.isEmpty() ? Optional.empty() : Optional.of(new OrderDecision.Rejected(reasons));
    }

    /**
     * Decisión completa. Vuelve a verificar al cliente para que el resultado sea correcto
     * aunque se llame sin screenClient.
     *
     * @param products productos encontrados; un id ausente significa "no existe" (404).
     */
    public OrderDecision decide(OrderRequest order, Client client, Map<ProductId, Product> products) {
        Optional<OrderDecision.Rejected> byClient = screenClient(order, Optional.of(client));
        if (byClient.isPresent()) {
            return byClient.get();
        }
        List<RejectionReason> byProducts = eligibility.checkProducts(order, products);
        if (!byProducts.isEmpty()) {
            return new OrderDecision.Rejected(byProducts);
        }
        return new OrderDecision.Approved(calculator.price(order, client, products));
    }
}
