package com.finapp.app.lending;

import static com.finapp.app.lending.LendingStormDatabaseTest.allRaw;
import static com.finapp.app.lending.LendingStormDatabaseTest.bothAtOnce;
import static com.finapp.app.lending.LendingStormDatabaseTest.money;
import static com.finapp.app.lending.LendingStormWorld.APPLICATIONS;
import static com.finapp.app.lending.LendingStormWorld.EUR;
import static com.finapp.app.lending.LendingStormWorld.LOANS;
import static com.finapp.app.lending.LendingStormWorld.OFFERS;
import static com.finapp.app.lending.LendingStormWorld.OPERATOR_LOANS;
import static com.finapp.app.lending.LendingStormWorld.count;
import static com.finapp.app.lending.LendingStormWorld.judged;
import static com.finapp.app.lending.LendingStormWorld.key;
import static com.finapp.app.lending.LendingStormWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.LendingStormCredit;
import com.finapp.app.lending.LendingStormDatabaseTest.Account;
import com.finapp.app.lending.LendingStormDatabaseTest.AccountKind;
import com.finapp.app.lending.LendingStormDatabaseTest.Borrower;
import com.finapp.app.lending.LendingStormDatabaseTest.Instance;
import com.finapp.app.lending.LendingStormDatabaseTest.Role;
import com.finapp.app.lending.LendingStormWorld.Answer;
import com.finapp.app.lending.LendingStormWorld.Tally;
import com.finapp.identity.RoleName;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanTerms;
import com.finapp.lending.LoanTermsStore;
import com.finapp.lending.LoanTermsVersionId;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.platform.outbox.EventPublisher;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.outbox.PendingEvent;
import com.finapp.platform.outbox.RetryPolicy;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The lending storm's catalogue and crash points (`P11-TST-001`, the design's sections 16.2 and 21.5): the ten mandatory
 * scenarios and the contention scenes, each made to happen ON PURPOSE across both instances at a barrier - chance does
 * not find races, choreography does - and each followed by the full census; then the twelve crash points, each re-driven
 * by the other instance, each followed by the census. The coverage ledger is incremented only from ROWS, never from the
 * storm's intentions. Nothing about a raced pair is asserted before the census has read it.
 */
@SuppressWarnings("try") // Scopes and faults are used for their close side effect (the established idiom).
final class LendingStormCatalogue {

    private static final Duration PATIENCE = Duration.ofSeconds(90);

    /** Scenario 2's auto-collected accounts, made while the terms in force collect. */
    private List<Aged> collecting = List.of();

    private final LendingStormDatabaseTest storm;
    private final Tally tally;
    private final LendingStormWorld world;

    LendingStormCatalogue(LendingStormDatabaseTest storm) {
        this.storm = storm;
        this.tally = storm.tally();
        this.world = storm.world();
    }

    private Instance a() {
        return storm.a();
    }

    private Instance b() {
        return storm.b();
    }

    // ================================================================== the scenes

    void run() throws Exception {
        lastCapital();
        String[] treasurers = storm.treasurers();
        world.tranche(a().door(), b().door(), treasurers[0], treasurers[1], 600_000_00, true, tally);
        // Scenario 2's collected accounts under the terms in force (v4 collects automatically); then a version that does
        // not, so the scenes' instalments stay due for the repayments racing on them.
        collecting = agedLoans(3, 0);
        String[] officers = storm.officers();
        world.activateTerms(b().door(), a().door(), officers[1], officers[0],
                LendingStormWorld.terms("PERSONAL_LOAN", "UTC", false, "0.0849", "v5 for the catalogue"), tally);
        sameDisbursementOnBothInstances();
        tenDispatchersOneWithdrawal();
        sameRepaymentKeyOnBothInstances();
        twoRepaymentsOnOneInstalment();
        accrualRunOnBothInstances();
        waiverAcrossInstances();
        lostResponseConcludedByInquiry();
        reversalAfterAllocation();
        payoffRacingARepayment();
        amendmentUnderARace();
        payoffBesideTheDelinquencyWorker();
        exposureRace();
        drawsRacingTheLimit();
        closureRacingADraw();
        acceptanceRacingExpiry();
        standingLostBeforeDisbursement();
        selfDealingAndOutOfRole();
        capitalThatNeverArrives();
    }

    /** Scenario 1: one loan's disbursement fired on A and B at a barrier, beside both instances' own sweeps - five loans. */
    private void sameDisbursementOnBothInstances() throws Exception {
        for (int i = 0; i < 5; i++) {
            Borrower borrower = freshBorrower(false);
            String loan = acceptedLoan(borrower, 2_500_00 + 10_00L * i, 10, false, true);
            List<Object> raced = bothAtOnce(() -> disburse(a(), loan), () -> disburse(b(), loan));
            awaitStatus(loan, "ACTIVE");
            for (Object answer : raced) {
                if (answer instanceof Throwable failure) {
                    tally.unexplained("a direct disbursement failed: " + failure.getClass().getSimpleName());
                }
            }
            if (count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    "ledger.post:lending.disbursement:" + loan) == 1 && count("SELECT count(*) FROM lending.loan_event"
                    + " WHERE loan_id = ?::uuid AND to_status = 'ACTIVE'", loan) == 1) {
                storm.coverage("scenario 1 one disbursement on both instances", 1);
            }
        }
        censusAfter("scenario 1, one disbursement on both instances");
    }

    private Object disburse(Instance on, String loan) {
        try (var actor = com.finapp.platform.security.SecurityContext.enterSystem();
                var flow = com.finapp.platform.correlation.CorrelationContext.enter(LendingStormWorld.flow())) {
            return on.transactions().inTransaction(uow -> on.beans().getBean(com.finapp.lending.LoanDisbursements.class)
                    .disburse(uow, com.finapp.lending.LoanId.of(UUID.fromString(loan)),
                            com.finapp.platform.security.SecurityContext.require()));
        }
    }

    /** Scenario 1's payout half: ten payout sweepers - five on each instance - racing one payout: one withdrawal, one send. */
    private void tenDispatchersOneWithdrawal() throws Exception {
        Borrower borrower = freshBorrower(true);
        String loan = acceptedLoan(borrower, 2_400_00, 10, true, true);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instance on = i % 2 == 0 ? a() : b();
            racers.add(() -> on.payoutSchedule().sweepOnce());
        }
        awaitStatus(loan, "ACTIVE");
        allRaw(racers);
        censusAfter("scenario 1, ten dispatchers");
        String withdrawal = scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + loan);
        long sends = withdrawal == null ? 0 : storm.scheme().headerValues(SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                "Idempotency-Key").stream().filter(withdrawal::equals).count();
        if (count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?", "lending:" + loan) == 1
                && sends == 1) {
            storm.coverage("scenario 1 ten dispatchers one withdrawal", 1);
        }
    }

    /**
     * Scenario 2: one repayment key on A and B at once, then replayed - one repayment; and an auto-collected account
     * serviced on A and B at a barrier - one attempt for the day.
     */
    private void sameRepaymentKeyOnBothInstances() throws Exception {
        List<Aged> keyed = agedLoans(5, 0);
        for (int i = 0; i < 5; i++) {
            Aged aged = keyed.get(i);
            serviceOn(a(), aged.loan());
            String repaymentKey = key();
            String body = money(40_00 + i);
            String path = LOANS + "/" + aged.loan() + "/repayments";
            List<Object> raced = bothAtOnce(() -> a().door().post(path, body, aged.token(), repaymentKey),
                    () -> b().door().post(path, body, aged.token(), repaymentKey));
            for (Object answer : raced) {
                judged(tally, "scenario 2 repayment", answer(answer), "409 api.IdempotencyInProgress");
            }
            Answer replayed = b().door().post(path, body, aged.token(), repaymentKey);
            judged(tally, "scenario 2 replay", replayed, "409 api.IdempotencyInProgress");
            if (count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?::uuid AND kind = 'CUSTOMER'",
                    aged.loan()) == 1) {
                storm.coverage("scenario 2 one repayment key on both instances", 1);
            }
        }
        censusAfter("scenario 2, one key on both instances");
        for (int i = 0; i < 3; i++) {
            Aged aged = collecting.get(i);
            List<Callable<Object>> racers = new ArrayList<>();
            for (int r = 0; r < 6; r++) {
                Instance on = r % 2 == 0 ? a() : b();
                racers.add(() -> serviceOn(on, aged.loan()));
            }
            allRaw(racers);
            if (count("SELECT count(*) FROM lending.collection_attempt WHERE loan_id = ?::uuid", aged.loan()) == 1) {
                storm.coverage("scenario 2 auto-collection serviced on both instances", 1);
            }
        }
        censusAfter("scenario 2, auto-collection on both instances");
    }

    /** Scenario 3: two repayment keys on one instalment (A, B), an auto-collection step and a payoff at a barrier. */
    private void twoRepaymentsOnOneInstalment() throws Exception {
        List<Aged> instalments = agedLoans(10, 0);
        for (int i = 0; i < 10; i++) {
            boolean withPayoff = i % 3 == 0;
            Aged aged = instalments.get(i);
            serviceOn(a(), aged.loan());
            String path = LOANS + "/" + aged.loan() + "/repayments";
            Account account = new Account(aged.loan(), aged.borrower(), AccountKind.WALLET_LOAN, 0, Role.PLAIN);
            List<Callable<Object>> racers = List.of(
                    () -> a().door().post(path, money(30_00), aged.token(), key()),
                    () -> b().door().post(path, money(35_00), aged.token(), key()),
                    () -> serviceOn(b(), aged.loan()),
                    () -> withPayoff ? storm.payOffOn(a(), account) : a().door().get(LOANS + "/" + aged.loan(),
                            aged.token()));
            // Every other race under a PLANTED projection drift (P5's reach): the owner moves the ledger's projection of
            // the loan's interest due off its journal lines while the repayments allocate - the due state is derived
            // from the rows and the lines, never the projection, so nothing may notice; the drift is put back after.
            boolean drifted = i % 2 == 0;
            if (drifted) {
                LendingStormFaults.plantProjectionDrift(aged.loan(), "LOAN_INTEREST_DUE", 1_00);
            }
            List<Object> answers;
            try {
                answers = allRaw(racers);
            } finally {
                if (drifted) {
                    LendingStormFaults.repairProjection(aged.loan(), "LOAN_INTEREST_DUE", 1_00);
                }
            }
            for (Object answer : answers) {
                if (answer instanceof Answer response) {
                    judged(tally, "scenario 3", response, "409 lending.LoanNotRepayable", "409 lending.LoanClosed",
                            "409 lending.PayoffQuoteStale", "422 lending.InsufficientFunds");
                } else if (answer instanceof Throwable failure) {
                    tally.unexplained("scenario 3: a step failed - " + failure.getClass().getSimpleName());
                }
            }
            if (count("SELECT count(*) FROM (SELECT a.billing_id FROM lending.repayment_allocation a JOIN lending.repayment r"
                    + " ON r.id = a.repayment_id WHERE r.loan_id = ?::uuid AND a.billing_id IS NOT NULL GROUP BY"
                    + " a.billing_id HAVING count(DISTINCT r.id) >= 2) shared", aged.loan()) >= 1) {
                storm.coverage("scenario 3 two repayments on one instalment", 1);
            }
        }
        censusAfter("scenario 3, two repayments on one instalment");
    }

    /** Scenario 4: one account with many pending dates serviced by ten steps on both instances at a barrier. */
    private void accrualRunOnBothInstances() throws Exception {
        List<Aged> pending = agedLoans(5, 20);
        for (int i = 0; i < 5; i++) {
            Aged aged = pending.get(i);
            List<Callable<Object>> racers = new ArrayList<>();
            for (int r = 0; r < 10; r++) {
                Instance on = r % 2 == 0 ? a() : b();
                racers.add(() -> serviceOn(on, aged.loan()));
            }
            for (Object answer : allRaw(racers)) {
                if (answer instanceof Throwable failure) {
                    tally.unexplained("scenario 4: a servicing step failed - " + failure.getClass().getSimpleName());
                }
            }
            long dates = count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?::uuid", aged.loan());
            long distinct = count("SELECT count(DISTINCT accrual_date) FROM lending.interest_accrual WHERE loan_id = ?::uuid",
                    aged.loan());
            if (dates >= 3 && dates == distinct) {
                storm.coverage("scenario 4 one accrual run on both instances", 1);
            }
            if (i == 0) {
                // The lock-bypass writer (the unique's own rank, P1's reach): refused by lending V010's unique.
                if (LendingStormFaults.bypassWriterDuplicatesAnAccrual(aged.loan())) {
                    tally.count("the lock-bypass writer duplicated an accrual");
                    storm.census("scenario 4, the lock-bypass writer", true);
                } else {
                    tally.count("the lock-bypass writer refused by the unique");
                }
            }
        }
        censusAfter("scenario 4, ten steps on one account");
    }

    /** Scenario 5: a payout's response lost - payments UNKNOWN, the payout DISPATCHED - then concluded by inquiry. */
    private void lostResponseConcludedByInquiry() throws Exception {
        for (int i = 0; i < 3; i++) {
            Borrower borrower = freshBorrower(true);
            storm.scheme().losesTheResponseWhenTheBodyContains(SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                    "dest-" + borrower.grant());
            String loan = acceptedLoan(borrower, 2_200_00, 10, true, true);
            LendingStormWorld.awaitDatabase(PATIENCE, "the payout held DISPATCHED while payments is UNKNOWN",
                    "SELECT p.status = 'DISPATCHED' AND w.status = 'UNKNOWN' FROM lending.loan_payout p"
                            + " JOIN payments.withdrawal w ON w.id = p.withdrawal_id WHERE p.loan_id = ?::uuid", loan);
            boolean held = count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?::uuid", loan) == 0;
            Thread.sleep(1_500); // several sweeps of both instances while it is UNKNOWN: never re-sent
            String reference = scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE instruction_reference = ?",
                    "lending:" + loan);
            storm.scheme().succeedsWith(SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + reference, 200,
                    "{\"status\":\"accepted\",\"reference\":\"sch-lost-" + reference + "\",\"cycle\":\"C9\"}");
            LendingStormWorld.awaitDatabase(PATIENCE, "the payout concluded by inquiry",
                    "SELECT status = 'PAID_OUT' FROM lending.loan_payout WHERE loan_id = ?::uuid", loan);
            long sends = storm.scheme().headerValues(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, "Idempotency-Key")
                    .stream().filter(reference::equals).count();
            if (held && sends == 1) {
                storm.coverage("scenario 5 a lost response concluded by inquiry", 1);
            }
        }
        censusAfter("scenario 5, a lost response concluded by inquiry");
    }

    /** Scenario 6: an allocated repayment reversed - a later one present - approved on the other instance under a race. */
    private void reversalAfterAllocation() throws Exception {
        String[] agents = storm.agents();
        List<Aged> reversed = agedLoans(3, 0);
        for (int i = 0; i < 3; i++) {
            Aged aged = reversed.get(i);
            serviceOn(a(), aged.loan());
            String bill = scalar("SELECT (interest_due_minor + principal_due_minor)::text FROM lending.instalment_billing"
                    + " WHERE loan_id = ?::uuid ORDER BY due_date LIMIT 1", aged.loan());
            Answer first = a().door().post(LOANS + "/" + aged.loan() + "/repayments", money(Long.parseLong(bill)),
                    aged.token(), key());
            judged(tally, "scenario 6 first repayment", first);
            Answer later = b().door().post(LOANS + "/" + aged.loan() + "/repayments", money(25_00), aged.token(), key());
            judged(tally, "scenario 6 later repayment", later);
            String reversal = OPERATOR_LOANS + "/" + aged.loan() + "/repayments/" + first.field("repaymentId") + "/reversal";
            Answer proposed = a().door().post(reversal, "{\"reason\":\"the transfer was recalled\"}", agents[0], key());
            judged(tally, "scenario 6 propose", proposed);
            String decision = "{\"reversalId\":\"" + proposed.field("reversalId") + "\",\"reason\":\"matched\"}";
            List<Object> raced = bothAtOnce(() -> b().door().post(reversal + "/approval", decision, agents[1], key()),
                    () -> a().door().post(LOANS + "/" + aged.loan() + "/repayments", money(15_00), aged.token(), key()));
            for (Object answer : raced) {
                judged(tally, "scenario 6 race", answer(answer));
            }
            if (count("SELECT count(*) FROM lending.repayment_reversal WHERE id = ?::uuid AND status = 'APPROVED'",
                    proposed.field("reversalId")) == 1 && count("SELECT count(*) FROM lending.repayment_allocation_reversal n"
                    + " JOIN lending.repayment_allocation x ON x.id = n.allocation_id WHERE x.repayment_id = ?::uuid",
                    later.field("repaymentId")) == 0) {
                storm.coverage("scenario 6 a reversal after allocation, a later repayment untouched", 1);
            }
        }
        censusAfter("scenario 6, a reversal after allocation");
    }

    /**
     * A late fee waived under four eyes across instances (A14): proposed by one agent on A, its self-approval refused on
     * B, approved by the other agent on B - the fee assessments scenario 4's overdue accounts were charged.
     */
    private void waiverAcrossInstances() throws Exception {
        String[] agents = storm.agents();
        for (String[] fee : LendingStormWorld.rows("SELECT f.loan_id::text, f.id::text, f.amount_minor::text FROM"
                + " lending.fee_assessment f JOIN lending.loan l ON l.id = f.loan_id WHERE l.status = 'ACTIVE' AND NOT"
                + " EXISTS (SELECT 1 FROM lending.waiver w WHERE w.fee_assessment_id = f.id) ORDER BY f.id LIMIT 2")) {
            String waivers = OPERATOR_LOANS + "/" + fee[0] + "/waivers";
            Answer proposed = a().door().post(waivers, "{\"kind\":\"FEE\",\"targetId\":\"" + fee[1] + "\",\"amount\":\""
                    + LendingStormWorld.decimal(Long.parseLong(fee[2])) + "\",\"currency\":\"EUR\","
                    + "\"reason\":\"the storm's goodwill\"}", agents[0], key());
            judged(tally, "waiver propose", proposed, "422 lending.WaiverExceedsDue");
            if (proposed.status() != 201) {
                continue;
            }
            String waiver = waivers + "/" + proposed.field("waiverId");
            judged(tally, "waiver self-approval", b().door().post(waiver + "/approval", "{\"reason\":\"mine\"}",
                    agents[0], key()), "403 lending.SelfApprovalRefused");
            Answer approved = b().door().post(waiver + "/approval", "{\"reason\":\"checked\"}", agents[1], key());
            judged(tally, "waiver approve", approved);
            if (approved.status() == 200 && count("SELECT count(*) FROM lending.waiver_decision d JOIN lending.waiver w"
                    + " ON w.id = d.waiver_id WHERE w.fee_assessment_id = ?::uuid AND d.outcome = 'APPROVED'", fee[1]) == 1) {
                storm.coverage("a waiver approved across instances", 1);
            }
        }
        censusAfter("a waiver across instances");
    }

    /** Scenario 7: a payoff on A against a repayment on B at a barrier, until both orders have happened. */
    private void payoffRacingARepayment() throws Exception {
        List<Aged> racing = new ArrayList<>();
        for (int i = 0; i < 16 && (covered("scenario 7 payoff stale") == 0
                || covered("scenario 7 closed then not repayable") == 0); i++) {
            if (racing.isEmpty()) {
                racing.addAll(bankedAgedLoans(4, 0));
            }
            Aged aged = racing.remove(0);
            serviceOn(a(), aged.loan());
            // The payoff owes the principal disbursed AND the month's interest: the borrower's bank tops the wallet up
            // first (a real pay-in), so the payoff can win the race and not only lose it.
            storm.fundFromBank(b(), aged.borrower(), 500_00);
            Answer quoted = a().door().post(LOANS + "/" + aged.loan() + "/payoff-quotes", "{}", aged.token(), key());
            judged(tally, "scenario 7 quote", quoted);
            String quote = quoted.field("quoteId");
            if (i % 2 == 1) {
                Thread.sleep(RANDOMNESS_JITTER());
            }
            List<Object> raced = bothAtOnce(
                    () -> a().door().post(LOANS + "/" + aged.loan() + "/payoff", "{\"quoteId\":\"" + quote + "\"}",
                            aged.token(), key()),
                    () -> b().door().post(LOANS + "/" + aged.loan() + "/repayments", money(20_00), aged.token(), key()));
            Answer payoff = answer(raced.get(0));
            Answer repayment = answer(raced.get(1));
            judged(tally, "scenario 7 payoff", payoff, "409 lending.PayoffQuoteStale");
            judged(tally, "scenario 7 repayment", repayment, "409 lending.LoanNotRepayable", "409 lending.LoanClosed");
            String status = scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", aged.loan());
            if (payoff.is(409, "lending.PayoffQuoteStale") && "ACTIVE".equals(status)) {
                storm.coverage("scenario 7 payoff stale", 1);
            }
            if (payoff.status() == 201 && repayment.is(409, "lending.LoanNotRepayable") && "CLOSED".equals(status)) {
                storm.coverage("scenario 7 closed then not repayable", 1);
            }
        }
        censusAfter("scenario 7, payoff against a repayment");
    }

    private static long RANDOMNESS_JITTER() {
        return 5 + LendingStormWorld.RANDOMNESS.nextInt(40);
    }

    /**
     * Scenario 8: an amendment proposed on A, approved on B, accepted racing a repayment - the agreement and schedule
     * v n+1 once; and a second approved amendment lapsed by the agreement the first one moved.
     */
    private void amendmentUnderARace() throws Exception {
        String[] agents = storm.agents();
        List<Aged> amended = agedLoans(3, -20);
        for (int i = 0; i < 2; i++) {
            Aged aged = amended.get(i);
            String amendments = OPERATOR_LOANS + "/" + aged.loan() + "/amendments";
            String proposal = "{\"nominalAnnualRate\":\"0.0599\",\"remainingInstalments\":" + (14 + i)
                    + ",\"rescheduleArrears\":false,\"reason\":\"hardship, documented\"}";
            Answer proposed = a().door().post(amendments, proposal, agents[0], key());
            judged(tally, "scenario 8 propose", proposed);
            String amendment = proposed.field("amendmentId");
            judged(tally, "scenario 8 approve", b().door().post(amendments + "/" + amendment + "/approval",
                    "{\"reason\":\"affordability ok\"}", agents[1], key()));
            String mine = LOANS + "/" + aged.loan() + "/amendments/" + amendment;
            String document = a().door().get(mine, aged.token()).field("documentSha256");
            List<Object> raced = bothAtOnce(
                    () -> a().door().post(mine + "/acceptance", "{\"documentSha256\":\"" + document + "\"}",
                            aged.token(), key()),
                    () -> b().door().post(LOANS + "/" + aged.loan() + "/repayments", money(30_00), aged.token(), key()));
            for (Object answer : raced) {
                judged(tally, "scenario 8 race", answer(answer), "409 lending.AmendmentLapsed");
            }
            if (count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?::uuid AND amendment_id = ?::uuid",
                    aged.loan(), amendment) == 1 && count("SELECT count(*) FROM lending.repayment_schedule WHERE"
                    + " loan_id = ?::uuid", aged.loan()) == 2) {
                storm.coverage("scenario 8 an amendment applied once", 1);
            }
        }
        // A moved agreement lapses an approved amendment: approved, then a prepayment moves the agreement first.
        Aged aged = amended.get(2);
        String amendments = OPERATOR_LOANS + "/" + aged.loan() + "/amendments";
        Answer proposed = a().door().post(amendments, "{\"nominalAnnualRate\":\"0.0599\",\"remainingInstalments\":12,"
                + "\"rescheduleArrears\":false,\"reason\":\"hardship, documented\"}", agents[0], key());
        judged(tally, "scenario 8 propose", proposed);
        String amendment = proposed.field("amendmentId");
        judged(tally, "scenario 8 approve", b().door().post(amendments + "/" + amendment + "/approval",
                "{\"reason\":\"affordability ok\"}", agents[1], key()));
        String mine = LOANS + "/" + aged.loan() + "/amendments/" + amendment;
        String document = a().door().get(mine, aged.token()).field("documentSha256");
        judged(tally, "scenario 8 prepayment", b().door().post(LOANS + "/" + aged.loan() + "/prepayments",
                money(200_00), aged.token(), key()), "409 lending.ArrearsFirst");
        Answer lapsed = a().door().post(mine + "/acceptance", "{\"documentSha256\":\"" + document + "\"}", aged.token(),
                key());
        judged(tally, "scenario 8 lapsed", lapsed, "409 lending.AmendmentLapsed", "409 lending.TermsChanged");
        censusAfter("scenario 8, a lapsed amendment");
        if (lapsed.status() == 409 && count("SELECT count(*) FROM lending.loan_agreement WHERE amendment_id = ?::uuid",
                amendment) == 0) {
            storm.coverage("scenario 8 an amendment lapsed", 1);
        }
    }

    /** Scenario 10: a delinquent account's servicing step on A against a payoff on B at a barrier. */
    private void payoffBesideTheDelinquencyWorker() throws Exception {
        List<Aged> delinquent = bankedAgedLoans(4, 5);
        for (int i = 0; i < 4; i++) {
            Aged aged = delinquent.get(i);
            Account account = new Account(aged.loan(), aged.borrower(), AccountKind.WALLET_LOAN, 0, Role.PLAIN);
            serviceOn(b(), aged.loan());
            // The payoff owes the arrears' interest and fees beside the principal: the borrower's bank tops up first.
            storm.fundFromBank(a(), aged.borrower(), 500_00);
            List<Object> raced = bothAtOnce(() -> serviceOn(a(), aged.loan()), () -> storm.payOffOn(b(), account));
            if (raced.get(0) instanceof Throwable failure) {
                tally.unexplained("scenario 10: the servicing step failed - " + failure.getClass().getSimpleName());
            }
            if ("CLOSED".equals(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", aged.loan()))) {
                storm.coverage("scenario 10 payoff beside the delinquency worker", 1);
            }
        }
        censusAfter("scenario 10, payoff beside the delinquency worker");
    }

    /**
     * The exposure race (INV-LND-06, INV-CRD-09; P2's reach): per racing party, under a held profile row (L0), a loan
     * offer accepted on A while the party's line is applied for on B - the acceptance's own backend observed waiting on
     * credit's profile row, unanswered, holding no offer lock (C17), then both complete; the census reads the party's
     * exposure.
     *
     * <p><strong>Whose backend C17 saw</strong>: not any session behind the held row - an acceptance that skipped L0
     * commits at once, and the disbursement sweep then waits on that same row for the loan it just made (P2's first
     * performance survived exactly so). The acceptance claims its key before L0, so its blocked backend already holds a
     * transaction id; the acceptance row it commits after the release carries that id as its {@code xmin} (append-only,
     * never rewritten - unlike the loan row the sweep later updates). C17 holds when, with L0 still held, a waiter in
     * credit's profile statement was seen, the acceptance had not answered and the offer was free to {@code NOWAIT} - and,
     * for a committed acceptance, its row's {@code xmin} is one of the seen waiters' transaction ids.
     */
    private void exposureRace() throws Exception {
        for (int i = 0; i < 4; i++) {
            CreditFixtures.Customer racer = world.borrower(LendingStormCredit.Profile.RACER);
            Borrower borrower = new Borrower(racer, null, null);
            String offer = offered(borrower, LendingStormWorld.loanApplication(8_000_00, 24, 10, false));
            String hash = LendingStormWorld.termsHash(a().door(), racer.token(), offer);
            String line = LendingStormWorld.lineApplication(2_000_00, 10);
            if (i % 2 == 0) {
                // C17: behind a held L0 the acceptance's OWN backend waits in credit's profile statement, unanswered,
                // holding no offer lock (NOWAIT free) - identified by its transaction id, never by "some waiter".
                // Then the party's line is decided over the commitment.
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try (Connection holder = DatabaseRoles.application()) {
                    holder.setAutoCommit(false);
                    lock(holder, "SELECT 1 FROM credit.credit_profile WHERE party_id = ? FOR UPDATE", racer.party());
                    Future<Answer> accepting = pool.submit(() -> a().door().post(OFFERS + "/" + offer + "/acceptance",
                            LendingStormWorld.acceptance(hash, null), racer.token(), key()));
                    List<Waiter> waiting = awaitBlockedBy(pid(holder), "credit_profile");
                    boolean offerFree = lockableNoWait("SELECT 1 FROM lending.loan_offer WHERE id = ?::uuid"
                            + " FOR UPDATE NOWAIT", offer);
                    // Read while L0 is still held: an acceptance that waits there cannot have answered.
                    boolean unanswered = !accepting.isDone();
                    holder.rollback();
                    Answer accepted = accepting.get(2, TimeUnit.MINUTES);
                    judged(tally, "exposure race acceptance", accepted, "422 lending.CapitalUnavailable");
                    // A committed acceptance names its transaction: the one its blocked backend already held.
                    String committedBy = accepted.is(201) ? scalar("SELECT xmin::text FROM lending.loan_acceptance"
                            + " WHERE offer_id = ?::uuid AND agreement_version = 1", offer) : null;
                    boolean own = committedBy != null && waiting.stream().anyMatch(w -> committedBy.equals(w.xid()));
                    if (waiting.isEmpty() || !unanswered || !offerFree || (accepted.is(201) && !own)) {
                        tally.unexplained("C17: the acceptance's own backend was not seen waiting behind the held"
                                + " profile lock (L0) holding no later-order lock - waiters seen: " + waiting
                                + ", the acceptance unanswered while L0 was held: " + unanswered + ", the offer free: "
                                + offerFree + ", the acceptance committed by transaction: " + committedBy);
                    }
                    System.out.printf("P11-TST-001 C17: the acceptance %d %s, committed by %s; waiters seen %s;"
                            + " unanswered while L0 held %s; the offer free %s%n", accepted.status(), accepted.code(),
                            committedBy, waiting, unanswered, offerFree);
                    storm.coverage("C17 lock order observed behind a held L0", own && unanswered && offerFree ? 1 : 0);
                } finally {
                    pool.shutdownNow();
                }
                judged(tally, "exposure race line application", b().door().post(APPLICATIONS, line, racer.token(),
                        key()));
            } else {
                // The acceptance on A racing the party's line application - and its decision - on B.
                List<Object> raced = bothAtOnce(
                        () -> a().door().post(OFFERS + "/" + offer + "/acceptance", LendingStormWorld.acceptance(hash,
                                null), racer.token(), key()),
                        () -> b().door().post(APPLICATIONS, line, racer.token(), key()));
                judged(tally, "exposure race acceptance", answer(raced.get(0)), "422 lending.CapitalUnavailable");
                judged(tally, "exposure race line application", answer(raced.get(1)));
            }
        }
        censusAfter("the exposure race");
    }

    /**
     * The last capital (INV-LND-13; P3's reach): capital topped up through a statement so the headroom fits exactly one of
     * ten offered amounts; ten acceptances on A and B at a barrier; repeated.
     */
    private void lastCapital() throws Exception {
        long amount = 9_000_00;
        String[] treasurers = storm.treasurers();
        for (int round = 0; round < 3; round++) {
            List<Callable<String[]>> offering = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                offering.add(() -> {
                    Borrower borrower = freshBorrower(false);
                    String offer = offered(borrower, LendingStormWorld.loanApplication(amount, 24, 10, false));
                    return new String[] {offer, borrower.customer().token(),
                            LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer)};
                });
            }
            List<String[]> offers = LendingStormDatabaseTest.all(offering);
            fillHeadroomBelow(amount);
            long headroom = headroom();
            long topUp = amount + amount / 3 - headroom;
            world.tranche(a().door(), b().door(), treasurers[0], treasurers[1], topUp, true, tally);
            assertThat(headroom()).as("the headroom fits exactly one of the ten").isBetween(amount, 2 * amount - 1);
            List<Callable<Answer>> racers = new ArrayList<>();
            for (int i = 0; i < offers.size(); i++) {
                String[] offer = offers.get(i);
                LendingStormDatabaseTest.Instance on = i % 2 == 0 ? a() : b();
                racers.add(() -> on.door().post(OFFERS + "/" + offer[0] + "/acceptance",
                        LendingStormWorld.acceptance(offer[2], null), offer[1], key()));
            }
            int admitted = 0;
            for (Object answer : allRaw(racers)) {
                Answer response = answer(answer);
                judged(tally, "last capital acceptance", response, "422 lending.CapitalUnavailable");
                if (response.status() == 201) {
                    admitted++;
                }
            }
            censusAfter("the last capital " + (round + 1));
            storm.coverage("the last capital raced", admitted == 1 ? 1 : 0);
            tally.count("last capital admitted " + admitted);
        }
    }

    /** Accepts filler loans until the headroom is below {@code amount} - capital consumed by real acceptances. */
    private void fillHeadroomBelow(long amount) throws Exception {
        for (int guard = 0; guard < 40; guard++) {
            long headroom = headroom();
            if (headroom < amount) {
                return;
            }
            long filler = Math.min(9_500_00, Math.max(1_000_00, headroom - amount / 2));
            Borrower borrower = freshBorrower(false);
            acceptedLoan(borrower, filler, 24, false, false);
        }
        throw new AssertionError("the headroom could not be brought below the racers' amount");
    }

    private long headroom() {
        return a().transactions().inTransaction(uow -> a().beans().getBean(LendingCapitalHeadroom.class)
                .atRest(uow, EUR)).headroom().minorUnits();
    }

    /** Draws racing a line's limit: ten draws of a third of it, five on each instance at a barrier. */
    private void drawsRacingTheLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            Borrower borrower = freshBorrower(false);
            String line = acceptedLine(borrower, 2_400_00);
            List<Callable<Answer>> racers = new ArrayList<>();
            for (int r = 0; r < 10; r++) {
                LendingStormDatabaseTest.Instance on = r % 2 == 0 ? a() : b();
                racers.add(() -> on.door().post(LOANS + "/" + line + "/draws", money(800_00), borrower.customer().token(),
                        key()));
            }
            int drawn = 0;
            for (Object answer : allRaw(racers)) {
                judged(tally, "limit race draw", answer(answer), "422 lending.LimitExceeded");
                drawn += (answer(answer)).status() == 201 ? 1 : 0;
            }
            censusAfter("draws racing the limit " + (i + 1));
            storm.coverage("draws raced the limit", drawn == 3 ? 1 : 0);
        }
    }

    /** A line's closure on A racing a draw on B - exactly one of "drawn, then closing" and "closing, then refused". */
    private void closureRacingADraw() throws Exception {
        for (int i = 0; i < 3; i++) {
            Borrower borrower = freshBorrower(false);
            String line = acceptedLine(borrower, 2_000_00);
            List<Object> raced = bothAtOnce(
                    () -> a().door().post(LOANS + "/" + line + "/closure", null, borrower.customer().token(), key()),
                    () -> b().door().post(LOANS + "/" + line + "/draws", money(500_00), borrower.customer().token(), key()));
            judged(tally, "closure race closure", answer(raced.get(0)));
            judged(tally, "closure race draw", answer(raced.get(1)), "409 lending.LineNotDrawable");
            censusAfter("a closure racing a draw " + (i + 1));
            storm.coverage("closure raced a draw", 1);
        }
    }

    /**
     * An acceptance racing the offer's expiry at the boundary: the offer's expiry moved to an instant just ahead (the
     * owner's fixture), the acceptance on A and the expiry sweep on B released as it passes - never both.
     */
    private void acceptanceRacingExpiry() throws Exception {
        for (int i = 0; i < 4; i++) {
            Borrower borrower = freshBorrower(false);
            String offer = offered(borrower, LendingStormWorld.loanApplication(1_500_00, 12, 10, false));
            String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
            try (Connection owner = DatabaseRoles.bootstrap()) {
                owner.setAutoCommit(false);
                try (PreparedStatement move = owner.prepareStatement("SET LOCAL session_replication_role = replica;"
                        + " UPDATE lending.loan_offer SET expires_at = now() + interval '1500 milliseconds', next_step_at ="
                        + " now() + interval '1500 milliseconds' WHERE id = '" + offer + "'::uuid")) {
                    move.execute();
                }
                owner.commit();
            }
            LendingStormWorld.awaitDatabase(Duration.ofSeconds(10), "the offer's expiry is a moment away",
                    "SELECT expires_at - now() < interval '" + (40 + 30 * i) + " milliseconds' FROM lending.loan_offer"
                            + " WHERE id = ?::uuid", offer);
            List<Object> raced = bothAtOnce(
                    () -> a().door().post(OFFERS + "/" + offer + "/acceptance", LendingStormWorld.acceptance(hash, null),
                            borrower.customer().token(), key()),
                    () -> b().originationSchedule().sweepOnce());
            judged(tally, "acceptance at the expiry", answer(raced.get(0)), "409 lending.OfferNotAcceptable",
                    "422 lending.CapitalUnavailable");
            LendingStormWorld.awaitDatabase(PATIENCE, "the offer settles", "SELECT status IN ('ACCEPTED', 'EXPIRED')"
                    + " FROM lending.loan_offer WHERE id = ?::uuid", offer);
            censusAfter("an acceptance racing the expiry " + (i + 1));
            tally.count("expiry race: " + scalar("SELECT status FROM lending.loan_offer WHERE id = ?::uuid", offer));
            storm.coverage("acceptance raced the expiry", 1);
        }
    }

    /** A standing lost before the disbursement: the loan CANCELLED (STANDING_LOST), nothing posted. */
    private void standingLostBeforeDisbursement() throws Exception {
        Borrower borrower = freshBorrower(false);
        String offer = offered(borrower, LendingStormWorld.loanApplication(1_800_00, 12, 10, false));
        String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
        String loan;
        try (AutoCloseable held = LendingStormFaults.refuse("lending.loan_disbursement", "UPDATE",
                "NEW.loan_id IN (SELECT id FROM lending.loan WHERE offer_id = '" + offer + "'::uuid)", false)) {
            Answer accepted = b().door().post(OFFERS + "/" + offer + "/acceptance",
                    LendingStormWorld.acceptance(hash, null), borrower.customer().token(), key());
            judged(tally, "standing-lost acceptance", accepted);
            loan = accepted.field("loanId");
            Thread.sleep(1_000);
            LendingStormWorld.execute("UPDATE party.customer SET status = 'SUSPENDED', status_changed_at ="
                    + " GREATEST(now(), status_changed_at) WHERE party_id = ?", borrower.customer().party());
        }
        awaitStatus(loan, "CANCELLED");
        censusAfter("a standing lost before the disbursement");
        storm.coverage("cancelled for a lost standing", count("SELECT count(*) FROM lending.loan WHERE id = ?::uuid"
                + " AND cancellation_reason = 'STANDING_LOST'", loan) == 1 ? 1 : 0);
    }

    /** An employee who borrows is refused their own loan's reversal (audited FAILED); self-approval refused. */
    private void selfDealingAndOutOfRole() throws Exception {
        Aged aged = agedLoan(0, false);
        serviceOn(a(), aged.loan());
        Answer repaid = a().door().post(LOANS + "/" + aged.loan() + "/repayments", money(50_00), aged.token(), key());
        judged(tally, "self-dealing repayment", repaid);
        world.assign(UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE party_id = ?",
                aged.borrower().customer().party())), RoleName.LOAN_SERVICING_AGENT);
        Answer insider = b().door().post(OPERATOR_LOANS + "/" + aged.loan() + "/repayments/" + repaid.field("repaymentId")
                + "/reversal", "{\"reason\":\"my own loan\"}", aged.token(), key());
        judged(tally, "self-dealing", insider, "403 lending.SelfDealingRefused");
        if (insider.is(403, "lending.SelfDealingRefused")) {
            tally.count("self-dealing refused");
        }
        censusAfter("self-dealing refused");
    }

    /** A contribution approved whose statement line never arrives: an expectation open, no capital. */
    private void capitalThatNeverArrives() throws Exception {
        String[] treasurers = storm.treasurers();
        long before = count("SELECT count(*) FROM reconciliation.capital_recognition");
        String contribution = world.tranche(b().door(), a().door(), treasurers[1], treasurers[0], 5_000_00, false, tally);
        censusAfter("capital that never arrives");
        assertThat(count("SELECT count(*) FROM reconciliation.capital_recognition")).isEqualTo(before);
        storm.coverage("capital that never arrives", count("SELECT count(*) FROM reconciliation.expectation"
                + " WHERE operation_ref = ? AND status = 'OPEN'", contribution) == 1 ? 1 : 0);
    }

    // ================================================================== the crash points

    void crashPoints() throws Exception {
        killedAfterThePostingBeforeTheEvent();
        killedInTheMiddleOfACatchUp();
        killedAtTheAllocation();
        refusedAtTheLoan();
        refusedAtTheDispatchedEdge();
        refusedAtTheTerminalEdge();
        refusedAtTheBilling();
        refusedAtTheStatement();
        refusedAtTheDraw();
        refusedAtTheClosure();
        refusedAtTheReversalApproval();
        theRelayKilledAndRestarted();
    }

    /** K1 (scenario 9): T-dis killed at the LoanDisbursed outbox row - neither entry nor event; then one of each. */
    private void killedAfterThePostingBeforeTheEvent() throws Exception {
        Borrower borrower = freshBorrower(false);
        String offer = offered(borrower, LendingStormWorld.loanApplication(2_100_00, 12, 10, false));
        String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
        String loan;
        try (AutoCloseable killed = LendingStormFaults.refuse("platform.outbox_event", "INSERT",
                "NEW.event_type = 'lending.LoanDisbursed' AND NEW.aggregate_id IN (SELECT id FROM lending.loan"
                        + " WHERE offer_id = '" + offer + "'::uuid)", true)) {
            Answer accepted = a().door().post(OFFERS + "/" + offer + "/acceptance",
                    LendingStormWorld.acceptance(hash, null), borrower.customer().token(), key());
            judged(tally, "K1 acceptance", accepted);
            loan = accepted.field("loanId");
            Thread.sleep(2_500); // both instances' sweeps try, and each attempt dies after its posting
        }
        boolean neither = count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.disbursement:" + loan) == 0 && count("SELECT count(*) FROM platform.outbox_event"
                + " WHERE aggregate_id = ?::uuid AND event_type = 'lending.LoanDisbursed'", loan) == 0
                && "PENDING_DISBURSEMENT".equals(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loan));
        awaitStatus(loan, "ACTIVE");
        censusAfter("K1 killed after the posting");
        if (neither && count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?::uuid"
                + " AND event_type = 'lending.LoanDisbursed'", loan) == 1) {
            storm.coverage("scenario 9 killed after the posting", 1);
            storm.coverage("crash points", 1);
        }
    }

    /** K2 (scenario 4): a catch-up killed at its third date - the two before rolled back with it; then every date once. */
    private void killedInTheMiddleOfACatchUp() throws Exception {
        Aged aged = agedLoan(20, false);
        LocalDate start = LocalDate.parse(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id ="
                + " ?::uuid", aged.loan()));
        storm.servicingFaultsInjected().incrementAndGet();
        try (AutoCloseable killed = LendingStormFaults.refuse("lending.interest_accrual", "INSERT",
                "NEW.loan_id = '" + aged.loan() + "'::uuid AND NEW.accrual_date = DATE '" + start.plusDays(2) + "'", true)) {
            try {
                serviceOn(a(), aged.loan());
                tally.unexplained("K2: the killed catch-up committed");
            } catch (RuntimeException expected) {
                tally.count("K2 the catch-up killed");
            }
        }
        boolean rolledBack = count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?::uuid", aged.loan())
                == 0;
        serviceOn(b(), aged.loan());
        censusAfter("K2 killed mid catch-up");
        if (rolledBack && count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?::uuid", aged.loan()) > 3) {
            storm.coverage("scenario 4 a step killed mid-run", 1);
            storm.coverage("crash points", 1);
        }
    }

    /** K3: T-rep killed at its allocation - nothing posted; the same key on the other instance repays once. */
    private void killedAtTheAllocation() throws Exception {
        Aged aged = agedLoan(0, false);
        serviceOn(a(), aged.loan());
        String repaymentKey = key();
        String path = LOANS + "/" + aged.loan() + "/repayments";
        try (AutoCloseable killed = LendingStormFaults.refuse("lending.repayment_allocation", "INSERT",
                "NEW.repayment_id IN (SELECT id FROM lending.repayment WHERE loan_id = '" + aged.loan() + "'::uuid)", true)) {
            Answer died = a().door().post(path, money(45_00), aged.token(), repaymentKey);
            tally.count("K3 the repayment killed " + died.status());
        }
        boolean nothing = count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?::uuid", aged.loan()) == 0;
        sameKeyAgain(b(), path, money(45_00), aged.token(), repaymentKey, "K3 the same key again");
        censusAfter("K3 killed at the allocation");
        if (nothing && count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?::uuid", aged.loan()) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R1: T-acc refused at the loan's birth - no consumption, no account; the same key on the other instance once. */
    private void refusedAtTheLoan() throws Exception {
        Borrower borrower = freshBorrower(false);
        String offer = offered(borrower, LendingStormWorld.loanApplication(1_900_00, 12, 10, false));
        String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
        String acceptanceKey = key();
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.loan", "INSERT",
                "NEW.offer_id = '" + offer + "'::uuid", false)) {
            Answer died = a().door().post(OFFERS + "/" + offer + "/acceptance", LendingStormWorld.acceptance(hash, null),
                    borrower.customer().token(), acceptanceKey);
            tally.count("R1 the acceptance refused " + died.status());
        }
        boolean nothing = count("SELECT count(*) FROM lending.loan WHERE offer_id = ?::uuid", offer) == 0
                && count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN lending.loan_offer o"
                + " ON o.decision_id = c.decision_id WHERE o.id = ?::uuid", offer) == 0;
        sameKeyAgain(b(), OFFERS + "/" + offer + "/acceptance", LendingStormWorld.acceptance(hash, null),
                borrower.customer().token(), acceptanceKey, "R1 the same key again");
        censusAfter("R1 refused at the loan");
        if (nothing && count("SELECT count(*) FROM lending.loan WHERE offer_id = ?::uuid", offer) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R2: the payout's DISPATCHED edge refused after payments accepted - re-driven, payments' key replays, one send. */
    private void refusedAtTheDispatchedEdge() throws Exception {
        Borrower borrower = freshBorrower(true);
        String offer = offered(borrower, LendingStormWorld.loanApplication(2_050_00, 12, 10, true));
        String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
        String loan;
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.loan_payout", "UPDATE",
                "NEW.loan_id IN (SELECT id FROM lending.loan WHERE offer_id = '" + offer + "'::uuid)"
                        + " AND OLD.status = 'DISPATCHING' AND NEW.status <> 'DISPATCHING'", false)) {
            Answer accepted = a().door().post(OFFERS + "/" + offer + "/acceptance",
                    LendingStormWorld.acceptance(hash, borrower.bankMethod()), borrower.customer().token(), key());
            judged(tally, "R2 acceptance", accepted);
            loan = accepted.field("loanId");
            LendingStormWorld.awaitDatabase(PATIENCE, "payments accepted the withdrawal",
                    "SELECT count(*) = 1 FROM payments.withdrawal WHERE instruction_reference = ?", "lending:" + loan);
            Thread.sleep(1_500);
        }
        LendingStormWorld.awaitDatabase(PATIENCE, "the payout concluded once re-driven",
                "SELECT status = 'PAID_OUT' FROM lending.loan_payout WHERE loan_id = ?::uuid", loan);
        censusAfter("R2 refused at the DISPATCHED edge");
        if (count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?", "lending:" + loan) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R3: the payout's terminal edge refused - the sweep concludes it once, the accrual start once. */
    private void refusedAtTheTerminalEdge() throws Exception {
        Borrower borrower = freshBorrower(true);
        storm.scheme().losesTheResponseWhenTheBodyContains(SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                "dest-" + borrower.grant());
        String loan = acceptedLoan(borrower, 2_150_00, 10, true, false);
        LendingStormWorld.awaitDatabase(PATIENCE, "the payout DISPATCHED while payments is UNKNOWN",
                "SELECT p.status = 'DISPATCHED' AND w.status = 'UNKNOWN' FROM lending.loan_payout p"
                        + " JOIN payments.withdrawal w ON w.id = p.withdrawal_id WHERE p.loan_id = ?::uuid", loan);
        String reference = scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + loan);
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.loan_payout", "UPDATE",
                "NEW.loan_id = '" + loan + "'::uuid AND NEW.status IN ('PAID_OUT', 'FAILED')", false)) {
            storm.scheme().succeedsWith(SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + reference, 200,
                    "{\"status\":\"accepted\",\"reference\":\"sch-r3-" + reference + "\",\"cycle\":\"C9\"}");
            LendingStormWorld.awaitDatabase(PATIENCE, "payments concluded", "SELECT status = 'COMPLETED' FROM"
                    + " payments.withdrawal WHERE end_to_end_reference = ?", reference);
            Thread.sleep(1_500);
        }
        LendingStormWorld.awaitDatabase(PATIENCE, "the payout concluded", "SELECT status = 'PAID_OUT' FROM"
                + " lending.loan_payout WHERE loan_id = ?::uuid", loan);
        censusAfter("R3 refused at the terminal edge");
        if (count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?::uuid", loan) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R4: the billing refused - the servicing sweep bills the date once. */
    private void refusedAtTheBilling() throws Exception {
        Aged aged = agedLoan(0, false);
        storm.servicingFaultsInjected().incrementAndGet();
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.instalment_billing", "INSERT",
                "NEW.loan_id = '" + aged.loan() + "'::uuid", false)) {
            try {
                serviceOn(a(), aged.loan());
                tally.unexplained("R4: the refused billing committed");
            } catch (RuntimeException expected) {
                tally.count("R4 the billing refused");
            }
        }
        serviceOn(b(), aged.loan());
        censusAfter("R4 refused at the billing");
        if (count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?::uuid", aged.loan()) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /**
     * R5: a line's statement refused - the line born twenty days in the past, its first cycle ended five days ago; the
     * refused step rolls back whole (its accruals with it) and the other instance states the cycle once.
     */
    private void refusedAtTheStatement() throws Exception {
        Borrower borrower = freshBorrower(false);
        String zoneName = scalar("SELECT t.servicing_zone FROM lending.loan_terms t WHERE t.terms_version_id ="
                + " (SELECT id FROM lending.loan_terms_version WHERE product = 'CREDIT_LINE' AND status = 'ACTIVE')");
        java.time.ZoneOffset zone = "UTC".equals(zoneName) ? java.time.ZoneOffset.UTC : java.time.ZoneOffset.of(zoneName);
        LocalDate today = LocalDate.parse(scalar("SELECT lending.business_date(now(), ?)::text", zoneName));
        LocalDate statementDate = today.minusDays(5);
        while (statementDate.getDayOfMonth() > 28) {
            statementDate = statementDate.minusDays(1);
        }
        String offer = offered(borrower, LendingStormWorld.lineApplication(1_500_00, statementDate.getDayOfMonth()));
        String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
        Answer accepted = b().door().post(OFFERS + "/" + offer + "/acceptance", LendingStormWorld.acceptance(hash, null),
                borrower.customer().token(), key());
        judged(tally, "R5 line acceptance", accepted);
        String line = accepted.field("loanId");
        storm.reserved().add(line);
        storm.accounts().add(new Account(line, borrower, AccountKind.LINE, 0, Role.PLAIN));
        judged(tally, "R5 draw", a().door().post(LOANS + "/" + line + "/draws", money(400_00),
                borrower.customer().token(), key()));
        LendingStormWorld.awaitDatabase(PATIENCE, "the line serviced once", "SELECT next_servicing_at >"
                + " statement_timestamp() FROM lending.loan WHERE id = ?::uuid", line);
        LendingStormWorld.lineBornInThePast(line, today.minusDays(20), zone, Instant.parse("2999-01-01T00:00:00Z"));
        storm.servicingFaultsInjected().incrementAndGet();
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.credit_line_statement", "INSERT",
                "NEW.loan_id = '" + line + "'::uuid", false)) {
            try {
                serviceOn(a(), line);
                tally.unexplained("R5: the refused statement committed");
            } catch (RuntimeException expected) {
                tally.count("R5 the statement refused");
            }
        }
        boolean rolledBack = count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?::uuid", line) == 0;
        serviceOn(b(), line);
        censusAfter("R5 refused at the statement");
        if (rolledBack && count("SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = ?::uuid", line) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R6: the draw refused - the same key draws once. */
    private void refusedAtTheDraw() throws Exception {
        Borrower borrower = freshBorrower(false);
        String line = acceptedLine(borrower, 1_500_00);
        String drawKey = key();
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.credit_line_draw", "INSERT",
                "NEW.loan_id = '" + line + "'::uuid", false)) {
            Answer died = a().door().post(LOANS + "/" + line + "/draws", money(300_00), borrower.customer().token(),
                    drawKey);
            tally.count("R6 the draw refused " + died.status());
        }
        sameKeyAgain(b(), LOANS + "/" + line + "/draws", money(300_00), borrower.customer().token(), drawKey,
                "R6 the same key again");
        censusAfter("R6 refused at the draw");
        if (count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?::uuid", line) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R7: the payoff refused at the closure - the same quote closes it once, every balance zero. */
    private void refusedAtTheClosure() throws Exception {
        Aged aged = agedLoan(0, false, true);
        serviceOn(a(), aged.loan());
        storm.fundFromBank(b(), aged.borrower(), 500_00); // the month's interest beside the principal disbursed
        Answer quoted = a().door().post(LOANS + "/" + aged.loan() + "/payoff-quotes", "{}", aged.token(), key());
        judged(tally, "R7 quote", quoted);
        String body = "{\"quoteId\":\"" + quoted.field("quoteId") + "\"}";
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.loan_closure", "INSERT",
                "NEW.loan_id = '" + aged.loan() + "'::uuid", false)) {
            Answer died = a().door().post(LOANS + "/" + aged.loan() + "/payoff", body, aged.token(), key());
            tally.count("R7 the payoff refused " + died.status());
        }
        Answer again = b().door().post(LOANS + "/" + aged.loan() + "/payoff", body, aged.token(), key());
        judged(tally, "R7 the same quote again", again);
        censusAfter("R7 refused at the closure");
        if (count("SELECT count(*) FROM lending.loan_closure WHERE loan_id = ?::uuid", aged.loan()) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R8: the reversal's approval refused at its APPROVED edge - approved again, one reversal. */
    private void refusedAtTheReversalApproval() throws Exception {
        String[] agents = storm.agents();
        Aged aged = agedLoan(0, false);
        serviceOn(a(), aged.loan());
        Answer repaid = a().door().post(LOANS + "/" + aged.loan() + "/repayments", money(60_00), aged.token(), key());
        judged(tally, "R8 repayment", repaid);
        String reversal = OPERATOR_LOANS + "/" + aged.loan() + "/repayments/" + repaid.field("repaymentId") + "/reversal";
        Answer proposed = a().door().post(reversal, "{\"reason\":\"recalled\"}", agents[0], key());
        judged(tally, "R8 propose", proposed);
        String decision = "{\"reversalId\":\"" + proposed.field("reversalId") + "\",\"reason\":\"matched\"}";
        try (AutoCloseable refused = LendingStormFaults.refuse("lending.repayment_reversal", "UPDATE",
                "NEW.id = '" + proposed.field("reversalId") + "'::uuid AND NEW.status = 'APPROVED'", false)) {
            Answer died = b().door().post(reversal + "/approval", decision, agents[1], key());
            tally.count("R8 the approval refused " + died.status());
        }
        Answer again = a().door().post(reversal + "/approval", decision, agents[1], key());
        judged(tally, "R8 approved again", again);
        censusAfter("R8 refused at the reversal's approval");
        if (count("SELECT count(*) FROM lending.repayment_reversal WHERE repayment_id = ?::uuid AND status = 'APPROVED'",
                repaid.field("repaymentId")) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** R9 (scenario 9): the relay killed before the broker's answer; a new relay publishes every pending event once. */
    private void theRelayKilledAndRestarted() throws Exception {
        Set<UUID> watched = Set.copyOf(LendingStormWorld.strings("SELECT id::text FROM lending.loan ORDER BY opened_at"
                + " DESC LIMIT 20").stream().map(UUID::fromString).toList());
        Recorder killed = new Recorder(watched, true);
        relay(killed).pollOnce();
        relay(killed).pollOnce();
        Set<String> pending = Set.copyOf(LendingStormWorld.strings("SELECT event_id::text FROM platform.outbox_event"
                + " WHERE aggregate_id = ANY (?) AND published_at IS NULL", (Object) watched.toArray(UUID[]::new)));
        Recorder restarted = new Recorder(watched, false);
        OutboxRelay relay = relay(restarted);
        for (int poll = 0; poll < 5_000 && relay.pollOnce().didWork(); poll++) {
            // drained
        }
        storm.census("R9 the relay killed and restarted", true);
        List<String> received = List.copyOf(restarted.received);
        if (!pending.isEmpty() && received.containsAll(pending) && Set.copyOf(received).size() == received.size()) {
            storm.coverage("scenario 9 the relay killed and restarted", 1);
            storm.coverage("crash points", 1);
        } else {
            tally.unexplained("R9: the restarted relay did not publish every pending event exactly once");
        }
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(DatabaseRoles::application, publisher,
                new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 50), 10_000, 200);
    }

    /** A broker that records what reaches it for the watched aggregates - or, killed, dies before acknowledging. */
    private static final class Recorder implements EventPublisher {

        private final Set<UUID> watched;
        private final boolean killed;
        private final List<String> received = new CopyOnWriteArrayList<>();

        Recorder(Set<UUID> watched, boolean killed) {
            this.watched = watched;
            this.killed = killed;
        }

        @Override
        public void publish(PendingEvent event) {
            if (!watched.contains(event.aggregateId())) {
                return;
            }
            if (killed) {
                throw new IllegalStateException("the relay died before the broker acknowledged");
            }
            received.add(event.eventId().value().toString());
        }
    }

    /**
     * The scene is over: its aged accounts - held from the sweeps by a far permit while the scene drove them - handed
     * back to both instances' servicing (the permit due now, the application role's own permit-only move), then the
     * rest and the full census.
     */
    private void censusAfter(String scene) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement release = app.prepareStatement("UPDATE lending.loan SET next_servicing_at ="
                        + " statement_timestamp() WHERE status IN ('ACTIVE', 'CLOSING')"
                        + " AND next_servicing_at > statement_timestamp() + interval '1 year'")) {
            release.executeUpdate();
        }
        storm.restAndCensus(scene);
    }

    /**
     * The client's retry under the same key after a failed attempt: while the key's claim is still held by the attempt
     * that died ({@code 409 api.IdempotencyInProgress}), the client waits and asks again - then the act runs once.
     */
    private Answer sameKeyAgain(LendingStormDatabaseTest.Instance on, String path, String body, String token, String key,
            String act, String... expected) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (true) {
            Answer again = on.door().post(path, body, token, key);
            if (!again.is(409, "api.IdempotencyInProgress") || System.nanoTime() > deadline) {
                judged(tally, act, again, expected);
                return again;
            }
            tally.count(act + " waited for the dead attempt's claim");
            Thread.sleep(500);
        }
    }

    // ================================================================== the scenes' accounts

    /** A fresh borrower - never one the movers know - with a bank account through the exchange if {@code banked}. */
    private Borrower freshBorrower(boolean banked) throws Exception {
        CreditFixtures.Customer customer = world.borrower(LendingStormCredit.Profile.GOOD);
        String[] bank = banked ? world.bankAccount(customer) : new String[] {null, null};
        return new Borrower(customer, bank[0], bank[1]);
    }

    /** {@code body} applied for over A, decided by credit's sweeps and offered by the origination sweeps; the offer. */
    private String offered(Borrower borrower, String body) throws Exception {
        Answer submitted = a().door().post(APPLICATIONS, body, borrower.customer().token(), key());
        assertThat(submitted.status()).as(submitted.body()).isEqualTo(202);
        Optional<String> offer = LendingStormWorld.awaitOffer(submitted.field("applicationId"), PATIENCE);
        assertThat(offer).as("the scene's application was offered").isPresent();
        return offer.get();
    }

    /** A loan offered and accepted (on B), reserved for the scene; awaited ACTIVE unless {@code pending} is wanted. */
    private String acceptedLoan(Borrower borrower, long amount, int day, boolean external, boolean keepPending)
            throws Exception {
        String offer = offered(borrower, LendingStormWorld.loanApplication(amount, 12, day, external));
        String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
        Answer accepted = b().door().post(OFFERS + "/" + offer + "/acceptance", LendingStormWorld.acceptance(hash,
                external ? borrower.bankMethod() : null), borrower.customer().token(), key());
        judged(tally, "scene acceptance", accepted);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(201);
        String loan = accepted.field("loanId");
        storm.reserved().add(loan);
        storm.accounts().add(new Account(loan, borrower, external ? AccountKind.EXTERNAL_LOAN : AccountKind.WALLET_LOAN,
                0, Role.PLAIN));
        if (!keepPending) {
            awaitStatus(loan, "ACTIVE");
        }
        return loan;
    }

    private String acceptedLine(Borrower borrower, long limit) throws Exception {
        String offer = offered(borrower, LendingStormWorld.lineApplication(limit, 10));
        String hash = LendingStormWorld.termsHash(a().door(), borrower.customer().token(), offer);
        Answer accepted = b().door().post(OFFERS + "/" + offer + "/acceptance", LendingStormWorld.acceptance(hash, null),
                borrower.customer().token(), key());
        judged(tally, "scene line acceptance", accepted);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(201);
        String line = accepted.field("loanId");
        storm.reserved().add(line);
        storm.accounts().add(new Account(line, borrower, AccountKind.LINE, 0, Role.PLAIN));
        return line;
    }

    /** {@code n} aged wallet loans made at once - see {@link #agedLoan}. */
    private List<Aged> agedLoans(int n, int daysAgo) throws Exception {
        List<Callable<Aged>> makers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            makers.add(() -> agedLoan(daysAgo, false, false));
        }
        return LendingStormDatabaseTest.all(makers);
    }

    /** {@link #agedLoans}, each borrower banked - so their own bank can top their wallet up. */
    private List<Aged> bankedAgedLoans(int n, int daysAgo) throws Exception {
        List<Callable<Aged>> makers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            makers.add(() -> agedLoan(daysAgo, false, true));
        }
        return LendingStormDatabaseTest.all(makers);
    }

    /** An aged wallet loan the scene drives: its loan, borrower and token. */
    record Aged(String loan, Borrower borrower) {
        String token() {
            return borrower.customer().token();
        }
    }

    /**
     * A wallet loan "born in the past": accepted, disbursed and serviced once (nothing written), then aged so its first
     * instalment fell due {@code daysAgo} days before today (negative: falls due in the future) - its permit set far
     * ahead, so only the scene services it. {@code collects}: under terms that collect automatically (the loan's own).
     */
    private Aged agedLoan(int daysAgo, boolean collects) throws Exception {
        return agedLoan(daysAgo, collects, false);
    }

    /** {@link #agedLoan(int, boolean)}, the borrower {@code banked} - a bank account of their own registered. */
    private Aged agedLoan(int daysAgo, boolean collects, boolean banked) throws Exception {
        Borrower borrower = freshBorrower(banked);
        String today = scalar("SELECT lending.business_date(now(), 'UTC')::text");
        LocalDate due = LocalDate.parse(today).minusDays(daysAgo);
        int day = due.getDayOfMonth() > 28 ? 28 : due.getDayOfMonth();
        if (day != due.getDayOfMonth()) {
            due = due.withDayOfMonth(28);
        }
        String loan = acceptedLoan(borrower, 3_000_00, day, false, false);
        LendingStormWorld.awaitDatabase(PATIENCE, "the scene's loan is serviced once", "SELECT next_servicing_at >"
                + " statement_timestamp() FROM lending.loan WHERE id = ?::uuid", loan);
        String termsVersion = scalar("SELECT o.terms_version_id::text FROM lending.loan l JOIN lending.loan_offer o"
                + " ON o.id = l.offer_id WHERE l.id = ?::uuid", loan);
        LoanTerms terms = a().transactions().inTransaction(uow -> a().beans().getBean(LoanTermsStore.class)
                .version(uow, LoanTermsVersionId.of(UUID.fromString(termsVersion))).orElseThrow().terms());
        if (collects && !terms.autoCollection()) {
            tally.count("an aged loan wanted auto-collection under terms without it");
        }
        LendingStormWorld.bornInThePast(loan, terms, due, Instant.parse("2999-01-01T00:00:00Z"));
        return new Aged(loan, borrower);
    }

    /** A raced answer - or, when the racer threw, an unexplained failure and an answer no closed set admits. */
    private Answer answer(Object raced) {
        if (raced instanceof Answer response) {
            return response;
        }
        tally.unexplained("a racer failed: " + (raced instanceof Throwable failure ? failure.getClass().getSimpleName()
                : String.valueOf(raced)));
        return new Answer(0, "", "racer failed");
    }

    private Object serviceOn(Instance on, String loan) {
        return storm.service(on, loan);
    }

    private static void awaitStatus(String loan, String status) throws Exception {
        LendingStormWorld.awaitDatabase(PATIENCE, "the loan " + status, "SELECT status = ? FROM lending.loan"
                + " WHERE id = ?::uuid", status, loan);
    }

    private int covered(String scenario) {
        return storm.covered(scenario);
    }

    // ================================================================== the lock observations (C17)

    private static void lock(Connection holder, String sql, Object id) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement(sql)) {
            lock.setObject(1, id);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).as("the row to hold").isTrue();
            }
        }
    }

    private static int pid(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT pg_backend_pid()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getInt(1);
        }
    }

    /** A session seen blocked behind a held lock: its backend, its transaction's id (null before it writes), its statement. */
    private record Waiter(int pid, String xid, String query) {

        @Override
        public String toString() {
            String statement = query == null ? "" : query.replaceAll("\\s+", " ");
            return "[pid " + pid + ", xid " + xid + ": " + statement.substring(0, Math.min(100, statement.length())) + "]";
        }
    }

    /**
     * Every session seen blocked behind {@code blocker} in a statement mentioning {@code statement} - polled, the
     * sightings unioned, until one of them holds a transaction id (an acceptance has claimed its key before L0) or 20 s
     * pass. Who they are is judged by the caller, never assumed here.
     */
    private static List<Waiter> awaitBlockedBy(int blocker, String statement) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Map<Integer, Waiter> seen = new LinkedHashMap<>();
        while (System.nanoTime() < deadline) {
            // Read as the bootstrap owner: another role's statement text reads "<insufficient privilege>" otherwise.
            // pg_stat_activity is a per-transaction snapshot while pg_blocking_pids reads the live lock table: a row
            // qualifies only when it itself shows the session waiting on a lock, so its statement and transaction id
            // are the waiting ones - never a previous statement's text ('' for a fresh connection).
            try (Connection owner = DatabaseRoles.bootstrap();
                    PreparedStatement select = owner.prepareStatement("SELECT pid, backend_xid::text, query FROM"
                            + " pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid)) AND state = 'active'"
                            + " AND wait_event_type = 'Lock'")) {
                select.setInt(1, blocker);
                try (ResultSet waiting = select.executeQuery()) {
                    while (waiting.next()) {
                        String query = waiting.getString(3);
                        if (query != null && query.contains(statement)) {
                            Waiter waiter = new Waiter(waiting.getInt(1), waiting.getString(2), query);
                            seen.merge(waiter.pid(), waiter, (before, now) -> now.xid() != null ? now : before);
                        }
                    }
                }
            }
            if (seen.values().stream().anyMatch(waiter -> waiter.xid() != null)) {
                break;
            }
            Thread.sleep(25);
        }
        return List.copyOf(seen.values());
    }

    private static boolean lockableNoWait(String sql, String id) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(sql)) {
                lock.setString(1, id);
                lock.executeQuery().close();
                return true;
            } catch (SQLException held) {
                if ("55P03".equals(held.getSQLState())) {
                    return false;
                }
                throw held;
            } finally {
                probe.rollback();
            }
        }
    }
}
