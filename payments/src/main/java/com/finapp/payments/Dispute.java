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
 * <h2>What the row is</h2>
 *
 * <p>The provider, its dispute reference, the contested attempt and the reason category are
 * the network's opening statement, fixed at birth: `V020` freezes them for every writer, and a
 * later notification that contradicts the attempt moves nothing. What the network says
 * afterwards moves {@link #stage()}, one edge at a time — and, once, the chargeback: what the
 * network took and, since `P7-TSK-013`, who bears it ({@link #split()}). The money each stage
 * moves is posted beside the row, keyed by this row's identifier and its stage (ADR-0061 §4):
 * the row records the lifecycle and the attribution, the ledger the value.
 *
 * <h2>The chargeback arrives with the chargeback</h2>
 *
 * <p>{@link #chargeback()} is present exactly when the network has taken the funds
 * ({@link DisputeStage#isChargedBack()}), set on entering {@code CHARGED_BACK} — at birth, or
 * on the inquiry's escalation — and never moved after: the captured amount's discipline,
 * {@code NULL → value} for every writer. An inquiry states only the transaction it asks about,
 * and a chargeback may take less than that; the figure posted is what the network took.
 *
 * <h2>Its attribution is judged when it arrives, and only its excess ever moves back</h2>
 *
 * <p>The {@link ChargebackSplit} is decided on the edge that enters {@code CHARGED_BACK}, under
 * the attempt row lock both money paths take — the counterparty charged at most what the
 * capture credited it, net of refunds and of the chargebacks already standing
 * ({@code INV-DSP-01}). Afterwards the one legal change is {@link #reattributed}: a counted
 * refund that failed gives its share of the excess back to the counterparty, while the
 * chargeback stands.
 *
 * <h2>Recorded against the attempt the network names, whatever its state</h2>
 *
 * <p>The external fact first (ADR-0061 §4): a chargeback is money the network has
 * <em>already</em> taken. An attempt that has captured nothing — yet, or ever — has credited
 * nobody, so its chargeback's attribution is none and the whole amount is excess: recorded,
 * never refused, never deferred. Should the capture land later, the counterparty's share comes
 * back to it then ({@link #reattributed}, through {@code ChargebackAccounting#captureLanded}).
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
    private final Optional<ChargebackSplit> chargeback;
    private final Optional<Money> fee;
    private final Instant openedAt;

    private Dispute(
            DisputeId id,
            String provider,
            ProviderReference providerReference,
            PaymentAttemptId attemptId,
            DisputeReason reason,
            DisputeStage stage,
            Optional<ChargebackSplit> chargeback,
            Optional<Money> fee,
            Instant openedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.providerReference =
                Objects.requireNonNull(providerReference, "providerReference must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.chargeback = Objects.requireNonNull(chargeback, "chargeback must not be null");
        this.fee = Objects.requireNonNull(fee, "fee must not be null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        // Every rule V020's and V021's CHECKs hold is held here too, so a corrupt row is
        // refused at read rather than acted on (the Withdrawal constructor's stance). The
        // split's own rules (positive amount, shares within it) are ChargebackSplit's.
        requireProvider(provider);
        if (stage.isChargedBack() != chargeback.isPresent()) {
            throw new IllegalArgumentException(
                    "a chargeback is recorded exactly when the network has taken the funds ("
                            + stage + ")");
        }
        if (fee.isPresent()) {
            if (chargeback.isEmpty()) {
                throw new IllegalArgumentException(
                        "a dispute fee is charged with a chargeback, never on an inquiry");
            }
            Money amount = chargeback.get().amount();
            if (!fee.get().isPositive()
                    || !fee.get().currency().equals(amount.currency())
                    || fee.get().scale() != amount.scale()) {
                throw new IllegalArgumentException(
                        "a dispute fee is positive and in the chargeback's currency and scale");
            }
        }
    }

    /**
     * The network's opening statement: born at an entry stage and nowhere else
     * ({@code INV-LIFE-02}; `V020`'s birth trigger holds the same for every writer), carrying
     * the chargeback and its attribution exactly when born {@code CHARGED_BACK}.
     */
    public static Dispute open(
            IdGenerator ids,
            Instant at,
            String provider,
            ProviderReference providerReference,
            PaymentAttemptId attemptId,
            DisputeReason reason,
            DisputeStage entry,
            Optional<ChargebackSplit> chargeback,
            Optional<Money> fee) {
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
                fee,
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
            Optional<ChargebackSplit> chargeback,
            Optional<Money> fee,
            Instant openedAt) {
        return new Dispute(
                id, provider, providerReference, attemptId, reason, stage, chargeback, fee,
                openedAt);
    }

    /**
     * One edge of the machine, or the refusal ({@code INV-LIFE-02}, {@code INV-LIFE-04}).
     * {@code arriving} is the chargeback and its attribution: required on the edge that enters
     * {@code CHARGED_BACK} and refused on every other — a recorded chargeback never moves.
     */
    public Dispute advanceTo(DisputeStage next, Optional<ChargebackSplit> arriving) {
        Objects.requireNonNull(next, "next must not be null");
        Objects.requireNonNull(arriving, "arriving must not be null");
        if (!stage.canTransitionTo(next)) {
            throw new IllegalDisputeTransitionException(stage, next);
        }
        boolean entersChargeback = next == DisputeStage.CHARGED_BACK;
        if (entersChargeback != arriving.isPresent()) {
            throw new IllegalArgumentException(
                    "a chargeback arrives on the edge that enters CHARGED_BACK and on no other");
        }
        return new Dispute(
                id,
                provider,
                providerReference,
                attemptId,
                reason,
                next,
                entersChargeback ? arriving : chargeback,
                fee,
                openedAt);
    }

    /**
     * {@code moved} of the chargeback's excess comes back to the counterparty — the one change
     * a split admits after it is judged (ADR-0061 §3: a counted refund that failed). Only while
     * the chargeback stands: a won dispute's attribution was reversed with the funds.
     */
    public Dispute reattributed(Money moved, boolean counterpartyPostable) {
        if (!stage.isStanding()) {
            throw new IllegalStateException(
                    "only a standing chargeback is re-attributed, not one at " + stage);
        }
        return new Dispute(
                id,
                provider,
                providerReference,
                attemptId,
                reason,
                stage,
                Optional.of(chargeback.orElseThrow().reattributed(moved, counterpartyPostable)),
                fee,
                openedAt);
    }

    /**
     * The PSP's dispute fee, recorded once ({@code NULL → value}, `V021` for every writer): it is
     * charged with the chargeback, so only a charged-back dispute carries one.
     */
    public Dispute withFee(Money charged) {
        Objects.requireNonNull(charged, "charged must not be null");
        if (fee.isPresent()) {
            throw new IllegalStateException("a recorded dispute fee never changes");
        }
        return new Dispute(
                id,
                provider,
                providerReference,
                attemptId,
                reason,
                stage,
                chargeback,
                Optional.of(charged),
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

    /**
     * What the network took — present exactly when {@link DisputeStage#isChargedBack()}. The
     * figure every stage posting is computed from (never a later statement's figure).
     */
    public Optional<Money> chargeback() {
        return chargeback.map(ChargebackSplit::amount);
    }

    /** The chargeback with its attribution — present exactly when {@link #chargeback()} is. */
    public Optional<ChargebackSplit> split() {
        return chargeback;
    }

    /** The PSP's dispute fee, once reported — only ever on a charged-back dispute. */
    public Optional<Money> fee() {
        return fee;
    }

    public Instant openedAt() {
        return openedAt;
    }

    /** Identifiers and stage only — never an amount, never a reference (INV-AUD-02). */
    @Override
    public String toString() {
        return "Dispute[" + id + ", " + stage + "]";
    }
}
