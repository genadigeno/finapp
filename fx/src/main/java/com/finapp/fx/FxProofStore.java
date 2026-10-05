package com.finapp.fx;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What fx's own rows say the FX books and the plans must be (`P9-TSK-013`; PHASE_9_PLAN.md
 * section 12.9.4) - read-only, in the caller's snapshot. Sums are per currency in minor units at
 * that currency's scale; nothing here names a ledger account.
 */
public interface FxProofStore {

    /**
     * The books' expected values per currency code, each in its book's natural sense:
     * {@code position} DR-CR = every trade's position legs (CR source, DR destination) plus every
     * executed cover's plan legs (DR sold, CR bought); {@code margin} CR-DR and {@code residual}
     * (the trades' stored residual, posted CR when positive) per computed-leg currency;
     * {@code gains} CR-DR and {@code losses} DR-CR of the executions' realised results per leg
     * currency. A currency with no rows is absent (expected 0).
     */
    record Expected(
            Map<String, Long> position,
            Map<String, Long> margin,
            Map<String, Long> residual,
            Map<String, Long> gains,
            Map<String, Long> losses) {

        public Expected {
            position = Map.copyOf(position);
            margin = Map.copyOf(margin);
            residual = Map.copyOf(residual);
            gains = Map.copyOf(gains);
            losses = Map.copyOf(losses);
        }
    }

    Expected expected(Connection unitOfWork);

    /** One booked trade's stored inputs and outputs, for the replay (the quote's frozen columns). */
    record ReplayRow(
            FxTradeId tradeId,
            UUID journalEntryId,
            FxQuoteId quoteId,
            PricingPolicyId version,
            FixedSide fixedSide,
            String sourceCurrency,
            String destinationCurrency,
            int sourceScale,
            int destinationScale,
            BigDecimal providerRate,
            long providerValidForMillis,
            BigDecimal referenceRate,
            BigDecimal storedCustomerRate,
            BigDecimal storedInternalRate,
            BigDecimal storedDisclosedMargin,
            BigDecimal spread,
            BigDecimal markup,
            int rateScale,
            String rateRounding,
            String amountRounding,
            String marginRounding,
            long customerSourceMinor,
            long customerDestinationMinor,
            long positionSourceMinor,
            long positionDestinationMinor,
            long marginMinor,
            long spreadMarginMinor,
            long markupMarginMinor,
            long residualMinor) {}

    /** Every booked trade's replay row, oldest first. */
    List<ReplayRow> replayRows(Connection unitOfWork);
}
