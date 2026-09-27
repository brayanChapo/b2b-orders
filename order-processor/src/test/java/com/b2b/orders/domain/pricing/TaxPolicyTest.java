package com.b2b.orders.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.TaxCategory;
import com.b2b.orders.domain.model.TaxRegime;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class TaxPolicyTest {

    private final TaxPolicy policy = new TaxPolicy();

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @CsvSource({
            "MX, STANDARD, 0.16", "MX, REDUCED, 0.08", "MX, EXEMPT, 0.00",
            "CO, STANDARD, 0.19", "CO, REDUCED, 0.05", "CO, EXEMPT, 0.00",
            "PE, STANDARD, 0.18", "PE, REDUCED, 0.10", "PE, EXEMPT, 0.00"})
    void tasaPorMercadoYCategoria(Market market, TaxCategory category, String expected) {
        assertThat(policy.rateFor(market, category, TaxRegime.GENERAL)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "cliente EXEMPT en {0}: tasa 0 para cualquier categoría")
    @EnumSource(Market.class)
    void clienteExentoAnulaLaTasa(Market market) {
        for (TaxCategory category : new TaxCategory[] {TaxCategory.STANDARD, TaxCategory.REDUCED, TaxCategory.EXEMPT}) {
            assertThat(policy.rateFor(market, category, TaxRegime.EXEMPT)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @ParameterizedTest(name = "régimen {0} no es exento")
    @EnumSource(value = TaxRegime.class, names = {"GENERAL", "SIMPLIFIED", "UNKNOWN"})
    void soloExemptAnulaLaTasa(TaxRegime regime) {
        assertThat(policy.rateFor(Market.MX, TaxCategory.STANDARD, regime)).isEqualByComparingTo("0.16");
    }

    @Test
    void categoriaDesconocidaNoSeGrava() {
        assertThatThrownBy(() -> policy.rateFor(Market.MX, TaxCategory.UNKNOWN, TaxRegime.GENERAL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
