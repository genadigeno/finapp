package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A fee assessed on a billed BNPL instalment (`P12-TSK-020`, {@code bnpl V013}'s {@code bnpl_fee_assessment}; A13,
 * {@code PHASE_12_PLAN.md} sections 12.3 and 12.11 F4; lending's {@code FeeAssessment}): born once per instalment and kind,
 * naming the agreement version in force whose pinned terms decided it and F4's entry ({@code DR BNPL_FEES_DUE / CR
 * BNPL_FEE_INCOME} under {@code bnpl.fee:<instalment>:<kind>}). It joins its instalment's {@code FEES}
 * ({@code bnpl.billing_component_billed}) - so every payer allocates it first by the pinned order, and an unpaid fee keeps
 * its instalment past due. RESTRICTED-FINANCIAL.
 *
 * @param instalment the billing's own instalment - the billed item's identity
 * @param businessDate the business date of the assessing transaction in the pinned zone
 */
public record BnplFeeAssessment(
        UUID id,
        BnplObligationId obligation,
        UUID instalment,
        BnplFeeKind kind,
        LocalDate businessDate,
        Money amount,
        BnplAgreementId agreement,
        int agreementVersion,
        JournalEntryId entry,
        CorrelationId correlation) {

    public BnplFeeAssessment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(instalment, "instalment must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a fee of nothing is no fee");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("agreement versions are numbered from 1");
        }
    }

    /** F4's key: {@code bnpl.fee:<instalment>:<kind>} - the assessment's natural identity. */
    public static String postingKey(UUID instalment, BnplFeeKind kind) {
        return BnplConditions.FEE_KEY_PREFIX + Objects.requireNonNull(instalment, "instalment") + ":"
                + Objects.requireNonNull(kind, "kind").name();
    }

    /** Identifiers only - never the amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplFeeAssessment[" + id + ", " + kind + " on " + instalment + "]";
    }
}
