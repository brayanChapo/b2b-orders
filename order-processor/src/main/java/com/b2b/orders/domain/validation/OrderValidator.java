package com.b2b.orders.domain.validation;

import com.b2b.orders.domain.model.ClientId;
import com.b2b.orders.domain.model.Currency;
import com.b2b.orders.domain.model.EventId;
import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.OrderId;
import com.b2b.orders.domain.model.OrderItem;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.validation.OrderDraft.ItemDraft;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public final class OrderValidator {

    public OrderRequest validate(OrderDraft draft) {
        var violations = new ArrayList<Violation>();

        requireText(draft.eventId(), "eventId", violations);
        requireText(draft.orderId(), "orderId", violations);
        requireText(draft.clientId(), "clientId", violations);

        if (draft.eventVersion() == null) {
            violations.add(missing("eventVersion"));
        } else if (draft.eventVersion() < 1) {
            violations.add(new Violation(ViolationCode.INVALID_EVENT_VERSION, "eventVersion",
                    "debe ser mayor o igual que 1"));
        }

        Optional<Market> market = validateMarket(draft.market(), violations);
        Optional<Currency> currency = validateCurrency(draft.currency(), market, violations);
        List<OrderItem> items = validateItems(draft.items(), violations);

        if (!violations.isEmpty()) {
            throw new InvalidOrderException(violations);
        }
        return new OrderRequest(
                new EventId(draft.eventId()),
                draft.eventVersion(),
                draft.occurredAt(),
                new OrderId(draft.orderId()),
                market.orElseThrow(),
                currency.orElseThrow(),
                new ClientId(draft.clientId()),
                draft.channel(),
                items);
    }

    private static Optional<Market> validateMarket(String code, List<Violation> violations) {
        if (isBlank(code)) {
            violations.add(missing("market"));
            return Optional.empty();
        }
        Optional<Market> market = Market.fromCode(code);
        if (market.isEmpty()) {
            violations.add(new Violation(ViolationCode.UNSUPPORTED_MARKET, "market",
                    "mercado " + code + " no soportado (MX, CO, PE)"));
        }
        return market;
    }

    private static Optional<Currency> validateCurrency(String code, Optional<Market> market,
            List<Violation> violations) {
        if (isBlank(code)) {
            violations.add(missing("currency"));
            return Optional.empty();
        }
        // Sin mercado válido no hay contra qué comparar: ese problema ya está reportado.
        if (market.isEmpty()) {
            return Optional.empty();
        }
        Currency expected = market.get().currency();
        if (!expected.name().equals(code)) {
            violations.add(new Violation(ViolationCode.CURRENCY_MARKET_MISMATCH, "currency",
                    "la moneda " + code + " no corresponde al mercado " + market.get() + " (se esperaba " + expected + ")"));
            return Optional.empty();
        }
        return Optional.of(expected);
    }

    private static List<OrderItem> validateItems(List<ItemDraft> drafts, List<Violation> violations) {
        if (drafts == null) {
            violations.add(missing("items"));
            return List.of();
        }
        if (drafts.isEmpty()) {
            violations.add(new Violation(ViolationCode.EMPTY_ITEMS, "items", "debe contener al menos un elemento"));
            return List.of();
        }
        var items = new ArrayList<OrderItem>();
        var seen = new HashSet<String>();
        for (int i = 0; i < drafts.size(); i++) {
            ItemDraft draft = drafts.get(i);
            String path = "items[" + i + "]";
            int before = violations.size();

            if (draft == null) {
                violations.add(missing(path));
                continue;
            }
            if (isBlank(draft.productId())) {
                violations.add(missing(path + ".productId"));
            } else if (!seen.add(draft.productId())) {
                violations.add(new Violation(ViolationCode.DUPLICATE_PRODUCT, path + ".productId",
                        "el producto " + draft.productId() + " ya aparece en el pedido"));
            }
            if (draft.quantity() == null) {
                violations.add(missing(path + ".quantity"));
            } else if (draft.quantity() <= 0) {
                violations.add(new Violation(ViolationCode.INVALID_QUANTITY, path + ".quantity",
                        "debe ser un entero mayor que cero"));
            }
            if (draft.unitPrice() == null) {
                violations.add(missing(path + ".unitPrice"));
            } else if (draft.unitPrice().signum() < 0) {
                violations.add(new Violation(ViolationCode.NEGATIVE_UNIT_PRICE, path + ".unitPrice",
                        "debe ser mayor o igual que cero"));
            }

            if (violations.size() == before) {
                items.add(new OrderItem(new ProductId(draft.productId()), draft.quantity(), draft.unitPrice()));
            }
        }
        return items;
    }

    private static void requireText(String value, String field, List<Violation> violations) {
        if (isBlank(value)) {
            violations.add(missing(field));
        }
    }

    private static Violation missing(String field) {
        return new Violation(ViolationCode.MISSING_FIELD, field, "es obligatorio");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
