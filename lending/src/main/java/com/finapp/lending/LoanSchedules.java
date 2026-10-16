package com.finapp.lending;

import com.finapp.creditmath.Schedule;
import java.sql.Connection;
import java.util.List;
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
 * and a version that does not exist are one empty answer. The projection beside the bill (`P11-TSK-017`): the loan's
 * billed items with what has been paid of each, so each instalment reads as unbilled, due, partly paid or paid, and the
 * post-maturity periods billed after the final due date read beside them. A read decides nothing.
 */
@RequiredArgsConstructor
public final class LoanSchedules {

    @NonNull private final LoanDisbursementStore store;
    @NonNull private final InstalmentBillingStore billings;

    /** A schedule version and the loan's billed items, oldest first, with what has been paid of each. */
    public record Read(RepaymentSchedule schedule, List<InstalmentBillingStore.BilledItem> billed) {

        public Read {
            Objects.requireNonNull(schedule, "schedule must not be null");
            billed = List.copyOf(Objects.requireNonNull(billed, "billed must not be null"));
        }

        @Override
        public String toString() {
            return "Read[" + schedule + ", " + billed.size() + " billed]";
        }
    }

    /** {@code party}'s loan {@code id}'s schedule version {@code version}, or its latest, with its billed items. */
    public Optional<Read> read(Connection unitOfWork, LoanId id, UUID party, Optional<Integer> version) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(version, "version");
        if (version.isPresent() && version.get() < 1) {
            return Optional.empty();
        }
        return store.scheduleOwnedBy(unitOfWork, id, party, version)
                .map(schedule -> new Read(schedule, billings.billedItemsOwnedBy(unitOfWork, id, party)));
    }
}
