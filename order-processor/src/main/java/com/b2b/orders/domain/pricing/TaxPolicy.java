package com.b2b.orders.domain.pricing;

import com.b2b.orders.domain.model.Market;
import com.b2b.orders.domain.model.TaxCategory;
import com.b2b.orders.domain.model.TaxRegime;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

public final class TaxPolicy {

    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

    private static final Map<Market, Map<TaxCategory, BigDecimal>> RATES = new EnumMap<>(Map.of(
            Market.MX, rates("0.16", "0.08"),
            Market.CO, rates("0.19", "0.05"),
            Market.PE, rates("0.18", "0.10")));

    /**
     * Si el cliente es EXEMPT la tasa es 0 sin importar la categoría del producto.
     *
     * @throws IllegalArgumentException si la categoría es UNKNOWN: la elegibilidad ya debió
     *                                  rechazar ese producto, llegar aquí es un error de programación.
     */
    public BigDecimal rateFor(Market market, TaxCategory category, TaxRegime clientRegime) {
        if (category == TaxCategory.UNKNOWN) {
            throw new IllegalArgumentException("No se puede gravar una categoría fiscal desconocida");
        }
        if (clientRegime == TaxRegime.EXEMPT) {
            return ZERO_RATE;
        }
        return RATES.get(market).get(category);
    }

    private static Map<TaxCategory, BigDecimal> rates(String standard, String reduced) {
        var map = new EnumMap<TaxCategory, BigDecimal>(TaxCategory.class);
        map.put(TaxCategory.STANDARD, new BigDecimal(standard));
        map.put(TaxCategory.REDUCED, new BigDecimal(reduced));
        map.put(TaxCategory.EXEMPT, ZERO_RATE);
        return map;
    }
}
