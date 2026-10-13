package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
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
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * T-svc's late-fee step (`P11-TSK-025`; {@code PHASE_11_PLAN.md} sections 7.1, 12.3 and 12.8 rule 4; A18, ADR-0098 point 5;
 * {@code INV-IDEM-02}, {@code INV-LND-10}, {@code INV-LND-08}). Called by {@link LoanServicing} under the step's loan row
 * lock (L3), once the accrual has caught up, <strong>after the condition step</strong> and only on an account the step did
 * not close - never on its own. No L0, L5, L6 or L7: a fee is not principal (exposure and capital do not move) and no
 * wallet is touched.
 *
 * <h2>When a fee is due</h2>
 *
 * <p>Per billed item - an instalment's billing or a line's statement, from {@link InstalmentBillingStore#dueItems} under L3,
 * oldest first: once the item's <em>own</em> age - the business date less its due date - has reached the pinned
 * {@code late_fee_day} (always beyond grace: grace delays only the fee, never DPD) while it still owes interest or
 * principal, and it has no late fee yet. A missed day is caught up while the item still owes; an item paid in the meantime
 * is never charged late (the borrower's side of an outage). Capped per account: the fee is the pinned late fee or what the
 * pinned cap still admits, whichever is less - nothing once the cap is reached; a zero late fee assesses nothing.
 *
 * <h2>What it writes</h2>
 *
 * <p>Posting rule 4, {@code DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME}, dated the business date of the transaction, under
 * {@code lending.fee:<item>:<kind>} - the assessment's natural identity, its reference the loan, so the ledger key is an
 * arbiter of its own and a second attempt from the same history a replay refused as a defect; then the
 * {@code fee_assessment} row naming it and {@code lending.LoanFeeAssessed}. The fee joins its item's {@code FEES}
 * ({@code lending.billing_component_billed}), so every payer allocates it first by the pinned order and an unpaid fee keeps
 * its item past due.
 *
 * <h2>Why once</h2>
 *
 * <p>L3 serialises every step on the account, each reading the assessments after the lock; {@code UNIQUE (due_item_id,
 * kind)} beneath it; the ledger key beneath that; and {@code lending V021}'s trigger re-judges the day, the fee, the cap
 * and the item for every writer under the same lock.
 */
@RequiredArgsConstructor
public final class LateFees {

    /** Rule 4's key prefix: {@code lending.fee:<item>:<kind>}. */
    public static final String POSTING_KEY_PREFIX = "lending.fee:";

    /** The event an assessment publishes - one per fee. */
    public static final String ASSESSED_EVENT = "lending.LoanFeeAssessed";

    @NonNull private final FeeAssessmentStore store;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final CreditLineStatementStore statements;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PostingService postings;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one fee step did - counts only, for the meter: fees {@code assessed}, of which {@code capped} at less than the fee. */
    public record Assessed(int assessed, int capped) {

        public Assessed {
            if (assessed < 0 || capped < 0 || capped > assessed) {
                throw new IllegalArgumentException("counts are not negative, and a capped fee is an assessed one");
            }
        }

        static final Assessed NOTHING = new Assessed(0, 0);
    }

    /**
     * The fee step for {@code loan}, under the caller's L3 and on its unit of work.
     *
     * @param pinned the governing agreement's pinned terms
     * @param today the business date of the database clock in the pinned zone, read after L3
     */
    public Assessed assess(Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(correlation, "correlation");
        if (loan.status() != LoanStatus.ACTIVE && loan.status() != LoanStatus.CLOSING) {
            return Assessed.NOTHING; // A terminal account takes no fee (INV-LND-08); the caller never asks.
        }
        Money fee = pinned.lateFee();
        if (!fee.isPositive()) {
            return Assessed.NOTHING;
        }
        if (loan.kind() == LoanKind.REVOLVING
                && statements.latestStatementDate(unitOfWork, loan.id()).filter(today::isBefore).isPresent()) {
            // A business date inside a cycle already stated - a database clock stepped back: no fee is dated into a stated
            // cycle (each is carried by exactly one statement); the next step assesses it.
            return Assessed.NOTHING;
        }
        List<FeeAssessment> assessments = store.ofLoan(unitOfWork, loan.id());
        Set<DueItemId> charged = new HashSet<>();
        Money total = Money.ofPersisted(0, fee.currency(), fee.scale());
        for (FeeAssessment assessment : assessments) {
            if (assessment.kind() == FeeKind.LATE) {
                charged.add(assessment.dueItem());
            }
            total = total.plus(assessment.amount());
        }
        Money cap = pinned.lateFeeCap();
        Optional<Accounts> accounts = Optional.empty();
        int assessed = 0;
        int capped = 0;
        for (DueState.DueItem item : billings.dueItems(unitOfWork, loan.id())) {
            if (ChronoUnit.DAYS.between(item.dueDate(), today) < pinned.lateFeeDay()) {
                break; // Oldest first: every later item is younger.
            }
            if (charged.contains(item.id()) || !item.interest().plus(item.principal()).isPositive()) {
                continue;
            }
            Money room = cap.minus(total);
            if (!room.isPositive()) {
                break; // The cap is reached: nothing more is assessed on this account, ever.
            }
            Money amount = fee.compareTo(room) <= 0 ? fee : room;
            if (accounts.isEmpty()) {
                accounts = Optional.of(accounts(unitOfWork, loan));
            }
            JournalEntryId entry = post(unitOfWork, loan, item.id(), amount, today, accounts.get());
            FeeAssessment assessment = new FeeAssessment(ids.next(), loan.id(), item.id(), FeeKind.LATE, today, amount,
                    agreement.id(), agreement.version(), entry);
            store.insert(unitOfWork, assessment, correlation);
            publish(unitOfWork, loan, assessment, item, correlation);
            total = total.plus(amount);
            charged.add(item.id());
            assessed++;
            if (amount.compareTo(fee) < 0) {
                capped++;
            }
        }
        return new Assessed(assessed, capped);
    }

    // ------------------------------------------------------------------ rule 4

    private record Accounts(LedgerAccountId feesDue, LedgerAccountId feeIncome) {}

    private Accounts accounts(Connection unitOfWork, Loan loan) {
        LedgerAccountId feesDue = ledgerAccounts
                .findOwned(unitOfWork, loan.id().value(), AccountPurpose.LOAN_FEES_DUE, loan.currency())
                .orElseThrow(() -> new IllegalStateException("the acceptance opened the loan's six accounts"))
                .id();
        LedgerAccountId feeIncome = ledgerAccounts
                .findOperational(unitOfWork, AccountPurpose.LOAN_FEE_INCOME, loan.currency())
                .orElseThrow(() -> new IllegalStateException(
                        "ledger V026 seeds the loan fee income account in every supported currency"))
                .id();
        return new Accounts(feesDue, feeIncome);
    }

    /**
     * Rule 4 - {@code DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME}, dated today, its key the assessment's natural identity and its
     * reference the loan: a second attempt at the item from the same history is the same request under the same key.
     */
    private JournalEntryId post(Connection unitOfWork, Loan loan, DueItemId item, Money amount, LocalDate today,
            Accounts accounts) {
        PostingResult result = postings.post(unitOfWork, new PostingCommand(
                POSTING_KEY_PREFIX + item.value() + ":" + FeeKind.LATE.name(), today, today,
                loan.id().value().toString(), List.of(
                        new JournalLine(accounts.feesDue(), Direction.DEBIT, amount),
                        new JournalLine(accounts.feeIncome(), Direction.CREDIT, amount))));
        if (result.replayed()) {
            // The key's entry exists while the item has no fee: the row and its entry were not committed together.
            throw new IllegalStateException("a fee entry exists for an item with no fee assessment (INV-LND-01)");
        }
        return result.entryId();
    }

    // ------------------------------------------------------------------ the event

    /**
     * {@code lending.LoanFeeAssessed} - the assessment, the item and its due date, the kind, the amount, the business date,
     * the entry and the agreement version; never a party.
     */
    private void publish(Connection unitOfWork, Loan loan, FeeAssessment assessment, DueState.DueItem item,
            CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("assessmentId", assessment.id().toString())
                .with("dueItemId", assessment.dueItem().value().toString())
                .with("dueDate", item.dueDate().toString())
                .with("kind", assessment.kind().name())
                .with("businessDate", assessment.businessDate().toString())
                .with("amountMinor", Long.toString(assessment.amount().minorUnits()))
                .with("scale", Integer.toString(assessment.amount().scale()))
                .with("currency", assessment.amount().currency().code())
                .with("journalEntryId", assessment.entry().value().toString())
                .with("agreementVersion", Integer.toString(assessment.agreementVersion()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ASSESSED_EVENT,
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
}
