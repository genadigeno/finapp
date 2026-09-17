package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A hold: a claim against available balance, reserving funds for an anticipated movement
 * (`P3-TSK-015`, {@code INV-BAL-04}).
 *
 * <p><strong>Not a posting, and not a debit</strong> — value has not moved (the glossary's own
 * words). What a hold changes is one derived number: available balance is settled minus the
 * active holds, so a hold that is never released is as damaging as a wrong posting, and the
 * lifecycle is as guarded as the journal is.
 *
 * <p><strong>The amount is strictly positive.</strong> A zero hold reserves nothing and a
 * negative one is a release wearing a placement's clothes — the {@link JournalLine} argument,
 * applied to the reservation.
 *
 * <p>Whether a hold <em>may be placed</em> is not this type's question: {@code INV-BAL-04} is
 * judged against the account's postings and standing holds inside the account lock, which is
 * {@link HoldService}'s protocol (ADR-0039). This type owns what a hold <em>is</em> and which
 * transitions exist ({@code INV-LIFE-02}).
 */
public record Hold(
        HoldId id,
        LedgerAccountId account,
        Money amount,
        HoldStatus status,
        Instant placedAt,
        Optional<Instant> releasedAt) {

    public Hold {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(placedAt, "placedAt must not be null");
        Objects.requireNonNull(releasedAt, "releasedAt must not be null");
        if (!amount.isPositive()) {
            // Amount-free by INV-AUD-02: an exception message reaches logs.
            throw new IllegalArgumentException(
                    "a hold's amount must be strictly positive: a zero hold reserves nothing"
                            + " and a negative one is a release wearing a placement's clothes");
        }
        if (status == HoldStatus.RELEASED && releasedAt.isEmpty()) {
            throw new IllegalArgumentException("a released hold records when it was released");
        }
        if (status == HoldStatus.ACTIVE && releasedAt.isPresent()) {
            throw new IllegalArgumentException("an active hold has no release instant");
        }
    }

    /** A newly placed hold: {@code ACTIVE} from birth — a reservation either stands or ended. */
    public static Hold place(HoldId id, LedgerAccountId account, Money amount, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new Hold(
                id, account, amount, HoldStatus.ACTIVE, Instant.now(clock), Optional.empty());
    }

    /** A stored row, already validated by the schema. */
    public static Hold rehydrate(
            HoldId id,
            LedgerAccountId account,
            Money amount,
            HoldStatus status,
            Instant placedAt,
            Instant releasedAt) {
        return new Hold(
                id, account, amount, status, placedAt, Optional.ofNullable(releasedAt));
    }

    /**
     * The one transition: {@code ACTIVE → RELEASED}.
     *
     * @throws IllegalHoldTransitionException from any other state ({@code INV-LIFE-02},
     *     {@code INV-LIFE-04}) — the aggregate refuses, not merely the store's conditional
     */
    public Hold release(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(HoldStatus.RELEASED)) {
            throw new IllegalHoldTransitionException(id, status, HoldStatus.RELEASED);
        }
        return new Hold(
                id,
                account,
                amount,
                HoldStatus.RELEASED,
                placedAt,
                Optional.of(Instant.now(clock)));
    }

    /**
     * Names the hold and its states, never the amount ({@code INV-AUD-02}): a record's
     * generated {@code toString} prints every component, and the amount is
     * {@code RESTRICTED-FINANCIAL}.
     */
    @Override
    public String toString() {
        return "Hold[id=" + id + ", account=" + account + ", status=" + status + "]";
    }
}
