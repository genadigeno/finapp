package com.finapp.ledger;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Places and releases holds against available balance (`P3-TSK-015`, {@code INV-BAL-04}) —
 * the in-process command path, {@link PostingService}'s position: Phase 4's flows call it
 * inside their own transaction under the flow's actor, and there is deliberately no HTTP
 * surface (plan §9 declares none) and no idempotency key of this service's own — a hold
 * joins its commanding flow's transaction and replays with that flow's key
 * ({@code INV-IDEM-01} at the boundary that has one).
 *
 * <h2>The protocol is lock-then-look, and the lock mode is the analysis (ADR-0039)</h2>
 *
 * <p>Both operations take {@code SELECT … FOR UPDATE} on the <strong>account row</strong>,
 * then act in fresh statements whose snapshots postdate the lock grant (`P2-TSK-015`):
 *
 * <ul>
 *   <li><strong>Two placements serialize on the row</strong>: the loser's fresh derivation
 *       includes the winner's committed hold, so the sum of accepted holds can never exceed
 *       what was available ({@code INV-CON-01}) — ten instances, one arbiter, PostgreSQL.
 *   <li><strong>A posting in flight blocks a placement</strong>: every in-flight posting
 *       holds {@code FOR KEY SHARE} on its accounts (the {@code journal_line} FK and `V007`'s
 *       trigger read), and {@code FOR UPDATE} is the mode that conflicts with it — so the
 *       placement's derivation sees the committed money, never a stale snapshot
 *       (`P3-TSK-014`'s lock-mode analysis, reused verbatim).
 * </ul>
 *
 * <h2>The decision's inputs are authoritative rows, never the projection</h2>
 *
 * <p>{@code INV-BAL-05}: settled derives from postings ({@link BalanceDerivation} — the
 * definition), standing holds from {@code ledger.hold}'s {@code ACTIVE} rows folded through
 * {@link Money#plus} — never a SQL {@code SUM} (`P3-TSK-008`'s argument). The projection's
 * {@code holds_minor} is maintained in the same transaction as a <em>consequence</em> of the
 * decision and is read by nothing here — a corrupted projection row changes no decision,
 * which is this task's behavioural proof of the property.
 */
public final class HoldService {

    /** One fact, named once, in two registries — the audit actions carry the same codes. */
    static final String PLACED_EVENT_TYPE = "ledger.HoldPlaced";

    static final String RELEASED_EVENT_TYPE = "ledger.HoldReleased";
    static final String PRODUCER = "ledger";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "hold";

    /** The release's result: the hold as it now stands, and whether this call ended it. */
    public record Release(Hold hold, boolean released) {}

    private final LedgerAccountStore<Connection> accounts;
    private final AvailableBalance<Connection> available;
    private final HoldStore<Connection> holds;
    private final BalanceProjection<Connection> projection;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public HoldService(
            LedgerAccountStore<Connection> accounts,
            BalanceDerivation<Connection> derivation,
            HoldStore<Connection> holds,
            BalanceProjection<Connection> projection,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        // The availability computation is AvailableBalance's (extracted by P4-TSK-005 when the
        // transfer became its second caller); the constructor keeps taking the derivation and
        // the hold store so wiring stays honest.
        this.available =
                new AvailableBalance<>(
                        Objects.requireNonNull(derivation, "derivation must not be null"), holds);
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.projection = Objects.requireNonNull(projection, "projection must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Places a hold of {@code amount} against {@code accountId}, or refuses without writing
     * anything — a refusal thrown here rolls the caller's transaction back, so a refused
     * placement writes nothing structurally.
     *
     * @throws HoldExceedsAvailableBalanceException when the hold would make available balance
     *     negative ({@code INV-BAL-04}) — no account may permit it
     * @throws LedgerAccountNotPostableException when the account is not {@code ACTIVE}: an
     *     account that accepts no postings accepts no new reservations either, for the same
     *     reason (`P3-TSK-014`)
     * @throws IllegalArgumentException for an unknown account or a currency foreign to it —
     *     caller defects on an internal API, loud and amount-free
     */
    public Hold place(Connection unitOfWork, LedgerAccountId accountId, Money amount) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        // An unestablished actor is an error, never a default (ADR-0021).
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation("placing");

        // The serialization point: FOR UPDATE on the account row - the mode that conflicts
        // with every in-flight posting's FOR KEY SHARE and with every sibling placer.
        LedgerAccount account =
                accounts.lockForUpdate(unitOfWork, accountId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "no ledger account " + accountId
                                                        + " to hold against"));
        if (!amount.currency().equals(account.currency())) {
            throw new IllegalArgumentException(
                    "a hold on account " + accountId + " must be in the account's own"
                            + " currency (INV-MON-02/04)");
        }
        if (account.status() != LedgerAccountStatus.ACTIVE) {
            throw new LedgerAccountNotPostableException(
                    "account " + accountId + " is " + account.status()
                            + " and accepts no new reservations (P3-TSK-015)");
        }

        // Look, under the lock: both inputs from authoritative rows in fresh statements
        // whose snapshots postdate the lock grant (INV-BAL-05 - never holds_minor). The
        // computation lives in AvailableBalance since its second caller arrived (P4-TSK-005),
        // so "what can this account spend?" has one answer however many commands ask.
        if (available.underLock(unitOfWork, accountId).minus(amount).isNegative()) {
            throw new HoldExceedsAvailableBalanceException(accountId, account.currency());
        }

        Hold hold = Hold.place(HoldId.next(ids), accountId, amount, clock);
        holds.insert(unitOfWork, hold);
        // The projection follows the decision in the same transaction (ADR-0041 rule 1);
        // it is a consequence here, never an input.
        projection.adjustHolds(unitOfWork, accountId, amount, hold.placedAt());

        record(unitOfWork, actor, correlation, hold, LedgerAuditAction.HOLD_PLACED,
                PLACED_EVENT_TYPE, hold.placedAt());
        return hold;
    }

    /**
     * Ends the hold {@code holdId}, or converges on an already-released one — the row count
     * of the conditional move is the outcome, so of N concurrent releasers exactly one
     * decrements, records and announces; the rest write <strong>nothing</strong>.
     *
     * <p>Takes the same account-row lock as placement (the plan's own protocol), keeping the
     * serialization point single — release never derives, because restoring availability
     * cannot violate {@code INV-BAL-04}.
     *
     * @return empty when no such hold exists — an internal API's honest answer, shaped by a
     *     surface if one ever arrives
     */
    public Optional<Release> release(Connection unitOfWork, HoldId holdId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(holdId, "holdId must not be null");

        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation("releasing");

        Optional<Hold> found = holds.findById(unitOfWork, holdId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LedgerAccountId accountId = found.get().account();
        accounts.lockForUpdate(unitOfWork, accountId)
                .orElseThrow(
                        () ->
                                new LedgerStorageException(
                                        "hold " + holdId + " references account " + accountId
                                                + " and the row is gone - an invariant is"
                                                + " already broken"));

        Instant now = Instant.now(clock);
        if (!holds.moveToReleased(unitOfWork, holdId, now)) {
            // Converged: already released, and a retry is not a second act - re-read under
            // the lock, nothing written (the AccountClosing idiom).
            Hold released = holds.findById(unitOfWork, holdId).orElseThrow();
            return Optional.of(new Release(released, false));
        }

        Hold released = found.get().release(clock);
        projection.adjustHolds(unitOfWork, accountId, released.amount().negated(), now);

        record(unitOfWork, actor, correlation, released, LedgerAuditAction.HOLD_RELEASED,
                RELEASED_EVENT_TYPE, now);
        return Optional.of(new Release(released, true));
    }

    private void record(
            Connection unitOfWork,
            Actor actor,
            Correlation correlation,
            Hold hold,
            LedgerAuditAction action,
            String eventType,
            Instant at) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        TARGET_TYPE,
                        hold.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never an amount (INV-AUD-02).
                        Optional.of(
                                "hold=" + hold.id() + ", account=" + hold.account()
                                        + ", status=" + hold.status())));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        hold.id(),
                        TARGET_TYPE,
                        at,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("ledgerAccountId", hold.account().value().toString())
                        .with("status", hold.status().name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** The flow's correlation with the cause resolved — the {@code PostingService} idiom. */
    private static Correlation resolvedCorrelation(String verb) {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                verb
                                                        + " a hold must run inside a"
                                                        + " correlation scope: the audit"
                                                        + " record and the event both carry"
                                                        + " the identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
