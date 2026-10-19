package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Delinquency, default and late fees - the servicing step's condition and late-fee slot, and the customer read's condition
 * (`P12-TSK-020`; {@code PHASE_12_PLAN.md} sections 7.1 T-svc, 7.2, 12.3, 12.8 and 12.11 F4; A13, A14; lending's
 * {@code LoanConditions} and {@code LateFees} - P11-TSK-024 and -025 - mirrored; {@code INV-LND-12} and {@code INV-LND-08}
 * by analogy, {@code INV-IDEM-02}, {@code INV-HIST-01}, {@code INV-BNPL-01}).
 *
 * <h2>The condition step (T-svc, after the closure)</h2>
 *
 * <p>Called by {@link BnplServicing} under the step's obligation row lock (B4), once the accrual gate passed and after the
 * billing, the credit-balance applications, the collection and the closure - and only on an obligation the step did not
 * close (a closed obligation's condition is its closure: nothing is evaluated on it). It derives the condition
 * ({@link BnplDelinquencyState#derive} over the billed items, on the business date of the database clock in the pinned
 * zone) and compares it with the latest recorded change of each kind: a bucket other than the latest {@code DELINQUENCY}
 * row's appends a {@code DELINQUENCY} row and publishes {@code bnpl.BnplDelinquencyChanged} (cure included); a flag other
 * than the latest {@code DEFAULT} row's appends a {@code DEFAULT} row and publishes {@code bnpl.BnplDefaulted} or
 * {@code bnpl.BnplDefaultCleared}. Nothing else: no posting, no acceleration, no penalty interest, no notification, no
 * collections case (Phase 13 consumes the events). <strong>Once a day</strong>: a business date with a change recorded is
 * not evaluated again (a second change of a kind would collide with {@code UNIQUE (obligation_id, business_date, kind)}),
 * so the history is the daily end-of-day evaluation's and lags a repayment by at most one business date, while every read
 * derives the condition live. <strong>A missed day</strong> computes from the dates - one row, never a day at a time.
 *
 * <h2>The late-fee step (after the condition step)</h2>
 *
 * <p>Per billed instalment, oldest first: once the instalment's <em>own</em> age - the business date less its due date - has
 * reached the pinned {@code late_fee_day} (always beyond grace: grace delays only the fee, never DPD) while it still owes
 * interest or principal, and it has no late fee yet. A missed day is caught up while it still owes; one settled in the
 * meantime is never charged late. Capped per agreement: the pinned fee or what the pinned cap still admits, whichever is
 * less - nothing once the cap is reached (every assessment counts, a released one included); a zero fee assesses nothing
 * (production terms carry none until activation, A13). F4 {@code DR BNPL_FEES_DUE / CR BNPL_FEE_INCOME}, posted and valued
 * today (a fee is no principal fact: no floor) under {@code bnpl.fee:<instalment>:LATE} - after <strong>(L8)</strong> the
 * fees-due row and the currency's shared {@code BNPL_FEE_INCOME} row in ONE sorted {@code lockBalancesInOrder} call, after
 * any wallet the step's collection took under (P): no income row is ever held while a wallet is waited on (the A2-F1 rule).
 * Then the {@code bnpl_fee_assessment} row ({@code UNIQUE (instalment_id, kind)}) and {@code bnpl.BnplLateFeeAssessed}.
 *
 * <h2>Why once (the ten-sweeper question)</h2>
 *
 * <p>B4 serialises every act on the obligation - servicing, repayment, refund, payoff - each reading the history and the
 * assessments after the lock; beneath it the two uniques, the ledger key (a replay - an entry without its row - is a
 * defect the book refuses), and {@code bnpl V013}'s triggers, which re-take B4 and re-judge the DPD, the bucket, the flag,
 * the change, the day, the fee and the cap for every writer. Nothing here holds JVM state.
 */
@RequiredArgsConstructor
public final class BnplConditions {

    /** A bucket changed - cure included. */
    public static final String DELINQUENCY_CHANGED_EVENT = "bnpl.BnplDelinquencyChanged";

    /** The default flag set at the pinned threshold (A14). */
    public static final String DEFAULTED_EVENT = "bnpl.BnplDefaulted";

    /** The default flag cleared at cure. */
    public static final String DEFAULT_CLEARED_EVENT = "bnpl.BnplDefaultCleared";

    /** A late fee assessed. */
    public static final String LATE_FEE_ASSESSED_EVENT = "bnpl.BnplLateFeeAssessed";

    /** F4's key prefix: {@code bnpl.fee:<instalment>:<kind>}. */
    public static final String FEE_KEY_PREFIX = "bnpl.fee:";

    @NonNull private final BnplConditionStore store;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplBook book;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one fee step did - counts only, for the meter: fees {@code assessed}, of which {@code capped} below the fee. */
    public record Assessed(int assessed, int capped) {

        public static final Assessed NOTHING = new Assessed(0, 0);

        public Assessed {
            if (assessed < 0 || capped < 0 || capped > assessed) {
                throw new IllegalArgumentException("counts are not negative, and a capped fee is an assessed one");
            }
        }
    }

    /** {@code obligation}'s condition on {@code today} under {@code pinned} - its billed items and its recorded flag. */
    public BnplDelinquencyState derive(Connection unitOfWork, BnplObligationId obligation, BnplTerms pinned,
            LocalDate today) {
        boolean previouslyDefaulted = store.latest(unitOfWork, obligation, BnplConditionKind.DEFAULT)
                .map(event -> event.state().defaulted()).orElse(false);
        return BnplDelinquencyState.derive(purchases.billedItems(unitOfWork, obligation), today, pinned,
                previouslyDefaulted);
    }

    // ------------------------------------------------------------------ the condition step

    /**
     * The condition step for the obligation {@code locked} holds, under the caller's B4 and on its unit of work: the changes
     * appended (0, 1 or 2).
     *
     * @param pinned the agreement version in force's pinned terms
     * @param today the business date of the database clock in the pinned zone, read after B4
     */
    public int evaluate(Connection unitOfWork, BnplServicingStore.Locked locked, BnplTerms pinned, LocalDate today,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(locked, "locked");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(correlation, "correlation");
        BnplObligation obligation = locked.obligation();
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            return 0; // A closed obligation is never evaluated (INV-LND-08 by analogy); the caller never asks.
        }
        Optional<BnplConditionEvent> latestBucket = store.latest(unitOfWork, obligation.id(),
                BnplConditionKind.DELINQUENCY);
        Optional<BnplConditionEvent> latestDefault = store.latest(unitOfWork, obligation.id(), BnplConditionKind.DEFAULT);
        if (latestBucket.filter(event -> !event.businessDate().isBefore(today)).isPresent()
                || latestDefault.filter(event -> !event.businessDate().isBefore(today)).isPresent()) {
            // This business date's condition is recorded: once a day. A cure since is the next date's record - every read
            // derives it live already.
            return 0;
        }
        Optional<BnplDelinquencyBucket> recordedBucket = latestBucket.flatMap(event -> event.state().bucket());
        boolean recordedDefault = latestDefault.map(event -> event.state().defaulted()).orElse(false);
        BnplDelinquencyState state = BnplDelinquencyState.derive(purchases.billedItems(unitOfWork, obligation.id()),
                today, pinned, recordedDefault);
        int appended = 0;
        if (!state.bucket().equals(recordedBucket)) {
            BnplConditionEvent change = new BnplConditionEvent(ids.next(), obligation.id(), today,
                    BnplConditionKind.DELINQUENCY, state, recordedBucket, locked.agreement(), locked.agreementVersion(),
                    correlation);
            store.insertCondition(unitOfWork, change);
            publishCondition(unitOfWork, obligation, change, DELINQUENCY_CHANGED_EVENT);
            appended++;
        }
        if (state.defaulted() != recordedDefault) {
            BnplConditionEvent change = new BnplConditionEvent(ids.next(), obligation.id(), today,
                    BnplConditionKind.DEFAULT, state, Optional.empty(), locked.agreement(), locked.agreementVersion(),
                    correlation);
            store.insertCondition(unitOfWork, change);
            publishCondition(unitOfWork, obligation, change, state.defaulted() ? DEFAULTED_EVENT : DEFAULT_CLEARED_EVENT);
            appended++;
        }
        return appended;
    }

    // ------------------------------------------------------------------ the late-fee step

    /**
     * The late-fee step for the obligation {@code locked} holds, under the caller's B4, after its condition step.
     *
     * @param accounts the obligation's six accounts
     * @param today the business date of the database clock in the pinned zone, read after B4
     */
    public Assessed assessLateFees(Connection unitOfWork, BnplServicingStore.Locked locked, BnplTerms pinned,
            BnplLedgerAccounts accounts, LocalDate today, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(locked, "locked");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(correlation, "correlation");
        BnplObligation obligation = locked.obligation();
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            return Assessed.NOTHING; // A closed obligation takes no fee (409 bnpl.ObligationClosed's rule).
        }
        Money fee = pinned.lateFee();
        if (!fee.isPositive()) {
            return Assessed.NOTHING; // Production terms carry no fee until activation (A13).
        }
        Set<UUID> charged = new HashSet<>();
        Money total = Money.ofPersisted(0, fee.currency(), fee.scale());
        for (BnplFeeAssessment assessment : store.assessments(unitOfWork, obligation.id())) {
            if (assessment.kind() == BnplFeeKind.LATE) {
                charged.add(assessment.instalment());
            }
            total = total.plus(assessment.amount());
        }
        Money cap = pinned.lateFeeCap();
        boolean locked8 = false;
        LedgerAccountId income = null;
        int assessed = 0;
        int capped = 0;
        List<BnplPurchaseStore.BilledItem> items = purchases.billedItems(unitOfWork, obligation.id()).stream()
                .sorted(java.util.Comparator.comparing(BnplPurchaseStore.BilledItem::dueDate)).toList();
        for (BnplPurchaseStore.BilledItem item : items) {
            if (ChronoUnit.DAYS.between(item.dueDate(), today) < pinned.lateFeeDay()) {
                break; // Oldest first: every later instalment is younger.
            }
            if (charged.contains(item.instalment())
                    || !item.due(AllocationComponent.INTEREST).plus(item.due(AllocationComponent.PRINCIPAL)).isPositive()) {
                continue; // Charged already, or settled in time: never charged late.
            }
            Money room = cap.minus(total);
            if (!room.isPositive()) {
                break; // The cap is reached: nothing more is assessed on this agreement, ever.
            }
            Money amount = fee.compareTo(room) <= 0 ? fee : room;
            if (!locked8) {
                // (L8) The fees-due row and the currency's shared fee income row in one sorted call - after any wallet the
                // collection took under (P); nothing but inserts follows.
                income = book.operational(unitOfWork, AccountPurpose.BNPL_FEE_INCOME, obligation.currency());
                book.lockBalancesInOrder(unitOfWork, List.of(accounts.feesDue().id(), income));
                locked8 = true;
            }
            UUID id = ids.next();
            JournalEntryId entry = book.post(unitOfWork, new PostingCommand(
                    BnplFeeAssessment.postingKey(item.instalment(), BnplFeeKind.LATE), today, today, id.toString(),
                    List.of(new JournalLine(accounts.feesDue().id(), Direction.DEBIT, amount),
                            new JournalLine(income, Direction.CREDIT, amount))));
            BnplFeeAssessment assessment = new BnplFeeAssessment(id, obligation.id(), item.instalment(), BnplFeeKind.LATE,
                    today, amount, locked.agreement(), locked.agreementVersion(), entry, correlation);
            store.insertAssessment(unitOfWork, assessment);
            publishFee(unitOfWork, assessment, item);
            total = total.plus(amount);
            charged.add(item.instalment());
            assessed++;
            if (amount.compareTo(fee) < 0) {
                capped++;
            }
        }
        return new Assessed(assessed, capped);
    }

    // ------------------------------------------------------------------ the read

    /**
     * {@code owned}'s condition now - derived in the caller's snapshot on the database clock's business date in the pinned
     * zone; empty for an obligation no longer in servicing (DPD is never computed on a closed one). A read decides nothing.
     */
    public Optional<BnplDelinquencyState> read(Connection unitOfWork, BnplPurchaseStore.Owned owned) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(owned, "owned");
        if (owned.obligation().status() != BnplObligationStatus.ACTIVE) {
            return Optional.empty();
        }
        BnplTerms pinned = terms.version(unitOfWork, owned.agreement().termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        LocalDate today = LocalDate.ofInstant(store.databaseNow(unitOfWork), ZoneId.of(pinned.servicingZone()));
        return Optional.of(derive(unitOfWork, owned.obligation().id(), pinned, today));
    }

    // ------------------------------------------------------------------ the events

    /**
     * A change's event: the obligation, the business date, the condition after it (DPD, bucket, flag) and - for a bucket
     * change - the bucket before; the agreement version that judged it. RESTRICTED-FINANCIAL facts for the future
     * collections owner (Phase 13) - never a party, an amount, or anything a merchant consumer could read.
     */
    private void publishCondition(Connection unitOfWork, BnplObligation obligation, BnplConditionEvent change,
            String type) {
        EventPayload payload = EventPayload.of()
                .with("obligationId", obligation.id().value().toString())
                .with("product", obligation.product().name())
                .with("businessDate", change.businessDate().toString())
                .with("daysPastDue", Integer.toString(change.state().daysPastDue()))
                .with("bucket", change.state().bucketName())
                .with("defaulted", Boolean.toString(change.state().defaulted()))
                .with("agreementVersion", Integer.toString(change.agreementVersion()));
        if (change.kind() == BnplConditionKind.DELINQUENCY) {
            payload = payload.with("previousBucket", change.previousBucketName());
        }
        write(unitOfWork, obligation.id(), type, payload, change.correlation());
    }

    /**
     * {@code bnpl.BnplLateFeeAssessed} - the assessment, the instalment and its due date, the kind, the amount, the
     * business date, the entry and the agreement version; never a party.
     */
    private void publishFee(Connection unitOfWork, BnplFeeAssessment assessment, BnplPurchaseStore.BilledItem item) {
        EventPayload payload = EventPayload.of()
                .with("obligationId", assessment.obligation().value().toString())
                .with("assessmentId", assessment.id().toString())
                .with("instalmentId", assessment.instalment().toString())
                .with("dueDate", item.dueDate().toString())
                .with("kind", assessment.kind().name())
                .with("businessDate", assessment.businessDate().toString())
                .with("currency", assessment.amount().currency().code())
                .with("scale", Integer.toString(assessment.amount().scale()))
                .with("amountMinor", Long.toString(assessment.amount().minorUnits()))
                .with("journalEntryId", assessment.entry().value().toString())
                .with("agreementVersion", Integer.toString(assessment.agreementVersion()));
        write(unitOfWork, assessment.obligation(), LATE_FEE_ASSESSED_EVENT, payload, assessment.correlation());
    }

    private void write(Connection unitOfWork, BnplObligationId obligation, String type, EventPayload payload,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        obligation,
                        BnplAcceptances.OBLIGATION_AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
