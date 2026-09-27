package com.b2b.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.application.port.DependencyException;
import com.b2b.orders.domain.OrderEvaluator;
import com.b2b.orders.domain.TestData;
import com.b2b.orders.domain.eligibility.EligibilityPolicy;
import com.b2b.orders.domain.eligibility.RejectionCode;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientStatus;
import com.b2b.orders.domain.model.EventId;
import com.b2b.orders.domain.model.OrderRequest;
import com.b2b.orders.domain.pricing.DiscountPolicy;
import com.b2b.orders.domain.pricing.OrderCalculator;
import com.b2b.orders.domain.pricing.TaxPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProcessOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T15:42:11Z");

    private final Fakes.Clients clients = new Fakes.Clients();
    private final Fakes.Products products = new Fakes.Products();
    private final Fakes.Store store = new Fakes.Store();
    private final ProcessOrderService service = new ProcessOrderService(clients, products, store,
            new OrderEvaluator(new EligibilityPolicy(), new OrderCalculator(new TaxPolicy(), new DiscountPolicy())),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final OrderRequest order = TestData.goldenOrder();
    private final EventMetadata metadata = new EventMetadata("orders.created.v1", 0, 10, NOW, "trace-1", "{}");

    @BeforeEach
    void seed() {
        Client client = TestData.goldenClient();
        clients.data.put(client.id(), client);
        products.data.putAll(TestData.goldenProducts());
    }

    private ProcessedOrder lastSaved() {
        return store.saved.getLast();
    }

    @Test
    void apruebaYGuardaElPedidoConSusTotales() {
        ProcessingOutcome outcome = service.process(order, metadata);

        assertThat(outcome.status()).contains(ProcessingStatus.APPROVED);
        assertThat(outcome.storeOutcome()).isEqualTo(StoreOutcome.SAVED);
        assertThat(lastSaved().pricing().totals().grandTotal()).isEqualTo("2100.11");
        assertThat(lastSaved().processedAt()).isEqualTo(NOW);
        assertThat(lastSaved().metadata()).isEqualTo(metadata);
    }

    @Test
    void clienteInexistenteSeRechazaSinConsultarProductos() {
        clients.data.clear();

        ProcessingOutcome outcome = service.process(order, metadata);

        assertThat(outcome.status()).contains(ProcessingStatus.REJECTED);
        assertThat(lastSaved().primaryReason().orElseThrow().code()).isEqualTo(RejectionCode.CLIENT_NOT_FOUND);
        assertThat(products.calls).isZero();
    }

    @Test
    void clienteBloqueadoSeRechazaConservandoSusDatos() {
        Client golden = TestData.goldenClient();
        clients.data.put(golden.id(), new Client(golden.id(), golden.name(), ClientStatus.BLOCKED,
                golden.segment(), golden.taxRegime(), golden.marketCode()));

        service.process(order, metadata);

        assertThat(lastSaved().status()).isEqualTo(ProcessingStatus.REJECTED);
        assertThat(lastSaved().clientIfKnown()).isPresent();
        assertThat(products.calls).isZero();
    }

    @Test
    void productoInexistenteRechazaElPedido() {
        products.data.remove(TestData.PRD_008);

        service.process(order, metadata);

        assertThat(lastSaved().status()).isEqualTo(ProcessingStatus.REJECTED);
        assertThat(lastSaved().primaryReason().orElseThrow().code()).isEqualTo(RejectionCode.PRODUCT_NOT_FOUND);
    }

    @Nested
    class FallosDeDependencias {

        @Test
        void errorTransitorioAgotadoTerminaEnTechnicalFailure() {
            products.failure = new DependencyException(DependencyException.Kind.TRANSIENT,
                    "products-api", "PRODUCTS_API_503", "503 after 3 attempts", 3, null);

            ProcessingOutcome outcome = service.process(order, metadata);

            assertThat(outcome.status()).contains(ProcessingStatus.TECHNICAL_FAILURE);
            TechnicalError error = lastSaved().error();
            assertThat(error.category()).isEqualTo(ErrorCategory.TRANSIENT_DEPENDENCY_EXHAUSTED);
            assertThat(error.code()).isEqualTo("PRODUCTS_API_503");
            assertThat(error.attempts()).isEqualTo(3);
            assertThat(lastSaved().clientIfKnown()).isPresent();
        }

        @Test
        void errorDefinitivoTerminaEnTechnicalFailureSinReintentos() {
            clients.failure = new DependencyException(DependencyException.Kind.DEFINITIVE,
                    "clients-api", "CLIENTS_API_400", "400", 1, null);

            service.process(order, metadata);

            assertThat(lastSaved().status()).isEqualTo(ProcessingStatus.TECHNICAL_FAILURE);
            assertThat(lastSaved().error().category()).isEqualTo(ErrorCategory.DEFINITIVE_DEPENDENCY_ERROR);
            assertThat(lastSaved().clientIfKnown()).isEmpty();
        }
    }

    @Nested
    class Idempotencia {

        private void existing(String eventId, int version, ProcessingStatus status) {
            store.states.put(order.orderId(), new StoredOrderState(new EventId(eventId), version, status));
        }

        @Test
        void mismoEventoYaProcesadoNoRepiteEfectosNiLlamadas() {
            existing(order.eventId().value(), order.eventVersion(), ProcessingStatus.APPROVED);

            ProcessingOutcome outcome = service.process(order, metadata);

            assertThat(outcome.storeOutcome()).isEqualTo(StoreOutcome.DUPLICATE);
            assertThat(outcome.status()).isEmpty();
            assertThat(store.saved).isEmpty();
            assertThat(clients.calls).isZero();
        }

        @Test
        void versionMenorQueLaVigenteSeDescarta() {
            existing("OTRO-EVENTO", order.eventVersion() + 1, ProcessingStatus.APPROVED);

            assertThat(service.process(order, metadata).storeOutcome()).isEqualTo(StoreOutcome.STALE);
            assertThat(store.saved).isEmpty();
        }

        @Test
        void otroEventoConLaMismaVersionEsUnConflicto() {
            existing("OTRO-EVENTO", order.eventVersion(), ProcessingStatus.APPROVED);

            assertThat(service.process(order, metadata).storeOutcome()).isEqualTo(StoreOutcome.CONFLICT);
            assertThat(store.saved).isEmpty();
        }

        @Test
        void unFalloTecnicoPuedeReprocesarseConElMismoEvento() {
            existing(order.eventId().value(), order.eventVersion(), ProcessingStatus.TECHNICAL_FAILURE);

            ProcessingOutcome outcome = service.process(order, metadata);

            assertThat(outcome.status()).contains(ProcessingStatus.APPROVED);
            assertThat(store.saved).hasSize(1);
        }

        @Test
        void siLaEscrituraCondicionalPierdeSeInformaSinEstado() {
            store.nextOutcome = StoreOutcome.DUPLICATE;

            ProcessingOutcome outcome = service.process(order, metadata);

            assertThat(outcome.status()).isEmpty();
            assertThat(outcome.storeOutcome()).isEqualTo(StoreOutcome.DUPLICATE);
        }
    }
}
