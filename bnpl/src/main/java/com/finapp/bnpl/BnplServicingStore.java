package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence for the servicing step (`P12-TSK-015`, {@code bnpl V008}; lending's {@code LoanServicingStore} and
 * {@code InstalmentBillingStore}): the leaderless sweep's claim, the obligation's row lock (B4), the database clock, the
 * principal value floor, the accrual record the billing reads, the latest schedule version, the catch-up number and the
 * application's rows, and the permit. Every read of an obligation is of one the caller holds {@code FOR UPDATE} - claimed
 * by the sweep - never a request value; every instant the database's.
 */
public interface BnplServicingStore {

    /**
     * The {@code ACTIVE} obligations whose permit has come ({@code next_servicing_at <= statement_timestamp()}), oldest
     * permit first, at most {@code limit} - claimed in ONE statement, {@code FOR UPDATE SKIP LOCKED}, each permit
     * re-stamped to {@code clock_timestamp() + retryCadence}, so a step that fails retries at that cadence and never holds
     * the page, and rows another sweeper holds are skipped.
     */
    List<BnplObligationId> claimDue(Connection unitOfWork, int limit, Duration retryCadence);

    /** (B4) The obligation's row {@code FOR UPDATE} with its agreement version in force - empty when absent. */
    Optional<Locked> lock(Connection unitOfWork, BnplObligationId obligation);

    /** The database clock, {@code clock_timestamp()} - read after the lock. */
    Instant databaseNow(Connection unitOfWork);

    /** {@code bnpl.principal_value_floor(obligation)} - read under B4. */
    LocalDate principalValueFloor(Connection unitOfWork, BnplObligationId obligation);

    /** The latest accrued date of the obligation - empty when nothing has accrued. */
    Optional<LocalDate> latestAccrualDate(Connection unitOfWork, BnplObligationId obligation);

    /** Σ {@code posted_minor} of the obligation's accruals dated in {@code [from, toExclusive)} - a period's interest. */
    Money periodInterest(Connection unitOfWork, BnplObligationId obligation, LocalDate from, LocalDate toExclusive,
            Money zero);

    /** The obligation's latest schedule version, its instalments in order. */
    Optional<BnplPurchaseStore.StoredSchedule> latestSchedule(Connection unitOfWork, BnplObligationId obligation);

    /** The obligation's latest catch-up application number - 0 when none; read under B4, the next is this plus one. */
    int latestCatchUpSequence(Connection unitOfWork, BnplObligationId obligation);

    /** Births an application and its lines - conserving at commit. */
    void insertApplication(Connection unitOfWork, BnplCreditBalanceApplication application);

    /**
     * The permit moved to the start of {@code openDate} in the obligation's pinned zone, computed by the database -
     * conditional on {@code ACTIVE}; whether it moved.
     */
    boolean restamp(Connection unitOfWork, BnplObligationId obligation, LocalDate openDate);

    /**
     * An obligation as its servicing step holds it under B4: the obligation, its agreement version in force and what it
     * pins, and the servicing zone.
     */
    record Locked(BnplObligation obligation, BnplAgreementId agreement, int agreementVersion,
            BnplTermsVersionId termsVersionId, String scheduleEngine, Optional<String> accrualEngine,
            String allocationEngine, String servicingZone) {

        public Locked {
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
            Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
            Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
            Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
            Objects.requireNonNull(servicingZone, "servicingZone must not be null");
        }

        @Override
        public String toString() {
            return "Locked[" + obligation.id().value() + ", v" + agreementVersion + "]";
        }
    }
}
