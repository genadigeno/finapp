package com.finapp.lending;

import com.finapp.sharedkernel.id.IdGenerator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A loan's schedule version as recorded (`P11-TSK-014`, {@code lending V008}; ADR-0093, {@code PHASE_11_PLAN.md}
 * section 12.2): the {@link Schedule} the agreement's pinned schedule engine projected from the accrual start, named
 * for the agreement version it projects and - through the schedule - the engine version that produced it, so it is
 * re-derivable years later. Its periods are persisted as {@link LoanInstalment} rows. Version 1 is fixed at
 * disbursement (path W) in the disbursement's transaction; later versions are an accepted change's (`P11-TSK-027`).
 *
 * <p>Conservation is the {@link Schedule}'s, asserted at construction - here and again on every read, which rebuilds the
 * schedule from its rows - and the database's, held at commit for every writer ({@code INV-LND-03}). The projection, not
 * the bill: billing is `P11-TSK-017`'s.
 */
public record RepaymentSchedule(
        UUID id, LoanId loan, int version, LoanAgreementId agreement, int agreementVersion, Schedule schedule) {

    public RepaymentSchedule {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(schedule, "schedule must not be null");
        if (version < 1 || agreementVersion < 1) {
            throw new IllegalArgumentException("a schedule version and its agreement version are numbered from 1");
        }
    }

    /** The periods as rows to persist, each with a fresh identity. */
    public List<LoanInstalment> instalments(IdGenerator ids) {
        Objects.requireNonNull(ids, "ids must not be null");
        return schedule.periods().stream().map(period -> new LoanInstalment(ids.next(), id, period)).toList();
    }

    /** The version and the engine - never an amount. */
    @Override
    public String toString() {
        return "RepaymentSchedule[" + loan.value() + ", v" + version + ", " + schedule + "]";
    }
}
