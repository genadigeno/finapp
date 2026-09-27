package com.finapp.payments;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One dispute on a card payment (`P7-TSK-012`, ADR-0061 §1–§2) — its own aggregate in
 * {@code payments}, one per provider dispute reference, contesting the attempt the network
 * named.
 *
 * <h2>What the row is, and what it is not</h2>
 *
 * <p>The provider, its dispute reference, the contested attempt and the reason category are
 * the network's opening statement, fixed at birth: `V020` freezes them for every writer, and a
 * later notification that contradicts the attempt moves nothing. What the network says
 * afterwards moves {@link #stage()}, one edge at a time — and, once, the chargeback's amount.
 * The dispute is <strong>not a posting</strong>: this task records the lifecycle, and the
 * money each stage moves is `P7-TSK-013`'s (the chargeback's lines, the win's exact inverse,
 * the loss's write-off) — keyed by this row's identifier and its stage, judged against the
 * attempt it contests.
 *
 * <h2>The chargeback's amount arrives with the chargeback</h2>
 *
 * <p>{@link #chargeback()} is present exactly when the network has taken the funds
 * ({@link DisputeStage#isChargedBack()}), set on entering {@code CHARGED_BACK} — at birth, or
 * on the inquiry's escalation — and never moved after: the captured amount's discipline,
 * {@code NULL → value} for every writer. An inquiry states only the transaction it asks about,
 * and a chargeback may take less than that; storing the inquiry's figure as the dispute's
 * amount would refuse a partial chargeback — refusing to record what the network did, the
 * alternative ADR-0061 rejects — and hand `P7-TSK-013` the wrong number to post.
 *
 * <h2>Recorded against the attempt the network names, whatever its state</h2>
 *
 * <p>The external fact first (ADR-0061 §4): a chargeback is money the network has
 * <em>already</em> taken, so refusing to record one — because our own capture record is still
 * ambiguous, say — would leave {@code SETTLEMENT_CLEARING} disagreeing with what the PSP will
 * net, with nothing explaining the break (the ADR's rejected alternative). Attribution of the
 * money is the combined bound's job, not the birth's.
 */
public final class Dispute {

    /** The provider-name shape `V020` holds: the adapter's stable name, never free text. */
    private static final Pattern PROVIDER = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private final DisputeId id;
    private final String provider;
    private final ProviderReference providerReference;
    private final PaymentAttemptId attemptId;
    private final DisputeReason reason;
    private final DisputeStage stage;
    private final Optional<Money> chargeback;
    private final Instant openedAt;

    private Dispute(
            DisputeId id,
            String provider,
            ProviderReference providerReference,
            PaymentAttemptId attemptId,
            DisputeReason reason,
            DisputeStage stage,
            Optional<Money> chargeback,
            Instant openedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.providerReference =
                Objects.requireNonNull(providerReference, "providerReference must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.chargeback = Objects.requireNonNull(chargeback, "chargeback must not be null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        // Every rule V020's CHECKs hold is held here too, so a corrupt row is refused at read
        // rather than acted on (the Withdrawal constructor's stance).
        requireProvider(provider);
        if (stage.isChargedBack() != chargeback.isPresent()) {
            throw new IllegalArgumentException(
                    "a chargeback amount is recorded exactly when the network has taken the"
                            + " funds (" + stage + ")");
        }
        if (chargeback.filter(amount -> !amount.isPositive()).isPresent()) {
            throw new IllegalArgumentException("a chargeback amount must be positive");
        }
    }

    /**
     * The network's opening statement: born at an entry stage and nowhere else
     * ({@code INV-LIFE-02}; `V020`'s birth trigger holds the same for every writer), carrying
     * the chargeback's amount exactly when born {@code CHARGED_BACK}.
     */
    public static Dispute open(
            IdGenerator ids,
            Instant at,
            String provider,
            ProviderReference providerReference,
            PaymentAttemptId attemptId,
            DisputeReason reason,
            DisputeStage entry,
            Optional<Money> chargeback) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (!entry.isEntry()) {
            throw new IllegalDisputeTransitionException(entry);
        }
        return new Dispute(
                DisputeId.next(ids),
                provider,
                providerReference,
                attemptId,
                reason,
                entry,
                chargeback,
                // The column's own microsecond resolution (the P7-TSK-004 clock lesson).
                at.truncatedTo(ChronoUnit.MICROS));
    }

    /** A row read back from storage — the constructor's coherence refuses a corrupt one. */
    public static Dispute rehydrate(
            DisputeId id,
            String provider,
            ProviderReference providerReference,
            PaymentAttemptId attemptId,
            DisputeReason reason,
            DisputeStage stage,
            Optional<Money> chargeback,
            Instant openedAt) {
        return new Dispute(
                id, provider, providerReference, attemptId, reason, stage, chargeback, openedAt);
    }

    /**
     * One edge of the machine, or the refusal ({@code INV-LIFE-02}, {@code INV-LIFE-04}).
     * {@code stated} is the amount the network's statement carries: it becomes the chargeback's
     * amount on entering {@code CHARGED_BACK} and is ignored on every other edge — a recorded
     * chargeback never moves.
     */
    public Dispute advanceTo(DisputeStage next, Money stated) {
        Objects.requireNonNull(next, "next must not be null");
        Objects.requireNonNull(stated, "stated must not be null");
        if (!stage.canTransitionTo(next)) {
            throw new IllegalDisputeTransitionException(stage, next);
        }
        return new Dispute(
                id,
                provider,
                providerReference,
                attemptId,
                reason,
                next,
                next == DisputeStage.CHARGED_BACK ? Optional.of(stated) : chargeback,
                openedAt);
    }

    /** The provider-name shape, shared with {@link DisputeNotice}. */
    static void requireProvider(String provider) {
        if (!PROVIDER.matcher(provider).matches()) {
            throw new IllegalArgumentException(
                    "a provider name is 1-64 characters of [a-z0-9-], starting with a letter");
        }
    }

    public DisputeId id() {
        return id;
    }

    public String provider() {
        return provider;
    }

    public ProviderReference providerReference() {
        return providerReference;
    }

    public PaymentAttemptId attemptId() {
        return attemptId;
    }

    public DisputeReason reason() {
        return reason;
    }

    public DisputeStage stage() {
        return stage;
    }

    /** What the network took — present exactly when {@link DisputeStage#isChargedBack()}. */
    public Optional<Money> chargeback() {
        return chargeback;
    }

    public Instant openedAt() {
        return openedAt;
    }

    /** Identifiers and stage only — never the amount, never a reference (INV-AUD-02). */
    @Override
    public String toString() {
        return "Dispute[" + id + ", " + stage + "]";
    }
}
