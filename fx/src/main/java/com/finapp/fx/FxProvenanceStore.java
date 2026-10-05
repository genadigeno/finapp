package com.finapp.fx;

import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * An FX trade's provenance (`P9-TSK-013`; PHASE_9_PLAN.md section 12.3's rate chain, "every link
 * stored"): the trade, the quote whose frozen plan it executed, and the cover with its execution -
 * read-only, for the investigator's audited door.
 */
public interface FxProvenanceStore {

    /** The cover's one execution, when it executed. */
    record Execution(
            String providerTradeReference,
            Money sold,
            Money bought,
            BigDecimal executedRate,
            LocalDate valueDate,
            Money realisedSold,
            Money realisedBought,
            boolean executedOffPlan,
            UUID journalEntryId) {}

    /** The cover a trade wants. */
    record Cover(UUID id, CoverStatus status, int attempts, Optional<Execution> execution) {}

    /** The whole chain. */
    record Provenance(
            FxTradeId tradeId,
            FxQuoteId quoteId,
            PricingPurpose purpose,
            TradeStatus status,
            FixedSide fixedSide,
            PricingPolicyId policyVersion,
            UUID referenceSnapshotId,
            BigDecimal referenceRate,
            String providerCode,
            String providerQuoteReference,
            BigDecimal providerRate,
            LocalDate providerValueDate,
            BigDecimal internalRate,
            BigDecimal customerRate,
            BigDecimal disclosedMargin,
            Money customerSource,
            Money customerDestination,
            Money positionSource,
            Money positionDestination,
            Money margin,
            Money spreadMargin,
            Money markupMargin,
            Money residual,
            Instant bookedAt,
            UUID journalEntryId,
            Optional<Cover> cover) {}

    /** The trade's provenance, if the trade exists. */
    Optional<Provenance> provenance(Connection unitOfWork, FxTradeId tradeId);
}
