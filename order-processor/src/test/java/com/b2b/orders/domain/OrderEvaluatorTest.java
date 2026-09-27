package com.b2b.orders.domain;

import static com.b2b.orders.domain.TestData.PRD_001;
import static com.b2b.orders.domain.TestData.PRD_008;
import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.domain.eligibility.EligibilityPolicy;
import com.b2b.orders.domain.eligibility.RejectionCode;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientStatus;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.model.ProductStatus;
import com.b2b.orders.domain.model.TaxCategory;
import com.b2b.orders.domain.pricing.DiscountPolicy;
import com.b2b.orders.domain.pricing.OrderCalculator;
import com.b2b.orders.domain.pricing.TaxPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderEvaluatorTest {

    private final OrderEvaluator evaluator = new OrderEvaluator(
            new EligibilityPolicy(), new OrderCalculator(new TaxPolicy(), new DiscountPolicy()));

    @Test
    void pedidoElegibleSeApruebaConSusTotales() {
        OrderDecision decision = evaluator.decide(TestData.goldenOrder(), TestData.goldenClient(), TestData.goldenProducts());

        assertThat(decision).isInstanceOf(OrderDecision.Approved.class);
        assertThat(((OrderDecision.Approved) decision).pricing().totals().grandTotal()).isEqualTo("2100.11");
    }

    @Test
    void clienteNoElegibleSeRechazaSinNecesitarProductos() {
        Optional<OrderDecision.Rejected> rejected = evaluator.screenClient(TestData.goldenOrder(), Optional.empty());

        assertThat(rejected).isPresent();
        assertThat(rejected.get().primary().code()).isEqualTo(RejectionCode.CLIENT_NOT_FOUND);
    }

    @Test
    void clienteElegiblePasaElFiltro() {
        assertThat(evaluator.screenClient(TestData.goldenOrder(), Optional.of(TestData.goldenClient()))).isEmpty();
    }

    @Test
    void productoDiscontinuadoRechazaElPedidoSinCalcular() {
        Map<ProductId, Product> products = new HashMap<>(TestData.goldenProducts());
        products.put(PRD_008, new Product(PRD_008, "x", "y", ProductStatus.DISCONTINUED, TaxCategory.STANDARD));

        OrderDecision decision = evaluator.decide(TestData.goldenOrder(), TestData.goldenClient(), products);

        assertThat(decision).isInstanceOf(OrderDecision.Rejected.class);
        OrderDecision.Rejected rejected = (OrderDecision.Rejected) decision;
        assertThat(rejected.primary().code()).isEqualTo(RejectionCode.PRODUCT_NOT_ACTIVE);
        assertThat(rejected.primary().productIdIfPresent()).contains(PRD_008);
    }

    @Test
    void productoInexistenteRechazaElPedido() {
        Map<ProductId, Product> products = Map.of(PRD_008, TestData.goldenProducts().get(PRD_008));

        OrderDecision decision = evaluator.decide(TestData.goldenOrder(), TestData.goldenClient(), products);

        assertThat(((OrderDecision.Rejected) decision).primary().code()).isEqualTo(RejectionCode.PRODUCT_NOT_FOUND);
        assertThat(((OrderDecision.Rejected) decision).primary().productIdIfPresent()).contains(PRD_001);
    }

    @Test
    void decideVuelveAVerificarAlClienteAunqueNoSeLlameScreenClient() {
        Client golden = TestData.goldenClient();
        Client blocked = new Client(golden.id(), golden.name(), ClientStatus.BLOCKED,
                golden.segment(), golden.taxRegime(), golden.marketCode());

        OrderDecision decision = evaluator.decide(TestData.goldenOrder(), blocked, TestData.goldenProducts());

        assertThat(((OrderDecision.Rejected) decision).primary().code()).isEqualTo(RejectionCode.CLIENT_NOT_ACTIVE);
    }
}
