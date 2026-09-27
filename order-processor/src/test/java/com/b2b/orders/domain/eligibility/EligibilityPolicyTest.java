package com.b2b.orders.domain.eligibility;

import static com.b2b.orders.domain.TestData.item;
import static com.b2b.orders.domain.TestData.order;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EligibilityPolicyTest {

    private final EligibilityPolicy policy = new EligibilityPolicy();
    private final OrderRequest order = order("MX", item("P-1", 1, "10"), item("P-2", 1, "10"), item("P-3", 1, "10"));

    private static Client client(ClientStatus status, String market) {
        return new Client(new ClientId("CLI-1"), "Cliente", status, Segment.RETAIL, TaxRegime.GENERAL, market);
    }

    private static Product product(String id, ProductStatus status, TaxCategory category) {
        return new Product(new ProductId(id), id, "SKU", status, category);
    }

    private static List<RejectionCode> codes(List<RejectionReason> reasons) {
        return reasons.stream().map(RejectionReason::code).toList();
    }

    @Nested
    class Cliente {

        @Test
        void clienteActivoDelMismoMercadoEsElegible() {
            assertThat(policy.checkClient(order, Optional.of(client(ClientStatus.ACTIVE, "MX")))).isEmpty();
        }

        @Test
        void clienteInexistente() {
            assertThat(codes(policy.checkClient(order, Optional.empty()))).containsExactly(RejectionCode.CLIENT_NOT_FOUND);
        }

        @Test
        void clienteBloqueado() {
            assertThat(codes(policy.checkClient(order, Optional.of(client(ClientStatus.BLOCKED, "MX")))))
                    .containsExactly(RejectionCode.CLIENT_NOT_ACTIVE);
        }

        @Test
        void estadoDesconocidoNoSeConsideraActivo() {
            assertThat(codes(policy.checkClient(order, Optional.of(client(ClientStatus.UNKNOWN, "MX")))))
                    .containsExactly(RejectionCode.CLIENT_NOT_ACTIVE);
        }

        @Test
        void mercadoDistintoAlDelPedido() {
            assertThat(codes(policy.checkClient(order, Optional.of(client(ClientStatus.ACTIVE, "PE")))))
                    .containsExactly(RejectionCode.CLIENT_MARKET_MISMATCH);
        }

        @Test
        void mercadoQueEsteServicioNoSoporta() {
            assertThat(codes(policy.checkClient(order, Optional.of(client(ClientStatus.ACTIVE, "AR")))))
                    .containsExactly(RejectionCode.CLIENT_MARKET_MISMATCH);
        }

        @Test
        void reportaTodasLasRazonesEnOrdenDeterminista() {
            assertThat(codes(policy.checkClient(order, Optional.of(client(ClientStatus.BLOCKED, "CO")))))
                    .containsExactly(RejectionCode.CLIENT_NOT_ACTIVE, RejectionCode.CLIENT_MARKET_MISMATCH);
        }
    }

    @Nested
    class Productos {

        @Test
        void todosActivosEsElegible() {
            Map<ProductId, Product> products = Map.of(
                    new ProductId("P-1"), product("P-1", ProductStatus.ACTIVE, TaxCategory.STANDARD),
                    new ProductId("P-2"), product("P-2", ProductStatus.ACTIVE, TaxCategory.REDUCED),
                    new ProductId("P-3"), product("P-3", ProductStatus.ACTIVE, TaxCategory.EXEMPT));
            assertThat(policy.checkProducts(order, products)).isEmpty();
        }

        @Test
        void reportaCadaProductoEnElOrdenDeLasLineas() {
            Map<ProductId, Product> products = Map.of(
                    new ProductId("P-1"), product("P-1", ProductStatus.DISCONTINUED, TaxCategory.STANDARD),
                    // P-2 ausente: el proveedor respondió 404
                    new ProductId("P-3"), product("P-3", ProductStatus.UNKNOWN, TaxCategory.STANDARD));

            List<RejectionReason> reasons = policy.checkProducts(order, products);

            assertThat(codes(reasons)).containsExactly(
                    RejectionCode.PRODUCT_NOT_ACTIVE, RejectionCode.PRODUCT_NOT_FOUND, RejectionCode.PRODUCT_NOT_ACTIVE);
            assertThat(reasons).extracting(r -> r.productIdIfPresent().orElseThrow().value())
                    .containsExactly("P-1", "P-2", "P-3");
        }

        @Test
        void categoriaFiscalDesconocidaNoEsElegible() {
            Map<ProductId, Product> products = Map.of(
                    new ProductId("P-1"), product("P-1", ProductStatus.ACTIVE, TaxCategory.UNKNOWN),
                    new ProductId("P-2"), product("P-2", ProductStatus.ACTIVE, TaxCategory.STANDARD),
                    new ProductId("P-3"), product("P-3", ProductStatus.ACTIVE, TaxCategory.STANDARD));
            assertThat(codes(policy.checkProducts(order, products)))
                    .containsExactly(RejectionCode.PRODUCT_TAX_CATEGORY_UNKNOWN);
        }
    }
}
