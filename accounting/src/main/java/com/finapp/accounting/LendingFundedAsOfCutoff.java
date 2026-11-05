package com.finapp.accounting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the safeguarding return's lending lines rest on beyond the trial balance's reads, <strong>as of a period's cutoff, through reads that cannot move after the lock</strong> (`P14-TSK-019`, `P14-TSK-023`; ADR-0096 section 6 as corrected; ADR-0118 decision 4; {@code INV-ACC-04}): what a FINAL body may read, and {@code LendingCapitalHeadroom.atRest} is not, because its
 * {@code committed} is the loans {@code PENDING_DISBURSEMENT} now and its {@code outstanding} a present balance. {@code ReportingNeverWritesTest} refuses a report port's adapter any edge to {@code com.finapp.lending} for that reason. <strong>The port's name is capital's figure, as of a cutoff</strong>: {@code lending_funded}, {@code LendingCapitalHeadroom.Headroom.lendingFunded} there, is the principal outstanding, committed and written off, and the return subtracts only {@link #principalUnrepaid}, the outstanding and written-off terms, {@code committed} standing as a line beside it. So the relation is held here and there, and {@code
 * SafeguardingFormulaIsTheHeadroomsTest} in {@code app}, which sees both, pins the return's term + committed to the headroom's figure, and the purpose sets together.
 *
 * <p><strong>Committed: immutable history can say it.</strong> An accepted, undisbursed loan posts nothing, and a loan's status moves, so it is never read. A loan's frozen facts ({@link LoanFacts}) can: it counts when its own servicing zone's business date of its opening is on or before the cutoff, and neither its disbursement's value date nor its cancellation's date is.
 *
 * <p><strong>Why the disbursement's value date.</strong> The disbursement's journal line is dated exactly that day ({@code LoanDisbursements}: {@code posting_date = value_date}, as stated, never floored). So in a FINAL run a loan crosses from {@code committed} to the outstanding term on one date, never counted twice and never in neither, in any servicing zone and across any midnight its transaction
 * straddles. A PROVISIONAL run compares two instants against its cut, the entry's creation and the decision's, written by two statements of one transaction: a cut between them shows the loan in both terms, or in neither. Labelled, and never issued. Reading its {@code ACTIVE} edge's instant instead double-counts a loan in a zone west of UTC disbursed after midnight UTC, and drops one in a zone east of it. Nothing here orders two instants, so a database clock stepped back (ADR-0063) changes nothing either.
 *
 * <p>A credit line commits no capital (A28) and BNPL has no committed term (ADR-0104): neither has a fact here. Read-only, on the caller's snapshot; a read that fails throws, never an empty answer, so a currency's true zero and an unreadable read can never look alike.
 *
 * <p><strong>What the trial balance's reads cannot say, beside it: who owes whom</strong> ({@link #heldPerAccount}). Those reads sum a purpose across its accounts, so a customer's wallet in debit nets against another customer's money and reads as cover. ADR-0061 section 5 calls a negative wallet a receivable from that customer and a negative payable merchant debt: claims, and no cover for anyone else. So the many-account liabilities are also read per account, the credit balances gross and the debit
 * balances apart, and a form's footing holds the two reads to each other.
 */
public interface LendingFundedAsOfCutoff {

    /** The ledger's purposes the outstanding term sums: {@code LendingCapitalHeadroom.principalPurposes()}, by name. */
    Set<String> PRINCIPAL_PURPOSES = Set.of("LOAN_PRINCIPAL", "LOAN_PRINCIPAL_DUE", "BNPL_PRINCIPAL", "BNPL_PRINCIPAL_DUE");

    /** The expenses an entry holds a line on when it is a write-off: {@code LendingCapitalHeadroom.writeOffExpenses()}, by name. */
    Set<String> WRITE_OFF_EXPENSES = Set.of("LOAN_WRITE_OFF_EXPENSE", "BNPL_WRITE_OFF_EXPENSE");

    /** The liabilities with many holders, each account its own: what {@link #heldPerAccount} reads. Every other purpose in the position has one account per currency, or per counterparty and currency. */
    Set<String> HELD_PER_ACCOUNT = Set.of("CUSTOMER_WALLET", "MERCHANT_PAYABLE", "LOAN_CREDIT_BALANCE", "BNPL_CREDIT_BALANCE");

    /**
     * The return's term, in exact decimals: the principal outstanding plus written off (`P14-TSK-023`, the owner's ruling of 2026-11-05 correcting ADR-0096 section 6 for this return). The wallet money a written-off loan created is still there and still capital's, so a write-off must not raise what the return holds customers funded (`P14-TSK-021`, the owner's item (h)). <strong>Committed is not in it:</strong> an accepted loan awaiting disbursement has created no wallet money, so subtracting it would lift a position with nothing behind it. This + committed = {@code Headroom.lendingFunded()}, which keeps capital's identity.
     */
    static BigDecimal principalUnrepaid(BigDecimal outstanding, BigDecimal writtenOff) {
        return outstanding.add(writtenOff);
    }

    /** Per currency and scale, sorted: the principal of the loans that stood committed at the end of {@code cutoff}. */
    List<Committed> committed(Connection snapshot, LocalDate cutoff, Optional<Instant> cut);

    /** Per currency and scale, sorted: {@link #PRINCIPAL_PURPOSES}' credits less debits of the entries holding a line on {@link #WRITE_OFF_EXPENSES}, by posting date through {@code cutoff}. */
    List<WrittenOff> writtenOff(Connection snapshot, LocalDate cutoff, Optional<Instant> cut);

    /**
     * Per {@link #HELD_PER_ACCOUNT} purpose, currency and scale, sorted: the accounts' balances by posting date through {@code cutoff}, those in credit summed apart from those in debit, each account one balance. Frozen by the argument the trial balance's reads rest on: the ledger refuses a line dated in a locked period.
     */
    List<HeldPerAccount> heldPerAccount(Connection snapshot, LocalDate cutoff, Optional<Instant> cut);

    /**
     * One purpose in one currency and scale, per account: what is owed, gross; what is owed back, as debt; and how many accounts stand in debit. No account is named.
     *
     * @param owedMinor the accounts in credit, summed: what the platform owes their holders
     * @param debtMinor the accounts in debit, summed: what their holders owe the platform, positive
     */
    record HeldPerAccount(String purpose, String currency, int scale, BigInteger owedMinor, BigInteger debtMinor, long debtors) {

        public HeldPerAccount {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(owedMinor, "owedMinor");
            Objects.requireNonNull(debtMinor, "debtMinor");
        }

        /** What is owed, as an exact decimal in the currency. */
        public BigDecimal owed() {
            return new BigDecimal(owedMinor, scale);
        }

        /** What is owed back, as an exact decimal in the currency. */
        public BigDecimal debt() {
            return new BigDecimal(debtMinor, scale);
        }

        /** An amount stays out of a log line. */
        @Override
        public String toString() {
            return "HeldPerAccount[" + purpose + ", " + currency + "]";
        }
    }

    /** The principal one currency and scale held committed, and how many loans: no loan is named. */
    record Committed(String currency, int scale, BigInteger minor, long loans) {

        public Committed {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(minor, "minor");
        }

        /** The sum as an exact decimal in the currency. */
        public BigDecimal amount() {
            return new BigDecimal(minor, scale);
        }

        /** An amount stays out of a log line. */
        @Override
        public String toString() {
            return "Committed[" + currency + "]";
        }
    }

    /** The principal one currency and scale wrote off, credit-positive, and how many lines. */
    record WrittenOff(String currency, int scale, BigInteger minor, long lines) {

        public WrittenOff {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(minor, "minor");
        }

        /** The sum as an exact decimal in the currency. */
        public BigDecimal amount() {
            return new BigDecimal(minor, scale);
        }

        /** An amount stays out of a log line. */
        @Override
        public String toString() {
            return "WrittenOff[" + currency + "]";
        }
    }

    /**
     * What is frozen about one instalment loan, by trigger, for every writer: its identity and opening, its agreement version 1's principal - an amendment exists only on an {@code ACTIVE} loan, so a loan awaiting disbursement has only that - and its disbursement's outcome, absent until decided and frozen once it is.
     *
     * @param openedOn the opening's business date in the loan's servicing zone
     * @param openedAt the opening's instant, which a PROVISIONAL run's cut compares
     * @param disbursedOn the disbursement's value date, which is its journal line's posting date; empty until posted
     * @param cancelledOn the failed disbursement's business date in the loan's servicing zone; empty unless cancelled
     * @param decidedAt the disbursement's decision instant, which a PROVISIONAL run's cut compares; empty until decided
     */
    record LoanFacts(String currency, int scale, BigInteger principal, LocalDate openedOn, Instant openedAt, Optional<LocalDate> disbursedOn, Optional<LocalDate> cancelledOn, Optional<Instant> decidedAt) {

        public LoanFacts {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(openedOn, "openedOn");
            Objects.requireNonNull(openedAt, "openedAt");
            Objects.requireNonNull(disbursedOn, "disbursedOn");
            Objects.requireNonNull(cancelledOn, "cancelledOn");
            Objects.requireNonNull(decidedAt, "decidedAt");
        }

        /**
         * Whether this loan stood committed at the end of {@code cutoff}. A PROVISIONAL run's {@code cut} is its claim: what was recorded after it has not happened yet, as the journal's reads count only entries created at or before it.
         */
        public boolean stoodCommitted(LocalDate cutoff, Optional<Instant> cut) {
            if (openedOn.isAfter(cutoff) || cut.filter(openedAt::isAfter).isPresent()) {
                return false;
            }
            boolean decidedByTheCut = decidedAt.isPresent() && cut.filter(decidedAt.get()::isAfter).isEmpty();
            boolean disbursed = decidedByTheCut && disbursedOn.filter(day -> !day.isAfter(cutoff)).isPresent();
            boolean cancelled = decidedByTheCut && cancelledOn.filter(day -> !day.isAfter(cutoff)).isPresent();
            return !disbursed && !cancelled;
        }

        /** An amount stays out of a log line. */
        @Override
        public String toString() {
            return "LoanFacts[" + currency + "]";
        }
    }

    /** The loans of {@code facts} that stood committed at the end of {@code cutoff}, summed per currency and scale, sorted: the scale is a key, so no sum crosses one. */
    static List<Committed> committedAt(List<LoanFacts> facts, LocalDate cutoff, Optional<Instant> cut) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(cut, "cut");
        Map<String, Committed> folded = new TreeMap<>();
        for (LoanFacts loan : facts) {
            if (loan.stoodCommitted(cutoff, cut)) {
                folded.merge(loan.currency() + ":" + loan.scale(), new Committed(loan.currency(), loan.scale(), loan.principal(), 1),
                        (held, one) -> new Committed(held.currency(), held.scale(), held.minor().add(one.minor()), held.loans() + one.loans()));
            }
        }
        return new ArrayList<>(folded.values());
    }
}
