package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
    private final Attribution attribution;

    /** Why a statement parked (the Phase 7 -> 8 transition, {@code V023}). */
    public enum Cause {
        /** It named nothing the platform made. */
        UNATTRIBUTED,
        /**
         * It named an attempt already concluded — failed, or executed under another scheme
         * reference: value arrived that the attempt's own story does not explain.
         */
        ATTEMPT_CONCLUDED,
        /** It named a waiting attempt but executed an amount other than the initiation's ask. */
        AMOUNT_MISMATCH
    }

    /**
     * What Phase 8 resolves the parking with: why it parked, the attempt it named when it
     * named one, the end-to-end reference it named when that had OUR minted shape, and the
     * scheme's settlement cycle.
     */
    public record Attribution(
            Cause cause,
            Optional<PaymentAttemptId> attempt,
            Optional<EndToEndReference> namedReference,
            Optional<String> settlementCycle) {

        public Attribution {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(attempt, "attempt must not be null");
            Objects.requireNonNull(namedReference, "namedReference must not be null");
            Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
            if ((cause == Cause.UNATTRIBUTED) != attempt.isEmpty()) {
                throw new IllegalArgumentException(
                        "a parking names its attempt exactly when it was attributed to one ("
                                + cause + ")");
            }
            settlementCycle.ifPresent(PushAnswer::requireCycleShape);
        }

        /** A statement that named nothing we made. */
        public static Attribution unattributed(
                Optional<EndToEndReference> namedReference, Optional<String> settlementCycle) {
            return new Attribution(
                    Cause.UNATTRIBUTED, Optional.empty(), namedReference, settlementCycle);
        }

        /** A statement that named {@code attempt}, parked for {@code cause}. */
        public static Attribution of(
                Cause cause,
                PaymentAttemptId attempt,
                EndToEndReference namedReference,
                Optional<String> settlementCycle) {
            return new Attribution(
                    cause, Optional.of(attempt), Optional.of(namedReference), settlementCycle);
        }
    }

    public UnmatchedConfirmation(
            UUID id,
            RailId rail,
            ProviderReference schemeReference,
            Money amount,
            Instant receivedAt,
            UUID entryRef,
            Attribution attribution) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
        this.schemeReference =
                Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        this.entryRef = Objects.requireNonNull(entryRef, "entryRef must not be null");
        this.attribution = Objects.requireNonNull(attribution, "attribution must not be null");
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

    /** Why it parked and what it named (the Phase 7 -> 8 transition). */
    public Attribution attribution() {
        return attribution;
    }

    /** Identifiers only — never the amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "UnmatchedConfirmation[" + id + ", rail=" + rail.value() + ", cause="
                + attribution.cause() + "]";
    }
}
