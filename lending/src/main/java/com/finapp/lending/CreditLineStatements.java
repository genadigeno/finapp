package com.finapp.lending;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.CreditLineStatementStore.StatedItem;
import com.finapp.lending.LoanReceivables.Accounts;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * T-svc's statement step for a credit line, and the customer's read of its statements (`P11-TSK-022`; {@code PHASE_11_PLAN.md}
 * sections 7.1, 12.6 and 12.8 rules 3 and 6; ADR-0100 point 6, ADR-0094 points 6 and 8, ADR-0095 point 5; A23, A24;
 * {@code INV-LND-01}, {@code INV-LND-04}, {@code INV-LND-09}, {@code INV-LND-10}, {@code INV-IDEM-02}). The step is called by
 * {@link LoanServicing} in the billing slot - after the accrual, in the same transaction, under the same line row lock (L3) -
 * never on its own: a line's statement is what an instalment loan's billing is ({@link InstalmentBillings}).
 *
 * <h2>When a cycle is stated</h2>
 *
 * <p>A cycle of the line ({@link StatementEngineV1#cycle}: from the opening business date to the first statement date
 * after it, then a month at a time, each ending on the agreement's statement day) is stated once its statement date has come
 * on the database clock in the pinned zone (the caller's {@code today}) <em>and</em> every date of it has been accrued or
 * found with no principal to accrue on (the caller's {@code accruedThrough}: a catch-up stopped at its bound states only
 * what its accruals reach) - oldest first, contiguous, several in one step after an outage, each followed by rule 6.
 *
 * <h2>What a statement bills</h2>
 *
 * <ul>
 *   <li><strong>interest</strong>: the cycle's accrual rows, oldest first, summed by {@link StatementEngineV1#state} (which
 *       refuses a list that is not one chain of the cycle's own) - interest actually accrued, never the projection;
 *   <li><strong>the minimum payment's principal part</strong> (A24) on the drawn principal <em>at the cycle's close</em>:
 *       {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE} over the journal lines valued before the statement date, read in ONE
 *       statement - so a draw dated on or after the statement date belongs to the next cycle whichever order it and the
 *       step meet L3 in; capped at the principal not yet due both at the cycle's close and now (a statement never bills
 *       principal drawn after its cycle, and never more than {@code LOAN_PRINCIPAL} holds - after an outage an older
 *       statement of the same step has already billed its part);
 *   <li><strong>fees carried</strong> (`P11-TSK-025`): the line's late fees dated in the cycle, less those waived before the
 *       statement - carried into the minimum payment (A24) while each stays due on the item it was assessed on.
 * </ul>
 *
 * <p>Posting rule 3 - {@code DR LOAN_INTEREST_DUE / CR LOAN_INTEREST_ACCRUED}, {@code DR LOAN_PRINCIPAL_DUE / CR
 * LOAN_PRINCIPAL} - re-classifies the same claim as payable, dated the business date of the transaction (ADR-0094 point 8),
 * under {@code lending.statement:<line>:<statement date>} - the statement's natural key, its reference the line (the
 * post-maturity billing's precedent), so a second attempt at a cycle is the same request under the same key: a replay the
 * step refuses as the defect it is. A statement of nothing writes its row and no entry. Then rule 6
 * ({@link CreditBalanceApplications}), then {@code lending.CreditLineStatementIssued}.
 *
 * <h2>Why once (the ten-sweeper question)</h2>
 *
 * <p>L3 serialises every step and every draw on the line, each reading the latest statement after the lock; beneath it
 * {@code credit_line_statement UNIQUE (loan_id, cycle_end)} and {@code UNIQUE (loan_id, due_date)},
 * {@code credit_balance_application UNIQUE (billing_id)}; and the ledger key. No L0 (exposure does not move: billed
 * principal is still drawn, A28), no L6 (capital does not move), no L7; every ledger account written is the line's own.
 *
 * <h2>The read</h2>
 *
 * <p>{@link #read}: the owner's statements, oldest first, each with what has been paid of it and the line's balance - every
 * receivable less the credit balance - at the cycle's opening and close, <strong>derived from the journal lines</strong>
 * valued before each date, never stored (ADR-0100 point 6). Owner-scoped; an instalment loan has no statements.
 */
@RequiredArgsConstructor
public final class CreditLineStatements {

    /** Rule 3's key prefix for a statement: {@code lending.statement:<line>:<statement date>}. */
    public static final String POSTING_KEY_PREFIX = "lending.statement:";

    /** The event a statement publishes - one per statement. */
    public static final String ISSUED_EVENT = "lending.CreditLineStatementIssued";

    @NonNull private final CreditLineStatementStore store;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LoanStore loans;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one statement step did - counts only, for the meter. */
    public record Stated(int stated, int applied) {

        public Stated {
            if (stated < 0 || applied < 0) {
                throw new IllegalArgumentException("counts are not negative");
            }
        }

        static final Stated NOTHING = new Stated(0, 0);
    }

    // ------------------------------------------------------------------ the step

    /**
     * States every cycle of {@code line} ended by {@code today} and accrued through {@code accruedThrough}, oldest first, each
     * followed by the credit-balance application - on the caller's unit of work, under the caller's L3 and correlation.
     *
     * @param pinned the governing agreement's pinned terms
     * @param today the business date of the database clock in the pinned zone, read after L3
     * @param accruedThrough the last date the caller's accrual step has accrued or found nothing to accrue on
     */
    public Stated state(Connection unitOfWork, Loan line, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            LocalDate accruedThrough) {
        return state(unitOfWork, line, agreement, pinned, today, accruedThrough, false);
    }

    /**
     * A payoff's statements (`P11-TSK-026`, posting rule 7's rule 3): every cycle {@link #state} would state, then the
     * line's <em>current</em> cycle, if it has begun before {@code today}, stated early - on its accruals through yesterday,
     * by the same engine, its statement date and due date its own - so the line's accrued interest is all due. On the
     * caller's unit of work, under the caller's L3, after the caller has accrued every elapsed date and beside the line's
     * {@code payoff_execution} (the statement trigger admits an early statement only there). The payoff's repayment then
     * pays the statements and pays down the principal not yet due.
     */
    Stated stateForPayoff(Connection unitOfWork, Loan line, LoanAgreement agreement, LoanTerms pinned, LocalDate today) {
        return state(unitOfWork, line, agreement, pinned, today, today.minusDays(1), true);
    }

    private Stated state(Connection unitOfWork, Loan line, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            LocalDate accruedThrough, boolean currentToo) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(accruedThrough, "accruedThrough");
        if (line.kind() != LoanKind.REVOLVING) {
            return Stated.NOTHING; // An instalment loan's billing is InstalmentBillings'.
        }
        StatementTerms statementTerms = StatementTerms.of(pinned, agreement.repaymentDay());
        if (agreement.statementEngine().isEmpty() || statementTerms.statementEngine() != agreement.statementEngine().get()) {
            throw new IllegalStateException("the pinned terms and the agreement name one statement engine (INV-LND-09)");
        }
        AllocationRules rules = AllocationRules.of(pinned);
        if (rules.engine() != agreement.allocationEngine()) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        LocalDate openedOn = LocalDate.ofInstant(line.openedAt(), ZoneId.of(pinned.servicingZone()));
        // A statement date is stated once it has come and every date before it is accrued.
        LocalDate limit = today.isBefore(accruedThrough.plusDays(1)) ? today : accruedThrough.plusDays(1);
        LocalDate start = store.latestStatementDate(unitOfWork, line.id()).orElse(openedOn);
        AccrualPeriod cycle = StatementEngineV1.cycle(statementTerms, openedOn, start);
        if (!stateable(cycle, limit, today, currentToo)) {
            return Stated.NOTHING;
        }
        Money zero = Money.ofPersisted(0, line.currency(), agreement.amount().scale());
        Accounts accounts = new LoanReceivables(billings, ledgerAccounts, balances)
                .accounts(unitOfWork, line.id(), line.currency());
        // The principal not yet due now, from the ledger under L3 - what no statement of this step may exceed in sum.
        Money notDueNow = netDebit(unitOfWork, accounts.principal(), zero);
        CreditBalanceApplications applications =
                new CreditBalanceApplications(billings, ledgerAccounts, balances, postings, ids);
        int stated = 0;
        int applied = 0;
        while (stateable(cycle, limit, today, currentToo)) {
            if (!cycle.start().equals(start)) {
                throw new IllegalStateException("a line's cycles are contiguous from its opening (A23)");
            }
            Statement figures = figures(unitOfWork, line, statementTerms, cycle, accounts, notDueNow, zero);
            UUID statementId = ids.next();
            Optional<JournalEntryId> entry = figures.interestBilled().plus(figures.principalBilled()).isPositive()
                    ? Optional.of(post(unitOfWork, line, figures, today, accounts))
                    : Optional.empty();
            CreditLineStatement statement = new CreditLineStatement(statementId, line.id(), figures, agreement.id(),
                    agreement.version(), agreement.accrualEngine(), entry);
            store.insert(unitOfWork, statement, line.correlation());
            notDueNow = notDueNow.minus(figures.principalBilled());
            stated++;
            Optional<CreditBalanceApplication> application =
                    applications.apply(unitOfWork, line, agreement, rules, statementId, today, accounts, zero);
            if (application.isPresent()) {
                applied++;
            }
            publish(unitOfWork, line, statement, application);
            start = cycle.endExclusive();
            cycle = StatementEngineV1.cycle(statementTerms, openedOn, start);
        }
        return new Stated(stated, applied);
    }

    /**
     * A cycle is stated once its statement date has come and every date before it is accrued ({@code limit}) - or, for a
     * payoff, when it is the current cycle and has begun before {@code today} (its elapsed dates all accrued by the payoff).
     */
    private static boolean stateable(AccrualPeriod cycle, LocalDate limit, LocalDate today, boolean currentToo) {
        return !cycle.endExclusive().isAfter(limit) || (currentToo && cycle.start().isBefore(today));
    }

    /**
     * The engine over the cycle's accruals and the drawn principal at its close: {@code D} the two principal accounts over
     * the lines valued before the statement date, the cap {@code N} the lesser of {@code LOAN_PRINCIPAL} then and now.
     */
    private Statement figures(Connection unitOfWork, Loan line, StatementTerms statementTerms, AccrualPeriod cycle,
            Accounts accounts, Money notDueNow, Money zero) {
        List<InterestAccrual> accruals = store.accrualsIn(unitOfWork, line.id(), cycle);
        Money notDueAtClose = zero;
        Money dueAtClose = zero;
        for (PurposeBalances.DatedLine dated : balances.datedLinesOn(unitOfWork,
                Set.of(accounts.principal(), accounts.principalDue()), cycle.endExclusive().minusDays(1))) {
            Money signed = dated.direction() == Direction.DEBIT ? dated.amount() : dated.amount().negated();
            if (dated.account().equals(accounts.principal())) {
                notDueAtClose = notDueAtClose.plus(signed);
            } else {
                dueAtClose = dueAtClose.plus(signed);
            }
        }
        Money drawnAtClose = notDueAtClose.plus(dueAtClose);
        Money cap = notDueAtClose.compareTo(notDueNow) <= 0 ? notDueAtClose : notDueNow;
        // The fees assessed in the cycle, less those waived (P11-TSK-025): carried into the minimum payment (A24) - each
        // stays due on the item it was assessed on.
        Money feesCarried = store.feesCarriedIn(unitOfWork, line.id(), cycle, zero);
        // The engine refuses a negative figure - a history the ledger cannot have, rolled back, never stated.
        return StatementEngineV1.state(statementTerms, cycle, accruals, feesCarried, cap, drawnAtClose.minus(cap));
    }

    // ------------------------------------------------------------------ rule 3

    private JournalEntryId post(Connection unitOfWork, Loan line, Statement figures, LocalDate today, Accounts accounts) {
        List<JournalLine> lines = new ArrayList<>();
        Money interest = figures.interestBilled();
        Money principal = figures.principalBilled();
        if (interest.isPositive()) {
            lines.add(new JournalLine(accounts.interestDue(), Direction.DEBIT, interest));
            lines.add(new JournalLine(accounts.interestAccrued(), Direction.CREDIT, interest));
        }
        if (principal.isPositive()) {
            lines.add(new JournalLine(accounts.principalDue(), Direction.DEBIT, principal));
            lines.add(new JournalLine(accounts.principal(), Direction.CREDIT, principal));
        }
        // The key and the reference are the statement's natural identity - the line and its statement date - never the
        // attempt's fresh id: a second attempt at the cycle from the same history is the same request under the same key.
        PostingResult result = postings.post(unitOfWork, new PostingCommand(
                POSTING_KEY_PREFIX + line.id().value() + ":" + figures.statementDate(), today, today,
                line.id().value().toString(), lines));
        if (result.replayed()) {
            // The key's entry exists while the cycle has no statement: the row and its entry were not committed together.
            throw new IllegalStateException("a statement entry exists for a cycle with no statement (INV-LND-01)");
        }
        return result.entryId();
    }

    // ------------------------------------------------------------------ the event

    /**
     * {@code lending.CreditLineStatementIssued} - the statement, its cycle end and due date, the interest, principal and fees
     * it made due or carries, the minimum payment and what the credit balance paid at it.
     */
    private void publish(Connection unitOfWork, Loan line, CreditLineStatement statement,
            Optional<CreditBalanceApplication> application) {
        Statement figures = statement.statement();
        EventPayload payload = EventPayload.of()
                .with("loanId", line.id().value().toString())
                .with("statementId", statement.id().toString())
                .with("agreementVersion", Integer.toString(statement.agreementVersion()))
                .with("statementEngineVersion", Integer.toString(figures.engine().number()))
                .with("cycleStart", figures.cycle().start().toString())
                .with("cycleEnd", figures.statementDate().toString())
                .with("dueDate", figures.dueDate().toString())
                .with("interestDueMinor", Long.toString(figures.interestBilled().minorUnits()))
                .with("principalDueMinor", Long.toString(figures.principalBilled().minorUnits()))
                .with("feesDueMinor", Long.toString(figures.feesCarried().minorUnits()))
                .with("minimumPaymentMinor", Long.toString(figures.minimumPayment().minorUnits()))
                .with("creditAppliedMinor",
                        Long.toString(application.map(applied -> applied.amount().minorUnits()).orElse(0L)))
                .with("scale", Integer.toString(figures.interestBilled().scale()))
                .with("currency", figures.interestBilled().currency().code());
        if (statement.entry().isPresent()) {
            payload = payload.with("journalEntryId", statement.entry().get().value().toString());
        }
        CorrelationId correlation = line.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ISSUED_EVENT,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        line.id(),
                        LoanAcceptances.LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ the read

    /** One statement as its owner reads it: the row, what has been paid of it, and the line's balances around its cycle. */
    public record StatementRead(StatedItem item, Money openingBalance, Money closingBalance) {

        public StatementRead {
            Objects.requireNonNull(item, "item must not be null");
            Objects.requireNonNull(openingBalance, "openingBalance must not be null");
            Objects.requireNonNull(closingBalance, "closingBalance must not be null");
        }

        @Override
        public String toString() {
            return "StatementRead[" + item + "]";
        }
    }

    /**
     * {@code party}'s line {@code id}'s statements, oldest first - empty when the party has no such credit line (another
     * party's, an instalment loan, an unknown id), so the door answers the uniform {@code 404}. Each balance is every
     * receivable of the line less its credit balance over the journal lines valued before the cycle's start (opening) and
     * its statement date (closing) - derived, never stored; a statement's own re-classification moves nothing between them.
     */
    public Optional<List<StatementRead>> read(Connection unitOfWork, LoanId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Optional<LoanStore.Owned> owned = loans.ownedBy(unitOfWork, id, party);
        if (owned.isEmpty() || owned.get().loan().kind() != LoanKind.REVOLVING) {
            return Optional.empty();
        }
        List<StatedItem> items = store.statementsOwnedBy(unitOfWork, id, party);
        if (items.isEmpty()) {
            return Optional.of(List.of());
        }
        Loan line = owned.get().loan();
        Money zero = Money.ofPersisted(0, line.currency(), owned.get().agreement().amount().scale());
        Accounts accounts = new LoanReceivables(billings, ledgerAccounts, balances)
                .accounts(unitOfWork, id, line.currency());
        LocalDate last = items.get(items.size() - 1).statement().statementDate();
        // ONE read of every line valued before the latest statement date: the balances of every statement fold from it.
        List<PurposeBalances.DatedLine> lines = balances.datedLinesOn(unitOfWork, Set.of(accounts.principal(),
                accounts.principalDue(), accounts.interestAccrued(), accounts.interestDue(), accounts.feesDue(),
                accounts.creditBalance()), last.minusDays(1));
        List<StatementRead> reads = new ArrayList<>();
        for (StatedItem item : items) {
            Statement figures = item.statement().statement();
            reads.add(new StatementRead(item, owedBefore(lines, figures.cycle().start(), zero),
                    owedBefore(lines, figures.statementDate(), zero)));
        }
        return Optional.of(List.copyOf(reads));
    }

    /** DR - CR over every line valued before {@code date} - the five receivables less the credit balance (a liability). */
    private static Money owedBefore(List<PurposeBalances.DatedLine> lines, LocalDate date, Money zero) {
        Money owed = zero;
        for (PurposeBalances.DatedLine line : lines) {
            if (line.valueDate().isBefore(date)) {
                owed = line.direction() == Direction.DEBIT ? owed.plus(line.amount()) : owed.minus(line.amount());
            }
        }
        return owed;
    }

    // ------------------------------------------------------------------ the ledger

    private Money netDebit(Connection unitOfWork, LedgerAccountId account, Money zero) {
        return new LoanReceivables(billings, ledgerAccounts, balances).netDebit(unitOfWork, account, zero);
    }
}
