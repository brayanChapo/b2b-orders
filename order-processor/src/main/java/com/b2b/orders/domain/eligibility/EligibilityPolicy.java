package com.b2b.orders.domain.eligibility;

import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.OrderItem;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.model.TaxCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EligibilityPolicy {

    public List<RejectionReason> checkClient(OrderRequest order, Optional<Client> client) {
        if (client.isEmpty()) {
            return List.of(RejectionReason.ofClient(RejectionCode.CLIENT_NOT_FOUND,
                    "El cliente " + order.clientId() + " no existe"));
        }
        var reasons = new ArrayList<RejectionReason>();
        Client c = client.get();
        if (!c.isActive()) {
            reasons.add(RejectionReason.ofClient(RejectionCode.CLIENT_NOT_ACTIVE,
                    "El estado del cliente es " + c.status()));
        }
        if (!c.belongsTo(order.market())) {
            reasons.add(RejectionReason.ofClient(RejectionCode.CLIENT_MARKET_MISMATCH,
                    "El cliente pertenece al mercado " + c.marketCode() + " y el pedido es de " + order.market()));
        }
        return List.copyOf(reasons);
    }

    /**
     * @param products productos encontrados, por id. Un producto ausente del mapa es un
     *                 producto que el proveedor informó como inexistente (404).
     */
    public List<RejectionReason> checkProducts(OrderRequest order, Map<ProductId, Product> products) {
        var reasons = new ArrayList<RejectionReason>();
        for (OrderItem item : order.items()) {
            Product product = products.get(item.productId());
            if (product == null) {
                reasons.add(RejectionReason.ofProduct(RejectionCode.PRODUCT_NOT_FOUND, item.productId(),
                        "El producto no existe en el mercado " + order.market()));
                continue;
            }
            if (!product.isActive()) {
                reasons.add(RejectionReason.ofProduct(RejectionCode.PRODUCT_NOT_ACTIVE, item.productId(),
                        "El estado del producto es " + product.status()));
            }
            if (product.taxCategory() == TaxCategory.UNKNOWN) {
                reasons.add(RejectionReason.ofProduct(RejectionCode.PRODUCT_TAX_CATEGORY_UNKNOWN, item.productId(),
                        "La categoría fiscal del producto no es reconocida"));
            }
        }
        return List.copyOf(reasons);
    }
}
