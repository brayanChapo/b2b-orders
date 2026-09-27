package com.b2b.orders.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.b2b.orders.application.port.DependencyException;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientId;
import com.b2b.orders.domain.model.ClientStatus;
import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.model.Segment;
import com.b2b.orders.domain.model.TaxCategory;
import com.b2b.orders.infrastructure.config.AppProperties;
import com.b2b.orders.infrastructure.config.HttpConfiguration;
import com.b2b.orders.support.StubCatalogServer;
import com.b2b.orders.support.StubCatalogServer.Reply;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

/** Adaptadores HTTP reales (RestClient + timeouts + reintentos) contra un servidor local. */
class HttpCatalogsIT {

    private static final AppProperties.Http HTTP = new AppProperties.Http(Duration.ofMillis(500), Duration.ofMillis(300),
            3, Duration.ofMillis(10), 2.0, 0.2, Duration.ofSeconds(2), 4);

    private StubCatalogServer server;
    private HttpClientCatalog clients;
    private HttpProductCatalog products;

    @BeforeEach
    void start() throws IOException {
        server = new StubCatalogServer()
                .client("CLI-1", "MX", "ACTIVE", "WHOLESALE", "GENERAL")
                .product("PRD-001", "MX", "ACTIVE", "STANDARD")
                .product("PRD-002", "MX", "ACTIVE", "REDUCED");
        RestClient rest = HttpConfiguration.restClient(RestClient.builder(), server.baseUrl(), HTTP);
        HttpRetrier retrier = new HttpRetrier(new RetryPolicy(HTTP.maxAttempts(), HTTP.initialBackoff(),
                HTTP.backoffMultiplier(), HTTP.jitter(), HTTP.maxRetryAfter()), (d, c, a) -> {
                });
        clients = new HttpClientCatalog(rest, retrier);
        products = new HttpProductCatalog(rest, retrier, HTTP.productParallelism());
    }

    @AfterEach
    void stop() {
        server.close();
        MDC.clear();
    }

    @Test
    void mapeaElClienteAlModeloDeDominio() {
        Client client = clients.findById(new ClientId("CLI-1")).orElseThrow();
        assertThat(client.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(client.segment()).isEqualTo(Segment.WHOLESALE);
        assertThat(client.marketCode()).isEqualTo("MX");
    }

    @Test
    void un404EsUnRecursoInexistenteYNoSeReintenta() {
        assertThat(clients.findById(new ClientId("CLI-NO"))).isEmpty();
        assertThat(server.hits("/clients/CLI-NO")).isEqualTo(1);
    }

    @Test
    void un503SeReintentaHastaAgotarLosIntentos() {
        server.failNext("/clients/CLI-1", Reply.status(503), Reply.status(503), Reply.status(503));

        assertThatThrownBy(() -> clients.findById(new ClientId("CLI-1")))
                .isInstanceOfSatisfying(DependencyException.class, e -> {
                    assertThat(e.isTransient()).isTrue();
                    assertThat(e.code()).isEqualTo("CLIENTS_API_503");
                    assertThat(e.attempts()).isEqualTo(3);
                });
        assertThat(server.hits("/clients/CLI-1")).isEqualTo(3);
    }

    @Test
    void unErrorTransitorioSeRecuperaEnElSiguienteIntento() {
        server.failNext("/clients/CLI-1", Reply.status(502));
        assertThat(clients.findById(new ClientId("CLI-1"))).isPresent();
        assertThat(server.hits("/clients/CLI-1")).isEqualTo(2);
    }

    @Test
    void un429RespetaRetryAfter() {
        server.failNext("/clients/CLI-1", new Reply(429, "{}", Map.of("Retry-After", "1"), 0));
        long start = System.nanoTime();

        assertThat(clients.findById(new ClientId("CLI-1"))).isPresent();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    @Test
    void un400EsDefinitivoYNoSeReintenta() {
        server.failNext("/clients/CLI-1", Reply.status(400));
        assertThatThrownBy(() -> clients.findById(new ClientId("CLI-1")))
                .isInstanceOfSatisfying(DependencyException.class, e -> assertThat(e.isTransient()).isFalse());
        assertThat(server.hits("/clients/CLI-1")).isEqualTo(1);
    }

    @Test
    void unTimeoutEsTransitorio() {
        server.route("/clients/CLI-SLOW", Reply.slow(1_000, "{}"));
        assertThatThrownBy(() -> clients.findById(new ClientId("CLI-SLOW")))
                .isInstanceOfSatisfying(DependencyException.class, e -> {
                    assertThat(e.isTransient()).isTrue();
                    assertThat(e.code()).isEqualTo("CLIENTS_API_UNAVAILABLE");
                });
    }

    @Test
    void unCuerpoFueraDeContratoEsDefinitivo() {
        server.route("/clients/CLI-BAD", Reply.json("{\"name\":\"sin id\"}"));
        assertThatThrownBy(() -> clients.findById(new ClientId("CLI-BAD")))
                .isInstanceOfSatisfying(DependencyException.class, e -> {
                    assertThat(e.isTransient()).isFalse();
                    assertThat(e.code()).isEqualTo("CLIENTS_API_INVALID_BODY");
                });
    }

    @Test
    void unValorDeEnumDesconocidoNoRompeLaLectura() {
        server.client("CLI-NEW", "MX", "SUSPENDED", "VIP", "GENERAL");
        Client client = clients.findById(new ClientId("CLI-NEW")).orElseThrow();
        assertThat(client.status()).isEqualTo(ClientStatus.UNKNOWN);
        assertThat(client.segment()).isEqualTo(Segment.UNKNOWN);
    }

    @Test
    void consultaVariosProductosYOmiteLosInexistentes() {
        Map<ProductId, Product> found = products.findAll(
                List.of(new ProductId("PRD-001"), new ProductId("PRD-002"), new ProductId("PRD-404")), Market.MX);

        assertThat(found).containsOnlyKeys(new ProductId("PRD-001"), new ProductId("PRD-002"));
        assertThat(found.get(new ProductId("PRD-002")).taxCategory()).isEqualTo(TaxCategory.REDUCED);
    }

    @Test
    void consultaLosProductosEnParalelo() {
        List<ProductId> ids = IntStream.rangeClosed(1, 8).mapToObj(i -> new ProductId("PRD-S" + i)).toList();
        ids.forEach(id -> server.route("/products/" + id.value() + "?market=MX", Reply.slow(200,
                "{\"productId\":\"" + id.value() + "\",\"status\":\"ACTIVE\",\"taxCategory\":\"STANDARD\"}")));
        long start = System.nanoTime();

        assertThat(products.findAll(ids, Market.MX)).hasSize(8);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_000));
    }

    @Test
    void unFalloEnUnProductoFallaLaConsultaCompleta() {
        server.failNext("/products/PRD-002?market=MX", Reply.status(503), Reply.status(503), Reply.status(503));
        assertThatThrownBy(() -> products.findAll(List.of(new ProductId("PRD-001"), new ProductId("PRD-002")), Market.MX))
                .isInstanceOfSatisfying(DependencyException.class, e -> assertThat(e.code()).isEqualTo("PRODUCTS_API_503"));
    }

    @Test
    void propagaElTraceIdComoXRequestIdTambienEnLasConsultasParalelas() {
        MDC.put("traceId", "01J8ZP6M5E4RH0K7Y2N9A3TQWX");
        clients.findById(new ClientId("CLI-1"));
        products.findAll(List.of(new ProductId("PRD-001"), new ProductId("PRD-002")), Market.MX);

        assertThat(server.requestIds()).hasSize(3).containsOnly("01J8ZP6M5E4RH0K7Y2N9A3TQWX");
    }
}
