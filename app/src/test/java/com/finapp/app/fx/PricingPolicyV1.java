package com.finapp.app.fx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pricing policy v1 as the operator proposes it (`P9-TSK-007`, the phase plan's O7): every
 * directional pair of the five supported currencies under both purposes - 40 rows. No migration
 * seeds it (D26); two {@code FX_CONTROLLER}s propose and approve it (OPERATIONS_RUNBOOK §2).
 *
 * <ul>
 *   <li>spread 0.003500 and markup 0.001500 on every row;
 *   <li>rate scale 10 when JPY is the source (a rate below one needs the digits), 6 otherwise;
 *       the rate rounds towards zero, amounts and the margin half-even;
 *   <li>a 30 s window for a conversion, 60 s across a border; a 10 s cover margin;
 *   <li>a 150 bps band between EUR, GBP and USD, 300 bps for any JPY or BHD pair; the reference
 *       rate at most 120 s old; five open quotes per customer;
 *   <li>notional bounds EUR/GBP/USD 1.00-50,000.00, JPY 100-7,500,000, BHD 0.500-20,000.000.
 * </ul>
 */
final class PricingPolicyV1 {

    static final String PROVIDER = "fx-sim-a";
    static final int OPEN_QUOTE_CAP = 5;
    private static final Set<String> TIGHT = Set.of("EUR", "GBP", "USD");

    private PricingPolicyV1() {}

    static List<FxAdministrationController.PairRequest> pairs() {
        List<FxAdministrationController.PairRequest> pairs = new ArrayList<>();
        for (String purpose : List.of("CONVERSION", "CROSS_BORDER")) {
            for (CurrencyCode source : SupportedCurrencies.ALL) {
                for (CurrencyCode destination : SupportedCurrencies.ALL) {
                    if (!source.equals(destination)) {
                        pairs.add(pair(source.code(), destination.code(), purpose));
                    }
                }
            }
        }
        return List.copyOf(pairs);
    }

    static FxAdministrationController.PricingPolicyRequest request(String reason) {
        return new FxAdministrationController.PricingPolicyRequest(pairs(), OPEN_QUOTE_CAP, reason);
    }

    static String json(String reason) {
        try {
            return new ObjectMapper().writeValueAsString(request(reason));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static FxAdministrationController.PairRequest pair(String source, String destination, String purpose) {
        boolean tight = TIGHT.contains(source) && TIGHT.contains(destination);
        return new FxAdministrationController.PairRequest(
                source,
                destination,
                purpose,
                List.of(PROVIDER),
                "0.003500",
                "0.001500",
                source.equals("JPY") ? 10 : 6,
                "TOWARDS_ZERO",
                "HALF_EVEN",
                "HALF_EVEN",
                purpose.equals("CONVERSION") ? 30L : 60L,
                10L,
                tight ? "0.015" : "0.03",
                120L,
                minimum(source),
                maximum(source),
                minimum(destination),
                maximum(destination));
    }

    private static String minimum(String currency) {
        return switch (currency) {
            case "JPY" -> "100";
            case "BHD" -> "0.500";
            default -> "1.00";
        };
    }

    private static String maximum(String currency) {
        return switch (currency) {
            case "JPY" -> "7500000";
            case "BHD" -> "20000.000";
            default -> "50000.00";
        };
    }
}
