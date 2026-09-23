package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything a capture knows about how its money settles (`P6-TSK-005`, ADR-0048, ADR-0050 §6)
 * — the argument to {@link CaptureComposition}.
 *
 * <p><strong>Deliberately in payment vocabulary only.</strong> There is no merchant here, no
 * fee, no schedule and no rate: this record says <em>an approved capture of this amount
 * arrived, the clearing account is this, the account it credits is this</em>. What else the
 * money owes on the way is the composing flow's business, and naming it here would put the
 * {@code payments} module one field away from knowing what a fee is ({@code INV-PAY-03}'s
 * discipline at a second vocabulary).
 *
 * @param intent the intent whose capture this is — the composer's lookup key
 * @param attempt the attempt that was captured; also what the posting key is built from
 * @param clearing the {@code SETTLEMENT_CLEARING} account, already resolved for the currency
 * @param credit the account the capture credits, recorded on the intent at its birth
 * @param captured the amount the provider approved — the authorized promise, in full
 * @param correlation the flow this capture belongs to
 * @param at the capture instant, from the one injected clock
 */
public record CaptureSettlement(
        PaymentIntentId intent,
        PaymentAttemptId attempt,
        LedgerAccountId clearing,
        LedgerAccountId credit,
        Money captured,
        Correlation correlation,
        Instant at) {

    public CaptureSettlement {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(clearing, "clearing must not be null");
        Objects.requireNonNull(credit, "credit must not be null");
        Objects.requireNonNull(captured, "captured must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(at, "at must not be null");
    }
}
