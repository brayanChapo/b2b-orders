package com.b2b.orders.domain.pricing;

import com.b2b.orders.domain.model.Segment;
import java.math.BigDecimal;

public final class DiscountPolicy {

    public static final long WHOLESALE_MIN_QUANTITY = 20;
    public static final BigDecimal WHOLESALE_RATE = new BigDecimal("0.03");
    private static final BigDecimal NO_DISCOUNT = new BigDecimal("0.00");

    public BigDecimal rateFor(Segment segment, long quantity) {
        if (segment == Segment.WHOLESALE && quantity >= WHOLESALE_MIN_QUANTITY) {
            return WHOLESALE_RATE;
        }
        return NO_DISCOUNT;
    }
}
