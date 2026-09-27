package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A scheme confirmation that named no initiation the platform made, carrying money
 * (`P7-TSK-009`, ADR-0062 §5, {@code INV-REC-05}): retained, parked in
 * {@code SUSPENSE_UNMATCHED}, aged and alerted — never credited by guesswork, never a
 * permanent resting place. The row is evidence-rank (append-only for every writer); its
 * money fact is the {@code unmatched-confirmation:} entry it references, and its
 * resolution is Phase 8's, arriving with its own producer.
 *
 * <p>A class, not a record: it carries {@link Money}, and a generated {@code toString}
 * would render an amount ({@code RESTRICTED-FINANCIAL}, {@code INV-AUD-02}).
 */
public final class UnmatchedConfirmation {

    private final UUID id;
    private final RailId rail;
    private final ProviderReference schemeReference;
    private final Money amount;
    private final Instant receivedAt;
    private final UUID entryRef;

    public UnmatchedConfirmation(
            UUID id,
            RailId rail,
            ProviderReference schemeReference,
            Money amount,
            Instant receivedAt,
            UUID entryRef) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
        this.schemeReference =
                Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        this.entryRef = Objects.requireNonNull(entryRef, "entryRef must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "an unmatched confirmation parks value: a non-positive amount in "
                            + amount.currency() + " is not money to park");
        }
    }

    public UUID id() {
        return id;
    }

    public RailId rail() {
        return rail;
    }

    public ProviderReference schemeReference() {
        return schemeReference;
    }

    public Money amount() {
        return amount;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    /** The suspense entry this parking posted — the chain stays walkable by stored id. */
    public UUID entryRef() {
        return entryRef;
    }

    /** Identifiers only — never the amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "UnmatchedConfirmation[" + id + ", rail=" + rail.value() + "]";
    }
}
