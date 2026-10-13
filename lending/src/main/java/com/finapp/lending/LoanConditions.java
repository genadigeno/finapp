package com.finapp.lending;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Delinquency and default - the servicing step's condition step, the draw's suspension and the reads' condition
 * (`P11-TSK-024`; {@code PHASE_11_PLAN.md} sections 5.5, 7.1 T-svc and 12.7, ADR-0098; L7, A8, A25; {@code INV-LND-12},
 * {@code INV-LND-10}, {@code INV-LND-08}). One derivation - {@link DelinquencyState#derive} over
 * {@link InstalmentBillingStore#dueItems} (every billed item, billed less every payer's allocations) on the business date
 * of the database clock in the pinned zone - serves all three; nothing here stores a figure that could drift.
 *
 * <h2>The condition step (T-svc)</h2>
 *
 * <p>Called by {@link LoanServicing} under the step's loan row lock (L3), once the accrual has caught up and after the
 * billing (or a line's statement), its credit-balance application and auto-collection - and only on an account the step
 * did not close (a closed account's condition is its closure: nothing is evaluated on it, scenario 10). It derives the
 * condition and compares it with the latest recorded change of each kind ({@link LoanConditionStore#latest}): a bucket
 * other than the latest {@code DELINQUENCY} row's appends a {@code DELINQUENCY} row and publishes
 * {@code lending.LoanDelinquencyChanged} (cure included - the bucket back to {@code CURRENT}); a flag other than the latest
 * {@code DEFAULT} row's appends a {@code DEFAULT} row and publishes {@code lending.LoanDefaulted} or
 * {@code lending.LoanDefaultCleared}. Nothing else: no posting, no acceleration, no penalty interest, no notification
 * (ADR-0098 point 6). The rows and their events commit with the step, or none of it does.
 *
 * <p><strong>Once</strong>: L3 serialises every step on the account, each reading the latest rows after the lock;
 * {@code UNIQUE (loan_id, business_date, kind)} beneath it; and re-running a day finds the day's condition recorded - once a
 * change is recorded on a business date, nothing more is evaluated that date (a second change of a kind would collide with
 * the unique, and a bucket recorded without its flag, or the flag without its bucket, would describe a day that never
 * was). <strong>A missed
 * day</strong> computes from the dates - one row, the bucket the dates give, never a day at a time. <strong>The history is
 * the daily evaluation's</strong>: each business date's first step evaluates the end-of-day position (an item unpaid at the
 * end of its due date is past due then), so the record lags a repayment by at most one business date - while every
 * decision (the draw's suspension, the reads) derives the condition live, under the lock or in the read's snapshot.
 */
@RequiredArgsConstructor
public final class LoanConditions implements DrawSuspension {

    /** A bucket changed - cure included (ADR-0098 point 3; {@code DELIVERY_PLAN.md}'s {@code LoanDelinquent} renamed). */
    public static final String DELINQUENCY_CHANGED_EVENT = "lending.LoanDelinquencyChanged";

    /** The default flag set at the pinned threshold (L7). */
    public static final String DEFAULTED_EVENT = "lending.LoanDefaulted";

    /** The default flag cleared at cure (A8). */
    public static final String DEFAULT_CLEARED_EVENT = "lending.LoanDefaultCleared";

    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LoanConditionStore store;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** {@code loan}'s condition on {@code today} under {@code pinned} - its billed items and its recorded default flag. */
    public DelinquencyState derive(Connection unitOfWork, LoanId loan, LoanTerms pinned, LocalDate today) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        boolean previouslyDefaulted = store.latest(unitOfWork, loan, ConditionKind.DEFAULT)
                .map(event -> event.state().defaulted())
                .orElse(false);
        return DelinquencyState.derive(billings.dueItems(unitOfWork, loan), today, pinned, previouslyDefaulted);
    }

    // ------------------------------------------------------------------ the condition step

    /**
     * The condition step for {@code loan}, under the caller's L3 and on its unit of work: the changes appended (0, 1 or 2).
     *
     * @param pinned the governing agreement's pinned terms
     * @param today the business date of the database clock in the pinned zone, read after L3
     */
    public int evaluate(Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(correlation, "correlation");
        if (loan.status() != LoanStatus.ACTIVE && loan.status() != LoanStatus.CLOSING) {
            // A terminal account is never evaluated (INV-LND-08, scenario 10); the caller never asks, the rule is here too.
            return 0;
        }
        Optional<LoanConditionEvent> latestBucket = store.latest(unitOfWork, loan.id(), ConditionKind.DELINQUENCY);
        Optional<LoanConditionEvent> latestDefault = store.latest(unitOfWork, loan.id(), ConditionKind.DEFAULT);
        if (latestBucket.filter(event -> !event.businessDate().isBefore(today)).isPresent()
                || latestDefault.filter(event -> !event.businessDate().isBefore(today)).isPresent()) {
            // The condition of this business date is recorded: once a day (ADR-0098 point 3). A repayment that cured it
            // since is the next business date's evaluation - its decisions (the draw, the reads) derive it live already.
            return 0;
        }
        Optional<DelinquencyBucket> recordedBucket = latestBucket.flatMap(event -> event.state().bucket());
        boolean recordedDefault = latestDefault.map(event -> event.state().defaulted()).orElse(false);
        DelinquencyState state = DelinquencyState.derive(billings.dueItems(unitOfWork, loan.id()), today, pinned,
                recordedDefault);
        int appended = 0;
        if (!state.bucket().equals(recordedBucket)) {
            LoanConditionEvent change = new LoanConditionEvent(ids.next(), loan.id(), today, ConditionKind.DELINQUENCY,
                    state, recordedBucket, agreement.id(), agreement.version());
            store.insert(unitOfWork, change, correlation);
            publish(unitOfWork, loan, change, DELINQUENCY_CHANGED_EVENT, correlation);
            appended++;
        }
        if (state.defaulted() != recordedDefault) {
            LoanConditionEvent change = new LoanConditionEvent(ids.next(), loan.id(), today, ConditionKind.DEFAULT, state,
                    Optional.empty(), agreement.id(), agreement.version());
            store.insert(unitOfWork, change, correlation);
            publish(unitOfWork, loan, change, state.defaulted() ? DEFAULTED_EVENT : DEFAULT_CLEARED_EVENT, correlation);
            appended++;
        }
        return appended;
    }

    // ------------------------------------------------------------------ the draw's suspension (A25)

    /**
     * Whether {@code line}'s draws are suspended on {@code businessDate} - anything past due, or defaulted - derived from
     * its rows under the draw's L3, so a repayment that cured it (which takes the same lock) lifts the suspension at its
     * commit, before any servicing step records the cure.
     */
    @Override
    public boolean suspended(Connection unitOfWork, LoanId line, LoanTerms pinned, LocalDate businessDate) {
        return derive(unitOfWork, line, pinned, businessDate).suspendsDraws();
    }

    // ------------------------------------------------------------------ the reads

    /**
     * {@code owned}'s condition now - derived in the caller's snapshot on the database clock's business date in the pinned
     * zone; empty for an account not in servicing (awaiting disbursement, closed or cancelled - DPD is never computed on a
     * terminal account). A read decides nothing.
     */
    public Optional<DelinquencyState> read(Connection unitOfWork, LoanStore.Owned owned) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(owned, "owned");
        Loan loan = owned.loan();
        if (loan.status() != LoanStatus.ACTIVE && loan.status() != LoanStatus.CLOSING) {
            return Optional.empty();
        }
        LoanTerms pinned = terms.version(unitOfWork, owned.agreement().termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        LocalDate today = LocalDate.ofInstant(store.databaseNow(unitOfWork), ZoneId.of(pinned.servicingZone()));
        return Optional.of(derive(unitOfWork, loan.id(), pinned, today));
    }

    /**
     * The change's event: the loan, the business date, the condition after it (DPD, bucket, flag) and - for a bucket
     * change - the bucket before; the agreement version that judged it. RESTRICTED-FINANCIAL facts for the collections
     * owner (ADR-0098 point 7) - never a party, never an amount.
     */
    private void publish(Connection unitOfWork, Loan loan, LoanConditionEvent change, String type,
            CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("product", loan.product().name())
                .with("businessDate", change.businessDate().toString())
                .with("daysPastDue", Integer.toString(change.state().daysPastDue()))
                .with("bucket", change.state().bucketName())
                .with("defaulted", Boolean.toString(change.state().defaulted()))
                .with("agreementVersion", Integer.toString(change.agreementVersion()));
        if (change.kind() == ConditionKind.DELINQUENCY) {
            payload = payload.with("previousBucket", change.previousBucketName());
        }
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
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
