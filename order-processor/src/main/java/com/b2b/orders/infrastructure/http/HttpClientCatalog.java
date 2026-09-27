package com.b2b.orders.infrastructure.http;

import com.b2b.orders.application.port.ClientCatalog;
import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientId;
import java.util.Optional;
import org.springframework.web.client.RestClient;

public class HttpClientCatalog implements ClientCatalog {

    static final String DEPENDENCY = "clients-api";
    static final String PREFIX = "CLIENTS_API";

    private final RestClient rest;
    private final HttpRetrier retrier;

    public HttpClientCatalog(RestClient rest, HttpRetrier retrier) {
        this.rest = rest;
        this.retrier = retrier;
    }

    @Override
    public Optional<Client> findById(ClientId clientId) {
        return retrier.execute(DEPENDENCY, () -> HttpResponses.guard(PREFIX, () -> rest.get()
                .uri("/clients/{clientId}", clientId.value())
                .headers(HttpResponses::trace)
                .exchange((request, response) -> HttpResponses.read(response, ClientResponse.class, PREFIX))
                .map(CatalogMapper::toClient)));
    }
}
