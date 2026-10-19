package com.finapp.bnpl;

import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One pay-in-N obligation's accrued date (`P12-TSK-017`; {@code bnpl V008}'s {@code bnpl_interest_accrual}, written by
 * {@code V009}'s T-acc; {@code INV-LND-02} and {@code INV-LND-09} by analogy): the figures {@code ACCRUAL_ENGINE_V1}
 * computed - the date, the instalment period it is rounded within, the base it accrued on, the period's exact cumulative
 * interest and what the date posts - with the agreement version in force it accrued under and F2a's entry, present exactly
 * when the date posts something (a zero day writes its row and no entry). Born once per obligation and date, oldest first.
 *
 * <p>{@code toString} names the obligation, the date and the engine only - accrual amounts are
 * {@code RESTRICTED-FINANCIAL} ({@code INV-AUD-02}).
 */
public record BnplInterestAccrual(
        UUID id,
        BnplObligationId obligation,
        InterestAccrual accrual,
        BnplAgreementId agreement,
        int agreementVersion,
        Optional<JournalEntryId> entry,
        CorrelationId correlation) {

    public BnplInterestAccrual {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(accrual, "accrual must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        if (!accrual.base().isPositive()) {
            throw new IllegalArgumentException("a date accrues only on principal outstanding");
        }
        if (accrual.posted().isPositive() != entry.isPresent()) {
            throw new IllegalArgumentException("an accrual has an entry exactly when it posts something");
        }
    }

    /** The obligation, the date and the engine only. */
    @Override
    public String toString() {
        return "BnplInterestAccrual[" + obligation.value() + ", " + accrual.date() + ", " + accrual.engine() + "]";
    }
}
