package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.HOLDS;
import static com.finapp.lending.DisbursementWorld.REPAYMENT_STORE;
import static com.finapp.lending.DisbursementWorld.UNREACHABLE_WALLETS;
import static com.finapp.lending.DisbursementWorld.activate;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.advance;
import static com.finapp.lending.InstalmentBillingDatabaseTest.billings;
import static com.finapp.lending.InstalmentBillingDatabaseTest.daysTo;
import static com.finapp.lending.InstalmentBillingDatabaseTest.dues;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.projection;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.refused;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.assertSubledgerProves;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.held;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.lock;
import static com.finapp.lending.RepaymentDatabaseTest.lockableNoWait;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static com.finapp.lending.RepaymentDatabaseTest.today;
import static com.finapp.lending.RepaymentDatabaseTest.topUp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scheduled auto-collection against a real database (`P11-TSK-019`; {@code lending V015}, {@code PHASE_11_PLAN.md} sections
 * 7.1 T-svc, 7.4 and 12.5, section 13.2 scenario 2's sweeper half; ADR-0095 point 6, A21; {@code INV-IDEM-02},
 * {@code INV-LND-04}, {@code INV-BAL-04}): every figure read from the rows and the ledger, every case closing on the subledger
 * proof's arms ({@link RepaymentDatabaseTest#assertSubledgerProves}).
 *
 * <p>Loans are accepted under terms that collect automatically ({@link #collectingLoans} - activated for them, the suite's
 * default restored at once, so a loan keeps the terms it pinned), disbursed by the real T-dis to the world's wallet (which
 * then holds the principal) and serviced by the real T-svc, whose collection step runs the real {@link LoanCollections} and
 * {@link Repayments}' posting half over the real ledger, its {@link com.finapp.ledger.AvailableBalance} and its holds. A
 * smaller available balance is a hold (settled money unavailable, {@code INV-BAL-04}); a later business date is
 * {@link InstalmentBillingDatabaseTest#advance} - the fixture's clock, never a path.
 */
@Tag("database")
@DisplayName("scheduled auto-collection (P11-TSK-019)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class AutoCollectionDatabaseTest {

    // ------------------------------------------------------------------ scenario 2: two sweepers, one due date

    @Test
    @DisplayName("scenario 2 - two sweepers collect once: two servicing steps racing on one loan's due date - L3 serialises"
            + " them, the second finds the item attempted today and collects nothing: one attempt, one AUTO_COLLECTION"
            + " repayment of exactly the bill, one entry, one event; ten sweepers on another loan - the same")
    void twoSweepersCollectOnce() throws Exception {
        List<LoanId> loans = collectingLoans(2, 5_000_00, true);
        int[] racers = {2, 10};
        for (int round = 0; round < 2; round++) {
            LoanId loan = loans.get(round);
            backdate(loan, daysTo(loan, 1));
            List<LoanServicing.Outcome> steps = race(racers[round], index -> service(loan));
            long bill = billings(loan).get(0).billing().billed().minorUnits();
            assertThat(steps.stream().mapToInt(step -> step.collected(CollectionOutcome.COLLECTED)).sum())
                    .as("one step collected").isEqualTo(1);
            assertThat(steps).filteredOn(step -> step.billed() == 1)
                    .singleElement().as("the step that billed is the step that collected - billing first")
                    .satisfies(step -> assertThat(step.collected(CollectionOutcome.COLLECTED)).isEqualTo(1));
            assertThat(steps.stream().mapToInt(step -> step.collections().values().stream().mapToInt(Integer::intValue)
                    .sum()).sum()).as("and no other step attempted anything").isEqualTo(1);
            List<Attempt> attempts = attempts(loan);
            assertThat(attempts).hasSize(1);
            assertThat(attempts.get(0).outcome()).isEqualTo("COLLECTED");
            assertThat(attempts.get(0).due()).isEqualTo(bill);
            assertThat(attempts.get(0).collected()).isEqualTo(bill);
            assertThat(attempts.get(0).date()).isEqualTo(today());
            assertCollectedOnce(loan, bill);
            assertThat(-netDebit(walletOf(partyOf(loan))).minorUnits()).as("the wallet debited once")
                    .isEqualTo(5_000_00 + round * 100_00 - bill);
            assertThat(service(loan).collections()).as("a later step that day attempts nothing").isEmpty();
            assertSubledgerProves(loan);
        }
    }

    // ------------------------------------------------------------------ races on the due date

    @Test
    @DisplayName("the due date collects once whatever races: servicing steps racing the borrower's own repayment on the"
            + " due date, six rounds (a repayment beyond the bill, and one of half of it) - serialised on L3 in whatever"
            + " order: one attempt (COLLECTED for what was left, or NOTHING_DUE when the borrower's money paid it first),"
            + " the bill paid exactly, never more collected than due, the rest held - conserved")
    void theDueDateCollectsOnceWhateverRaces() throws Exception {
        List<LoanId> loans = collectingLoans(6, 4_600_00, true);
        for (int round = 0; round < loans.size(); round++) {
            LoanId loan = loans.get(round);
            backdate(loan, daysTo(loan, 1));
            long projected = projection(loan).get(0)[0] + projection(loan).get(0)[1];
            long amount = round < 3 ? projected + 50_00 : projected / 2;
            race(4, index -> index < 3 ? service(loan).billed() : repay(loan, key(), amount).repayment().hashCode());
            List<BilledItem> billed = billings(loan);
            assertThat(billed).hasSize(1);
            long bill = billed.get(0).billing().billed().minorUnits();
            List<Attempt> attempts = attempts(loan);
            assertThat(attempts).as("one attempt on the due date").hasSize(1);
            Attempt attempt = attempts.get(0);
            assertThat(attempt.outcome()).isIn("COLLECTED", "NOTHING_DUE");
            assertThat(attempt.collected()).as("never more than due").isLessThanOrEqualTo(bill);
            assertThat(billed.get(0).paid().minorUnits()).as("the bill paid exactly, never over-allocated").isEqualTo(bill);
            assertThat(due(loan)).isZero();
            assertThat(held(loan)).as("what came in less the bill is held").isEqualTo(amount + attempt.collected() - bill);
            assertThat(count("SELECT count(*) FROM lending.repayment_allocation a JOIN lending.repayment r"
                    + " ON r.id = a.repayment_id WHERE r.loan_id = ? AND r.kind = 'AUTO_COLLECTION'"
                    + " AND a.target <> 'DUE_ITEM'", loan.value())).as("a collection never holds credit").isZero();
            assertSubledgerProves(loan);
        }
    }

    @Test
    @DisplayName("billing and its credit-balance application precede the collection: in the due date's one step the bill"
            + " is billed, a credit balance the borrower paid ahead is applied to it, and only what is left is collected"
            + " from the wallet - COLLECTED for the rest, or NOTHING_DUE when the credit balance paid it all")
    void billingAndItsCreditApplicationPrecedeTheCollection() {
        List<LoanId> loans = collectingLoans(2, 4_800_00, true);
        for (int round = 0; round < 2; round++) {
            LoanId loan = loans.get(round);
            long projected = projection(loan).get(0)[0] + projection(loan).get(0)[1];
            long ahead = round == 0 ? projected / 2 : projected + 20_00;
            repay(loan, key(), ahead);
            backdate(loan, daysTo(loan, 1));
            LoanServicing.Outcome step = service(loan);
            assertThat(step.billed()).isEqualTo(1);
            assertThat(step.applied()).as("the credit balance applied first").isEqualTo(1);
            long bill = billings(loan).get(0).billing().billed().minorUnits();
            Attempt attempt = attempts(loan).get(0);
            if (round == 0) {
                assertThat(step.collected(CollectionOutcome.COLLECTED)).as("in the same step").isEqualTo(1);
                assertThat(attempt.due()).as("only what the credit balance left").isEqualTo(bill - ahead);
            } else {
                assertThat(step.collected(CollectionOutcome.NOTHING_DUE)).isEqualTo(1);
                assertThat(attempt.collected()).isZero();
            }
            assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
            assertSubledgerProves(loan);
        }
    }

    // ------------------------------------------------------------------ partial and all-or-nothing

    @Test
    @DisplayName("partial collection when allowed: a wallet with 100.00 available against a larger bill - PARTIAL, 100.00"
            + " collected (interest first, the pinned order), the rest still due, no CollectionFailed; the next business"
            + " date, the wallet topped up, the rest COLLECTED - one attempt per item and date")
    void partialCollectionWhenAllowed() {
        LoanId loan = collectingLoans(1, 5_000_00, true).get(0);
        UUID party = partyOf(loan);
        hold(walletOf(party), 4_900_00);
        backdate(loan, daysTo(loan, 1));
        LoanServicing.Outcome step = service(loan);
        assertThat(step.collected(CollectionOutcome.PARTIAL)).isEqualTo(1);
        BilledItem item = billings(loan).get(0);
        long bill = item.billing().billed().minorUnits();
        assertThat(item.outstanding().minorUnits()).isEqualTo(bill - 100_00);
        List<Attempt> attempts = attempts(loan);
        assertThat(attempts).extracting(Attempt::outcome).containsExactly("PARTIAL");
        assertThat(attempts.get(0).due()).isEqualTo(bill);
        assertThat(attempts.get(0).collected()).isEqualTo(100_00);
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation a JOIN lending.repayment r ON r.id ="
                + " a.repayment_id WHERE r.loan_id = ? AND a.component = 'INTEREST'", loan.value()))
                .as("interest first").isEqualTo(1);
        assertThat(events(loan, LoanCollections.FAILED_EVENT)).isZero();
        assertThat(service(loan).collections()).as("once a day").isEmpty();

        topUp(party, 1_000_00);
        advance(loan, 1);
        assertThat(service(loan).collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        attempts = attempts(loan);
        assertThat(attempts).extracting(Attempt::outcome).containsExactly("PARTIAL", "COLLECTED");
        assertThat(attempts.get(1).due()).as("what was left").isEqualTo(bill - 100_00);
        assertThat(attempts.get(1).date()).isEqualTo(today());
        assertThat(attempts.get(0).date()).isEqualTo(today().minusDays(1));
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertThat(events(loan, Repayments.RECEIVED_EVENT)).isEqualTo(2);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("all or nothing when not: the same wallet under terms without partial collection - INSUFFICIENT_FUNDS, no"
            + " repayment, no entry, lending.CollectionFailed naming the item and the outcome; the next business date,"
            + " topped up, the whole bill COLLECTED")
    void allOrNothingWhenNot() {
        LoanId loan = collectingLoans(1, 5_000_00, false).get(0);
        UUID party = partyOf(loan);
        hold(walletOf(party), 4_900_00);
        backdate(loan, daysTo(loan, 1));
        long walletBefore = netDebit(walletOf(party)).minorUnits();
        LoanServicing.Outcome step = service(loan);
        assertThat(step.collected(CollectionOutcome.INSUFFICIENT_FUNDS)).isEqualTo(1);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        assertThat(attempts(loan)).extracting(Attempt::outcome).containsExactly("INSUFFICIENT_FUNDS");
        assertThat(attempts(loan).get(0).due()).isEqualTo(bill);
        assertThat(autoRepayments(loan)).isZero();
        assertThat(netDebit(walletOf(party)).minorUnits()).as("nothing posted").isEqualTo(walletBefore);
        assertThat(events(loan, LoanCollections.FAILED_EVENT)).isEqualTo(1);
        String item = billings(loan).get(0).billing().id().toString();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE ? AND convert_from(payload, 'UTF8') LIKE ?", loan.value(),
                LoanCollections.FAILED_EVENT, "%\"dueItemId\":\"" + item + "\"%", "%\"outcome\":\"INSUFFICIENT_FUNDS\"%"))
                .isEqualTo(1);

        topUp(party, 1_000_00);
        advance(loan, 1);
        assertThat(service(loan).collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        assertThat(attempts(loan).get(1).collected()).isEqualTo(bill);
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a hold racing the collection never overdraws: three rounds of a wallet hold placed (under the wallet's"
            + " row lock, the ledger's own) racing the due date's step - serialised on L7 in either order: the hold first,"
            + " the collection takes what is left (PARTIAL); the collection first, a hold beyond what is left is refused;"
            + " the available balance never below zero, what was collected and held never more than the wallet held")
    void aHoldRacingTheCollectionNeverOverdraws() throws Exception {
        List<LoanId> loans = collectingLoans(3, 5_000_00, true);
        for (LoanId loan : loans) {
            UUID party = partyOf(loan);
            LedgerAccountId wallet = walletOf(party);
            long principal = -netDebit(wallet).minorUnits();
            backdate(loan, daysTo(loan, 1));
            List<String> outcomes = race(2, index -> {
                if (index == 0) {
                    return service(loan).collections().keySet().toString();
                }
                try {
                    hold(wallet, principal - 200_00);
                    return "HELD";
                } catch (com.finapp.ledger.HoldExceedsAvailableBalanceException refused) {
                    return "REFUSED";
                }
            });
            long collected = attempts(loan).get(0).collected();
            long held = count("SELECT coalesce(sum(amount_minor), 0) FROM ledger.hold WHERE ledger_account_id = ?"
                    + " AND status = 'ACTIVE'", wallet.value());
            long settled = -netDebit(wallet).minorUnits();
            assertThat(settled).isEqualTo(principal - collected);
            assertThat(settled - held).as("available never below zero").isGreaterThanOrEqualTo(0);
            if (outcomes.contains("HELD")) {
                assertThat(collected).as("the collection took what the hold left").isLessThanOrEqualTo(200_00);
            } else {
                assertThat(collected).as("the collection came first and took the bill")
                        .isEqualTo(billings(loan).get(0).billing().billed().minorUnits());
            }
            assertSubledgerProves(loan);
        }
    }

    // ------------------------------------------------------------------ failures and retries

    @Test
    @DisplayName("insufficient funds records and retries tomorrow: nothing available - INSUFFICIENT_FUNDS, nothing posted;"
            + " the same day nothing more; each later business date a new attempt - a suspended wallet or none at all"
            + " WALLET_NOT_POSTABLE - until a top-up is COLLECTED: one row per item and date, never two")
    void insufficientFundsRecordsAndRetriesTomorrow() {
        LoanId loan = collectingLoans(1, 5_000_00, true).get(0);
        UUID party = partyOf(loan);
        LedgerAccountId wallet = walletOf(party);
        hold(wallet, 5_000_00);
        backdate(loan, daysTo(loan, 1));
        service(loan);
        assertThat(service(loan).collections()).as("the same day: nothing more").isEmpty();
        advance(loan, 1);
        service(loan);
        advance(loan, 1);
        asOwner("UPDATE ledger.ledger_account SET status = 'POSTING_SUSPENDED' WHERE id = '" + wallet.value() + "'");
        try {
            service(loan);
        } finally {
            asOwner("UPDATE ledger.ledger_account SET status = 'ACTIVE' WHERE id = '" + wallet.value() + "'");
        }
        advance(loan, 1);
        UNREACHABLE_WALLETS.add(party);
        try {
            service(loan);
        } finally {
            UNREACHABLE_WALLETS.remove(party);
        }
        assertThat(autoRepayments(loan)).as("nothing collected yet").isZero();
        topUp(party, 1_000_00);
        advance(loan, 1);
        service(loan);
        List<Attempt> attempts = attempts(loan);
        assertThat(attempts).extracting(Attempt::outcome).containsExactly("INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS",
                "WALLET_NOT_POSTABLE", "WALLET_NOT_POSTABLE", "COLLECTED");
        assertThat(attempts).extracting(Attempt::date).doesNotHaveDuplicates()
                .containsExactly(today().minusDays(4), today().minusDays(3), today().minusDays(2), today().minusDays(1),
                        today());
        assertThat(events(loan, LoanCollections.FAILED_EVENT)).isEqualTo(4);
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("never more than due: a wallet holding many times the bill - exactly the bill collected, nothing held as"
            + " credit; every line of the collection on the item it was attempted on; two bills due at once each collected"
            + " exactly, oldest first")
    void neverMoreThanDue() {
        List<LoanId> loans = collectingLoans(2, 5_000_00, false);
        LoanId loan = loans.get(0);
        backdate(loan, daysTo(loan, 1));
        service(loan);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        assertThat(attempts(loan).get(0).collected()).isEqualTo(bill);
        assertThat(held(loan)).isZero();
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation a JOIN lending.repayment r"
                + " ON r.id = a.repayment_id WHERE r.loan_id = ? AND (a.target <> 'DUE_ITEM' OR a.billing_id <> ?)",
                loan.value(), billings(loan).get(0).billing().id())).isZero();
        assertThat(-netDebit(walletOf(partyOf(loan))).minorUnits()).isEqualTo(5_000_00 - bill);

        // Two bills due at once (a catch-up): each collected exactly, oldest first.
        LoanId two = loans.get(1);
        backdate(two, daysTo(two, 2));
        InstalmentBillingDatabaseTest.serviceUntilCaughtUp(two);
        List<BilledItem> items = billings(two);
        assertThat(items).hasSize(2);
        assertThat(attempts(two)).extracting(Attempt::outcome).containsExactly("COLLECTED", "COLLECTED");
        assertSubledgerProves(loan);
        assertSubledgerProves(two);
    }

    @Test
    @DisplayName("oldest first under all-or-nothing: an older bill past due and a younger one due today, the wallet short"
            + " of the older - both INSUFFICIENT_FUNDS, nothing collected (a younger collection would be allocated to the"
            + " older); topped up, both COLLECTED the next business date, oldest first")
    void anOlderUnpaidBillComesFirst() {
        LoanId loan = collectingLoans(1, 5_000_00, false).get(0);
        UUID party = partyOf(loan);
        LedgerAccountId wallet = walletOf(party);
        hold(wallet, 5_000_00);
        backdate(loan, daysTo(loan, 1));
        service(loan);
        List<LocalDate> dues = dues(loan);
        advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(0), dues.get(1))));
        // The first bill was missed and is past due; now the wallet holds a cent less than it.
        topUp(party, billings(loan).get(0).billing().billed().minorUnits() - 1);
        service(loan);
        List<BilledItem> items = billings(loan);
        assertThat(items).hasSize(2);
        List<Attempt> today = attempts(loan).stream().filter(attempt -> attempt.date().equals(today())).toList();
        assertThat(today).extracting(Attempt::outcome).containsExactly("INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS");
        assertThat(autoRepayments(loan)).isZero();
        topUp(party, 2_000_00);
        advance(loan, 1);
        service(loan);
        List<Attempt> last = attempts(loan).stream().filter(attempt -> attempt.date().equals(today())).toList();
        assertThat(last).extracting(Attempt::outcome).containsExactly("COLLECTED", "COLLECTED");
        assertThat(billings(loan)).allSatisfy(item -> assertThat(item.outstanding().isZero()).isTrue());
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("an agreement without auto-collection is not collected: the suite's default terms - the bill billed and"
            + " left due, no attempt, no repayment, the wallet untouched")
    void anAgreementWithoutAutoCollectionIsNotCollected() {
        LoanId loan = disbursed(5_000_00, 12);
        backdate(loan, daysTo(loan, 1));
        LoanServicing.Outcome step = service(loan);
        assertThat(step.billed()).isEqualTo(1);
        assertThat(step.collections()).isEmpty();
        assertThat(attempts(loan)).isEmpty();
        assertThat(autoRepayments(loan)).isZero();
        assertThat(billings(loan).get(0).outstanding().isPositive()).isTrue();
        assertThat(-netDebit(walletOf(partyOf(loan))).minorUnits()).isEqualTo(5_000_00);
    }

    // ------------------------------------------------------------------ closure, atomicity, lock order

    @Test
    @DisplayName("a collection that settles the loan closes it: a six-month loan collected on each due date - the last"
            + " collection closes the loan REPAID in its step, the closure naming that repayment, its RepaymentReceived"
            + " closed; servicing never claims it again")
    void aCollectionThatSettlesTheLoanClosesIt() {
        LoanId loan = collectingLoans(1, 3_000_00, 6, true).get(0);
        topUp(partyOf(loan), 500_00);
        List<LocalDate> dues = dues(loan);
        LoanServicing.Outcome step = null;
        for (int j = 0; j < dues.size(); j++) {
            if (j == 0) {
                backdate(loan, daysTo(loan, 1));
            } else {
                advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(j - 1), dues.get(j))));
            }
            step = service(loan);
            assertThat(step.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
            assertThat(step.closed()).as("closed only by the last").isEqualTo(j == dues.size() - 1);
        }
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        String last = scalar("SELECT repayment_id::text FROM lending.collection_attempt WHERE loan_id = ?"
                + " ORDER BY attempt_date DESC LIMIT 1", loan.value());
        assertThat(scalar("SELECT repayment_id::text FROM lending.loan_closure WHERE loan_id = ?", loan.value()))
                .as("the closure names the collection that settled it").isEqualTo(last);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE '%\"closed\":\"true\"%'", loan.value(),
                Repayments.RECEIVED_EVENT)).isEqualTo(1);
        assertThat(service(loan).kind()).isEqualTo(LoanServicing.Outcome.Kind.NOT_SERVICED);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("the attempt, the repayment, its rows, the entry and the events are one transaction with the step: an"
            + " instance dying after the collection's event row leaves none of them - nor the billing - and the step"
            + " re-driven collects once")
    void aCrashMidStepLeavesNothing() {
        LoanId loan = collectingLoans(1, 5_000_00, true).get(0);
        backdate(loan, daysTo(loan, 1));
        OutboxWriter<Connection> crashing = (unitOfWork, envelope, payload, mediaType) -> {
            new JdbcOutboxWriter().write(unitOfWork, envelope, payload, mediaType);
            if (envelope.eventType().equals(Repayments.RECEIVED_EVENT)) {
                throw new IllegalStateException("the instance died after the collection's event row");
            }
        };
        LoanServicing dying = new LoanServicing(DisbursementWorld.LOAN_STORE, new JdbcLoanServicingStore(),
                DisbursementWorld.TERMS_STORE, DisbursementWorld.LEDGER_ACCOUNTS, DisbursementWorld.BALANCES,
                DisbursementWorld.POSTINGS, DisbursementWorld.IDS, DisbursementWorld.BILLINGS, DisbursementWorld.CLOSURES,
                DisbursementWorld.collections(REPAYMENT_STORE, crashing));
        assertThatThrownBy(() -> service(dying, loan)).hasMessageContaining("instance died");
        assertThat(attempts(loan)).isEmpty();
        assertThat(autoRepayments(loan)).isZero();
        assertThat(billings(loan)).as("the step's billing rolled back with it").isEmpty();
        assertThat(-netDebit(walletOf(partyOf(loan))).minorUnits()).isEqualTo(5_000_00);
        service(loan);
        assertCollectedOnce(loan, billings(loan).get(0).billing().billed().minorUnits());
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("the collection takes the loan, then the wallet: behind a transaction holding the wallet's row (L7) the"
            + " step waits there holding the loan (L3) - the order every wallet writer keeps")
    void collectionTakesTheLoanThenTheWallet() throws Exception {
        LoanId loan = collectingLoans(1, 5_000_00, true).get(0);
        backdate(loan, daysTo(loan, 1));
        LedgerAccountId wallet = walletOf(partyOf(loan));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            lock(holder, "SELECT 1 FROM ledger.ledger_account WHERE id = ? FOR UPDATE", wallet.value());
            Future<LoanServicing.Outcome> step = pool.submit(() -> service(loan));
            assertThat(awaitBlockedBy(pid(holder)).query()).as("waits on the wallet's row (L7)").contains("ledger_account");
            assertThat(lockableNoWait("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE NOWAIT", loan.value()))
                    .as("holding the loan while it waits").isFalse();
            holder.rollback();
            assertThat(step.get(60, TimeUnit.SECONDS).collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("the attempts are held for every writer: a second attempt on an item and date refused (the unique); an"
            + " attempt under terms that do not collect, a partial one where partial collection is not allowed, one whose"
            + " figures do not match the item, one naming a repayment that holds credit - refused; an AUTO_COLLECTION"
            + " repayment without its attempt refused at commit; the rows immutable and never truncated")
    void theAttemptsAreHeldForEveryWriter() throws Exception {
        List<LoanId> loans = collectingLoans(2, 5_000_00, false);
        LoanId loan = loans.get(0);
        backdate(loan, daysTo(loan, 1));
        service(loan);
        String item = billings(loan).get(0).billing().id().toString();
        refusedAlone(rawAttempt(loan, item, "NOTHING_DUE", 0, 0, null), "23505", "collection_attempt_item_date_unique");

        LoanId other = loans.get(1);
        hold(walletOf(partyOf(other)), 5_000_00);
        backdate(other, daysTo(other, 1));
        service(other);
        BilledItem unpaid = billings(other).get(0);
        String otherItem = unpaid.billing().id().toString();
        long bill = unpaid.billing().billed().minorUnits();
        asOwner("DELETE FROM lending.collection_attempt WHERE loan_id = '" + other.value() + "'");
        refusedAlone(rawAttempt(other, otherItem, "PARTIAL", bill, 1, null), "P0001", "partial collection");
        refusedAlone(rawAttempt(other, otherItem, "INSUFFICIENT_FUNDS", bill - 1, 0, null), "P0001",
                "what its item had due");
        String repayment = UUID.randomUUID().toString();
        long interest = unpaid.billing().interestDue().minorUnits();
        long principal = unpaid.billing().principalDue().minorUnits();
        refusedAlone("BEGIN; " + rawAutoRepayment(other, repayment, otherItem, bill + 1, List.of(
                "DUE_ITEM:" + otherItem + ":INTEREST:" + interest, "DUE_ITEM:" + otherItem + ":PRINCIPAL:" + principal,
                "CREDIT_BALANCE:::1")) + " " + rawAttempt(other, otherItem, "COLLECTED", bill + 1, bill + 1, repayment)
                + " COMMIT;", "P0001", "pays only its billed item");
        refusedAlone("BEGIN; " + rawAutoRepayment(other, UUID.randomUUID().toString(), otherItem, interest, List.of(
                "DUE_ITEM:" + otherItem + ":INTEREST:" + interest)) + " COMMIT;", "P0001",
                "named by its collection attempt");
        LoanId plain = disbursed(5_000_00, 12);
        backdate(plain, daysTo(plain, 1));
        service(plain);
        String plainItem = billings(plain).get(0).billing().id().toString();
        long plainBill = billings(plain).get(0).billing().billed().minorUnits();
        refusedAlone(rawAttempt(plain, plainItem, "INSUFFICIENT_FUNDS", plainBill, 0, null), "P0001",
                "collect automatically");

        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            refused(app, "UPDATE lending.collection_attempt SET outcome = 'NOTHING_DUE' WHERE loan_id = '" + loan.value()
                    + "'", "42501", "");
            refused(app, "DELETE FROM lending.collection_attempt", "42501", "");
            refused(migrator, "UPDATE lending.collection_attempt SET collected_minor = 1 WHERE loan_id = '"
                    + loan.value() + "'", "P0001", "immutable");
            refused(migrator, "DELETE FROM lending.collection_attempt WHERE loan_id = '" + loan.value() + "'", "P0001",
                    "immutable");
            refused(migrator, "TRUNCATE lending.collection_attempt", "P0001", "");
        }
    }

    // ------------------------------------------------------------------ the world

    /**
     * {@code n} fresh parties' loans of {@code minor} over twelve months, accepted under terms that collect automatically
     * ({@code partial} or all-or-nothing) and disbursed to their wallets; the suite's default terms (no auto-collection)
     * restored at once - each loan keeps the version it pinned.
     */
    static List<LoanId> collectingLoans(int n, long minor, boolean partial) {
        return collectingLoans(n, minor, 12, partial);
    }

    /** As {@link #collectingLoans(int, long, boolean)}, over {@code term} months. */
    static List<LoanId> collectingLoans(int n, long minor, int term, boolean partial) {
        activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").autoCollection(true).partialCollection(partial)
                .build());
        try {
            List<LoanId> loans = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                loans.add(disbursed(minor + i * 100_00, term));
            }
            return loans;
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
    }

    /** A hold making {@code minor} of the wallet's settled money unavailable ({@code INV-BAL-04}). */
    static void hold(LedgerAccountId wallet, long minor) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation()).causing(CausationId.of("p11t19")))) {
            inOneTransaction(uow -> HOLDS.place(uow, wallet, eur(minor)));
        }
    }

    /** One attempt and one AUTO_COLLECTION repayment of {@code minor}: one entry, one event, the bill paid. */
    private static void assertCollectedOnce(LoanId loan, long minor) {
        assertThat(attempts(loan)).hasSize(1);
        assertThat(autoRepayments(loan)).isEqualTo(1);
        assertThat(count("SELECT amount_minor FROM lending.repayment WHERE loan_id = ? AND kind = 'AUTO_COLLECTION'",
                loan.value())).isEqualTo(minor);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE 'ledger.post:"
                + Repayments.POSTING_KEY_PREFIX + "%' AND reference = ?", loan.value().toString())).isEqualTo(1);
        assertThat(events(loan, Repayments.RECEIVED_EVENT)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE '%\"kind\":\"AUTO_COLLECTION\"%'", loan.value(),
                Repayments.RECEIVED_EVENT)).isEqualTo(1);
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertThat(scalar("SELECT request_key FROM lending.repayment WHERE loan_id = ? AND kind = 'AUTO_COLLECTION'",
                loan.value())).as("the attempt's natural key")
                .isEqualTo(billings(loan).get(0).billing().id() + ":" + today());
    }

    record Attempt(String outcome, long due, long collected, LocalDate date, String repayment) {}

    static List<Attempt> attempts(LoanId loan) {
        List<Attempt> rows = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT a.outcome, a.due_minor, a.collected_minor, a.attempt_date, a.repayment_id::text"
                        + " FROM lending.collection_attempt a JOIN lending.instalment_billing b ON b.id = a.due_item_id"
                        + " WHERE a.loan_id = ? ORDER BY a.attempt_date, b.due_date")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(new Attempt(row.getString(1), row.getLong(2), row.getLong(3),
                            row.getObject(4, LocalDate.class), row.getString(5)));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return rows;
    }

    static long autoRepayments(LoanId loan) {
        return count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'AUTO_COLLECTION'", loan.value());
    }

    /** An application-role collection attempt of {@code loan} as raw SQL, otherwise well formed (today, EUR 2). */
    static String rawAttempt(LoanId loan, String item, String outcome, long due, long collected, String repayment) {
        return "INSERT INTO lending.collection_attempt (id, loan_id, due_item_id, attempt_date, outcome, currency, scale,"
                + " due_minor, collected_minor, repayment_id, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                + loan.value() + "', '" + item + "', lending.business_date(statement_timestamp(), 'UTC'), '" + outcome
                + "', 'EUR', 2, " + due + ", " + collected + ", " + (repayment == null ? "NULL" : "'" + repayment + "'")
                + ", statement_timestamp(), 'p11t19-raw');";
    }

    /** An AUTO_COLLECTION repayment of {@code loan} under the attempt's key, with rows {@code TARGET:billing:COMPONENT:minor}. */
    static String rawAutoRepayment(LoanId loan, String id, String item, long amount, List<String> rows) {
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                loan.value());
        StringBuilder sql = new StringBuilder("INSERT INTO lending.repayment (id, loan_id, kind, request_key, currency,"
                + " scale, amount_minor, agreement_id, agreement_version, allocation_engine_version, journal_entry_id,"
                + " value_date, created_at, correlation_id) VALUES ('" + id + "', '" + loan.value() + "', 'AUTO_COLLECTION', '"
                + item + ":' || lending.business_date(statement_timestamp(), 'UTC'), 'EUR', 2, " + amount + ", '" + agreement
                + "', 1, 1, gen_random_uuid(), lending.business_date(statement_timestamp(), 'UTC'), statement_timestamp(),"
                + " 'p11t19-raw');");
        List<String> values = new ArrayList<>();
        int ordinal = 0;
        for (String row : rows) {
            String[] parts = row.split(":", -1);
            values.add("(gen_random_uuid(), '" + id + "', " + (++ordinal) + ", '" + parts[0] + "', "
                    + (parts[1].isBlank() ? "NULL" : "'" + parts[1] + "'") + ", "
                    + (parts[2].isBlank() ? "NULL" : "'" + parts[2] + "'") + ", 'EUR', 2, " + parts[3].strip() + ")");
        }
        return sql.append(" INSERT INTO lending.repayment_allocation (id, repayment_id, ordinal, target, billing_id,"
                + " component, currency, scale, amount_minor) VALUES ").append(String.join(", ", values)).append(";")
                .toString();
    }
}
