package com.b2b.orders.domain.eligibility;

import com.b2b.orders.domain.model.ProductId;
import java.util.Objects;
import java.util.Optional;

public record RejectionReason(RejectionCode code, ProductId productId, String detail) {

    public RejectionReason {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
    }

    public static RejectionReason ofClient(RejectionCode code, String detail) {
        return new RejectionReason(code, null, detail);
    }

    public static RejectionReason ofProduct(RejectionCode code, ProductId productId, String detail) {
        return new RejectionReason(code, Objects.requireNonNull(productId, "productId"), detail);
    }

    public Optional<ProductId> productIdIfPresent() {
        return Optional.ofNullable(productId);
    }
}
