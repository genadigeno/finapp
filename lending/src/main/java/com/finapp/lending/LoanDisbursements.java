package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The disbursement to the borrower's wallet - T-dis, path W (`P11-TSK-014`; {@code PHASE_11_PLAN.md} sections 5.3, 7.1,
 * 7.2, 12.4 and 12.8, {@code LENDING_LIFECYCLES.md} sections 4.5 and 4.6; ADR-0097, ADR-0096, ADR-0093;
 * {@code INV-LND-07}, {@code INV-LND-01}, {@code INV-LND-03}, {@code INV-LND-13}, {@code INV-LED-01},
 * {@code INV-IDEM-01}, {@code INV-EVT-01}, {@code INV-LIFE-04}). The loan is born financially - once, unambiguously, in
 * <strong>one local transaction</strong> on the caller's unit of work, with its schedule: the receivable exists exactly
 * when the wallet is credited. A system act with no door: the acceptance is the instruction (A7), and no person - no
 * operator - can disburse.
 *
 * <h2>The order, which is the lock order (L0, L3, L5)</h2>
 *
 * <ol>
 *   <li>the loan's party read unlocked (a frozen column), then <strong>(L0)</strong> its credit profile {@code FOR UPDATE}
 *       through {@link LoanCreditDecisions#lockExposure} - FIRST: the disbursement moves the party's exposure from
 *       committed to outstanding, and a credit decision for the party takes the same row first, so the two serialise;
 *   <li><strong>(L3)</strong> the loan {@code FOR UPDATE}, acted on only while {@code PENDING_DISBURSEMENT} - a loser of a
 *       race finds it moved and writes nothing; <strong>(L5)</strong> its disbursement {@code FOR UPDATE}, which a loan
 *       still awaiting it holds {@code PENDING} (they move together, held at commit by {@code lending V008});
 *   <li>the database clock, read after the locks: the value date is its business date in the agreement's pinned
 *       servicing zone (A10) - {@code posting_date = value_date}, the rule every later lending posting follows;
 *   <li>the standing re-read: lost - {@code CANCELLED (STANDING_LOST)} at once, nothing posted;
 *   <li>behind a savepoint, the wallet resolved through {@link LoanWallets} (opened if absent) and posting rule 1 through
 *       {@link PostingService} under {@code lending.disbursement:<loan>}: {@code DR LOAN_PRINCIPAL P / CR CUSTOMER_WALLET
 *       P - f, CR LOAN_FEE_INCOME f} (the origination fee {@code DEDUCTED}, A17). A wallet that cannot be had or posted
 *       (or a fee not below the principal) rolls back to the savepoint - nothing posted (rule 11) - and is retried on the
 *       sweep's permit until the deadline; the attempt that fails at or after it cancels {@code DISBURSEMENT_FAILED};
 *   <li>then only conditional edges and inserts: the disbursement {@code POSTED}, the loan {@code ACTIVE} and its
 *       history, the accrual start (the entry's value date) and schedule version 1 from the pinned engine,
 *       {@code lending.LoanDisbursed} in the outbox, the audit record.
 * </ol>
 *
 * <p>No L6: the principal was committed against capital at the acceptance, and the disbursement moves it from the
 * headroom's committed term to its outstanding term in the same commit - headroom does not move. No L7 on path W: a
 * credit needs no funds check. The ledger's own write set (lines, projections, its event and audit) joins the same
 * transaction, its projection rows last (L8). <strong>Why one transaction</strong>: credit's exposure read counts a loan's
 * agreement principal while {@code PENDING_DISBURSEMENT} and its principal lines once it is not, in one statement - an
 * edge without its posting, or a posting without its edge, would be counted not at all or twice.
 */
@RequiredArgsConstructor
public final class LoanDisbursements {

    public static final String DISBURSED_EVENT = "lending.LoanDisbursed";
    public static final String CANCELLED_EVENT = "lending.LoanCancelled";
    public static final String DISBURSED_REASON = "DISBURSED";

    /** The ledger key's prefix (rule 1): one entry per loan, whoever posts it and however often. */
    public static final String POSTING_KEY_PREFIX = "lending.disbursement:";

    @NonNull private final LoanStore loans;
    @NonNull private final LoanDisbursementStore disbursements;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanCreditDecisions credit;
    @NonNull private final LoanPartyStanding standing;
    @NonNull private final LoanWallets wallets;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PostingService postings;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one disbursement step did - for the meter; never a figure. */
    public record Outcome(Kind kind, Optional<PayoutPreference> path, Optional<CancellationReason> reason) {

        public Outcome {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        static Outcome nothing() {
            return new Outcome(Kind.NOTHING, Optional.empty(), Optional.empty());
        }

        /** The step's effect. */
        public enum Kind {
            /** The loan was no longer awaiting a wallet disbursement - another step moved it, or it is path X's. */
            NOTHING,
            /** Posting rule 1 committed with the loan {@code ACTIVE} and its schedule. */
            POSTED,
            /** The wallet could not be posted; nothing written - retried on the permit until the deadline. */
            RETRYING,
            /** The loan {@code CANCELLED} with its reason; nothing posted. */
            CANCELLED
        }
    }

    // ------------------------------------------------------------------ the claim

    /** The wallet disbursements due a step - see {@link LoanDisbursementStore#claimDue}. */
    public List<LoanId> claim(Connection unitOfWork, int limit, Duration permit) {
        return disbursements.claimDue(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(permit, "permit"));
    }

    // ------------------------------------------------------------------ T-dis

    /**
     * One loan's disbursement step, on the caller's unit of work - a transaction of its own. The history, the events, the
     * audit record and the ledger's entry carry the acceptance's correlation: one flow from the acceptance to the money.
     */
    @SuppressWarnings("try") // The correlation Scope is used for its close side effect (the idiom).
    public Outcome disburse(Connection unitOfWork, LoanId id, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Optional<UUID> owner = loans.partyOf(unitOfWork, id);
        if (owner.isEmpty()) {
            return Outcome.nothing();
        }
        UUID party = owner.get();
        // (L0) The party's exposure, first.
        if (!credit.lockExposure(unitOfWork, party)) {
            throw new IllegalStateException("a loan's party has a credit profile: its decision was made under it");
        }
        // (L3) The loan, acted on only while it awaits its disbursement.
        Optional<LoanStore.Owned> locked = loans.lock(unitOfWork, id);
        if (locked.isEmpty() || locked.get().loan().status() != LoanStatus.PENDING_DISBURSEMENT) {
            return Outcome.nothing();
        }
        Loan loan = locked.get().loan();
        LoanAgreement agreement = locked.get().agreement();
        // (L5) Its disbursement - PENDING while the loan awaits it (lending V008 holds the pair at commit).
        Disbursement disbursement = disbursements.lock(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("a loan awaiting disbursement has its disbursement row"));
        if (disbursement.status() != DisbursementStatus.PENDING) {
            throw new IllegalStateException(
                    "a loan PENDING_DISBURSEMENT has its disbursement PENDING - they move only together (INV-LND-07)");
        }
        if (disbursement.path() != PayoutPreference.WALLET) {
            // Path X posts beside its payout's hold - P11-TSK-015's; its sweep does not claim it until then.
            return Outcome.nothing();
        }
        PayoutPreference path = disbursement.path();
        // The database clock, read after the locks.
        Instant now = disbursements.databaseNow(unitOfWork);
        if (!standing.inGoodStanding(unitOfWork, party)) {
            return cancel(unitOfWork, loan, path, CancellationReason.STANDING_LOST, actor);
        }
        LoanTerms pinned = terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        LocalDate valueDate = LocalDate.ofInstant(now, ZoneId.of(pinned.servicingZone()));
        Money principal = agreement.amount();
        Schedule schedule = schedule(pinned, agreement, valueDate);

        Correlation flow = Correlation.startingWith(loan.correlation()).causing(CausationId.of(loan.correlation().value()));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow)) {
            Optional<JournalEntryId> posted = postBehindSavepoint(unitOfWork, loan, party, principal,
                    pinned.originationFee().on(principal), valueDate);
            if (posted.isEmpty()) {
                // The wallet could not be posted: nothing written. Retried on the permit - until the deadline, judged on
                // the database clock; the attempt that fails at or after it cancels the loan.
                return disbursement.deadlineAt().isAfter(now)
                        ? new Outcome(Outcome.Kind.RETRYING, Optional.of(path), Optional.empty())
                        : cancel(unitOfWork, loan, path, CancellationReason.DISBURSEMENT_FAILED, actor);
            }
            JournalEntryId entry = posted.get();
            if (!disbursements.post(unitOfWork, id, entry, valueDate)) {
                throw new IllegalStateException("the locked disbursement moved under its own row lock");
            }
            if (!loans.activate(unitOfWork, id)) {
                throw new IllegalStateException("the locked loan moved under its own row lock");
            }
            loans.appendEvent(unitOfWork, new LoanStore.HistoryRow(ids.next(), id,
                    Optional.of(LoanStatus.PENDING_DISBURSEMENT), LoanStatus.ACTIVE, DISBURSED_REASON, actor,
                    loan.correlation()));
            // Path W: interest runs from the entry's value date, and the schedule is fixed from it (ADR-0097 point 2).
            disbursements.insertAccrualStart(unitOfWork, ids.next(), new AccrualStart(id, valueDate, entry),
                    loan.correlation());
            RepaymentSchedule versionOne =
                    new RepaymentSchedule(ids.next(), id, 1, agreement.id(), agreement.version(), schedule);
            disbursements.insertSchedule(unitOfWork, versionOne, versionOne.instalments(ids), loan.correlation());
            publishDisbursed(unitOfWork, loan, entry, valueDate, principal, path);
            audited(unitOfWork, loan, entry, valueDate, path, actor);
            return new Outcome(Outcome.Kind.POSTED, Optional.of(path), Optional.empty());
        }
    }

    // ------------------------------------------------------------------ the money

    /**
     * Posting rule 1 behind a savepoint: the wallet resolved (opened if absent) and the entry posted, or - the wallet not
     * to be had, not postable, or nothing to credit it - everything since the savepoint rolled back and empty.
     */
    private Optional<JournalEntryId> postBehindSavepoint(
            Connection unitOfWork, Loan loan, UUID party, Money principal, Money fee, LocalDate valueDate) {
        CurrencyCode currency = loan.currency();
        Money credited = principal.minus(fee);
        if (fee.isNegative() || !credited.isPositive()) {
            // A fee not below the principal leaves the wallet nothing to be credited: it cannot be posted.
            return Optional.empty();
        }
        Savepoint beforePosting = savepoint(unitOfWork);
        try {
            Optional<LedgerAccountId> wallet = wallets.walletFor(unitOfWork, party, currency);
            if (wallet.isEmpty()) {
                rollback(unitOfWork, beforePosting);
                return Optional.empty();
            }
            LedgerAccountId receivable = ledgerAccounts
                    .findOwned(unitOfWork, loan.id().value(), AccountPurpose.LOAN_PRINCIPAL, currency)
                    .orElseThrow(() -> new IllegalStateException("the acceptance opened the loan's six accounts"))
                    .id();
            List<JournalLine> lines = new ArrayList<>();
            lines.add(new JournalLine(receivable, Direction.DEBIT, principal));
            lines.add(new JournalLine(wallet.get(), Direction.CREDIT, credited));
            if (fee.isPositive()) {
                lines.add(new JournalLine(ledgerAccounts
                        .findOperational(unitOfWork, AccountPurpose.LOAN_FEE_INCOME, currency)
                        .orElseThrow(() -> new IllegalStateException(
                                "ledger V026 seeds the loan fee income account in every supported currency"))
                        .id(), Direction.CREDIT, fee));
            }
            PostingResult result = postings.post(unitOfWork, new PostingCommand(
                    POSTING_KEY_PREFIX + loan.id().value(), valueDate, valueDate, loan.id().value().toString(), lines));
            if (result.replayed()) {
                // The key's entry exists while the loan still awaits it: the one-transaction rule was broken somewhere.
                throw new IllegalStateException("a disbursement entry exists for a loan still PENDING_DISBURSEMENT (INV-LND-07)");
            }
            release(unitOfWork, beforePosting);
            return Optional.of(result.entryId());
        } catch (LedgerAccountNotPostableException notPostable) {
            rollback(unitOfWork, beforePosting);
            return Optional.empty();
        }
    }

    /** Schedule version 1 by the agreement's pinned engine from the accrual start (ADR-0093). */
    private static Schedule schedule(LoanTerms pinned, LoanAgreement agreement, LocalDate accrualStart) {
        ScheduleOutcome projected = ScheduleEngineV1.project(pinned, agreement.amount(),
                agreement.termMonths().orElseThrow(() -> new IllegalStateException("an instalment agreement has a term")),
                agreement.repaymentDay(), accrualStart);
        if (projected instanceof ScheduleOutcome.Refused refused) {
            // The terms door judged these terms over every calendar of the leap cycle (P11-TSK-006): a defect, never a
            // business refusal.
            throw new IllegalStateException("the pinned schedule engine refused an accepted agreement: " + refused.reason());
        }
        return ((ScheduleOutcome.Projected) projected).schedule();
    }

    // ------------------------------------------------------------------ the cancellation

    /**
     * {@code PENDING_DISBURSEMENT -> CANCELLED} for {@code reason}, beside the disbursement {@code FAILED}: nothing
     * posted, the commitment released under L0 (credit's exposure and the capital headroom both stop counting it at this
     * commit), the decision staying consumed.
     */
    private Outcome cancel(
            Connection unitOfWork, Loan loan, PayoutPreference path, CancellationReason reason, Actor actor) {
        if (!disbursements.fail(unitOfWork, loan.id(), reason)) {
            throw new IllegalStateException("the locked disbursement moved under its own row lock");
        }
        if (!loans.cancel(unitOfWork, loan.id(), reason)) {
            throw new IllegalStateException("the locked loan moved under its own row lock");
        }
        loans.appendEvent(unitOfWork, new LoanStore.HistoryRow(ids.next(), loan.id(),
                Optional.of(LoanStatus.PENDING_DISBURSEMENT), LoanStatus.CANCELLED, reason.name(), actor,
                loan.correlation()));
        write(unitOfWork, CANCELLED_EVENT, loan, EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("product", loan.product().name())
                .with("status", LoanStatus.CANCELLED.name())
                .with("reason", reason.name()));
        return new Outcome(Outcome.Kind.CANCELLED, Optional.of(path), Optional.of(reason));
    }

    // ------------------------------------------------------------------ the event and the record

    /** {@code lending.LoanDisbursed} - the entry, the value date, the principal and its currency, the path; no wallet. */
    private void publishDisbursed(
            Connection unitOfWork, Loan loan, JournalEntryId entry, LocalDate valueDate, Money principal,
            PayoutPreference path) {
        write(unitOfWork, DISBURSED_EVENT, loan, EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("product", loan.product().name())
                .with("journalEntryId", entry.value().toString())
                .with("valueDate", valueDate.toString())
                .with("principalMinor", Long.toString(principal.minorUnits()))
                .with("scale", Integer.toString(principal.scale()))
                .with("currency", principal.currency().code())
                .with("path", path.name()));
    }

    /** {@code lending.LoanDisbursed} - the loan, the entry, the value date, the path; never an amount. */
    private void audited(
            Connection unitOfWork, Loan loan, JournalEntryId entry, LocalDate valueDate, PayoutPreference path,
            Actor actor) {
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids),
                actor,
                clock.instant(),
                LendingAuditAction.LOAN_DISBURSED,
                LoanAcceptances.LOAN_AGGREGATE_TYPE,
                loan.id().value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                loan.correlation(),
                Optional.of("loan " + loan.id().value() + " disbursed: entry " + entry.value() + ", value date "
                        + valueDate + ", path " + path)));
    }

    private void write(Connection unitOfWork, String type, Loan loan, EventPayload payload) {
        CorrelationId correlation = loan.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        loan.id(),
                        LoanAcceptances.LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ savepoints

    private static Savepoint savepoint(Connection unitOfWork) {
        try {
            return unitOfWork.setSavepoint();
        } catch (SQLException failure) {
            throw new LendingStorageException("lending storage failed setting a savepoint: SQLSTATE " + failure.getSQLState());
        }
    }

    private static void rollback(Connection unitOfWork, Savepoint savepoint) {
        try {
            unitOfWork.rollback(savepoint);
        } catch (SQLException failure) {
            throw new LendingStorageException(
                    "lending storage failed rolling back to a savepoint: SQLSTATE " + failure.getSQLState());
        }
    }

    private static void release(Connection unitOfWork, Savepoint savepoint) {
        try {
            unitOfWork.releaseSavepoint(savepoint);
        } catch (SQLException failure) {
            throw new LendingStorageException(
                    "lending storage failed releasing a savepoint: SQLSTATE " + failure.getSQLState());
        }
    }
}
