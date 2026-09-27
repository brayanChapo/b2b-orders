package com.b2b.orders.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.b2b.orders.domain.model.Segment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DiscountPolicyTest {

    private final DiscountPolicy policy = new DiscountPolicy();

    @ParameterizedTest(name = "{0} con {1} unidades → {2}")
    @CsvSource({
            "WHOLESALE, 1,    0.00",
            "WHOLESALE, 19,   0.00",
            "WHOLESALE, 20,   0.03",
            "WHOLESALE, 21,   0.03",
            "WHOLESALE, 1000, 0.03",
            "RETAIL,    20,   0.00",
            "RETAIL,    1000, 0.00",
            "UNKNOWN,   1000, 0.00"})
    void descuentoMayorista(Segment segment, long quantity, String expected) {
        assertThat(policy.rateFor(segment, quantity)).isEqualByComparingTo(expected);
    }
}
