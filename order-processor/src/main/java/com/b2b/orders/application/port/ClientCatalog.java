package com.b2b.orders.application.port;

import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientId;
import java.util.Optional;

public interface ClientCatalog {

    /** Vacío si el cliente no existe. @throws DependencyException si no se pudo consultar. */
    Optional<Client> findById(ClientId clientId);
}
