package com.finapp.lending;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's loan schedule as they read it (`P11-TSK-014`; {@code PHASE_11_PLAN.md} section 9,
 * {@code GET /v1/me/loans/{id}/schedule?version=}): a schedule version - its latest when none is asked - with its
 * instalments, rebuilt from its rows so the {@link Schedule}'s construction re-asserts conservation on every read
 * ({@code INV-LND-03}). Owner-scoped in the statement; another party's loan, a loan not yet disbursed (it has no schedule)
 * and a version that does not exist are one empty answer. The projection, not the bill: billed and paid status arrive
 * with billing and repayment (`P11-TSK-017`, `-018`). A read decides nothing.
 */
@RequiredArgsConstructor
public final class LoanSchedules {

    @NonNull private final LoanDisbursementStore store;

    /** {@code party}'s loan {@code id}'s schedule version {@code version}, or its latest. */
    public Optional<RepaymentSchedule> read(Connection unitOfWork, LoanId id, UUID party, Optional<Integer> version) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(version, "version");
        if (version.isPresent() && version.get() < 1) {
            return Optional.empty();
        }
        return store.scheduleOwnedBy(unitOfWork, id, party, version);
    }
}
