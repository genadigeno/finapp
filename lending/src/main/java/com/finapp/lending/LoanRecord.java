package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything one loan account's history holds, read in ONE snapshot (`P11-TSK-029`; PHASE_11_PLAN.md sections 9, 12.8 and
 * 13.1; ADR-0092 point 8, ADR-0096 point 3; {@code INV-LND-01}) - the explanation's rows, the subledger proof's operands and
 * the replay's inputs, so the three read one state and nothing else.
 *
 * <p><strong>Rows as written.</strong> Every amount is the row's own minor units at its own scale; every engine version is
 * the row's own NUMBER, never resolved to an {@link EngineVersion} here - a number this build does not hold must reach the
 * replay as a number, to be reported {@code DIVERGED}, never refused at the read ({@code INV-LND-09}). Every fact carries
 * the database instant it was written ({@code created_at}, a decision's {@code decided_at} - lending {@code V025}): each was
 * stamped under the loan's row lock (L3), so the instants are the lock's order and the replay walks them.
 *
 * <p><strong>The ledger as it stands.</strong> The loan's own ledger accounts (every account the ledger holds for the loan as
 * owner) and every journal line on them with its entry and value date - derived balances, never the projection
 * ({@code INV-BAL-05}).
 *
 * <p><strong>Amounts inside.</strong> {@link #toString()} names the loan and the row counts only ({@code INV-AUD-02}): a
 * record is served to an investigator, never logged.
 *
 * @param termsVersions every terms version an agreement pins, by id - empty when the version cannot be read in this build
 *     (an engine it pins is absent): the replay reports it, never the read
 */
public record LoanRecord(
        Account loan,
        List<Agreement> agreements,
        Map<UUID, Optional<LoanTermsStore.LoanTermsVersion>> termsVersions,
        Optional<LoanOffer> offer,
        Optional<Disbursement> disbursement,
        Optional<AccrualStartRow> accrualStart,
        Optional<Payout> payout,
        List<ScheduleVersion> schedules,
        List<Draw> draws,
        List<Accrual> accruals,
        List<Billing> billings,
        List<StatementRow> statements,
        List<Application> applications,
        List<Payment> repayments,
        List<Reversal> reversals,
        List<Fee> fees,
        List<WaiverRow> waivers,
        List<Amendment> amendments,
        List<Rescheduling> reschedulings,
        Optional<Closure> closure,
        List<PayoffQuoteRow> payoffQuotes,
        List<PayoffExecutionRow> payoffExecutions,
        Optional<LineClosureRow> lineClosure,
        List<Condition> conditions,
        List<Attempt> collectionAttempts,
        List<LedgerAccountRow> ledgerAccounts,
        List<PurposeBalances.DatedLine> journalLines) {

    public LoanRecord {
        Objects.requireNonNull(loan, "loan");
        agreements = List.copyOf(agreements);
        termsVersions = Map.copyOf(termsVersions);
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(disbursement, "disbursement");
        Objects.requireNonNull(accrualStart, "accrualStart");
        Objects.requireNonNull(payout, "payout");
        schedules = List.copyOf(schedules);
        draws = List.copyOf(draws);
        accruals = List.copyOf(accruals);
        billings = List.copyOf(billings);
        statements = List.copyOf(statements);
        applications = List.copyOf(applications);
        repayments = List.copyOf(repayments);
        reversals = List.copyOf(reversals);
        fees = List.copyOf(fees);
        waivers = List.copyOf(waivers);
        amendments = List.copyOf(amendments);
        reschedulings = List.copyOf(reschedulings);
        Objects.requireNonNull(closure, "closure");
        payoffQuotes = List.copyOf(payoffQuotes);
        payoffExecutions = List.copyOf(payoffExecutions);
        Objects.requireNonNull(lineClosure, "lineClosure");
        conditions = List.copyOf(conditions);
        collectionAttempts = List.copyOf(collectionAttempts);
        ledgerAccounts = List.copyOf(ledgerAccounts);
        journalLines = List.copyOf(journalLines);
    }

    /** The loan's id. */
    public LoanId id() {
        return loan.id();
    }

    /** Zero in the loan's currency at the scale of its first agreement - every figure of the loan is at it. */
    public Money zero() {
        int scale = agreements.isEmpty() ? loan.currency().minorUnits() : agreements.get(0).scale();
        return Money.ofPersisted(0, loan.currency(), scale);
    }

    /** {@code minor} in the loan's currency at its scale. */
    Money money(long minor) {
        return Money.ofPersisted(minor, loan.currency(), zero().scale());
    }

    /** The loan's account of {@code purpose} in its currency, if the ledger holds one. */
    public Optional<LedgerAccountRow> account(AccountPurpose purpose) {
        return ledgerAccounts.stream()
                .filter(account -> account.purpose() == purpose && account.currency().equals(loan.currency()))
                .findFirst();
    }

    /** Names the loan and the row counts - never an amount, a party or a key ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanRecord[" + loan.id().value() + ", agreements=" + agreements.size() + ", schedules=" + schedules.size()
                + ", accruals=" + accruals.size() + ", billings=" + billings.size() + ", statements=" + statements.size()
                + ", repayments=" + repayments.size() + ", lines=" + journalLines.size() + "]";
    }

    // ------------------------------------------------------------------ the rows

    /** The loan row. */
    public record Account(LoanId id, UUID party, LoanProduct product, LoanKind kind, LoanStatus status,
            CurrencyCode currency, LoanOfferId offer, UUID decision, Instant openedAt, Optional<Instant> closedAt,
            Optional<String> closureReason, Optional<String> cancellationReason) {

        @Override
        public String toString() {
            return "Account[" + id.value() + ", " + product + ", " + status + "]";
        }
    }

    /** An agreement version, as written: its origin, its terms, its engines by number and its canonical bytes. */
    public record Agreement(UUID id, int version, Optional<UUID> offer, Optional<UUID> amendment, Optional<UUID> prepayment,
            UUID decision, UUID termsVersionId, LoanProduct product, int scale, long amountMinor, Optional<Integer> termMonths,
            String nominalAnnualRate, int repaymentDay, Optional<Integer> scheduleEngine, int accrualEngine,
            int allocationEngine, Optional<Integer> statementEngine, String templateId, int templateVersion,
            int canonicalFormat, String canonicalTerms, byte[] termsSha256, Instant effectiveFrom, Instant createdAt) {

        public Agreement {
            termsSha256 = termsSha256.clone();
        }

        @Override
        public byte[] termsSha256() {
            return termsSha256.clone();
        }

        /** The stored hash, lower-case hex - the token a later version's canonical terms name it by. */
        public String termsSha256Hex() {
            return HexFormat.of().formatHex(termsSha256);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Agreement that && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Agreement[" + id + ", v" + version + "]";
        }
    }

    /** The disbursement: its machine and, once posted, rule 1's entry and value date. */
    public record Disbursement(UUID id, String status, Optional<UUID> payoutMethod, Optional<JournalEntryId> entry,
            Optional<LocalDate> valueDate, Optional<Instant> decidedAt, Instant createdAt) {}

    /** The day interest starts. */
    public record AccrualStartRow(LocalDate startDate, String origin, Optional<JournalEntryId> entry, Instant createdAt) {}

    /** The external payout (path X). */
    public record Payout(UUID id, String status, Optional<UUID> withdrawal, Optional<String> notDispatchedReason,
            Instant createdAt, Optional<Instant> dispatchedAt, Optional<Instant> concludedAt) {}

    /** A schedule version and its instalments. */
    public record ScheduleVersion(UUID id, int version, UUID agreementId, int agreementVersion, int engine, Money principal,
            Money instalment, Money finalInstalment, Money totalInterest, int termMonths, LocalDate startDate,
            int intendedDay, Instant createdAt, List<Instalment> instalments) {

        public ScheduleVersion {
            instalments = List.copyOf(instalments);
        }

        @Override
        public String toString() {
            return "ScheduleVersion[" + id + ", v" + version + "]";
        }
    }

    /** One instalment of a schedule version. */
    public record Instalment(UUID id, int sequence, LocalDate periodStart, LocalDate dueDate, int days, Money opening,
            Money interest, Money principal, Money amount, Money closing) {

        @Override
        public String toString() {
            return "Instalment[" + id + ", #" + sequence + "]";
        }
    }

    /** A credit line's draw. */
    public record Draw(UUID id, Money amount, UUID agreementId, int agreementVersion, JournalEntryId entry,
            LocalDate valueDate, Instant createdAt) {

        @Override
        public String toString() {
            return "Draw[" + id + "]";
        }
    }

    /** A day's accrual. */
    public record Accrual(UUID id, LocalDate date, LocalDate periodStart, LocalDate periodEnd, Money base,
            BigDecimal cumulativeExact, Money posted, UUID agreementId, int agreementVersion, int engine,
            Optional<JournalEntryId> entry, Instant createdAt) {

        @Override
        public String toString() {
            return "Accrual[" + id + ", " + date + "]";
        }
    }

    /** An instalment loan's billing. */
    public record Billing(UUID id, String kind, UUID scheduleId, int scheduleVersion, Optional<UUID> instalment,
            int sequence, LocalDate periodStart, LocalDate dueDate, Money interestDue, Money principalDue,
            UUID agreementId, int agreementVersion, int scheduleEngine, int accrualEngine,
            Optional<JournalEntryId> entry, Instant createdAt) {

        @Override
        public String toString() {
            return "Billing[" + id + ", " + dueDate + "]";
        }
    }

    /** A credit line's statement. */
    public record StatementRow(UUID id, LocalDate cycleStart, LocalDate cycleEnd, LocalDate dueDate, Money interestBilled,
            Money feesCarried, Money principalBilled, Money minimumPayment, UUID agreementId, int agreementVersion,
            int statementEngine, int accrualEngine, Optional<JournalEntryId> entry, Instant createdAt) {

        @Override
        public String toString() {
            return "StatementRow[" + id + ", " + cycleEnd + "]";
        }
    }

    /** A credit-balance application and its lines. */
    public record Application(UUID id, UUID billingId, Money amount, UUID agreementId, int agreementVersion,
            int allocationEngine, JournalEntryId entry, Instant createdAt, List<ApplicationLine> lines) {

        public Application {
            lines = List.copyOf(lines);
        }

        @Override
        public String toString() {
            return "Application[" + id + "]";
        }
    }

    /** One line of an application - a billed item's component. */
    public record ApplicationLine(int ordinal, UUID billingId, AllocationComponent component, Money amount) {

        @Override
        public String toString() {
            return "ApplicationLine[" + ordinal + "]";
        }
    }

    /** Money received - a repayment of any kind - and its allocation lines. */
    public record Payment(UUID id, RepaymentKind kind, Money amount, UUID agreementId, int agreementVersion,
            int allocationEngine, JournalEntryId entry, LocalDate valueDate, Instant createdAt, List<PaymentLine> lines) {

        public Payment {
            lines = List.copyOf(lines);
        }

        @Override
        public String toString() {
            return "Payment[" + id + ", " + kind + "]";
        }
    }

    /** One allocation line of a repayment. */
    public record PaymentLine(UUID id, int ordinal, AllocationLine.Target target, Optional<UUID> billingId,
            Optional<AllocationComponent> component, Money amount) {

        @Override
        public String toString() {
            return "PaymentLine[" + id + ", " + target + "]";
        }
    }

    /** A repayment reversal - proposed, approved or rejected - and, once approved, its negating rows. */
    public record Reversal(UUID id, UUID repaymentId, int agreementVersion, String status, Instant proposedAt,
            Optional<Instant> decidedAt, Optional<JournalEntryId> entry, List<Negation> negations) {

        public Reversal {
            negations = List.copyOf(negations);
        }
    }

    /** One negated allocation line. */
    public record Negation(UUID allocationId, Money amount) {

        @Override
        public String toString() {
            return "Negation[" + allocationId + "]";
        }
    }

    /** A fee assessed on a billed item. */
    public record Fee(UUID id, UUID dueItemId, String kind, LocalDate businessDate, Money amount, UUID agreementId,
            int agreementVersion, JournalEntryId entry, Instant createdAt) {

        @Override
        public String toString() {
            return "Fee[" + id + "]";
        }
    }

    /** A waiver and its decision, if decided. */
    public record WaiverRow(UUID id, WaiverKind kind, Optional<UUID> feeAssessmentId, UUID dueItemId, Money amount,
            int agreementVersion, Instant proposedAt, Optional<String> outcome, Optional<Instant> decidedAt,
            Optional<JournalEntryId> entry) {

        /** Whether its decision approved it. */
        public boolean approved() {
            return outcome.filter("APPROVED"::equals).isPresent();
        }

        @Override
        public String toString() {
            return "WaiverRow[" + id + ", " + kind + "]";
        }
    }

    /** An amendment and its decision, if decided. */
    public record Amendment(UUID id, UUID amendsAgreementId, int amendsVersion, String nominalAnnualRate,
            int remainingInstalments, boolean rescheduleArrears, byte[] documentSha256, Instant proposedAt,
            Optional<String> outcome, Optional<Instant> decidedAt, Optional<Instant> expiresAt) {

        public Amendment {
            documentSha256 = documentSha256.clone();
        }

        @Override
        public byte[] documentSha256() {
            return documentSha256.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Amendment that && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Amendment[" + id + "]";
        }
    }

    /** Arrears an accepted amendment re-scheduled (rule 13). */
    public record Rescheduling(UUID id, UUID amendmentId, UUID billingId, Money principal, JournalEntryId entry,
            Instant createdAt) {

        @Override
        public String toString() {
            return "Rescheduling[" + id + "]";
        }
    }

    /** The closure and its refund. */
    public record Closure(UUID id, String reason, Money refund, Optional<JournalEntryId> refundEntry,
            Optional<UUID> repaymentId, Instant createdAt) {

        @Override
        public String toString() {
            return "Closure[" + id + ", " + reason + "]";
        }
    }

    /** A payoff quote. */
    public record PayoffQuoteRow(UUID id, LocalDate businessDate, LocalDate goodThrough, Money outstanding,
            int agreementVersion, Instant createdAt) {

        @Override
        public String toString() {
            return "PayoffQuoteRow[" + id + "]";
        }
    }

    /** A payoff execution. */
    public record PayoffExecutionRow(UUID id, UUID quoteId, LocalDate businessDate, Instant executedAt) {}

    /** A line's closure request. */
    public record LineClosureRow(UUID id, String outcome, Instant requestedAt) {}

    /** A condition change (delinquency, default). */
    public record Condition(UUID id, LocalDate businessDate, String kind, int daysPastDue, String bucket,
            Optional<String> previousBucket, boolean defaulted, int agreementVersion, Instant createdAt) {}

    /** An automatic collection attempt. */
    public record Attempt(UUID id, UUID dueItemId, LocalDate attemptDate, String outcome, Money due, Money collected,
            Optional<UUID> repaymentId, Instant createdAt) {

        @Override
        public String toString() {
            return "Attempt[" + id + ", " + outcome + "]";
        }
    }

    /** One of the loan's ledger accounts. */
    public record LedgerAccountRow(LedgerAccountId id, AccountPurpose purpose, CurrencyCode currency, String status) {}
}
