package com.b2b.orders.infrastructure.http;

import com.b2b.orders.domain.model.Client;
import com.b2b.orders.domain.model.ClientId;
import com.b2b.orders.domain.model.ClientStatus;
import com.b2b.orders.domain.model.Product;
import com.b2b.orders.domain.model.ProductId;
import com.b2b.orders.domain.model.ProductStatus;
import com.b2b.orders.domain.model.Segment;
import com.b2b.orders.domain.model.TaxCategory;
import com.b2b.orders.domain.model.TaxRegime;

final class CatalogMapper {

    private CatalogMapper() {
    }

    static Client toClient(ClientResponse dto) {
        if (isBlank(dto.clientId()) || isBlank(dto.status())) {
            throw HttpCallException.definitiveFailure("CLIENTS_API_INVALID_BODY",
                    "Respuesta de clients-api sin clientId o status", null);
        }
        return new Client(new ClientId(dto.clientId()), dto.name(),
                ClientStatus.fromCode(dto.status()),
                Segment.fromCode(dto.segment()),
                TaxRegime.fromCode(dto.taxRegime()),
                dto.market());
    }

    static Product toProduct(ProductResponse dto) {
        if (isBlank(dto.productId()) || isBlank(dto.status())) {
            throw HttpCallException.definitiveFailure("PRODUCTS_API_INVALID_BODY",
                    "Respuesta de products-api sin productId o status", null);
        }
        return new Product(new ProductId(dto.productId()), dto.name(), dto.sku(),
                ProductStatus.fromCode(dto.status()),
                TaxCategory.fromCode(dto.taxCategory()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
