package com.b2b.orders.application.port;

import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import java.util.List;
import java.util.Map;

public interface ProductCatalog {

    /**
     * Productos encontrados; un id ausente del mapa no existe en ese mercado.
     * @throws DependencyException si algún producto no se pudo consultar.
     */
    Map<ProductId, Product> findAll(List<ProductId> productIds, Market market);
}
