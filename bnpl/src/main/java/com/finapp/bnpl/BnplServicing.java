package com.finapp.bnpl;

import com.finapp.creditmath.AllocationRules;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The servicing step - T-svc - its billing and the credit-balance applications (`P12-TSK-015`; {@code PHASE_12_PLAN.md}
 * sections 7.1, 7.2, 7.5 and 12.11 F2, F3a; lending's {@code LoanServicing} and {@code InstalmentBillings};
 * {@code INV-BNPL-01}, {@code INV-BNPL-05}, {@code INV-LND-02} and {@code INV-LND-04} by analogy, {@code INV-IDEM-02}). One
 * obligation per call, in <strong>one local transaction</strong> on the caller's unit of work. A system act with no door:
 * an instalment falls due because its date came, never because someone asked.
 *
 * <h2>The skeleton, in its fixed order under B4</h2>
 *
 * <ol>
 *   <li><strong>(B4)</strong> the obligation {@code FOR UPDATE} - the serialisation point of every servicing act on it; an
 *       obligation not {@code ACTIVE} is not serviced. No L0 (exposure does not move: billing reclassifies), no L6, no (P);
 *   <li>the database clock, read after the lock, and its business date in the pinned servicing zone - never an instance
 *       clock;
 *   <li><strong>the accrual gate</strong>: for pay-in-N every elapsed date of the accrual span - from the purchase's
 *       business date through the business date's eve, never past the final due date - with principal outstanding already
 *       accrued ({@link BnplAccruals#caughtUp}, `P12-TSK-017`), else nothing is written and the step answers
 *       {@code BEHIND_ACCRUAL}: T-acc runs first in its own transaction, so the shared {@code BNPL_INTEREST_INCOME} row is
 *       never held here;
 *   <li><strong>bill</strong> (F2): every unbilled instalment of the latest schedule version whose due date has come, oldest
 *       first - the interest exactly the period's posted accruals (pay-in-N; never the projection), the principal
 *       {@code least(not yet due, max(0, amount - interest))} and the last instalment all of it - dated its due date (a
 *       base-neutral reclassification: the scheduler down three days bills each at its own date), posted on the business
 *       date under {@code bnpl.billing:<instalment>}; each followed by the credit-balance application at its birth (F3a) and
 *       {@code bnpl.InstalmentDue};
 *   <li><strong>apply what is held</strong> (the Phase 11 gate's R2): credit still held beside something already due -
 *       the obligation's next catch-up application (F3a), so no arrears the customer's own held money covers is collected,
 *       aged or charged a fee; each application value-dated {@code max(today, floor)};
 *   <li><strong>collect</strong> (`P12-TSK-019`, {@link BnplCollections}): when the pinned terms collect automatically,
 *       what each billed instalment still has due, oldest first, from the customer's existing wallet alone -
 *       <strong>(P)</strong> its row {@code FOR UPDATE} after the obligation's own projection rows (written only by holders
 *       of B4, so the wait closes no cycle), its available balance under that lock - each attempt one
 *       {@code bnpl_collection_attempt} row ({@code UNIQUE (instalment_id, attempt_date)}), the money an
 *       {@code AUTO_COLLECTION} repayment through {@link BnplRepayments}' posting half (L8, F3, value-dated
 *       {@code max(today, floor)});
 *   <li>the steps later tasks add, in this fixed order after the collection, under the same lock: closure (`-018`),
 *       condition and late fee (`-020`); <strong>the closure is built by `P12-TSK-018`</strong> ({@link BnplClosures}): settled - every receivable zero, every instalment billed, pay-in-N
 *       every elapsed date with principal outstanding accrued - the credit balance refunded to the wallet under (P) and
 *       {@code CLOSED (REPAID)}; a closed obligation's permit is not re-stamped (nothing services it again);
 *   <li>the permit: the start of the next business date in the pinned zone, stamped by the database.
 * </ol>
 *
 * <h2>Why once (the ten-sweeper question)</h2>
 *
 * <p>B4 serialises every step on one obligation, each reading the billings after the lock; beneath it
 * {@code bnpl_billing UNIQUE (instalment_id)} and {@code UNIQUE (obligation_id, value_date)},
 * {@code bnpl_credit_balance_application UNIQUE (billing_id)} and {@code UNIQUE (obligation_id, catch_up_sequence)}; and the
 * ledger keys, whose replay - an entry with no row - is a defect the book refuses, never a second posting. Every ledger
 * account written is the obligation's own, written by nobody without B4. No JVM state.
 */
@RequiredArgsConstructor
public final class BnplServicing {

    /** F2's key prefix (T-pur's): {@code bnpl.billing:<instalment>}. */
    public static final String BILLING_KEY_PREFIX = BnplAcceptances.BILLING_KEY_PREFIX;

    @NonNull private final BnplServicingStore store;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplBook book;
    @NonNull private final BnplClosures closures;
    @NonNull private final BnplCollections collections;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ the claim

    /** The obligations due servicing - see {@link BnplServicingStore#claimDue}. */
    public List<BnplObligationId> claim(Connection unitOfWork, int limit, Duration retryCadence) {
        return store.claimDue(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(retryCadence, "retryCadence"));
    }

    // ------------------------------------------------------------------ T-svc

    /** One obligation's servicing step, on the caller's unit of work - a transaction of its own. */
    @SuppressWarnings("try") // The correlation Scope is used for its close side effect (the idiom).
    public ServicingOutcome service(Connection unitOfWork, BnplObligationId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        // (B4) The obligation - every servicing act on it serialises here.
        Optional<BnplServicingStore.Locked> found = store.lock(unitOfWork, id);
        if (found.isEmpty() || found.get().obligation().status() != BnplObligationStatus.ACTIVE) {
            return ServicingOutcome.notServiced();
        }
        BnplServicingStore.Locked locked = found.get();
        BnplObligation obligation = locked.obligation();
        // The database clock, read after the lock, in the pinned zone.
        Instant now = store.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(locked.servicingZone()));
        // The accrual gate: pay-in-N is serviced only on a record caught up - every elapsed date of its accrual span with
        // principal outstanding accrued (BnplAccruals, P12-TSK-017). T-svc never accrues: T-acc runs first, in its own
        // transaction, so the shared BNPL_INTEREST_INCOME row is never held here.
        if (!BnplAccruals.caughtUp(unitOfWork, store, purchases, book, locked, today)) {
            return ServicingOutcome.behindAccrual(obligation.product());
        }
        BnplTerms pinned = terms.version(unitOfWork, locked.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        if (!pinned.allocationEngine().name().equals(locked.allocationEngine())) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        AllocationRules rules = new AllocationRules(pinned.allocationEngine(), pinned.allocationSequence(),
                pinned.allocationOrder(), pinned.overpaymentTreatment());
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, id);
        BnplPurchaseStore.StoredSchedule schedule = store.latestSchedule(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        List<BnplPurchaseStore.StoredInstalment> due = dueUnbilled(schedule,
                purchases.billedItems(unitOfWork, id), today);
        BnplCreditBalanceApplications applications = new BnplCreditBalanceApplications(store, receivables, book, ids);
        BnplCreditBalanceApplications.Context context =
                new BnplCreditBalanceApplications.Context(locked, rules, accounts, today);

        int billed = 0;
        int appliedAtBilling = 0;
        boolean caughtUp;
        BnplCollections.Collected collected;
        boolean closed;
        Correlation flow = Correlation.startingWith(obligation.correlation())
                .causing(CausationId.of(obligation.correlation().value()));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow)) {
            if (!due.isEmpty() || receivables.creditHeld(unitOfWork, accounts).isPositive()) {
                // (L8) The obligation's own projection rows, in the ledger's one order, before the first entry.
                book.lockBalancesInOrder(unitOfWork, accounts.all().stream().map(account -> account.id()).toList());
            }
            Money notDue = receivables.principalNotDue(unitOfWork, accounts);
            for (BnplPurchaseStore.StoredInstalment instalment : due) {
                BnplBilling billing = bill(unitOfWork, locked, schedule, instalment, notDue, accounts, today);
                notDue = notDue.minus(billing.principal());
                billed++;
                Optional<BnplCreditBalanceApplication> applied = applications.atBilling(unitOfWork, context, billing.id());
                if (applied.isPresent()) {
                    appliedAtBilling++;
                }
                publish(unitOfWork, billing);
            }
            // What is held settles what is already due (the Phase 11 gate's R2) - after billing, before the collection
            // (-019), the closure (-018), the condition and the late fee (-020), which run after it under this lock.
            caughtUp = applications.catchUp(unitOfWork, context).isPresent();
            // The collection (`P12-TSK-019`): what each billed instalment still has due, from the customer's wallet alone
            // under (P), oldest first - after the application (held money first), before the closure.
            collected = collections.collect(unitOfWork, locked, pinned, rules, accounts, today, obligation.correlation());
            // The closure (`P12-TSK-018`): an obligation whose last receivable a billing's or a catch-up's application, or
            // this step's collection, brought to zero - and, pay-in-N, every elapsed date with principal outstanding
            // accrued - closes in this step: the credit balance refunded under (P) the wallet (the one the collection
            // holds, if it took it), CLOSED (REPAID), naming the collection's last repayment when one moved money. The
            // system actor is the sweep's scope.
            closed = closures.closeIfSettled(unitOfWork, new BnplClosures.Closing(locked, accounts, today,
                    collected.lastRepayment(), collected.walletHeld()),
                    com.finapp.platform.security.SecurityContext::require, obligation.correlation()).isPresent();
        }
        if (closed) {
            // A closed obligation is never serviced again: its permit stays where the claim put it, and nothing moves it.
            return new ServicingOutcome(ServicingOutcome.Kind.SERVICED, Optional.of(obligation.product()), billed,
                    appliedAtBilling, caughtUp, true, collected.attempts());
        }
        if (!store.restamp(unitOfWork, id, today.plusDays(1))) {
            throw new IllegalStateException("the locked obligation left servicing under its own row lock");
        }
        return new ServicingOutcome(ServicingOutcome.Kind.SERVICED, Optional.of(obligation.product()), billed,
                appliedAtBilling, caughtUp, false, collected.attempts());
    }

    /**
     * The unbilled instalments of {@code schedule} due by {@code today}, oldest first - none after the first not due. An
     * instalment is billed when a billing is dated its due date (`P12-TSK-021`: one billing per due date, and a refund's
     * schedule version carries its billed instalments as new rows beside the billings that name the earlier version's).
     */
    static List<BnplPurchaseStore.StoredInstalment> dueUnbilled(BnplPurchaseStore.StoredSchedule schedule,
            List<BnplPurchaseStore.BilledItem> billed, LocalDate today) {
        Set<LocalDate> billedDates = new HashSet<>();
        for (BnplPurchaseStore.BilledItem item : billed) {
            billedDates.add(item.dueDate());
        }
        List<BnplPurchaseStore.StoredInstalment> due = new ArrayList<>();
        for (BnplPurchaseStore.StoredInstalment instalment : schedule.instalments()) {
            if (billedDates.contains(instalment.dueDate())) {
                continue;
            }
            if (instalment.dueDate().isAfter(today)) {
                return due; // Not yet due: neither is any later one.
            }
            due.add(instalment);
        }
        return due;
    }

    // ------------------------------------------------------------------ F2

    /** Bills {@code instalment}: its row, and its entry when it bills something. */
    private BnplBilling bill(Connection unitOfWork, BnplServicingStore.Locked locked,
            BnplPurchaseStore.StoredSchedule schedule, BnplPurchaseStore.StoredInstalment instalment, Money notDue,
            BnplLedgerAccounts accounts, LocalDate today) {
        BnplObligationId obligation = locked.obligation().id();
        Money zero = Money.ofPersisted(0, instalment.amount().currency(), instalment.amount().scale());
        LocalDate periodStart = periodStart(schedule, instalment);
        // Pay-in-N: interest actually accrued in the period - never the projection; pay-in-4 is interest-free.
        Money interest = locked.accrualEngine().isPresent()
                ? store.periodInterest(unitOfWork, obligation, periodStart, instalment.dueDate(), zero)
                : zero;
        Money principal = principalDue(schedule, instalment, interest, notDue, zero);
        Optional<JournalEntryId> entry = Optional.empty();
        if (interest.plus(principal).isPositive()) {
            List<JournalLine> lines = new ArrayList<>();
            if (principal.isPositive()) {
                lines.add(new JournalLine(accounts.principalDue().id(), Direction.DEBIT, principal));
                lines.add(new JournalLine(accounts.principal().id(), Direction.CREDIT, principal));
            }
            if (interest.isPositive()) {
                lines.add(new JournalLine(accounts.interestDue().id(), Direction.DEBIT, interest));
                lines.add(new JournalLine(accounts.interestAccrued().id(), Direction.CREDIT, interest));
            }
            // Posted on the business date, valued at the due date; the reference is the billed instalment, so a second
            // attempt at it is the same request under the same key - a replay the book refuses as the defect it is.
            entry = Optional.of(book.post(unitOfWork, new PostingCommand(BILLING_KEY_PREFIX + instalment.id(), today,
                    instalment.dueDate(), instalment.id().toString(), lines)));
        }
        BnplBilling billing = new BnplBilling(ids.next(), instalment.id(), instalment.sequence(), obligation,
                schedule.id(), schedule.version(), schedule.engine(), locked.agreement(), locked.agreementVersion(),
                locked.accrualEngine(), periodStart, instalment.dueDate(), principal, interest, entry,
                locked.obligation().correlation());
        purchases.insertBilling(unitOfWork, billing);
        return billing;
    }

    /** The start of the period an instalment closes: the previous instalment's due date, the first the schedule's start. */
    static LocalDate periodStart(BnplPurchaseStore.StoredSchedule schedule, BnplPurchaseStore.StoredInstalment instalment) {
        LocalDate start = schedule.start();
        for (BnplPurchaseStore.StoredInstalment earlier : schedule.instalments()) {
            if (earlier.sequence() == instalment.sequence() - 1) {
                start = earlier.dueDate();
            }
        }
        return start;
    }

    /**
     * The principal an instalment bills (INV-BNPL-05): the last instalment all that is not yet due; every other
     * {@code least(notDue, max(0, amount - interest))} - for pay-in-4, whose amount is its principal, exactly that.
     */
    static Money principalDue(BnplPurchaseStore.StoredSchedule schedule, BnplPurchaseStore.StoredInstalment instalment,
            Money interest, Money notDue, Money zero) {
        Money remaining = notDue.isNegative() ? zero : notDue;
        if (instalment.sequence() == schedule.instalments().size()) {
            return remaining;
        }
        Money portion = instalment.amount().minus(interest);
        if (!portion.isPositive()) {
            return zero;
        }
        return portion.compareTo(remaining) <= 0 ? portion : remaining;
    }

    // ------------------------------------------------------------------ the event

    /**
     * {@code bnpl.InstalmentDue} - T-pur's shape: the instalment, its due date and what it made due (principal and
     * pay-in-N interest); the entry when it posted one.
     */
    private void publish(Connection unitOfWork, BnplBilling billing) {
        Money principal = billing.principal();
        EventPayload payload = EventPayload.of()
                .with("obligationId", billing.obligation().value().toString())
                .with("instalmentId", billing.instalment().toString())
                .with("sequence", Integer.toString(billing.sequence()))
                .with("dueDate", billing.dueDate().toString())
                .with("currency", principal.currency().code())
                .with("scale", Integer.toString(principal.scale()))
                .with("principalMinor", Long.toString(principal.minorUnits()))
                .with("interestMinor", Long.toString(billing.interest().minorUnits()));
        if (billing.entry().isPresent()) {
            payload = payload.with("journalEntryId", billing.entry().get().value().toString());
        }
        CorrelationId correlation = billing.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        BnplAcceptances.INSTALMENT_DUE_EVENT,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        billing.obligation(),
                        BnplAcceptances.OBLIGATION_AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
