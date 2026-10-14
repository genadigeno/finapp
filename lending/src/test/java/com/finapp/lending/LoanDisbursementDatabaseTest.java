package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.EUR;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.STANDING;
import static com.finapp.lending.DisbursementWorld.UNREACHABLE_WALLETS;
import static com.finapp.lending.DisbursementWorld.acceptedLoan;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.disburse;
import static com.finapp.lending.DisbursementWorld.execute;
import static com.finapp.lending.DisbursementWorld.feeIncome;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The disbursement to the wallet against a real database (`P11-TSK-014`; {@code lending V008}, {@code PHASE_11_PLAN.md}
 * sections 7.1 T-dis, 7.2, 7.4, 12.4 and 12.8, {@code LENDING_LIFECYCLES.md} sections 4.5 and 4.6; ADR-0097;
 * {@code INV-LND-07}, {@code INV-LND-01}, {@code INV-LND-03}, {@code INV-LND-13}, {@code INV-LED-01},
 * {@code INV-IDEM-01}, {@code INV-LIFE-04}): every race counted from the rows - the same disbursement on two instances
 * and on ten, one entry (scenario 1); the schedule generated once and held to its rows; a wallet that cannot be posted
 * retried and then cancelled at the deadline, nothing posted; a lost standing cancelling and releasing the commitment;
 * the entry balancing and naming the loan; a crash after the acceptance disbursed once by the sweep; the origination
 * fee deducted; the profile taken before the loan; committed exposure becoming outstanding with the headroom unmoved -
 * and every rank held for every writer by raw SQL. Credit, the standing and the wallet are {@link DisbursementWorld}'s
 * doubles; the real ports are proven end to end in {@code app}'s {@code LoanAccountDatabaseTest} and
 * {@code LendingCreditExposureDatabaseTest}.
 */
@Tag("database")
@DisplayName("the disbursement to the wallet (P11-TSK-014)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LoanDisbursementDatabaseTest {

    // ------------------------------------------------------------------ scenario 1

    @Test
    @DisplayName("scenario 1 - the same disbursement on two instances posts once: one POSTED, one NOTHING; one journal"
            + " entry under lending.disbursement:<loan>, one ACTIVE edge, the wallet credited once, one schedule, one"
            + " LoanDisbursed and one audit record")
    void theSameDisbursementOnTwoInstancesPostsOnce() throws Exception {
        DisbursementWorld.Accepted loan = acceptedLoan(5_000_00, 24);
        List<LoanDisbursements.Outcome.Kind> outcomes = race(2, index -> disburse(loan.loan()).kind());
        assertThat(outcomes).containsExactlyInAnyOrder(
                LoanDisbursements.Outcome.Kind.POSTED, LoanDisbursements.Outcome.Kind.NOTHING);
        assertDisbursedOnce(loan);
    }

    @Test
    @DisplayName("ten disbursers on one loan post once; ten sweepers over three loans post each exactly once")
    void tenDisbursersPostOnce() throws Exception {
        DisbursementWorld.Accepted loan = acceptedLoan(3_000_00, 12);
        List<LoanDisbursements.Outcome.Kind> outcomes = race(10, index -> disburse(loan.loan()).kind());
        assertThat(outcomes).filteredOn(LoanDisbursements.Outcome.Kind.POSTED::equals).hasSize(1);
        assertThat(outcomes).filteredOn(LoanDisbursements.Outcome.Kind.NOTHING::equals).hasSize(9);
        assertDisbursedOnce(loan);

        List<DisbursementWorld.Accepted> mine = List.of(acceptedLoan(1_000_00, 12), acceptedLoan(1_100_00, 12),
                acceptedLoan(1_200_00, 12));
        Set<LoanId> ours = Set.of(mine.get(0).loan(), mine.get(1).loan(), mine.get(2).loan());
        // Due on the database's clock, deterministically (P11-TSK-034): born "due at once", a disbursement is due only once
        // the clock passes its birth, which a step back after the birth postpones past every round below.
        for (LoanId each : ours) {
            asOwner("UPDATE lending.loan_disbursement SET next_attempt_at = statement_timestamp() - interval '1 minute'"
                    + " WHERE loan_id = '" + each.value() + "'");
        }
        Map<LoanId, List<LoanDisbursements.Outcome.Kind>> byLoan = new ConcurrentHashMap<>();
        race(10, index -> {
            try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                // Each sweeper: a claim with a short permit, then each claimed loan's step - the schedule's shape.
                for (int round = 0; round < 3; round++) {
                    List<LoanId> claimed = inOneTransaction(uow ->
                            DisbursementWorld.DISBURSEMENTS.claim(uow, 50, Duration.ofMillis(200)));
                    for (LoanId claimedLoan : claimed) {
                        if (ours.contains(claimedLoan)) {
                            byLoan.computeIfAbsent(claimedLoan, any -> Collections.synchronizedList(new ArrayList<>()))
                                    .add(disburse(claimedLoan).kind());
                        }
                    }
                    Thread.sleep(250);
                }
            }
            return null;
        });
        for (DisbursementWorld.Accepted each : mine) {
            assertThat(byLoan.get(each.loan())).as("each of the three loans posted exactly once")
                    .filteredOn(LoanDisbursements.Outcome.Kind.POSTED::equals).hasSize(1);
            assertDisbursedOnce(each);
        }
    }

    // ------------------------------------------------------------------ the schedule

    @Test
    @DisplayName("the schedule is generated once, from the accrual start - the entry's value date - by the pinned engine:"
            + " the read equals the engine's projection; a second v1 is a unique violation, a v2 refused until -027, the"
            + " rows immutable for every role, and a row added to a committed schedule fails its conservation at commit")
    void theScheduleIsGeneratedOnce() throws Exception {
        DisbursementWorld.Accepted loan = acceptedLoan(6_000_00, 18);
        assertThat(disburse(loan.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        assertThat(disburse(loan.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.NOTHING);
        UUID id = loan.loan().value();
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_instalment WHERE loan_id = ?", id)).isEqualTo(18);
        LocalDate valueDate = LocalDate.parse(scalar("SELECT value_date::text FROM lending.loan_disbursement"
                + " WHERE loan_id = ?", id));
        assertThat(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?", id))
                .isEqualTo(valueDate.toString());

        RepaymentSchedule read = inOneTransaction(uow ->
                DisbursementWorld.SCHEDULES.read(uow, loan.loan(), loan.party(), Optional.empty())).orElseThrow()
                .schedule();
        LoanTerms pinned = inOneTransaction(uow -> DisbursementWorld.TERMS_STORE.version(uow, LoanTermsVersionId.of(
                UUID.fromString(scalar("SELECT terms_version_id::text FROM lending.loan_agreement WHERE loan_id = ?",
                        id)))).orElseThrow().terms());
        Schedule projected = ((ScheduleOutcome.Projected) ScheduleEngineV1.project(pinned, loan.principal(), 18, 10,
                valueDate)).schedule();
        assertThat(read.schedule()).as("the stored schedule is the pinned engine's projection, row for row")
                .isEqualTo(projected);
        assertThat(read.version()).isEqualTo(1);
        assertThat(read.agreementVersion()).isEqualTo(1);
        Optional<LoanSchedules.Read> absent = inOneTransaction(uow -> DisbursementWorld.SCHEDULES.read(uow, loan.loan(),
                loan.party(), Optional.of(2)));
        assertThat(absent).as("no such version").isEmpty();
        Optional<LoanSchedules.Read> foreign = inOneTransaction(uow -> DisbursementWorld.SCHEDULES.read(uow, loan.loan(),
                UUID.randomUUID(), Optional.empty()));
        assertThat(foreign).as("another party's loan has no schedule to read").isEmpty();

        String copy = "INSERT INTO lending.repayment_schedule SELECT gen_random_uuid(), loan_id, %d, agreement_id,"
                + " agreement_version, schedule_engine_version, currency, scale, principal_minor, instalment_minor,"
                + " final_instalment_minor, total_interest_minor, term_months, start_date, intended_day, now(),"
                + " correlation_id FROM lending.repayment_schedule WHERE loan_id = '" + id + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, copy.formatted(1), "23505");
            owner.rollback();
            refused(owner, copy.formatted(2), "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan_instalment SET interest_minor = interest_minor + 1 WHERE loan_id = '"
                    + id + "'", "P0001");
            owner.rollback();
            refused(owner, "DELETE FROM lending.repayment_schedule WHERE loan_id = '" + id + "'", "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.accrual_start SET start_date = start_date + 1 WHERE loan_id = '" + id + "'",
                    "P0001");
            owner.rollback();
            execute(owner, "INSERT INTO lending.loan_instalment SELECT gen_random_uuid(), schedule_id, loan_id,"
                    + " sequence + 100, due_date, due_date + 30, 30, currency, scale, 100, 0, 100, 100, 0"
                    + " FROM lending.loan_instalment WHERE loan_id = '" + id + "' AND sequence = 18");
            assertThatThrownBy(owner::commit).as("a row added to a committed schedule breaks its conservation")
                    .isInstanceOf(SQLException.class).hasMessageContaining("INV-LND-03");
            owner.rollback();
        }
        for (String table : List.of("lending.repayment_schedule", "lending.loan_instalment", "lending.accrual_start")) {
            try (Connection app = DatabaseRoles.application()) {
                refused(app, "UPDATE " + table + " SET loan_id = loan_id WHERE loan_id = '" + id + "'", "42501");
                refused(app, "DELETE FROM " + table + " WHERE loan_id = '" + id + "'", "42501");
            }
        }
    }

    // ------------------------------------------------------------------ failures

    @Test
    @DisplayName("a suspended wallet is retried, then cancelled: each attempt before the deadline writes nothing and"
            + " posts nothing; a DISBURSEMENT_FAILED before the deadline is refused for every writer; past it the attempt"
            + " cancels the loan DISBURSEMENT_FAILED, nothing posted, the commitment released; an unreachable wallet"
            + " retries alike")
    void aSuspendedWalletRetriesThenCancels() throws Exception {
        DisbursementWorld.Accepted loan = acceptedLoan(2_500_00, 12);
        UUID id = loan.loan().value();
        LedgerAccountId wallet = walletOf(loan.party());
        inOneTransaction(uow -> {
            LEDGER_ACCOUNTS.lockForUpdate(uow, wallet);
            return LEDGER_ACCOUNTS.moveStatus(uow, wallet, LedgerAccountStatus.ACTIVE,
                    LedgerAccountStatus.POSTING_SUSPENDED, java.time.Instant.now());
        });
        for (int attempt = 0; attempt < 2; attempt++) {
            LoanDisbursements.Outcome retried = disburse(loan.loan());
            assertThat(retried.kind()).isEqualTo(LoanDisbursements.Outcome.Kind.RETRYING);
            assertThat(retried.path()).contains(PayoutPreference.WALLET);
        }
        assertNothingPosted(loan, "PENDING_DISBURSEMENT", "PENDING");
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE lending.loan_disbursement SET status = 'FAILED', failure_reason = 'DISBURSEMENT_FAILED'"
                    + " WHERE loan_id = '" + id + "'", "P0001");
            app.rollback();
        }
        // The deadline passes - the fixture moves the stamps, the clock it is judged on is the database's.
        asOwner("UPDATE lending.loan_disbursement SET created_at = statement_timestamp() - interval '25 hours',"
                + " deadline_at = statement_timestamp() - interval '1 hour' WHERE loan_id = '" + id + "'");
        Money committedBefore = committed();
        LoanDisbursements.Outcome cancelled = disburse(loan.loan());
        assertThat(cancelled.kind()).isEqualTo(LoanDisbursements.Outcome.Kind.CANCELLED);
        assertThat(cancelled.reason()).contains(CancellationReason.DISBURSEMENT_FAILED);
        assertNothingPosted(loan, "CANCELLED", "FAILED");
        assertThat(scalar("SELECT cancellation_reason FROM lending.loan WHERE id = ?", id)).isEqualTo("DISBURSEMENT_FAILED");
        assertThat(scalar("SELECT failure_reason FROM lending.loan_disbursement WHERE loan_id = ?", id))
                .isEqualTo("DISBURSEMENT_FAILED");
        assertThat(committed()).as("the commitment released").isEqualTo(committedBefore.minus(loan.principal()));
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanCancelled'", id)).isEqualTo(1);
        assertThat(disburse(loan.loan()).kind()).as("terminal").isEqualTo(LoanDisbursements.Outcome.Kind.NOTHING);

        DisbursementWorld.Accepted unreachable = acceptedLoan(1_500_00, 12);
        UNREACHABLE_WALLETS.add(unreachable.party());
        assertThat(disburse(unreachable.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.RETRYING);
        assertNothingPosted(unreachable, "PENDING_DISBURSEMENT", "PENDING");
        UNREACHABLE_WALLETS.remove(unreachable.party());
        assertThat(disburse(unreachable.loan()).kind()).as("reachable again: posted").isEqualTo(
                LoanDisbursements.Outcome.Kind.POSTED);
    }

    @Test
    @DisplayName("a lost standing cancels the loan STANDING_LOST at once and releases the commitment - the headroom"
            + " restored, the party's committed exposure gone - nothing posted; the decision stays consumed")
    void standingLostCancelsAndReleasesTheCommitment() {
        DisbursementWorld.Accepted loan = acceptedLoan(4_200_00, 12);
        UUID id = loan.loan().value();
        Money headroomBefore = headroom().headroom();
        assertThat(partyCommitted(loan.party())).isEqualTo(loan.principal());
        STANDING.put(loan.party(), false);
        try {
            LoanDisbursements.Outcome cancelled = disburse(loan.loan());
            assertThat(cancelled.kind()).isEqualTo(LoanDisbursements.Outcome.Kind.CANCELLED);
            assertThat(cancelled.reason()).contains(CancellationReason.STANDING_LOST);
        } finally {
            STANDING.remove(loan.party());
        }
        assertNothingPosted(loan, "CANCELLED", "FAILED");
        assertThat(scalar("SELECT cancellation_reason FROM lending.loan WHERE id = ?", id)).isEqualTo("STANDING_LOST");
        assertThat(headroom().headroom()).as("capital headroom restored").isEqualTo(headroomBefore.plus(loan.principal()));
        assertThat(partyCommitted(loan.party()).isZero()).as("the commitment released").isTrue();
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ? AND from_status ="
                + " 'PENDING_DISBURSEMENT' AND to_status = 'CANCELLED' AND reason = 'STANDING_LOST'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", id))
                .as("the agreement - and the consumption that named it - stand").isEqualTo(1);
    }

    // ------------------------------------------------------------------ the money

    @Test
    @DisplayName("the entry balances and names the loan: DR LOAN_PRINCIPAL P / CR the wallet P, posting and value date the"
            + " business date of the database clock, the reference the loan; the loan's figures are ledger balances; the"
            + " event carries the entry and no wallet; the audit record carries no amount")
    void theEntryBalancesAndNamesTheLoan() {
        DisbursementWorld.Accepted loan = acceptedLoan(7_300_00, 24);
        UUID id = loan.loan().value();
        assertThat(disburse(loan.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        String entry = scalar("SELECT journal_entry_id::text FROM lending.loan_disbursement WHERE loan_id = ?", id);
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE entry_id = ?::uuid", entry)).isEqualTo(2);
        assertThat(scalar("SELECT sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END)::text"
                + " FROM ledger.journal_line WHERE entry_id = ?::uuid", entry)).as("INV-LED-01").isEqualTo("0");
        assertThat(scalar("SELECT reference FROM ledger.journal_entry WHERE id = ?::uuid", entry)).isEqualTo(id.toString());
        assertThat(scalar("SELECT idempotency_scope FROM ledger.journal_entry WHERE id = ?::uuid", entry))
                .isEqualTo("ledger.post:lending.disbursement:" + id);
        // The business date of the deciding statement on the database clock in the pinned zone - or the day before, when
        // the step read its date before a midnight its decision crossed (V008's window) - never an instance clock's date
        // (P11-TSK-032).
        LocalDate decided = LocalDate.parse(scalar("SELECT lending.business_date(d.decided_at, t.servicing_zone)::text"
                + " FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id"
                + " JOIN lending.loan_offer o ON o.id = l.offer_id"
                + " JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id WHERE d.loan_id = ?", id));
        assertThat(scalar("SELECT posting_date::text || '|' || value_date::text FROM ledger.journal_entry"
                + " WHERE id = ?::uuid", entry)).isIn(decided + "|" + decided,
                decided.minusDays(1) + "|" + decided.minusDays(1));
        assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL))).isEqualTo(loan.principal());
        assertThat(netDebit(walletOf(loan.party()))).as("the wallet credited P").isEqualTo(loan.principal().negated());
        Loans.Read read = inOneTransaction(uow -> new Loans(DisbursementWorld.LOAN_STORE, LEDGER_ACCOUNTS,
                DisbursementWorld.BALANCES, DisbursementWorld.PAYOUT_STORE, DisbursementWorld.CONDITIONS).read(uow, loan.loan(), loan.party()))
                .orElseThrow();
        assertThat(read.owned().loan().status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(read.balances().principalNotDue()).isEqualTo(loan.principal());
        String payload = scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanDisbursed'", id);
        assertThat(payload).contains(entry, "\"path\":\"WALLET\"", "\"principalMinor\":\"730000\"")
                .doesNotContain(walletOf(loan.party()).value().toString());
        String summary = scalar("SELECT change_summary FROM platform.audit_record WHERE target_id = ?"
                + " AND operation = 'lending.LoanDisbursed'", id.toString());
        assertThat(summary).contains(entry).doesNotContain("7300", "730000");
    }

    @Test
    @DisplayName("a crash after the acceptance - nothing disbursed - is disbursed once by the sweep: the claim finds it at"
            + " once, its step posts, and a later claim no longer finds it")
    void aCrashAfterAcceptanceDisbursesOnce() {
        DisbursementWorld.Accepted loan = acceptedLoan(2_000_00, 12);
        List<LoanId> claimed = inOneTransaction(uow ->
                DisbursementWorld.DISBURSEMENTS.claim(uow, 1_000, Duration.ofMinutes(1)));
        assertThat(claimed).as("due at once").contains(loan.loan());
        assertThat(disburse(loan.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        asOwner("UPDATE lending.loan_disbursement SET next_attempt_at = statement_timestamp() - interval '1 second'"
                + " WHERE loan_id = '" + loan.loan().value() + "'");
        List<LoanId> again = inOneTransaction(uow -> DisbursementWorld.DISBURSEMENTS.claim(uow, 1_000,
                Duration.ofMinutes(1)));
        assertThat(again)
                .as("a POSTED disbursement is never claimed again, whatever its permit").doesNotContain(loan.loan());
        assertDisbursedOnce(loan);
    }

    @Test
    @DisplayName("the origination fee is deducted: 150 bps of 4,000.00 - DR LOAN_PRINCIPAL 4,000.00 / CR the wallet"
            + " 3,940.00, CR LOAN_FEE_INCOME 60.00; the borrower owes the whole principal")
    void theOriginationFeeIsDeducted() {
        DisbursementWorld.ensureActive();
        DisbursementWorld.activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899")
                .originationFee(LoanTerms.OriginationFee.ofBasisPoints(150)).build());
        try {
            DisbursementWorld.Accepted loan = acceptedLoan(4_000_00, 12);
            Money feesBefore = netDebit(feeIncome());
            assertThat(disburse(loan.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
            String entry = scalar("SELECT journal_entry_id::text FROM lending.loan_disbursement WHERE loan_id = ?",
                    loan.loan().value());
            assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE entry_id = ?::uuid", entry)).isEqualTo(3);
            assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL))).isEqualTo(eur(4_000_00));
            assertThat(netDebit(walletOf(loan.party()))).isEqualTo(eur(-3_940_00));
            assertThat(netDebit(feeIncome()).minus(feesBefore)).isEqualTo(eur(-60_00));
            assertThat(scalar("SELECT sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END)::text"
                    + " FROM ledger.journal_line WHERE entry_id = ?::uuid", entry)).isEqualTo("0");
        } finally {
            DisbursementWorld.activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
    }

    // ------------------------------------------------------------------ the lock order and the exposure

    @Test
    @DisplayName("the disbursement takes the profile, then the loan: holding the loan, a disburser waits on it while"
            + " holding the profile (a second taker of the profile waits behind it); holding the profile, a disburser waits"
            + " on it and the loan stays free")
    void disbursementTakesTheProfileThenTheLoan() throws Exception {
        DisbursementWorld.Accepted first = acceptedLoan(1_800_00, 12);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            lock(holder, "SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE", first.loan().value());
            Future<LoanDisbursements.Outcome> disbursing = pool.submit(() -> disburse(first.loan()));
            DisbursementWorld.Blocked disburser = awaitBlockedBy(pid(holder));
            assertThat(disburser.query()).as("the disburser waits on the loan (L3)").contains("lending.loan");
            Future<?> profile = pool.submit(() -> inOneTransaction(uow -> {
                DisbursementWorld.CREDIT.lockExposure(uow, first.party());
                return null;
            }));
            assertThat(awaitBlockedBy(disburser.pid()).query()).as("it holds the profile (L0) while it waits")
                    .contains("pg_advisory_xact_lock");
            holder.rollback();
            assertThat(disbursing.get(2, TimeUnit.MINUTES).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
            profile.get(2, TimeUnit.MINUTES);
        }

        DisbursementWorld.Accepted second = acceptedLoan(1_900_00, 12);
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            DisbursementWorld.CREDIT.lockExposure(holder, second.party());
            Future<LoanDisbursements.Outcome> disbursing = pool.submit(() -> disburse(second.loan()));
            assertThat(awaitBlockedBy(pid(holder)).query()).as("the disburser waits on the profile first")
                    .contains("pg_advisory_xact_lock");
            try (Connection probe = DatabaseRoles.application()) {
                probe.setAutoCommit(false);
                lock(probe, "SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE NOWAIT", second.loan().value());
                probe.rollback();
            }
            holder.rollback();
            assertThat(disbursing.get(2, TimeUnit.MINUTES).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("exposure moves from committed to outstanding in one commit: before, the agreement principal committed"
            + " and no principal line; after, no commitment and the principal line - the party's figure and the capital"
            + " headroom unmoved, the lending-funded total unmoved")
    void exposureMovesFromCommittedToOutstanding() {
        DisbursementWorld.Accepted loan = acceptedLoan(6_600_00, 12);
        LendingCapitalHeadroom.Headroom before = headroom();
        assertThat(partyCommitted(loan.party())).isEqualTo(loan.principal());
        assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL)).isZero()).isTrue();
        assertThat(disburse(loan.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        LendingCapitalHeadroom.Headroom after = headroom();
        assertThat(partyCommitted(loan.party()).isZero()).as("no commitment once ACTIVE").isTrue();
        assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL))).isEqualTo(loan.principal());
        assertThat(after.committed()).isEqualTo(before.committed().minus(loan.principal()));
        assertThat(after.outstanding()).isEqualTo(before.outstanding().plus(loan.principal()));
        assertThat(after.headroom()).as("headroom does not move at disbursement").isEqualTo(before.headroom());
        assertThat(after.lendingFunded()).isEqualTo(before.lendingFunded());
    }

    // ------------------------------------------------------------------ the ranks

    @Test
    @DisplayName("the loan's and the disbursement's edges are held for every writer: ACTIVE without the disbursement"
            + " POSTED refused; a POSTED disbursement without its loan's edge refused at commit; ACTIVE -> CANCELLED, out"
            + " of CANCELLED and back to PENDING_DISBURSEMENT refused; a wrong value date refused; the stamps and the"
            + " frozen columns beyond the application role; nothing deleted")
    void theEdgesAreHeldForEveryWriter() throws Exception {
        DisbursementWorld.Accepted pending = acceptedLoan(1_300_00, 12);
        String p = pending.loan().value().toString();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE lending.loan SET status = 'ACTIVE' WHERE id = '" + p + "'", "P0001");
            app.rollback();
            execute(app, "UPDATE lending.loan_disbursement SET status = 'POSTED', journal_entry_id = gen_random_uuid(),"
                    + " value_date = (statement_timestamp() AT TIME ZONE 'UTC')::date WHERE loan_id = '" + p + "'");
            assertThatThrownBy(app::commit).as("POSTED commits only beside the loan's ACTIVE edge")
                    .isInstanceOf(SQLException.class).hasMessageContaining("without its loan ACTIVE");
            app.rollback();
            refused(app, "UPDATE lending.loan_disbursement SET status = 'POSTED', journal_entry_id = gen_random_uuid(),"
                    + " value_date = DATE '2001-01-01' WHERE loan_id = '" + p + "'", "P0001");
            app.rollback();
            refused(app, "UPDATE lending.loan SET closed_at = now() WHERE id = '" + p + "'", "42501");
            app.rollback();
            refused(app, "UPDATE lending.loan_disbursement SET deadline_at = now() WHERE loan_id = '" + p + "'", "42501");
            app.rollback();
            refused(app, "DELETE FROM lending.loan_disbursement WHERE loan_id = '" + p + "'", "42501");
            app.rollback();
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE lending.loan_disbursement SET deadline_at = deadline_at + interval '1 day'"
                    + " WHERE loan_id = '" + p + "'", "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan_disbursement SET next_attempt_at = next_attempt_at,"
                    + " failure_reason = 'STANDING_LOST' WHERE loan_id = '" + p + "'", "P0001");
            owner.rollback();
        }

        DisbursementWorld.Accepted disbursed = acceptedLoan(1_400_00, 12);
        assertThat(disburse(disbursed.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        String d = disbursed.loan().value().toString();
        STANDING.put(pending.party(), false);
        try {
            assertThat(disburse(pending.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.CANCELLED);
        } finally {
            STANDING.remove(pending.party());
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE lending.loan SET status = 'CANCELLED', cancellation_reason = 'STANDING_LOST'"
                    + " WHERE id = '" + d + "'", "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan SET status = 'PENDING_DISBURSEMENT' WHERE id = '" + d + "'", "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan SET status = 'ACTIVE', closed_at = NULL, cancellation_reason = NULL"
                    + " WHERE id = '" + p + "'", "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan_disbursement SET status = 'PENDING', journal_entry_id = NULL,"
                    + " value_date = NULL WHERE loan_id = '" + d + "'", "P0001");
            owner.rollback();
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** One disbursement of {@code loan}: one entry, one ACTIVE edge, the wallet credited once, one of every fact. */
    private static void assertDisbursedOnce(DisbursementWorld.Accepted loan) {
        UUID id = loan.loan().value();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.disbursement:" + id)).as("one journal entry").isEqualTo(1);
        assertThat(scalar("SELECT journal_entry_id::text FROM lending.loan_disbursement WHERE loan_id = ?", id))
                .isEqualTo(scalar("SELECT id::text FROM ledger.journal_entry WHERE idempotency_scope = ?",
                        "ledger.post:lending.disbursement:" + id));
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?", id)).isEqualTo("ACTIVE");
        assertThat(scalar("SELECT status FROM lending.loan_disbursement WHERE loan_id = ?", id)).isEqualTo("POSTED");
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ? AND to_status = 'ACTIVE'", id))
                .as("one ACTIVE edge").isEqualTo(1);
        assertThat(netDebit(walletOf(loan.party()))).as("the wallet credited once").isEqualTo(loan.principal().negated());
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanDisbursed'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                + " AND operation = 'lending.LoanDisbursed'", id.toString())).isEqualTo(1);
    }

    /** Nothing of a disbursement exists for {@code loan}: no entry, no claim on the key, no schedule, no event. */
    private static void assertNothingPosted(DisbursementWorld.Accepted loan, String loanStatus, String disbursementStatus) {
        UUID id = loan.loan().value();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.disbursement:" + id)).isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE scope = 'ledger.post'"
                + " AND idempotency_key = ?", "lending.disbursement:" + id)).as("the key not consumed").isZero();
        assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL)).isZero()).isTrue();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?", id)).isEqualTo(loanStatus);
        assertThat(scalar("SELECT status FROM lending.loan_disbursement WHERE loan_id = ?", id))
                .isEqualTo(disbursementStatus);
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanDisbursed'", id)).isZero();
    }

    private static LendingCapitalHeadroom.Headroom headroom() {
        return inOneTransaction(uow -> DisbursementWorld.HEADROOM.atRest(uow, EUR));
    }

    private static Money committed() {
        return inOneTransaction(uow -> DisbursementWorld.LOAN_STORE.committedPrincipal(uow, EUR));
    }

    /** The party's committed principal as credit's exposure read sees it: the definer view's PENDING loans. */
    private static Money partyCommitted(UUID party) {
        long minor = Long.parseLong(scalar("SELECT coalesce(sum(agreement_amount_minor), 0)::text"
                + " FROM lending.party_principal_exposure WHERE party_id = ? AND status = 'PENDING_DISBURSEMENT'", party));
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static void lock(Connection holder, String sql, Object id) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement(sql)) {
            lock.setObject(1, id);
            lock.executeQuery().close();
        }
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState}. */
    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> execute(connection, sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }
}
