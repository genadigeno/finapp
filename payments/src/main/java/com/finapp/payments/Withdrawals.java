package com.finapp.payments;

import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wallet withdrawal command (`P7-TSK-008`, ADR-0062 §6) — hold-then-dispatch on the
 * customer's wallet over the {@link PushRail}, the merchant payout's choreography
 * (ADR-0057) with the withdrawal's own three additions: <strong>it routes</strong>
 * (ADR-0060 §2's outbound moment, the decision pinned in the dispatch transaction),
 * <strong>its rail is irrevocable</strong> ({@link #reverse} refuses from the declaration
 * before anything exists, {@code INV-REV-03}), and <strong>its wire is the scheme's</strong>
 * (our {@link EndToEndReference} on every send, the scheme deduplicating on it).
 *
 * <h2>The two transactions (ADR-0046)</h2>
 *
 * <p><strong>Tx1 (dispatch)</strong>: the caller's resolver runs first — party, step-up,
 * wallet and instrument, all inside the deciding transaction, so a refusal rolls the claim
 * back and the key stays unburned (the `P7-TSK-007` idiom) — then the claim, the routing
 * decision, the hold judged under the wallet account's lock ({@code INV-BAL-04}), the row
 * with our minted reference, the first send permit, the initiated fact and the person's
 * audit record commit together. <strong>The wire</strong> holds no connection.
 * <strong>Tx2 (outcome)</strong>: the row locked, the answer applied through
 * {@link WithdrawalOutcomes}, the scheme's bytes retained, the claim completed with the
 * judged status — a replay answers it byte for byte.
 *
 * <h2>An unroutable withdrawal is a recorded refusal</h2>
 *
 * <p>No eligible rail commits the refused decision AND the claim's failed outcome, then
 * refuses — deliberately retryable under a NEW key once an operator repairs availability
 * (the `P7-TSK-003` refusal, keyed; the recorded asymmetry rides the claim). An unfunded
 * wallet, by contrast, aborts the whole transaction: transient state, same key retries.
 *
 * <p>The refusal's record is those two rows, deliberately without an audit record of its own
 * (the Phase 7 review's reading): the frozen decision carries the time, the pinned version,
 * the inputs and every step's rejection, and the failed claim carries the actor - its scope is
 * the owning customer's - and the correlation. Nothing moved and no state changed, so there is
 * no act to attribute beyond what those rows already attribute. A card or bank pay-in's
 * refusal IS audited ({@code PaymentsAuditAction.PAYMENT_ROUTING_REFUSED}) because its claim
 * belongs to the intent's creation, not to the confirmation that was refused.
 *
 * <h2>Judged by risk, in-lock, after the hold (`P13-TSK-009`)</h2>
 *
 * <p>The person's withdrawal is judged by the required {@link WithdrawalRiskDecision} inside Tx1, after the hold is
 * placed under the wallet account's lock and before anything commits ({@code PHASE_13_PLAN.md} §7.3): {@code PERMIT}
 * is born {@code DISPATCHED} and sent as before; {@code HOLD} is born {@code HELD} - the hold KEPT, nothing sent, the
 * held window stamped by the database from the verdict's hold validity; {@code REFUSE} is born
 * {@code FAILED(RISK_REFUSED)} with the hold released in the same transaction. The withdrawal's id is minted first: it
 * is the judgement's command reference, one assessment per withdrawal. The claim's response of record is the judged
 * status, so a retry under the key replays {@code HELD} or {@code FAILED} and is never judged again. The SYSTEM entry
 * on an instruction is not judged (the seam's javadoc).
 *
 * <p><strong>The held withdrawal's doors</strong>: {@link #releaseHeld} (keyed: {@code HELD → DISPATCHED} in Tx1, the
 * wire, the answer in Tx2 - the person's dispatch shape, a takeover converging by the row's recorded key) and
 * {@link #denyHeld} (keyed, one transaction: {@code FAILED(RISK_REFUSED)}, the hold released), each under the
 * withdrawal row's lock - payments' order: the row, then the wallet - answering {@link HeldOutcome}. The expiry is
 * {@link WithdrawalResolution#expireHolds}'s.
 */
public final class Withdrawals {

    static final String TARGET_TYPE = "withdrawal";

    /** The claim's scope prefix; the owning customer completes it (ADR-0004, ADR-0057 §5). */
    static final String CLAIM_SCOPE_PREFIX = "payments.withdrawal:";

    /** The release door's claim scope (`P13-TSK-009`); the caller's key completes it ({@code risk.action:<id>}). */
    public static final String RELEASE_SCOPE = "payments.withdrawal.release";

    /** The denial door's claim scope (`P13-TSK-009`). */
    public static final String DENY_SCOPE = "payments.withdrawal.deny";

    /** A door's reason: a reason CODE, never a note - a note never crosses into payments ({@code INV-RSK-08}). */
    static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** The person's claim scope for {@code customerId} - the door's step-up asks whether a key is recorded under it. */
    public static String claimScopeOf(UUID customerId) {
        return CLAIM_SCOPE_PREFIX + Objects.requireNonNull(customerId, "customerId must not be null");
    }

    /** What a held withdrawal's door did (`P13-TSK-009`) - risk's {@code ActionOutcome}, in payments' words. */
    public enum HeldOutcome {
        /** This call moved the withdrawal out of {@code HELD} - or its own earlier flight did, re-driven by the key. */
        DONE,
        /** The withdrawal had already left {@code HELD} - released, denied or expired - by another hand. */
        ALREADY,
        /**
         * Payments refused: no withdrawal answers to the id (no status), or - a release only - the hold's validity has
         * lapsed on the database clock while the row is still {@code HELD} (status {@code HELD}): the expiry is the
         * policy's outcome, and the expiry leg fails it.
         */
        REFUSED
    }

    /**
     * A door's answer: the outcome, the withdrawal's committed status when one exists, and whether the answer is a
     * replay of the key's recorded one.
     */
    public record Held(HeldOutcome outcome, Optional<WithdrawalStatus> status, boolean replayed) {
        public Held {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (outcome != HeldOutcome.REFUSED && status.isEmpty()) {
                throw new IllegalArgumentException("a door that acted, or found the row moved, names its status");
            }
            if (outcome == HeldOutcome.REFUSED && status.filter(s -> s != WithdrawalStatus.HELD).isPresent()) {
                throw new IllegalArgumentException("a refusal names no status, or a lapsed HELD");
            }
        }
    }

    private final WithdrawalStore<Connection> withdrawals;
    private final WithdrawalOutcomes outcomes;
    private final HoldService holds;
    private final RoutingStore<Connection> routing;
    private final PaymentRails rails;

    /**
     * Which rails speak the push operation (`P9-TSK-014`, ADR-0080 section 2): the routed rail is
     * looked up, and a rail without a push adapter - a corridor - is refused with nothing sent.
     */
    private final RailOperations operations;
    private final ProviderEvidenceStore<Connection> evidence;
    private final IdempotentExecutor executor;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;

    /**
     * The risk seam (`P13-TSK-009`) - required, with no defaulted overload: a withdrawal engine composed without a
     * judgement must not compile ({@code WithdrawalsSeamIsRequiredTest}).
     */
    private final WithdrawalRiskDecision<Connection> risk;

    public Withdrawals(
            WithdrawalStore<Connection> withdrawals,
            WithdrawalOutcomes outcomes,
            HoldService holds,
            RoutingStore<Connection> routing,
            PaymentRails rails,
            RailOperations operations,
            ProviderEvidenceStore<Connection> evidence,
            IdempotentExecutor executor,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            TransactionRunner transactions,
            WithdrawalRiskDecision<Connection> risk) {
        this.risk = Objects.requireNonNull(risk, "risk must not be null: a withdrawal is judged in-lock");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
        this.rails = Objects.requireNonNull(rails, "rails must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /** What the caller's boundary resolved inside Tx1 — every field a stored fact. */
    public record Resolved(
            UUID partyId,
            UUID customerId,
            LedgerAccountId walletAccountId,
            CurrencyCode walletCurrency,
            UUID paymentMethodId,
            ProviderReference destination) {

        public Resolved {
            Objects.requireNonNull(partyId, "partyId must not be null");
            Objects.requireNonNull(customerId, "customerId must not be null");
            Objects.requireNonNull(walletAccountId, "walletAccountId must not be null");
            Objects.requireNonNull(walletCurrency, "walletCurrency must not be null");
            Objects.requireNonNull(paymentMethodId, "paymentMethodId must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
        }

        /**
         * Identifiers and the currency - never the destination (the Phase 7 -&gt; 8 transition,
         * {@code INV-RAIL-03}): the generated form printed the customer's bank destination
         * reference, and the review's claim that it travelled only in redacting records was
         * false for this one.
         */
        @Override
        public String toString() {
            return "Resolved[party=" + partyId + ", customer=" + customerId
                    + ", wallet=" + walletAccountId + ", currency=" + walletCurrency
                    + ", method=" + paymentMethodId + ", destination=<redacted>]";
        }
    }

    /**
     * Resolves the caller's participants inside the dispatch transaction: the boundary's
     * step-up and ownership checks run HERE, authoritatively, and their refusal takes the
     * claim with it.
     */
    @FunctionalInterface
    public interface Resolver {
        Resolved resolve(Connection unitOfWork);
    }

    /**
     * A recorded instruction a SYSTEM flow asks payments to carry out (`P11-TSK-015`, ADR-0097
     * point 5): {@code <namespace>:<uuid>} - lending's {@code lending:<loanId>} - the amount, and
     * the ledger hold its instructor placed under the wallet's lock, which the withdrawal ADOPTS
     * instead of placing its own. The instruction is the claim's key, the row's dispatch key and
     * part of the request's fingerprint: one withdrawal per instruction, whoever re-drives it.
     */
    public record Instruction(String reference, Money amount, HoldId hold) {

        /** The namespace (lowercase letters and dashes - never a UUID), then the UUID. */
        static final Pattern SHAPE = Pattern.compile(
                "^([a-z][a-z-]{0,31}):"
                        + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

        public Instruction {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(hold, "hold must not be null");
            if (!SHAPE.matcher(reference).matches()) {
                throw new SystemWithdrawalRefusedException(
                        SystemWithdrawalRefusedException.Reason.INSTRUCTION_MALFORMED);
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(
                        "an instructed withdrawal moves a positive amount");
            }
        }

        /** The instruction and the hold - never the amount (the `Resolved` idiom). */
        @Override
        public String toString() {
            return "Instruction[" + reference + ", hold=" + hold + "]";
        }

        /** The claim's scope: {@code payments.withdrawal:<namespace>} - never a customer's. */
        String claimScope() {
            return CLAIM_SCOPE_PREFIX + part(1);
        }

        /** The claim's key: the instruction's UUID. */
        String claimKey() {
            return part(2);
        }

        private String part(int group) {
            Matcher parts = SHAPE.matcher(reference);
            if (!parts.matches()) {
                throw new IllegalStateException("an instruction keeps the shape it was born with");
            }
            return parts.group(group);
        }
    }

    /** The command's committed answer — the row's truth at this call's end. */
    public record Initiated(
            WithdrawalId id,
            WithdrawalStatus status,
            Optional<WithdrawalFailureReason> failureReason,
            Instant createdAt,
            boolean replayed) {

        public Initiated {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(failureReason, "failureReason must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    /** Tx1's committed working state, carried across the connectionless gap: the
     * dispatched row, the recorded routing refusal, or - on an instruction - the recorded
     * refusal of an instruction payments cannot serve (`P11-TSK-015`). */
    private record Dispatch(
            Optional<Withdrawal> withdrawal,
            boolean firstSend,
            Instant permit,
            boolean sendIt,
            Optional<RoutingDecisionId> refusedDecision,
            Optional<SystemWithdrawalRefusedException.Reason> refusedInstruction) {

        private Dispatch {
            int carried = (withdrawal.isPresent() ? 1 : 0) + (refusedDecision.isPresent() ? 1 : 0)
                    + (refusedInstruction.isPresent() ? 1 : 0);
            if (carried != 1) {
                throw new IllegalStateException(
                        "a dispatch carries exactly one of the row and a refusal");
            }
        }

        private Dispatch(
                Optional<Withdrawal> withdrawal,
                boolean firstSend,
                Instant permit,
                boolean sendIt,
                Optional<RoutingDecisionId> refusedDecision) {
            this(withdrawal, firstSend, permit, sendIt, refusedDecision, Optional.empty());
        }

        /** What the claim records as its working body: the row's id, or the refusal's. */
        private byte[] claimBody() {
            String body = withdrawal.map(w -> w.id().value().toString())
                    .or(() -> refusedDecision.map(decision -> decision.value().toString()))
                    .orElseGet(() -> "REFUSED:" + refusedInstruction.orElseThrow().name());
            return body.getBytes(StandardCharsets.UTF_8);
        }

        /** The refusal's response of record, when this dispatch is one. */
        private Optional<StoredResponse> refusalResponse() {
            Optional<String> recorded = refusedDecision
                    .map(decision -> "ERR|NO_ELIGIBLE_RAIL|" + decision.value())
                    .or(() -> refusedInstruction.map(reason -> "ERR|REFUSED|" + reason.name()));
            return recorded.map(text -> StoredResponse.of(text.getBytes(StandardCharsets.UTF_8), "text/plain"));
        }
    }

    public Initiated withdraw(String clientKey, Money amount, Resolver resolver) {
        Objects.requireNonNull(clientKey, "clientKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        return initiate(clientKey, amount, resolver, Optional.empty());
    }

    /**
     * The SYSTEM entry (`P11-TSK-015`, payments {@code V032}, ADR-0097 point 5): a withdrawal on
     * an instructor's recorded instruction, ADOPTING the hold the instructor placed rather than
     * placing a second one - judged under the wallet account's lock (ACTIVE, on the resolved
     * wallet, of exactly the amount; one withdrawal per hold). Everything after the hold is the
     * person's machine unchanged: routing (a refusal recorded under the instruction's key),
     * dispatch, our reference, {@code UNKNOWN} held, the inquiry-only outcomes, the expectation.
     * Refused before any transaction for any actor but the platform: a person cannot use it.
     *
     * <p><strong>The claim comes first, then the resolver</strong> - the person's door's order
     * reversed, because the instruction alone keys and fingerprints the request: so the
     * instruction's withdrawal is looked for, and the instructor's resolver consulted, only
     * under the instruction's claim, which concurrent asks serialise on. A withdrawal already
     * born under the instruction is converged on without asking the resolver at all, and the
     * resolver's two servable refusals - {@code CUSTOMER_UNAVAILABLE},
     * {@code DESTINATION_UNAVAILABLE} - are RECORDED under the key like the routing refusal and
     * replayed: an instruction refused is refused for good, so no withdrawal can ever be born
     * under it afterwards, and the instructor may release its hold.
     *
     * @throws SystemWithdrawalRefusedException not the system actor, a hold not adoptable, or a
     *     recorded refusal of the instruction (replayed under the key)
     * @throws NoEligibleRailException the recorded routing refusal (replayed under the key)
     */
    public Initiated withdrawOnInstruction(Instruction instruction, Resolver resolver) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        if (SecurityContext.require().type() != ActorType.SYSTEM) {
            throw new SystemWithdrawalRefusedException(
                    SystemWithdrawalRefusedException.Reason.NOT_A_SYSTEM_ACTOR);
        }
        return initiate(
                instruction.reference(), instruction.amount(), resolver, Optional.of(instruction));
    }

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    private Initiated initiate(
            String clientKey, Money amount, Resolver resolver, Optional<Instruction> instruction) {
        Correlation correlation = resolvedCorrelation();
        Actor person = SecurityContext.require();

        Dispatch[] holder = new Dispatch[1];
        IdempotencyKey[] keyHolder = new IdempotencyKey[1];
        IdempotentExecutor.BeginOutcome begun =
                transactions.inTransaction(
                        uow -> {
                            // The person's boundary checks run first, inside the deciding
                            // transaction: a refusal rolls the claim back, so the key stays
                            // unburned. An instruction keys itself: its resolver runs under the
                            // claim (dispatchOrConverge), and only when no withdrawal exists.
                            Optional<Resolved> early =
                                    instruction.isPresent()
                                            ? Optional.empty()
                                            : Optional.of(resolvedFor(uow, resolver, amount));
                            IdempotencyKey key =
                                    instruction.isPresent()
                                            ? new IdempotencyKey(
                                                    instruction.get().claimScope(),
                                                    instruction.get().claimKey())
                                            : new IdempotencyKey(
                                                    CLAIM_SCOPE_PREFIX + early.get().customerId(),
                                                    clientKey);
                            keyHolder[0] = key;
                            RequestFingerprint fingerprint =
                                    RequestFingerprint.sha256(
                                            fingerprinted(early, amount, instruction)
                                                    .getBytes(StandardCharsets.UTF_8));
                            IdempotentExecutor.BeginOutcome outcome =
                                    executor.begin(
                                            uow,
                                            key,
                                            fingerprint,
                                            claimed -> {
                                                Dispatch dispatched =
                                                        dispatchOrConverge(
                                                                claimed,
                                                                clientKey,
                                                                amount,
                                                                early,
                                                                resolver,
                                                                person,
                                                                correlation,
                                                                instruction);
                                                holder[0] = dispatched;
                                                return dispatched.claimBody();
                                            });
                            // A refusal is recorded: the refused decision (or the instruction's
                            // refusal) AND the claim's outcome commit together, and the exception
                            // follows the commit.
                            if (outcome.dispatched().isPresent()) {
                                holder[0].refusalResponse().ifPresent(
                                        response -> executor.complete(uow, key, false, response));
                            }
                            return outcome;
                        });
        if (begun.replay().isPresent()) {
            return parsedReplay(begun.replay().get());
        }
        Dispatch dispatch = holder[0];
        if (dispatch.refusedDecision().isPresent()) {
            throw new NoEligibleRailException(dispatch.refusedDecision().get());
        }
        if (dispatch.refusedInstruction().isPresent()) {
            throw new SystemWithdrawalRefusedException(dispatch.refusedInstruction().get());
        }
        Withdrawal committed = dispatch.withdrawal().orElseThrow();

        // THE WIRE, holding no connection (ADR-0046): the dispatch is durable, so whatever
        // happens now - a timeout, a crash, a lost response - the withdrawal is on the
        // record and a resolver will find it.
        Optional<PushAnswer> answer =
                dispatch.sendIt()
                        ? Optional.of(
                                pushRailOf(committed.railId()).send(
                                        new PushRail.CreditTransfer(
                                                committed.reference(),
                                                committed.destination(),
                                                committed.amount())))
                        : Optional.empty();

        // The outcome is the platform's act, whoever asked (an enumerated enterSystem()
        // site): the same answer applied by the sweep is the platform's too.
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> {
                        Withdrawal locked =
                                withdrawals
                                        .findForUpdate(uow, committed.id())
                                        .orElseThrow(
                                                () ->
                                                        new PaymentsStorageException(
                                                                "a committed withdrawal was"
                                                                        + " not found to"
                                                                        + " resolve"));
                        WithdrawalStatus judged = locked.status();
                        if (answer.isPresent()) {
                            WithdrawalOutcomes.Applied applied =
                                    outcomes.applySendAnswer(
                                            uow,
                                            locked,
                                            answer.get(),
                                            dispatch.firstSend(),
                                            dispatch.permit(),
                                            correlation);
                            judged = applied.status();
                            answer.get()
                                    .evidence()
                                    .ifPresent(
                                            bytes ->
                                                    evidence.appendForWithdrawal(
                                                            uow,
                                                            locked.id(),
                                                            EvidenceKind.RESPONSE,
                                                            bytes,
                                                            Instant.now(clock)));
                        }
                        Withdrawal current =
                                withdrawals
                                        .findForUpdate(uow, committed.id())
                                        .orElseThrow();
                        // The claim records the JUDGED status: a replay answers what this
                        // request was told (the MerchantPayouts rule).
                        executor.complete(
                                uow,
                                keyHolder[0],
                                true,
                                StoredResponse.of(
                                        renderedForm(current).getBytes(StandardCharsets.UTF_8),
                                        "text/plain"));
                        return new Initiated(
                                current.id(),
                                judged,
                                current.failureReason(),
                                current.createdAt(),
                                false);
                    });
        }
    }

    /**
     * {@code INV-REV-03}, judged before anything else exists: on a rail whose declaration
     * is final-on-acceptance with no reversal capability, the refusal costs zero
     * transactions, zero wire calls and writes nothing. Today's callers are the acceptance
     * tests. A recall is NOT a caller of this gate: it is a request the payee's PSP may refuse,
     * a new operation with its own lifecycle, never a reversal (ADR-0059's rejected
     * alternative; the Phase 7 review corrected this javadoc, which named one as the future
     * caller, and recorded recalls as deliberately deferred in {@code DECISIONS.md}).
     */
    public void reverse(WithdrawalId id) {
        Objects.requireNonNull(id, "id must not be null");
        // Every rail a withdrawal can ride is a push rail of the directory (P9-TSK-014), and none
        // declares a reversal - so the refusal needs no read of the row.
        for (RailId rail : operations.pushRailIds()) {
            if (!rails.capabilitiesOf(rail).reversals().isEmpty()) {
                throw new IllegalStateException(
                        "push rail '" + rail.value() + "' declares a reversal capability; a"
                                + " declaration change is a new descriptor version with its own"
                                + " reversal flow, not this method growing one (INV-REV-03, ADR-0062 §3)");
            }
        }
        throw new ReversalNotSupportedException(
                operations.pushRailIds().stream()
                        .sorted(java.util.Comparator.comparing(RailId::value))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "the withdrawal engine is composed only beside a push rail")));
    }

    // ----------------------------------------------------------------- the held withdrawal's doors

    /**
     * Releases a {@code HELD} withdrawal (`P13-TSK-009`; reached through risk's {@code HeldWithdrawalActions} by an
     * approved override, `-012`, and a dispatched risk action, `-017`): the person's dispatch shape, keyed by the
     * caller's {@code key} under {@link #RELEASE_SCOPE}.
     *
     * <ol>
     *   <li><strong>Tx1</strong>: the claim; the withdrawal row {@code FOR UPDATE} (payments' order - the row first);
     *       on a {@code HELD} row the conditional edge {@code HELD → DISPATCHED} with the database's send permit and the
     *       key recorded, the release announced and audited as the caller's act - committed before anything is sent.
     *       A row no longer {@code HELD} answers {@link HeldOutcome#ALREADY} (recorded under the key, nothing sent); an
     *       unknown id {@link HeldOutcome#REFUSED}.</li>
     *   <li><strong>The wire</strong>, holding no connection: our stored reference sent (ADR-0046).</li>
     *   <li><strong>Tx2</strong>, the platform's act: the row locked, the scheme's word applied through
     *       {@link WithdrawalOutcomes} (the hold released and the posting made only by an acceptance), the claim
     *       completed {@code DONE}.</li>
     * </ol>
     *
     * <p><strong>A crash between</strong> leaves the claim in flight: the same key, re-driven after the lease, finds
     * the row released under ITS key and re-sends our reference behind a conditionally renewed permit (the scheme
     * deduplicates on it), or converges on a row a resolver already concluded. Never re-driven, the inquiry sweep
     * concludes it as any dispatched withdrawal - {@code NEVER_RECEIVED} past the declared deadline, the hold released.
     *
     * @param reasonCode why, as a code ({@code [A-Z][A-Z0-9_]{0,63}}) - never a note
     * @throws IllegalArgumentException a malformed key or reason code
     * @throws IllegalStateException a caller that is not the platform or an operator - a customer never releases a
     *     held withdrawal, theirs or anyone's
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key reused for another withdrawal
     */
    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public Held releaseHeld(String key, WithdrawalId id, String reasonCode) {
        Objects.requireNonNull(id, "id must not be null");
        requireDoor(key, reasonCode);
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey claim = new IdempotencyKey(RELEASE_SCOPE, key);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("payments.withdrawal.release|" + id.value() + "|" + reasonCode)
                        .getBytes(StandardCharsets.UTF_8));

        Release[] holder = new Release[1];
        IdempotentExecutor.BeginOutcome begun =
                transactions.inTransaction(
                        uow -> {
                            IdempotentExecutor.BeginOutcome outcome =
                                    executor.begin(
                                            uow,
                                            claim,
                                            fingerprint,
                                            claimed -> {
                                                Release release =
                                                        releaseOrConverge(
                                                                claimed, id, key, reasonCode,
                                                                correlation);
                                                holder[0] = release;
                                                return release.claimBody();
                                            });
                            // An answer with nothing to send is recorded with the claim, now.
                            if (outcome.dispatched().isPresent() && holder[0].answer().isPresent()) {
                                executor.complete(
                                        uow, claim, true, heldResponse(holder[0].answer().get()));
                            }
                            return outcome;
                        });
        if (begun.replay().isPresent()) {
            return parsedHeld(begun.replay().get(), true);
        }
        Release release = holder[0];
        if (release.answer().isPresent()) {
            return release.answer().get();
        }
        Withdrawal released = release.withdrawal().orElseThrow();

        // THE WIRE, holding no connection (ADR-0046): the release is durable, so whatever happens
        // now the withdrawal is on the record as DISPATCHED and the key or a resolver finds it.
        Optional<PushAnswer> answer =
                release.sendIt()
                        ? Optional.of(
                                pushRailOf(released.railId()).send(
                                        new PushRail.CreditTransfer(
                                                released.reference(),
                                                released.destination(),
                                                released.amount())))
                        : Optional.empty();

        // The outcome is the platform's act, whoever released (an enumerated enterSystem() site):
        // the same answer applied by the inquiry sweep is the platform's too.
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> {
                        Withdrawal locked =
                                withdrawals
                                        .findForUpdate(uow, released.id())
                                        .orElseThrow(
                                                () ->
                                                        new PaymentsStorageException(
                                                                "a released withdrawal was not"
                                                                        + " found to resolve"));
                        if (answer.isPresent()) {
                            outcomes.applySendAnswer(
                                    uow,
                                    locked,
                                    answer.get(),
                                    release.firstSend(),
                                    release.permit(),
                                    correlation);
                            answer.get()
                                    .evidence()
                                    .ifPresent(
                                            bytes ->
                                                    evidence.appendForWithdrawal(
                                                            uow,
                                                            locked.id(),
                                                            EvidenceKind.RESPONSE,
                                                            bytes,
                                                            Instant.now(clock)));
                        }
                        Withdrawal current =
                                withdrawals.findForUpdate(uow, released.id()).orElseThrow();
                        Held done = new Held(HeldOutcome.DONE, Optional.of(current.status()), false);
                        executor.complete(uow, claim, true, heldResponse(done));
                        return done;
                    });
        }
    }

    /**
     * Denies a {@code HELD} withdrawal (`P13-TSK-009`): keyed by the caller's {@code key} under {@link #DENY_SCOPE},
     * in ONE transaction - the claim, the withdrawal row {@code FOR UPDATE}, the conditional edge
     * {@code HELD → FAILED(RISK_REFUSED)}, its hold released under the wallet account's lock (payments' order: the
     * row, then the wallet), the failure announced and the denial audited as the caller's act, the claim completed.
     * A row no longer {@code HELD} answers {@link HeldOutcome#ALREADY}; an unknown id {@link HeldOutcome#REFUSED}; a
     * re-driven key its recorded answer.
     *
     * @param reasonCode why, as a code ({@code [A-Z][A-Z0-9_]{0,63}}) - never a note
     * @throws IllegalArgumentException a malformed key or reason code
     * @throws IllegalStateException a caller that is not the platform or an operator
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key reused for another withdrawal
     */
    public Held denyHeld(String key, WithdrawalId id, String reasonCode) {
        Objects.requireNonNull(id, "id must not be null");
        requireDoor(key, reasonCode);
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey claim = new IdempotencyKey(DENY_SCOPE, key);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("payments.withdrawal.deny|" + id.value() + "|" + reasonCode)
                        .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome executed =
                transactions.inTransaction(
                        uow ->
                                executor.execute(
                                        uow,
                                        claim,
                                        fingerprint,
                                        claimed -> {
                                            Optional<Withdrawal> found =
                                                    withdrawals.findForUpdate(claimed, id);
                                            Held answer;
                                            if (found.isEmpty()) {
                                                answer = new Held(HeldOutcome.REFUSED,
                                                        Optional.empty(), false);
                                            } else if (found.get().status() != WithdrawalStatus.HELD) {
                                                answer = new Held(HeldOutcome.ALREADY,
                                                        Optional.of(found.get().status()), false);
                                            } else {
                                                outcomes.denyHeld(claimed, found.get(), key,
                                                        reasonCode, correlation);
                                                answer = new Held(HeldOutcome.DONE,
                                                        Optional.of(WithdrawalStatus.FAILED), false);
                                            }
                                            return CommandResult.succeeded(heldResponse(answer));
                                        }));
        return parsedHeld(executed.response(), executed.replayed());
    }

    /** Tx1's working state of a release: the released (or converged) row to send, or an answer with nothing to send. */
    private record Release(
            Optional<Withdrawal> withdrawal,
            boolean firstSend,
            Instant permit,
            boolean sendIt,
            Optional<Held> answer) {

        static Release answered(Held answer) {
            return new Release(Optional.empty(), false, Instant.EPOCH, false, Optional.of(answer));
        }

        static Release send(Withdrawal withdrawal, boolean firstSend, Instant permit) {
            return new Release(Optional.of(withdrawal), firstSend, permit, true, Optional.empty());
        }

        static Release converge(Withdrawal withdrawal) {
            return new Release(
                    Optional.of(withdrawal), false, withdrawal.lastDispatchedAt(), false, Optional.empty());
        }

        byte[] claimBody() {
            return withdrawal.map(w -> w.id().value().toString())
                    .orElseGet(() -> "ANSWERED:" + answer.orElseThrow().outcome().name())
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Tx1 of a release under the key's claim - a fresh claim or a takeover, converging either way (the
     * {@code DispatchCommand} contract): the row locked first.
     */
    private Release releaseOrConverge(
            Connection uow, WithdrawalId id, String key, String reasonCode, Correlation correlation) {
        Optional<Withdrawal> found = withdrawals.findForUpdate(uow, id);
        if (found.isEmpty()) {
            return Release.answered(new Held(HeldOutcome.REFUSED, Optional.empty(), false));
        }
        Withdrawal locked = found.get();
        if (locked.status() == WithdrawalStatus.HELD) {
            Optional<Withdrawal> released = outcomes.releaseHeld(uow, locked, key, reasonCode, correlation);
            if (released.isEmpty()) {
                // Past held_until on the database clock: the policy's outcome is the expiry, never
                // a late release - refused, nothing written, the expiry leg fails it.
                return Release.answered(
                        new Held(HeldOutcome.REFUSED, Optional.of(WithdrawalStatus.HELD), false));
            }
            return Release.send(released.get(), true, released.get().lastDispatchedAt());
        }
        // Released by THIS key: the row carries the key, and was not denied (a denial records its
        // key too, beside RISK_REFUSED; a released row can fail only by the scheme's word).
        boolean releasedByThisKey =
                locked.heldResolutionKey().filter(key::equals).isPresent()
                        && locked.failureReason()
                                .filter(WithdrawalFailureReason.RISK_REFUSED::equals)
                                .isEmpty();
        if (releasedByThisKey) {
            // THE TAKEOVER of this key's own release: its Tx1 committed and its flight was lost.
            // Re-send OUR reference behind a conditionally renewed permit while the scheme's word is
            // still awaited (INV-PAY-04: the scheme deduplicates on it); a concluded row converges.
            if (locked.status().isResolvable()) {
                Optional<Instant> renewed = withdrawals.renewSendPermit(uow, locked);
                if (renewed.isPresent()) {
                    return Release.send(locked.withSendPermit(renewed.get()), false, renewed.get());
                }
            }
            return Release.converge(locked);
        }
        // Released, denied or expired by another hand: this key changes nothing.
        return Release.answered(new Held(HeldOutcome.ALREADY, Optional.of(locked.status()), false));
    }

    /** The doors' preconditions: a bounded key, a reason code, and a caller who may act on a held withdrawal. */
    private static void requireDoor(String key, String reasonCode) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (key.isBlank() || key.length() > Withdrawal.MAX_RESOLUTION_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "a held withdrawal's door key is 1-" + Withdrawal.MAX_RESOLUTION_KEY_LENGTH + " characters");
        }
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "a held withdrawal's door takes a reason code, never a note (INV-RSK-08)");
        }
        ActorType acting = SecurityContext.require().type();
        if (acting != ActorType.SYSTEM && acting != ActorType.EMPLOYEE) {
            throw new IllegalStateException(
                    "a held withdrawal is released or denied only by the platform or an operator,"
                            + " through risk's four-eyes paths - never by " + acting);
        }
    }

    /** A door's answer, stored for the replay: {@code HELD|outcome|status}. */
    private static StoredResponse heldResponse(Held held) {
        String text = "HELD|" + held.outcome().name() + "|"
                + held.status().map(Enum::name).orElse("-");
        return StoredResponse.of(text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static Held parsedHeld(StoredResponse response, boolean replayed) {
        String[] parts = new String(response.body(), StandardCharsets.UTF_8).split("\\|");
        if (parts.length != 3 || !"HELD".equals(parts[0])) {
            throw new IllegalStateException("a held withdrawal's door claim held a response in no known form");
        }
        return new Held(
                HeldOutcome.valueOf(parts[1]),
                "-".equals(parts[2]) ? Optional.empty() : Optional.of(WithdrawalStatus.valueOf(parts[2])),
                replayed);
    }

    /**
     * The push adapter speaking for {@code rail}, or a loud refusal: a routed rail lacking the push
     * operation - a corridor (`P9-TSK-014`) - is a policy naming the wrong rail for a withdrawal.
     */
    private PushRail pushRailOf(RailId rail) {
        return operations.pushRail(rail)
                .orElseThrow(() -> new IllegalStateException(
                        "routing chose '" + rail.value() + "', which speaks no push operation in this"
                                + " composition: a withdrawal is sent through a push rail, and nothing"
                                + " was written or sent (ADR-0080 section 2)"));
    }

    // -----------------------------------------------------------------

    private Dispatch dispatchOrConverge(
            Connection uow,
            String clientKey,
            Money amount,
            Optional<Resolved> early,
            Resolver resolver,
            Actor person,
            Correlation correlation,
            Optional<Instruction> instruction) {
        Optional<Withdrawal> existing =
                instruction.isPresent()
                        ? withdrawals.findByInstruction(uow, instruction.get().reference())
                        : withdrawals.findByDispatchKey(uow, early.orElseThrow().customerId(), clientKey);
        if (existing.isPresent()) {
            // THE TAKEOVER: this claim's lease expired with its withdrawal already
            // committed. The fingerprint already refused different facts under this key;
            // a row that disagrees is a defect, refused loudly rather than guessed at.
            Withdrawal found = existing.get();
            boolean anotherHold =
                    instruction.isPresent() && !instruction.get().hold().equals(found.holdId());
            if (!found.amount().equals(amount) || anotherHold) {
                throw new IllegalStateException(
                        "a taken-over withdrawal claim found different facts under its key");
            }
            // A withdrawal that was HELD was never sent by this flight (P13-TSK-009): if a door has
            // since released it, the send is that door's key's to re-drive, never this one's.
            if (found.status().isResolvable() && found.held().isEmpty()) {
                Optional<Instant> renewed = withdrawals.renewSendPermit(uow, found);
                if (renewed.isPresent()) {
                    // The renewal won: re-send OUR stored reference (INV-PAY-04); the
                    // scheme deduplicates on it. The permit as the database stamped it.
                    return new Dispatch(
                            Optional.of(found.withSendPermit(renewed.get())),
                            false,
                            renewed.get(),
                            true,
                            Optional.empty());
                }
            }
            // A resolver got there first (or the row is terminal): nothing may be sent;
            // Tx2 converges on the row's truth.
            return new Dispatch(
                    Optional.of(found), false, found.lastDispatchedAt(), false, Optional.empty());
        }

        // An instruction's resolver, under its claim and only now that no withdrawal exists: its
        // servable refusals are the instruction's recorded answer (P11-TSK-015).
        Resolved resolved;
        if (early.isPresent()) {
            resolved = early.get();
        } else {
            try {
                resolved = resolvedFor(uow, resolver, amount);
            } catch (SystemWithdrawalRefusedException refused) {
                if (!refused.reason().isRecorded()) {
                    throw refused;
                }
                return new Dispatch(Optional.empty(), true, Instant.now(clock), false,
                        Optional.empty(), Optional.of(refused.reason()));
            }
        }

        // THE ROUTING MOMENT (ADR-0060 §2): decided from stored facts in this transaction,
        // pinned beside the row it governs.
        RoutingPolicyVersion policy =
                routing.findVersionInForce(uow, Instant.now(clock))
                        .orElseThrow(
                                () ->
                                        new PaymentsStorageException(
                                                "no routing policy version is in force:"
                                                        + " V016's seed guarantees one"));
        RoutingInputs inputs =
                new RoutingInputs(
                        PaymentDirection.PAY_OUT,
                        InstrumentKind.BANK_ACCOUNT,
                        amount,
                        // Reachability, from the grant exchange's stored result: one push
                        // scheme exists and the registry only mints through it
                        // (`P7-TSK-007`); a second scheme's arrival brings provenance and
                        // per-candidate answers (the recorded seam).
                        Optional.of(Boolean.TRUE));
        RoutingPlan plan = policy.decide(inputs, rails, routing.availabilityByRail(uow));
        if (plan.chosen().isEmpty()) {
            RoutingDecision refused =
                    RoutingDecision.create(
                            ids,
                            clock,
                            RoutingSubject.ofWithdrawal(WithdrawalId.next(ids)),
                            policy.id(),
                            inputs,
                            plan);
            // The refusal is recorded against a minted-but-never-born withdrawal id: the
            // decision is the fact (INV-RAIL-02's refusal arm), no row accompanies it,
            // and the wallet is untouched.
            routing.insertDecision(uow, refused);
            return new Dispatch(
                    Optional.empty(), true, Instant.now(clock), false, Optional.of(refused.id()));
        }
        RailId chosen = plan.chosen().orElseThrow();
        if (rails.capabilitiesOf(chosen).interactionModel() != InteractionModel.PUSH) {
            throw new IllegalStateException(
                    "routing chose '" + chosen.value() + "', whose interaction model is not"
                            + " the push machine this command dispatches: eligibility should"
                            + " have refused it");
        }
        // THE DIRECTORY'S LOOKUP (P9-TSK-014, ADR-0080 section 2): the routed rail must speak
        // the push operation this command sends through. A corridor rail is a push model too, so
        // the model check above passes it - and here it is refused, before the hold: the whole
        // transaction rolls back, nothing is written and nothing is sent.
        pushRailOf(chosen);

        // THE BOUND, judged under the wallet account's lock (INV-BAL-04): ten concurrent
        // withdrawals admit exactly the affordable set.
        // An instructed withdrawal ADOPTS the hold its instructor placed, judged under the same
        // lock (P11-TSK-015): the money was held once and is never held twice - payments V032's
        // one-withdrawal-per-hold index stands behind the judgement.
        Hold hold;
        if (instruction.isPresent()) {
            hold = holds.adopt(
                            uow, instruction.get().hold(), resolved.walletAccountId(), amount)
                    .orElseThrow(
                            () ->
                                    new SystemWithdrawalRefusedException(
                                            SystemWithdrawalRefusedException.Reason
                                                    .HOLD_NOT_ADOPTABLE));
        } else {
            try {
                hold = holds.place(uow, resolved.walletAccountId(), amount);
            } catch (HoldExceedsAvailableBalanceException unfunded) {
                // Aborts the transaction whole - claim, decision, everything: transient
                // state, and the same key may honestly retry after a top-up.
                throw new WithdrawalUnfundedException();
            }
        }

        // THE JUDGEMENT (P13-TSK-009, PHASE_13_PLAN.md section 7.3): in-lock, after the hold and
        // before anything commits, under the id this withdrawal will be born with - the judgement's
        // command reference. The instruction carries out a credit decision and is not judged (the
        // seam's javadoc). A seam failure aborts the whole transaction: no row, no hold, no claim.
        WithdrawalId id = WithdrawalId.next(ids);
        WithdrawalVerdict verdict =
                instruction.isPresent()
                        ? WithdrawalVerdict.permit()
                        : Objects.requireNonNull(
                                risk.check(
                                        uow,
                                        new WithdrawalRiskDecision.Judged(
                                                id,
                                                resolved.partyId(),
                                                resolved.customerId(),
                                                resolved.walletAccountId(),
                                                resolved.paymentMethodId(),
                                                amount)),
                                "the withdrawal risk seam answers a verdict");
        Withdrawal fresh =
                switch (verdict.kind()) {
                    case PERMIT ->
                            Withdrawal.dispatch(ids, clock, id, resolved.partyId(),
                                    resolved.customerId(), resolved.walletAccountId(),
                                    resolved.paymentMethodId(), resolved.destination(), amount,
                                    chosen, hold.id());
                    case HOLD ->
                            Withdrawal.held(ids, clock, id, resolved.partyId(),
                                    resolved.customerId(), resolved.walletAccountId(),
                                    resolved.paymentMethodId(), resolved.destination(), amount,
                                    chosen, hold.id(), verdict.holdValidity().orElseThrow());
                    case REFUSE ->
                            Withdrawal.refused(ids, clock, id, resolved.partyId(),
                                    resolved.customerId(), resolved.walletAccountId(),
                                    resolved.paymentMethodId(), resolved.destination(), amount,
                                    chosen, hold.id());
                };
        // The birth as stored (X-TSK-013, P13-TSK-009): the permit the first-send rule later
        // compares with, and a held birth's window on the database clock.
        WithdrawalStore.Stored stored =
                instruction.isPresent()
                        ? withdrawals.insertInstructed(uow, fresh, instruction.get().reference())
                        : withdrawals.insert(uow, fresh, clientKey);
        fresh = fresh.withStoredBirth(stored.sendPermit(), stored.held());
        routing.insertDecision(
                uow,
                RoutingDecision.create(
                        ids,
                        clock,
                        RoutingSubject.ofWithdrawal(fresh.id()),
                        policy.id(),
                        inputs,
                        plan));

        Instant now = Instant.now(clock);
        PaymentsAuditAction act;
        AuditOutcome actOutcome = AuditOutcome.SUCCEEDED;
        switch (verdict.kind()) {
            case PERMIT -> {
                outcomes.announceInitiated(uow, fresh, correlation, now);
                act = PaymentsAuditAction.WITHDRAWAL_DISPATCHED;
            }
            case HOLD -> {
                // The hold STANDS: the held withdrawal's reservation until a door or the expiry.
                outcomes.announceHeld(uow, fresh, correlation, now);
                act = PaymentsAuditAction.WITHDRAWAL_HELD;
            }
            default -> {
                // INV-BAL-04: the refusal's release, in the transaction that judged the hold.
                outcomes.refusedAtBirth(uow, fresh, correlation, now);
                act = PaymentsAuditAction.WITHDRAWAL_REFUSED;
                actOutcome = AuditOutcome.FAILED;
            }
        }
        // The person's own act (the dispatch, the hold, the refusal) - the platform's, on an
        // instruction; every outcome is the platform's.
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        person,
                        now,
                        act,
                        TARGET_TYPE,
                        fresh.id().value().toString(),
                        Optional.empty(),
                        actOutcome,
                        correlation.correlationId(),
                        Optional.of(
                                "withdrawal=" + fresh.id()
                                        + ", wallet=" + fresh.walletAccountId()
                                        + ", method=" + fresh.paymentMethodId()
                                        + ", rail=" + chosen.value()
                                        + (verdict.kind() == WithdrawalVerdict.Kind.PERMIT
                                                ? ""
                                                : ", status=" + fresh.status()))));
        // Only a permitted withdrawal is sent; a held or refused one has nothing on the wire, and
        // Tx2 records its judged status as the claim's response.
        return new Dispatch(
                Optional.of(fresh),
                true,
                fresh.lastDispatchedAt(),
                verdict.kind() == WithdrawalVerdict.Kind.PERMIT,
                Optional.empty());
    }

    /** The caller's resolution with the currency judged - a mismatch aborts, writing nothing. */
    private static Resolved resolvedFor(Connection uow, Resolver resolver, Money amount) {
        Resolved resolved = resolver.resolve(uow);
        if (!amount.currency().equals(resolved.walletCurrency())) {
            throw new WithdrawalCurrencyMismatchedException(
                    resolved.walletCurrency().code(), amount.currency().code());
        }
        return resolved;
    }

    /**
     * The request's fingerprint text: the person's verbatim; the system's the recorded
     * instruction, the hold it adopts and the amount (ADR-0097 point 5) - everything that keys
     * an instruction, known before its resolver runs; a re-drive naming another hold or amount
     * under the same instruction is the key's distinct conflict.
     */
    private static String fingerprinted(
            Optional<Resolved> early, Money amount, Optional<Instruction> instruction) {
        String money =
                amount.minorUnits() + "|" + amount.currency().code() + "|" + amount.scale();
        if (instruction.isEmpty()) {
            Resolved resolved = early.orElseThrow();
            return "payments.withdrawal|"
                    + resolved.customerId()
                    + "|"
                    + resolved.paymentMethodId()
                    + "|"
                    + money;
        }
        return "payments.withdrawal.system|"
                + instruction.get().reference()
                + "|"
                + instruction.get().hold()
                + "|"
                + money;
    }

    /** The judged status, stored for the replay: {@code OK|id|status|reason|createdAt}. */
    private static String renderedForm(Withdrawal current) {
        return "OK|"
                + current.id().value()
                + "|"
                + current.status().name()
                + "|"
                + current.failureReason().map(Enum::name).orElse("-")
                + "|"
                + current.createdAt();
    }

    private static Initiated parsedReplay(StoredResponse response) {
        String stored = new String(response.body(), StandardCharsets.UTF_8);
        if (stored.startsWith("ERR|NO_ELIGIBLE_RAIL|")) {
            throw new NoEligibleRailException(
                    RoutingDecisionId.of(UUID.fromString(stored.substring(21))));
        }
        if (stored.startsWith("ERR|REFUSED|")) {
            throw new SystemWithdrawalRefusedException(
                    SystemWithdrawalRefusedException.Reason.valueOf(stored.substring(12)));
        }
        String[] parts = stored.split("\\|");
        if (parts.length != 5 || !"OK".equals(parts[0])) {
            throw new IllegalStateException(
                    "a withdrawal claim held a response in no known form");
        }
        return new Initiated(
                WithdrawalId.of(UUID.fromString(parts[1])),
                WithdrawalStatus.valueOf(parts[2]),
                "-".equals(parts[3])
                        ? Optional.empty()
                        : Optional.of(WithdrawalFailureReason.valueOf(parts[3])),
                Instant.parse(parts[4]),
                true);
    }

    /** The flow's correlation with the cause resolved — the established idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a withdrawal must run inside a correlation"
                                                        + " scope"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
