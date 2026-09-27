package com.b2b.orders.domain;

import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientId;
import com.b2b.orders.domain.model.ClientStatus;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.model.ProductStatus;
import com.b2b.orders.domain.model.Segment;
import com.b2b.orders.domain.model.TaxCategory;
import com.b2b.orders.domain.model.TaxRegime;
import com.b2b.orders.domain.validation.OrderDraft;
import com.b2b.orders.domain.validation.OrderDraft.ItemDraft;
import com.b2b.orders.domain.validation.OrderValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class TestData {

    public static final ProductId PRD_001 = new ProductId("PRD-001");
    public static final ProductId PRD_008 = new ProductId("PRD-008");

    private TestData() {
    }

    public static OrderDraft goldenDraft() {
        return new OrderDraft(
                "01J8ZP6M5E4RH0K7Y2N9A3TQWX", 1, Instant.parse("2026-09-18T15:42:10Z"),
                "ORD-MX-000147", "MX", "MXN", "CLI-99821", "C1",
                List.of(item("PRD-001", 24, "35.5"), item("PRD-008", 12, "82.0")));
    }

    public static OrderRequest goldenOrder() {
        return new OrderValidator().validate(goldenDraft());
    }

    public static OrderDraft draft(String market, String currency, ItemDraft... items) {
        // Arrays.asList admite null: algunos tests validan líneas nulas.
        return new OrderDraft("EVT-1", 1, null, "ORD-1", market, currency, "CLI-1", null, Arrays.asList(items));
    }

    public static OrderRequest order(String market, ItemDraft... items) {
        String currency = com.b2b.orders.domain.model.Market.valueOf(market).currency().name();
        return new OrderValidator().validate(draft(market, currency, items));
    }

    public static ItemDraft item(String productId, long quantity, String unitPrice) {
        return new ItemDraft(productId, quantity, new BigDecimal(unitPrice));
    }

    public static Client client(Segment segment, TaxRegime regime, String market) {
        return new Client(new ClientId("CLI-1"), "Cliente", ClientStatus.ACTIVE, segment, regime, market);
    }

    public static Client goldenClient() {
        return new Client(new ClientId("CLI-99821"), "Distribuidora Central",
                ClientStatus.ACTIVE, Segment.WHOLESALE, TaxRegime.GENERAL, "MX");
    }

    public static Product product(String id, TaxCategory category) {
        return new Product(new ProductId(id), "Producto " + id, "SKU-" + id, ProductStatus.ACTIVE, category);
    }

    public static Map<ProductId, Product> goldenProducts() {
        return Map.of(
                PRD_001, new Product(PRD_001, "Bebida 600 ml", "BEB-600-PET", ProductStatus.ACTIVE, TaxCategory.STANDARD),
                PRD_008, new Product(PRD_008, "Bebida energética 473 ml", "BEN-473-LAT", ProductStatus.ACTIVE, TaxCategory.STANDARD));
    }

    public static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
