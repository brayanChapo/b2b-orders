package com.b2b.orders.domain;

import com.b2b.orders.domain.eligibility.RejectionReason;
import com.b2b.orders.domain.pricing.PricedOrder;
import java.util.List;
import java.util.Objects;

public sealed interface OrderDecision {

    record Approved(PricedOrder pricing) implements OrderDecision {
        public Approved {
            Objects.requireNonNull(pricing, "pricing");
        }
    }

    record Rejected(List<RejectionReason> reasons) implements OrderDecision {
        public Rejected {
            reasons = List.copyOf(reasons);
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("un rechazo tiene al menos una razón");
            }
        }

        /** Razón principal: la primera en el orden determinista de evaluación. */
        public RejectionReason primary() {
            return reasons.getFirst();
        }
    }
}
