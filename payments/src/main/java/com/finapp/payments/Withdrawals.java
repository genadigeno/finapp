package com.finapp.payments;

import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountId;
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
 */
public final class Withdrawals {

    static final String TARGET_TYPE = "withdrawal";

    /** The claim's scope prefix; the owning customer completes it (ADR-0004, ADR-0057 §5). */
    static final String CLAIM_SCOPE_PREFIX = "payments.withdrawal:";

    private final WithdrawalStore<Connection> withdrawals;
    private final WithdrawalOutcomes outcomes;
    private final HoldService holds;
    private final RoutingStore<Connection> routing;
    private final PaymentRails rails;
    private final PushRail rail;
    private final RailId railId;
    private final ProviderEvidenceStore<Connection> evidence;
    private final IdempotentExecutor executor;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;

    public Withdrawals(
            WithdrawalStore<Connection> withdrawals,
            WithdrawalOutcomes outcomes,
            HoldService holds,
            RoutingStore<Connection> routing,
            PaymentRails rails,
            PushRail rail,
            RailId railId,
            ProviderEvidenceStore<Connection> evidence,
            IdempotentExecutor executor,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            TransactionRunner transactions) {
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
        this.rails = Objects.requireNonNull(rails, "rails must not be null");
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
        this.railId = Objects.requireNonNull(railId, "railId must not be null");
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
     * dispatched row, or the recorded routing refusal. */
    private record Dispatch(
            Optional<Withdrawal> withdrawal,
            boolean firstSend,
            Instant permit,
            boolean sendIt,
            Optional<RoutingDecisionId> refusedDecision) {

        private Dispatch {
            if (withdrawal.isPresent() == refusedDecision.isPresent()) {
                throw new IllegalStateException(
                        "a dispatch carries exactly one of the row and the refusal");
            }
        }
    }

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public Initiated withdraw(String clientKey, Money amount, Resolver resolver) {
        Objects.requireNonNull(clientKey, "clientKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        Correlation correlation = resolvedCorrelation();
        Actor person = SecurityContext.require();

        Dispatch[] holder = new Dispatch[1];
        IdempotencyKey[] keyHolder = new IdempotencyKey[1];
        IdempotentExecutor.BeginOutcome begun =
                transactions.inTransaction(
                        uow -> {
                            // The boundary's own checks, inside the deciding transaction:
                            // a refusal rolls the claim back, so the key stays unburned.
                            Resolved resolved = resolver.resolve(uow);
                            if (!amount.currency().equals(resolved.walletCurrency())) {
                                throw new WithdrawalCurrencyMismatchedException(
                                        resolved.walletCurrency().code(),
                                        amount.currency().code());
                            }
                            IdempotencyKey key =
                                    new IdempotencyKey(
                                            CLAIM_SCOPE_PREFIX + resolved.customerId(),
                                            clientKey);
                            keyHolder[0] = key;
                            RequestFingerprint fingerprint =
                                    RequestFingerprint.sha256(
                                            ("payments.withdrawal|"
                                                            + resolved.customerId()
                                                            + "|"
                                                            + resolved.paymentMethodId()
                                                            + "|"
                                                            + amount.minorUnits()
                                                            + "|"
                                                            + amount.currency().code()
                                                            + "|"
                                                            + amount.scale())
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
                                                                resolved,
                                                                person,
                                                                correlation);
                                                holder[0] = dispatched;
                                                return dispatched
                                                        .withdrawal()
                                                        .map(w -> w.id().value().toString())
                                                        .orElseGet(
                                                                () ->
                                                                        dispatched
                                                                                .refusedDecision()
                                                                                .orElseThrow()
                                                                                .value()
                                                                                .toString())
                                                        .getBytes(StandardCharsets.UTF_8);
                                            });
                            // An unroutable withdrawal is a recorded refusal: the refused
                            // decision AND the claim's outcome commit together, and the
                            // exception follows the commit.
                            if (outcome.dispatched().isPresent()
                                    && holder[0].refusedDecision().isPresent()) {
                                executor.complete(
                                        uow,
                                        key,
                                        false,
                                        StoredResponse.of(
                                                ("ERR|NO_ELIGIBLE_RAIL|"
                                                                + holder[0]
                                                                        .refusedDecision()
                                                                        .get()
                                                                        .value())
                                                        .getBytes(StandardCharsets.UTF_8),
                                                "text/plain"));
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
        Withdrawal committed = dispatch.withdrawal().orElseThrow();

        // THE WIRE, holding no connection (ADR-0046): the dispatch is durable, so whatever
        // happens now - a timeout, a crash, a lost response - the withdrawal is on the
        // record and a resolver will find it.
        Optional<PushAnswer> answer =
                dispatch.sendIt()
                        ? Optional.of(
                                rail.send(
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
     * transactions, zero wire calls and writes nothing. The surface that will one day ask
     * (a Phase 13 recall) meets the same gate; today's callers are the acceptance tests.
     */
    public void reverse(WithdrawalId id) {
        Objects.requireNonNull(id, "id must not be null");
        RailCapabilities declared = rails.capabilitiesOf(railId);
        if (declared.reversals().isEmpty()) {
            throw new ReversalNotSupportedException(railId);
        }
        throw new IllegalStateException(
                "the instant rail declares no reversal capability; a declaration change is"
                        + " a new descriptor version with its own reversal flow, not this"
                        + " method growing one (INV-REV-03, ADR-0062 §3)");
    }

    // -----------------------------------------------------------------

    private Dispatch dispatchOrConverge(
            Connection uow,
            String clientKey,
            Money amount,
            Resolved resolved,
            Actor person,
            Correlation correlation) {
        Optional<Withdrawal> existing =
                withdrawals.findByDispatchKey(uow, resolved.customerId(), clientKey);
        if (existing.isPresent()) {
            // THE TAKEOVER: this claim's lease expired with its withdrawal already
            // committed. The fingerprint already refused different facts under this key;
            // a row that disagrees is a defect, refused loudly rather than guessed at.
            Withdrawal found = existing.get();
            if (!found.amount().equals(amount)) {
                throw new IllegalStateException(
                        "a taken-over withdrawal claim found different facts under its key");
            }
            if (found.status().isResolvable()) {
                Instant renewed =
                        Instant.now(clock)
                                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                if (withdrawals.renewSendPermit(uow, found, renewed)) {
                    // The renewal won: re-send OUR stored reference (INV-PAY-04); the
                    // scheme deduplicates on it.
                    return new Dispatch(
                            Optional.of(found.withSendPermit(renewed)),
                            false,
                            renewed,
                            true,
                            Optional.empty());
                }
            }
            // A resolver got there first (or the row is terminal): nothing may be sent;
            // Tx2 converges on the row's truth.
            return new Dispatch(
                    Optional.of(found), false, found.lastDispatchedAt(), false, Optional.empty());
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
        if (!chosen.equals(railId)) {
            // One push adapter is wired; a policy naming another is a composition fault,
            // loud before anything is written (the PaymentConfirmation precedent).
            throw new IllegalStateException(
                    "routing chose '" + chosen.value() + "' but the wired push rail is '"
                            + railId.value() + "': the composition and the policy disagree");
        }

        // THE BOUND, judged under the wallet account's lock (INV-BAL-04): ten concurrent
        // withdrawals admit exactly the affordable set.
        Hold hold;
        try {
            hold = holds.place(uow, resolved.walletAccountId(), amount);
        } catch (HoldExceedsAvailableBalanceException unfunded) {
            // Aborts the transaction whole - claim, decision, everything: transient
            // state, and the same key may honestly retry after a top-up.
            throw new WithdrawalUnfundedException();
        }

        Withdrawal fresh =
                Withdrawal.dispatch(
                        ids,
                        clock,
                        resolved.partyId(),
                        resolved.customerId(),
                        resolved.walletAccountId(),
                        resolved.paymentMethodId(),
                        resolved.destination(),
                        amount,
                        chosen,
                        hold.id());
        withdrawals.insert(uow, fresh, clientKey);
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
        outcomes.announceInitiated(uow, fresh, correlation, now);
        // The person's own act (the dispatch); every outcome is the platform's.
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        person,
                        now,
                        PaymentsAuditAction.WITHDRAWAL_DISPATCHED,
                        TARGET_TYPE,
                        fresh.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "withdrawal=" + fresh.id()
                                        + ", wallet=" + fresh.walletAccountId()
                                        + ", method=" + fresh.paymentMethodId()
                                        + ", rail=" + chosen.value())));
        return new Dispatch(
                Optional.of(fresh), true, fresh.lastDispatchedAt(), true, Optional.empty());
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
