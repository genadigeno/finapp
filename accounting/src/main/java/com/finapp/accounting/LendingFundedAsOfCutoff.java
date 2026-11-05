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
 * What {@code lending_funded} rests on beyond the trial balance's reads, <strong>as of a period's cutoff, through reads that cannot move after the lock</strong> (`P14-TSK-019`; ADR-0096 section 6; ADR-0118 decision 4; {@code INV-ACC-04}): what a FINAL body may read, and {@code LendingCapitalHeadroom.atRest} is not, because its
 * {@code committed} is the loans {@code PENDING_DISBURSEMENT} now and its {@code outstanding} a present balance. {@code ReportingNeverWritesTest} refuses a report port's adapter any edge to {@code com.finapp.lending} for that reason. So the formula is held twice, {@link #lendingFunded} here and {@code LendingCapitalHeadroom.Headroom.lendingFunded} there, and {@code
 * SafeguardingFormulaIsTheHeadroomsTest} in {@code app}, which sees both, pins the two and their purpose sets together.
 *
 * <p><strong>Committed: immutable history can say it.</strong> An accepted, undisbursed loan posts nothing, and a loan's status moves, so it is never read. A loan's frozen facts ({@link LoanFacts}) can: it counts when its own servicing zone's business date of its opening is on or before the cutoff, and neither its disbursement's value date nor its cancellation's date is.
 *
 * <p><strong>Why the disbursement's value date.</strong> The disbursement's journal line is dated exactly that day ({@code LoanDisbursements}: {@code posting_date = value_date}, as stated, never floored). So a loan crosses from {@code committed} to the outstanding term on one date, never counted twice and never in neither, in any servicing zone and across any midnight its transaction
 * straddles. Reading its {@code ACTIVE} edge's instant instead double-counts a loan in a zone west of UTC disbursed after midnight UTC, and drops one in a zone east of it. Nothing here orders two instants, so a database clock stepped back (ADR-0063) changes nothing either.
 *
 * <p>A credit line commits no capital (A28) and BNPL has no committed term (ADR-0104): neither has a fact here. Read-only, on the caller's snapshot; a read that fails throws, never an empty answer, so a currency's true zero and an unreadable read can never look alike.
 */
public interface LendingFundedAsOfCutoff {

    /** The ledger's purposes the outstanding term sums: {@code LendingCapitalHeadroom.principalPurposes()}, by name. */
    Set<String> PRINCIPAL_PURPOSES = Set.of("LOAN_PRINCIPAL", "LOAN_PRINCIPAL_DUE", "BNPL_PRINCIPAL", "BNPL_PRINCIPAL_DUE");

    /** The expenses an entry holds a line on when it is a write-off: {@code LendingCapitalHeadroom.writeOffExpenses()}, by name. */
    Set<String> WRITE_OFF_EXPENSES = Set.of("LOAN_WRITE_OFF_EXPENSE", "BNPL_WRITE_OFF_EXPENSE");

    /**
     * {@code lending_funded(c)}: the principal outstanding plus committed plus written off (ADR-0096 section 6, widened by `P14-TSK-021`, the owner's item (h)): the wallet money a written-off loan created is still there and still capital's, so a write-off must not raise what the return holds customers funded. The same sum as {@code Headroom.lendingFunded()}, in exact decimals.
     */
    static BigDecimal lendingFunded(BigDecimal outstanding, BigDecimal committed, BigDecimal writtenOff) {
        return outstanding.add(committed).add(writtenOff);
    }

    /** Per currency and scale, sorted: the principal of the loans that stood committed at the end of {@code cutoff}. */
    List<Committed> committed(Connection snapshot, LocalDate cutoff, Optional<Instant> cut);

    /** Per currency and scale, sorted: {@link #PRINCIPAL_PURPOSES}' credits less debits of the entries holding a line on {@link #WRITE_OFF_EXPENSES}, by posting date through {@code cutoff}. */
    List<WrittenOff> writtenOff(Connection snapshot, LocalDate cutoff, Optional<Instant> cut);

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
