package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Initiates a merchant payout (`P6-TSK-012`, ADR-0051, ADR-0057) — `P5-TSK-016`'s
 * two-transaction keyed command, reused rather than reinvented, pointed outward.
 *
 * <h2>Two transactions, the wire between them holding no connection</h2>
 *
 * <ol>
 *   <li><strong>The dispatch transaction</strong> claims the key (held {@code IN_PROGRESS}),
 *       locks the merchant (a suspension and a payout serialise), share-locks its effective
 *       destination (a supersession waits for this commit), places a ledger hold for the amount
 *       — the payable account locked, its available position judged in-lock with every in-flight
 *       payout already held, {@code INV-MER-05}'s arbiter — and commits the payout
 *       {@code DISPATCHED} with our minted reference, its audit record and
 *       {@code MerchantPayoutInitiated}. A refusal anywhere rolls ALL of it back, claim included:
 *       nothing held, nothing written, the key unspent.
 *   <li><strong>The wire</strong>: our reference, the destination's provider reference, the
 *       amount — after the commit, holding nothing.
 *   <li><strong>The outcome transaction</strong>, as the platform: the payout re-read under its
 *       lock, the answer applied through {@link MerchantPayoutOutcomes}, the bytes retained,
 *       and the claim completed with the judged {@code payoutId|status} — so a replay renders
 *       the judgement it was given, never a later one.
 * </ol>
 *
 * <h2>The takeover converges, and re-sends only behind a fresh permit</h2>
 *
 * <p>A crash between the two transactions strands the payout {@code DISPATCHED} with its claim
 * {@code IN_PROGRESS}. A retry after the claim's lease expires takes it over and re-runs the
 * dispatch, which finds the payout by its dispatch key and places <strong>no second
 * hold</strong>. If the payout still awaits the rail's word, it commits a renewed send permit
 * (conditional — a resolver that got there first leaves nothing to send) and re-sends our
 * STORED reference, which the provider cannot pay twice ({@code INV-PAY-04}); if the resolution
 * sweep already resolved it, nothing is sent and the claim completes with the row's status.
 */
@RequiredArgsConstructor
public final class MerchantPayouts {

    /** The claim's scope is the merchant's own payout namespace (ADR-0004, ADR-0057 §5). */
    public static final String IDEMPOTENCY_SCOPE_PREFIX = "merchant.payout:";

    public static final String TARGET_TYPE = "merchant.MerchantPayout";

    @NonNull private final MerchantTransactionRunner transactions;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final MerchantStore<Connection> merchants;
    @NonNull private final PayoutDestinationStore<Connection> destinations;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final HoldService holds;
    @NonNull private final MerchantPayoutStore<Connection> payouts;
    @NonNull private final PayoutProvider provider;
    @NonNull private final MerchantPayoutOutcomes outcomes;
    @NonNull private final PayoutEvidenceStore<Connection> evidence;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * A payout request.
     *
     * @param reason an operator's reason, required of them and never of the merchant's own key
     * @param keyId the merchant API key that asked, when it was one — named in the audit record
     *     (ADR-0052 §2)
     */
    public record InitiateCommand(
            MerchantId merchant,
            Money amount,
            String idempotencyKey,
            Optional<String> reason,
            Optional<MerchantApiKeyId> keyId) {

        public InitiateCommand {
            Objects.requireNonNull(merchant, "merchant must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(keyId, "keyId must not be null");
        }
    }

    /**
     * What the initiation answered.
     *
     * @param status the committed status when the claim completed — honestly
     *     {@code DISPATCHED} or {@code UNKNOWN} when it is
     * @param replayed whether this answer is a retried key's recorded judgement
     * @param acting whether THIS call's own conditional transition fired, so {@code status} is
     *     its judgement — the bit the payout meter counts (`P6-TSK-013`), carried out of
     *     {@link MerchantPayoutOutcomes.Applied} because that is the only place it exists. A
     *     replay, a converged takeover and an answer that moved nothing are never acting
     */
    public record Initiated(
            MerchantPayoutId payout, MerchantPayoutStatus status, boolean replayed, boolean acting) {

        public Initiated {
            Objects.requireNonNull(payout, "payout must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (replayed && acting) {
                throw new IllegalArgumentException(
                        "a replay answers a recorded judgement; it never acts");
            }
        }
    }

    /**
     * What the dispatch transaction hands the wire. {@code sendTo} empty: nothing to send.
     *
     * @param permit the send permit this flight committed, as the database stored it — a refused
     *     connection fails the payout only while the locked row's permit is still this one (the
     *     Phase 6 → 7 transition; {@code null} when nothing is sent)
     */
    private record Dispatch(
            MerchantPayout payout,
            Optional<PayoutDestinationReference> sendTo,
            boolean firstSend,
            Instant permit) {}

    @SuppressWarnings("try") // The platform Scope is used for its close side effect (the idiom).
    public Initiated initiate(InitiateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Actor actor = SecurityContext.require();
        if (actor.type() == ActorType.MERCHANT
                && !actor.id().equals(command.merchant().value().toString())) {
            // The merchant route derives both from one authenticated key; disagreement is a
            // wiring defect, refused before anything is claimed (INV-MER-01, defence in depth).
            throw new IllegalStateException("a merchant may only pay out its own payable");
        }
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key =
                new IdempotencyKey(
                        IDEMPOTENCY_SCOPE_PREFIX + command.merchant().value(),
                        command.idempotencyKey());
        RequestFingerprint fingerprint = RequestFingerprint.sha256(canonicalForm(command, actor));

        Dispatch[] holder = new Dispatch[1];
        IdempotentExecutor.BeginOutcome begun =
                transactions.inTransaction(
                        uow ->
                                executor.begin(
                                        uow,
                                        key,
                                        fingerprint,
                                        claimed -> {
                                            Dispatch dispatched =
                                                    dispatchOrConverge(
                                                            claimed, command, actor, correlation);
                                            holder[0] = dispatched;
                                            return dispatched
                                                    .payout()
                                                    .id()
                                                    .value()
                                                    .toString()
                                                    .getBytes(StandardCharsets.UTF_8);
                                        }));
        if (begun.replay().isPresent()) {
            return parsedReplay(begun.replay().get());
        }
        Dispatch dispatch = holder[0];

        // THE WIRE, holding no connection (ADR-0046 §1): the dispatch is durable, so whatever
        // happens now - a timeout, a crash, a lost response - the payout is on the record.
        Optional<PayoutAnswer> answer =
                dispatch.sendTo()
                        .map(
                                destination ->
                                        provider.dispatch(
                                                new PayoutProvider.PayoutRequest(
                                                        dispatch.payout().reference(),
                                                        destination,
                                                        dispatch.payout().amount())));

        // The outcome is the platform's act, whoever asked (an enumerated enterSystem() site):
        // the same answer applied by the sweep is the platform's too, so attribution cannot
        // depend on which resolver wins the harmless race.
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> {
                        MerchantPayout locked =
                                payouts.findForUpdate(
                                                uow,
                                                command.merchant(),
                                                dispatch.payout().id())
                                        .orElseThrow(
                                                () ->
                                                        new MerchantStorageException(
                                                                "a committed payout was not"
                                                                        + " found to resolve"));
                        MerchantPayoutStatus committed = locked.status();
                        boolean acting = false;
                        if (answer.isPresent()) {
                            MerchantPayoutOutcomes.Applied applied =
                                    outcomes.applySendAnswer(
                                            uow,
                                            locked,
                                            answer.get(),
                                            dispatch.firstSend(),
                                            dispatch.permit(),
                                            correlation);
                            committed = applied.status();
                            acting = applied.acting();
                            answer.get()
                                    .evidence()
                                    .ifPresent(
                                            bytes ->
                                                    evidence.append(
                                                            uow,
                                                            locked.id(),
                                                            PayoutEvidenceKind.RESPONSE,
                                                            bytes,
                                                            Instant.now(clock)));
                        }
                        // The claim records the JUDGED status: a replay answers what this
                        // request was told, and a later resolution never leaks into it. Its
                        // row count is not ours to judge - a lease-expired takeover completing
                        // first converges on the same payout.
                        executor.complete(
                                uow,
                                key,
                                true,
                                StoredResponse.of(renderedForm(locked.id(), committed), "text/plain"));
                        return new Initiated(locked.id(), committed, false, acting);
                    });
        }
    }

    /** The merchant's payout, for its own read. Unknown and another merchant's are one absence. */
    public Optional<MerchantPayout> find(
            Connection unitOfWork, MerchantId merchant, MerchantPayoutId id) {
        return payouts.find(unitOfWork, merchant, id);
    }

    private Dispatch dispatchOrConverge(
            Connection unitOfWork, InitiateCommand command, Actor actor, Correlation correlation) {
        Optional<MerchantPayout> existing =
                payouts.findByDispatchKey(unitOfWork, command.merchant(), command.idempotencyKey());
        if (existing.isEmpty()) {
            return dispatch(unitOfWork, command, actor, correlation);
        }
        // THE TAKEOVER: this claim's lease expired with its payout already committed.
        MerchantPayout found = existing.get();
        if (!found.amount().equals(command.amount())) {
            // The claim's fingerprint already refused different facts under this key; a row
            // that disagrees with it is a defect, refused loudly rather than guessed at.
            throw new IllegalStateException(
                    "a taken-over payout claim found different facts under its key");
        }
        if (found.status().isResolvable()
                && payouts.renewSendPermit(unitOfWork, found)) {
            PayoutDestination destination =
                    destinations.find(unitOfWork, command.merchant(), found.destinationId())
                            .orElseThrow(
                                    () ->
                                            new MerchantStorageException(
                                                    "a payout's recorded destination was not"
                                                            + " found"));
            // The RECORDED destination, even if superseded since: the payout was bound to the
            // version effective at its dispatch, and a re-send is that same payout.
            return new Dispatch(
                    found,
                    Optional.of(destination.reference()),
                    false,
                    storedPermit(unitOfWork, command.merchant(), found.id()));
        }
        return new Dispatch(found, Optional.empty(), false, null);
    }

    private Dispatch dispatch(
            Connection unitOfWork, InitiateCommand command, Actor actor, Correlation correlation) {
        // Serialised with suspension and closure, which lock this row to move it.
        Merchant merchant =
                merchants.findByIdForUpdate(unitOfWork, command.merchant())
                        .orElseThrow(UnknownMerchantException::new);
        if (merchant.status() != MerchantStatus.ACTIVE) {
            throw new MerchantNotTradingException();
        }
        if (!command.amount().currency().equals(merchant.settlementCurrency())) {
            throw new PayoutCurrencyMismatchException();
        }
        PayoutDestination destination =
                destinations.findEffectiveForShare(unitOfWork, command.merchant())
                        .orElseThrow(NoEffectiveDestinationException::new);
        LedgerAccount payable =
                accounts.findOwned(
                                unitOfWork,
                                merchant.id().value(),
                                AccountPurpose.MERCHANT_PAYABLE,
                                merchant.settlementCurrency())
                        .orElseThrow(
                                () ->
                                        new MerchantStorageException(
                                                "an onboarded merchant has no payable in its"
                                                        + " settlement currency"));
        Hold hold;
        try {
            // INV-MER-05's arbiter: the payable account locked, its available position - the
            // ledger-derived settled balance less every standing hold, in-flight payouts and
            // refunds alike - judged under that lock, and this payout's amount held against it.
            hold = holds.place(unitOfWork, payable.id(), command.amount());
        } catch (HoldExceedsAvailableBalanceException unfunded) {
            // A negative payable (ADR-0054) lands here too: no positive amount fits.
            throw new MerchantPayoutUnfundedException();
        }
        MerchantPayout payout =
                MerchantPayout.dispatch(
                        ids,
                        clock,
                        command.merchant(),
                        command.amount(),
                        destination.id(),
                        hold.id(),
                        actor,
                        command.reason());
        payouts.insert(unitOfWork, payout, command.idempotencyKey());
        recordInitiation(unitOfWork, payout, command, actor, correlation);
        outcomes.announceInitiated(unitOfWork, payout, correlation, payout.createdAt());
        return new Dispatch(
                payout,
                Optional.of(destination.reference()),
                true,
                storedPermit(unitOfWork, command.merchant(), payout.id()));
    }

    /**
     * The permit as the database stored it (the Phase 6 → 7 transition): what the outcome later
     * compares with the locked row's, so it is read back rather than taken from this instance's
     * clock at a finer precision than the column keeps.
     */
    private Instant storedPermit(Connection unitOfWork, MerchantId merchant, MerchantPayoutId id) {
        return payouts.findForUpdate(unitOfWork, merchant, id)
                .map(MerchantPayout::lastDispatchedAt)
                .orElseThrow(
                        () ->
                                new MerchantStorageException(
                                        "a payout written in this transaction is readable in it"));
    }

    private void recordInitiation(
            Connection unitOfWork,
            MerchantPayout payout,
            InitiateCommand command,
            Actor actor,
            Correlation correlation) {
        boolean byMerchant = actor.type() == ActorType.MERCHANT;
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        payout.createdAt(),
                        byMerchant
                                ? MerchantAuditAction.MERCHANT_PAYOUT_INITIATED
                                : MerchantAuditAction.MERCHANT_PAYOUT_INITIATED_BY_OPERATOR,
                        TARGET_TYPE,
                        payout.id().value().toString(),
                        command.reason(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only - never the destination's reference (INV-AUD-02). The
                        // key that acted is named, as ADR-0052 §2 requires of a merchant's act.
                        Optional.of(
                                "payout=" + payout.id()
                                        + ", merchant=" + payout.merchantId()
                                        + ", destination=" + payout.destinationId()
                                        + ", reference=" + payout.reference()
                                        + command.keyId().map(k -> ", key=" + k).orElse(""))));
    }

    private static byte[] canonicalForm(InitiateCommand command, Actor actor) {
        return String.join(
                        "|",
                        IDEMPOTENCY_SCOPE_PREFIX + command.merchant().value(),
                        actor.type().name(),
                        actor.id(),
                        Long.toString(command.amount().minorUnits()),
                        command.amount().currency().code(),
                        Integer.toString(command.amount().scale()),
                        command.reason().orElse(""))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] renderedForm(MerchantPayoutId payout, MerchantPayoutStatus status) {
        return (payout.value() + "|" + status.name()).getBytes(StandardCharsets.UTF_8);
    }

    private static Initiated parsedReplay(StoredResponse stored) {
        String body =
                new String(
                        stored.bodyBytes()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a completed payout claim stores its"
                                                                + " judgement; an empty body is"
                                                                + " a wiring defect")),
                        StandardCharsets.UTF_8);
        int separator = body.indexOf('|');
        return new Initiated(
                MerchantPayoutId.of(UUID.fromString(body.substring(0, separator))),
                MerchantPayoutStatus.valueOf(body.substring(separator + 1)),
                true,
                false);
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a merchant payout must run inside a correlation scope"));
    }
}
