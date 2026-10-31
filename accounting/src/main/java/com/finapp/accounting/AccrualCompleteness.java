package com.finapp.accounting;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * How far lending's and BNPL's daily accruals have run (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 3, 13.5; owner
 * decision C3) - the accrued-through dates the {@link CloseCheckCode#ACCRUALS_COMPLETE} check compares with the period's
 * end, because a period may not close while interest earned in it is still unposted. *(Declared empty by `P14-TSK-001`;
 * given its methods and {@code app}'s implementation by `P14-TSK-006`, owner decision C3 - "the close requires accruals
 * caught up"; the check that reads it is `P14-TSK-011`'s.)*
 *
 * <p>Why the close needs this beside C3's floor: an accrual the servicing step has not yet run for a date of the locked
 * period will - once it runs - post on the first open date (lending's rule {@code LND-2} version 2). <strong>After that
 * catch-up, accrued-through no longer tells whether the interest landed in the period</strong>: {@link #blocking} reads
 * empty while the locked period's income is short by exactly the floored amounts. So the reading only means "complete"
 * when judged before any catch-up can floor into the next period - inside the lock's transaction, with the cutoff key
 * held exclusive - or the close must also find the {@code LND-2} entries with {@code value_date <= period end <
 * posting_date} (they carry {@code flooredFrom=}). Which of the two is `P14-TSK-011`'s design (the review of
 * `P14-TSK-006`, finding I-1, 2026-10-31).
 *
 * <p><strong>Read-only</strong>, on the caller's unit of work: one statement per accruer and arm, each its own snapshot of
 * the servicing rows under READ COMMITTED - the close check runs it in {@code REPEATABLE READ READ ONLY} (C10), which
 * makes it one. Lock-free: it judges, it never waits for a servicing step. Implemented by {@code app}'s
 * {@code ServicingAccrualCompleteness} over lending's {@code AccrualLags} and bnpl's {@code BnplAccrualStore}.
 */
public interface AccrualCompleteness {

    /** Whose daily accrual - the two accruers the platform has. */
    enum Accruer {
        /** Lending's instalment loans and credit lines ({@code LoanServicing}, rule {@code LND-2}). */
        LENDING,
        /** BNPL's pay-in-N obligations ({@code BnplAccruals}, rule {@code BNPL-F2a}). */
        BNPL
    }

    /**
     * One accruer behind in one currency: {@code oldestUnaccrued} is the oldest date - on the database clock, in the
     * account's pinned servicing zone - that has ended and that some account in servicing should have accrued and has
     * not (a credit line's zero-principal days excepted: nothing accrues on them).
     *
     * @param currency an ISO 4217 code - strings, as everywhere in accounting (no ledger edge, ADR-0113)
     */
    record Lagging(Accruer accruer, String currency, LocalDate oldestUnaccrued) {

        public Lagging {
            Objects.requireNonNull(accruer, "accruer");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(oldestUnaccrued, "oldestUnaccrued");
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("a currency is an ISO 4217 code: " + currency);
            }
        }

        /** The date every account of the accruer in the currency has accrued through - the day before. */
        public LocalDate accruedThrough() {
            return oldestUnaccrued.minusDays(1);
        }

        /** True when the accrual has not reached {@code periodEnd}: interest of a date on or before it is unposted. */
        public boolean blocks(LocalDate periodEnd) {
            return !oldestUnaccrued.isAfter(Objects.requireNonNull(periodEnd, "periodEnd"));
        }
    }

    /**
     * Every (accruer, currency) with an account behind - ordered by accruer, then currency. An (accruer, currency) absent
     * has accrued every elapsed date: caught up through yesterday in each account's zone.
     */
    List<Lagging> lagging(Connection unitOfWork);

    /**
     * The positions that keep a period ending on {@code periodEnd} from closing ({@code ACCRUALS_COMPLETE}): an unaccrued
     * date on or before it. Empty: the period's accruals are complete.
     */
    default List<Lagging> blocking(Connection unitOfWork, LocalDate periodEnd) {
        Objects.requireNonNull(periodEnd, "periodEnd");
        return lagging(unitOfWork).stream().filter(lagging -> lagging.blocks(periodEnd)).toList();
    }
}
