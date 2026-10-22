package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplPayoffs;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplRepayments;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.ServicingOutcome;
import com.finapp.identity.Authorization;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.TransactionRunner;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The BNPL proofs over the real database and ledger (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12; gate
 * criterion 5): {@link BnplProofs} through {@code JdbcBnplRecordStore} and the wired three-way reads, on one read-only
 * {@code REPEATABLE READ} snapshot - and each plant the backlog names, made with the owner role in SQL (triggers off for
 * the plant only, {@code session_replication_role = replica}) or through the ledger's own posting service, and restored:
 *
 * <ul>
 *   <li>a byte flipped in an accrual, a billing, an allocation, a refund adjustment or an agreement → {@code DIVERGED};
 *   <li>a raw posting to a BNPL account without a bnpl row → the subledger proof red; a raw posting to a merchant payable
 *       under a {@code bnpl.} key without a purchase → the three-way proof's merchant leg red; a raw projection edit → the
 *       proofs still green (they read journal lines);
 *   <li>a planted pre-floor repayment and planted held credit beside a past-due item → {@code DIVERGED};
 *   <li>{@link #aProofDuringServicingIsStable} - readings racing the servicing sweep, refunds and repayments over rounds:
 *       every reading green, each in its own snapshot - the ten-instance answer's count.
 * </ul>
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 * Written by `P12-TSK-025` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // The Scopes are used for their close side effect (the idiom).
@DisplayName("the BNPL proofs over the real database and ledger (P12-TSK-025)")
// The suite's cases share one database, and two of them plant a raw posting no row explains - permanent, as every ledger
// line is - which every later whole-book proof then rightly fails. They run last, the payable's plant before the BNPL
// account's (whose case alone tolerates the other's); every other case runs before them, in any order (P12-TSK-028).
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BnplProofDatabaseTest {

    /** The two planting cases' places: after every unannotated case (JUnit's default order is MAX_VALUE / 2). */
    private static final int PLANTS_THE_PAYABLE = Integer.MAX_VALUE - 1;
    private static final int PLANTS_A_BNPL_ACCOUNT = Integer.MAX_VALUE;

    private static final long GROSS = 600_00L;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration terms;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplOrigination origination;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanWallets loanWallets;
    @Autowired private BnplServicing servicing;
    @Autowired private BnplServicingStore servicingStore;
    @Autowired private BnplAccruals accruals;
    @Autowired private BnplRepayments repayments;
    @Autowired private BnplRefunds refunds;
    @Autowired private BnplPayoffs payoffs;
    @Autowired private BnplProofs proofs;
    @Autowired private BnplProofMetrics proofMetrics;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private TransactionRunner settlementTransactionRunner;

    private BnplPurchaseWorld world;

    record Bought(BnplObligationId obligation, BnplAgreementId agreement, BnplPurchaseId purchase, UUID party,
            BnplApplicationClient.Merchant merchant) {

        String o() {
            return "'" + obligation.value() + "'";
        }
    }

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        // Recognised capital, never the world's raw plant: the proof's capital arm reads every line on LENDING_CAPITAL
        // against a recognition, and fails a raw posting - correctly (P12-TSK-028).
        new BnplRecognisedCapital(world.client, authorization, headroom, transactions, reception, parsing, acceptance,
                settlementTransactionRunner).headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("at rest every purchase is three-way zero, every obligation subledger zero and replayed IDENTICAL -"
            + " repaid, refunded with an excess and closed among them; the gauges agree")
    void atRestEverythingHolds() throws Exception {
        Bought repaid = bought();
        toAge(repaid, 2, 0);
        serviceStep(repaid);
        repay(repaid, "r2", 150_00);
        Bought refunded = bought();
        refund(refunded, "refund-all", GROSS);
        assertHolds();
        BnplProofs.Reading reading = proofMetrics.readNow();
        assertThat(reading.failing()).isZero();
        assertThat(reading.replaying(BnplReplayProof.Verdict.DIVERGED)).isZero();
        assertThat(reading.currencies(false)).isZero();
    }

    @Test
    @DisplayName("a byte flipped in a billing, an allocation, a refund adjustment or an agreement's terms (its hash"
            + " recomputed so the CHECK holds) is DIVERGED under its check - and restored, IDENTICAL again")
    void aFlippedFactDiverges() throws Exception {
        Bought bought = bought();
        toAge(bought, 2, 0);
        serviceStep(bought);
        repay(bought, "r2", 150_00);
        refund(bought, "refund-1", 100_00);
        String billing = scalar("SELECT id::text FROM bnpl.bnpl_billing WHERE obligation_id = " + bought.o()
                + " ORDER BY value_date DESC LIMIT 1");
        flipped(bought, "UPDATE bnpl.bnpl_billing SET principal_minor = principal_minor + 1 WHERE id = '" + billing + "'",
                "UPDATE bnpl.bnpl_billing SET principal_minor = principal_minor - 1 WHERE id = '" + billing + "'",
                BnplReplayProof.Check.BILLING);
        String allocation = scalar("SELECT a.id::text FROM bnpl.bnpl_repayment_allocation a JOIN bnpl.bnpl_repayment r"
                + " ON r.id = a.repayment_id WHERE r.obligation_id = " + bought.o() + " AND r.kind = 'CUSTOMER' AND"
                + " a.target = 'DUE_ITEM' AND a.component = 'PRINCIPAL' LIMIT 1");
        flipped(bought, "UPDATE bnpl.bnpl_repayment_allocation SET component = 'INTEREST' WHERE id = '" + allocation + "'",
                "UPDATE bnpl.bnpl_repayment_allocation SET component = 'PRINCIPAL' WHERE id = '" + allocation + "'",
                BnplReplayProof.Check.ALLOCATION);
        String adjustment = scalar("SELECT id::text FROM bnpl.bnpl_refund_adjustment WHERE obligation_id = " + bought.o()
                + " LIMIT 1");
        flipped(bought, "UPDATE bnpl.bnpl_refund_adjustment SET whole = NOT whole WHERE id = '" + adjustment + "'",
                "UPDATE bnpl.bnpl_refund_adjustment SET whole = NOT whole WHERE id = '" + adjustment + "'",
                BnplReplayProof.Check.REFUND);
        String terms = scalar("SELECT canonical_terms FROM bnpl.bnpl_agreement WHERE obligation_id = " + bought.o()
                + " AND version = 1");
        String flippedTerms = terms.replaceFirst("PAY_IN_4", "PAY_IN_5");
        flipped(bought, agreementTerms(bought, flippedTerms), agreementTerms(bought, terms), BnplReplayProof.Check.TERMS);
    }

    @Test
    @Order(PLANTS_A_BNPL_ACCOUNT)
    @DisplayName("a raw posting to a BNPL account that no bnpl row names fails the subledger proof (UNEXPLAINED_ENTRIES)"
            + " though the trial balance stays zero")
    void aRawPostingToABnplAccountFailsTheSubledger() throws Exception {
        Bought bought = bought();
        LedgerAccountId principal = account(bought, AccountPurpose.BNPL_PRINCIPAL);
        LedgerAccountId wallet = world.walletOf(bought.party());
        post("fixture.raw:" + UUID.randomUUID(), List.of(new JournalLine(wallet, Direction.DEBIT, eur(1_00)),
                new JournalLine(principal, Direction.CREDIT, eur(1_00))));
        BnplProofs.Proven proven = proven(bought);
        assertThat(proven.subledger().failures()).contains("UNEXPLAINED_ENTRIES", "BNPL_PRINCIPAL");
        BnplProofs.Reading reading = transactions.inSnapshot(proofs::prove);
        assertThat(reading.currencies().get(0).failures()).contains("CUSTOMER_LEG").doesNotContain("TRIAL_BALANCE");
    }

    @Test
    @Order(PLANTS_THE_PAYABLE)
    @DisplayName("a raw posting to a merchant payable under a bnpl. key without a purchase fails the merchant leg")
    void aRawBnplKeyedPayablePostingFailsTheMerchantLeg() throws Exception {
        Bought bought = bought();
        assertHolds();
        LedgerAccountId payable = LedgerAccountId.of(BnplPurchaseWorld.payableOf(bought.merchant()));
        LedgerAccountId wallet = world.walletOf(bought.party());
        post("bnpl.financing:" + UUID.randomUUID(), List.of(new JournalLine(wallet, Direction.DEBIT, eur(5_00)),
                new JournalLine(payable, Direction.CREDIT, eur(5_00))));
        BnplProofs.Reading reading = transactions.inSnapshot(proofs::prove);
        assertThat(reading.currencies().get(0).failures()).contains("MERCHANT_LEG");
        assertThat(reading.failing()).as("the customer leg cannot see a payable posting").isZero();
    }

    @Test
    @DisplayName("a database clock stepped back replays IDENTICAL - the record's order, never the clock (P12-TST-001's"
            + " finding, bnpl V017): a repayment stamped a second before the purchase that made its obligation, and"
            + " instalment 1's billing stamped before its own schedule in the purchase's one transaction")
    void aClockSteppedBackReplaysInTheRecordsOrder() throws Exception {
        Bought bought = bought();
        repay(bought, "held", 40_00); // nothing is due yet: held as credit
        assertThat(proven(bought).replay().verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        String o = bought.o();
        // The stamps a database clock stepped back would have written - the facts, their rows and their record order
        // untouched: the customer's repayment a second before the purchase's billing, then that billing 600 ms before the
        // schedule version 1 its own transaction wrote first. The storm's first load round met both shapes.
        asOwner("UPDATE bnpl.bnpl_repayment SET received_at = (SELECT min(b.billed_at) FROM bnpl.bnpl_billing b WHERE"
                + " b.obligation_id = " + o + ") - interval '1 second' WHERE obligation_id = " + o + " AND kind = 'CUSTOMER';"
                + " UPDATE bnpl.bnpl_billing SET billed_at = (SELECT s.created_at FROM bnpl.bnpl_schedule s WHERE"
                + " s.obligation_id = " + o + " AND s.schedule_version = 1) - interval '600 milliseconds' WHERE"
                + " obligation_id = " + o);
        assertThat(scalar("SELECT (r.received_at < b.billed_at AND b.billed_at < s.created_at)::text FROM"
                + " bnpl.bnpl_repayment r, bnpl.bnpl_billing b, bnpl.bnpl_schedule s WHERE r.obligation_id = " + o
                + " AND r.kind = 'CUSTOMER' AND b.obligation_id = " + o + " AND s.obligation_id = " + o))
                .as("the stamps now disagree with the order the facts were written in").isEqualTo("true");
        BnplProofs.Proven proven = proven(bought);
        assertThat(proven.replay().verdict()).as("the replay walks the record's order: %s", proven.replay())
                .isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        assertThat(proven.subledger().holds()).isTrue();
    }

    @Test
    @DisplayName("a repayment that waited on B4 behind a refund re-amortising the agreement is made under the version the"
            + " refund committed (P12-TST-001's finding): the lock and the agreement in force in two statements - in one,"
            + " READ COMMITTED's re-check refreshed only the locked row and the repayment met its trigger with version 1")
    void aRepaymentBehindARefundReadsTheVersionItCommitted() throws Exception {
        Bought bought = bought();
        world.topUpWallet(bought.party(), eur(50_00)); // the repayment's own money, paid in before the race
        CountDownLatch refunded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        int[] lockedVersion = new int[1];
        try {
            // The refund holds B4 with agreement version 2 written, uncommitted, until released.
            Future<BnplRefunds.Refunded> refund = pool.submit(() -> refund(bought, "while-held", 100_00, () -> {
                refunded.countDown();
                await(release);
            }));
            assertThat(refunded.await(60, TimeUnit.SECONDS)).as("the refund wrote its versions").isTrue();
            Future<BnplRepayments.Repaid> repaid = pool.submit(() -> repayFunded(bought, "behind-the-refund", 50_00));
            // Every other B4 taker - the step, the accrual, a payoff, a waiver, a reversal - through the servicing lock.
            Future<?> step = pool.submit(() -> transactions.inTransaction(uow -> {
                lockedVersion[0] = servicingStore.lock(uow, bought.obligation()).orElseThrow().agreementVersion();
                return null;
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            // Seen as the application role - the role whose sessions' statements it may read (P12-TSK-028's lesson).
            while (waitingOnB4() < 2) {
                assertThat(System.nanoTime()).as("the repayment waits on B4").isLessThan(deadline);
                Thread.sleep(50);
            }
            release.countDown();
            refund.get(60, TimeUnit.SECONDS);
            repaid.get(60, TimeUnit.SECONDS);
            step.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(scalar("SELECT max(version)::text FROM bnpl.bnpl_agreement WHERE obligation_id = " + bought.o()))
                .as("the refund re-amortised").isEqualTo("2");
        assertThat(scalar("SELECT agreement_version::text FROM bnpl.bnpl_repayment WHERE obligation_id = " + bought.o()
                + " AND request_key = 'behind-the-refund'")).as("made under the version in force").isEqualTo("2");
        assertThat(lockedVersion[0]).as("the servicing lock reads the version in force").isEqualTo(2);
        assertThat(proven(bought).replay().verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
    }

    @Test
    @DisplayName("a raw projection edit leaves the proofs green: they fold journal lines, never the balance projection")
    void aRawProjectionEditLeavesTheProofsGreen() throws Exception {
        Bought bought = bought();
        LedgerAccountId principal = account(bought, AccountPurpose.BNPL_PRINCIPAL);
        asOwner("UPDATE ledger.account_balance SET posted_minor = posted_minor + 777 WHERE ledger_account_id = '"
                + principal.value() + "'");
        try {
            assertHolds();
        } finally {
            asOwner("UPDATE ledger.account_balance SET posted_minor = posted_minor - 777 WHERE ledger_account_id = '"
                    + principal.value() + "'");
        }
    }

    @Test
    @DisplayName("R1's arm: a repayment re-dated before its obligation's floor (row and entry) is VALUE_DATE")
    void aPlantedPreFloorRepaymentDiverges() throws Exception {
        Bought bought = bought();
        toAge(bought, 2, 2);
        serviceStep(bought);
        repay(bought, "r2", 150_00);
        String repayment = scalar("SELECT id::text FROM bnpl.bnpl_repayment WHERE obligation_id = " + bought.o()
                + " AND kind = 'CUSTOMER'");
        String entry = scalar("SELECT journal_entry_id::text FROM bnpl.bnpl_repayment WHERE id = '" + repayment + "'");
        String back = "UPDATE bnpl.bnpl_repayment SET value_date = value_date - 5 WHERE id = '" + repayment + "';"
                + " UPDATE ledger.journal_entry SET value_date = value_date - 5 WHERE id = '" + entry + "'";
        String forth = "UPDATE bnpl.bnpl_repayment SET value_date = value_date + 5 WHERE id = '" + repayment + "';"
                + " UPDATE ledger.journal_entry SET value_date = value_date + 5 WHERE id = '" + entry + "'";
        flipped(bought, back, forth, BnplReplayProof.Check.VALUE_DATE);
    }

    @Test
    @DisplayName("RG-M5: held credit beside a past-due item at a servicing step's end - the step's catch-up application"
            + " (the application that applied it) and its entry planted away, the step's condition change written - is"
            + " CATCH_UP_MISSING")
    void plantedHeldCreditBesideAPastDueItemDiverges() throws Exception {
        Bought bought = bought();
        repay(bought, "held", 40_00);
        toAge(bought, 2, 6);
        ServicingOutcome step = serviceStep(bought);
        assertThat(step.kind()).isEqualTo(ServicingOutcome.Kind.SERVICED);
        String application = scalar("SELECT id::text FROM bnpl.bnpl_credit_balance_application WHERE obligation_id = "
                + bought.o() + " ORDER BY created_at DESC LIMIT 1");
        String entry = scalar("SELECT journal_entry_id::text FROM bnpl.bnpl_credit_balance_application WHERE id = '"
                + application + "'");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id ="
                + " ?::uuid", bought.obligation().value().toString())).as("the step wrote its later slot").isPositive();
        // The catch-up planted away - its rows and its entry parked, then put back exactly.
        String app = "'" + application + "'";
        String ent = "'" + entry + "'";
        asOwner("CREATE TABLE public.p12_parked_allocation AS SELECT * FROM bnpl.bnpl_credit_balance_allocation WHERE"
                + " application_id = " + app + "; CREATE TABLE public.p12_parked_application AS SELECT * FROM"
                + " bnpl.bnpl_credit_balance_application WHERE id = " + app + "; CREATE TABLE public.p12_parked_line AS"
                + " SELECT * FROM ledger.journal_line WHERE entry_id = " + ent + "; CREATE TABLE public.p12_parked_entry AS"
                + " SELECT * FROM ledger.journal_entry WHERE id = " + ent + "; DELETE FROM bnpl.bnpl_credit_balance_allocation"
                + " WHERE application_id = " + app + "; DELETE FROM bnpl.bnpl_credit_balance_application WHERE id = " + app
                + "; DELETE FROM ledger.journal_line WHERE entry_id = " + ent + "; DELETE FROM ledger.journal_entry WHERE"
                + " id = " + ent);
        try {
            BnplProofs.Proven proven = proven(bought);
            assertThat(proven.replay().checks()).contains(BnplReplayProof.Check.CATCH_UP_MISSING);
            assertThat(proven.subledger().holds()).as("rows and ledger both lost it: only the replay sees it").isTrue();
        } finally {
            asOwner("INSERT INTO ledger.journal_entry SELECT * FROM public.p12_parked_entry; INSERT INTO"
                    + " ledger.journal_line SELECT * FROM public.p12_parked_line; INSERT INTO"
                    + " bnpl.bnpl_credit_balance_application SELECT * FROM public.p12_parked_application; INSERT INTO"
                    + " bnpl.bnpl_credit_balance_allocation SELECT * FROM public.p12_parked_allocation; DROP TABLE"
                    + " public.p12_parked_entry, public.p12_parked_line, public.p12_parked_application,"
                    + " public.p12_parked_allocation");
        }
        assertHolds();
    }

    @Test
    @DisplayName("every closed obligation is zero; every identity holds over every refund shape - unbilled, billed"
            + " unpaid, with an excess, a second refund, the rest")
    void everyClosedObligationIsZeroAndEveryIdentityHoldsOverEveryRefundShape() throws Exception {
        Bought a = bought();
        refund(a, "a-1", 100_00);
        toAge(a, 2, 0);
        serviceStep(a);
        refund(a, "a-2", 300_00);
        repay(a, "a-r", 100_00);
        refund(a, "a-3", 200_00);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = " + a.o())).isEqualTo("CLOSED");
        BnplProofs.Proven proven = proven(a);
        assertThat(proven.subledger().holds()).isTrue();
        assertThat(proven.subledger().figures()).allSatisfy(figure -> assertThat(figure.ledger().isZero()).isTrue());
        BnplProofs.Reading reading = transactions.inSnapshot(proofs::prove);
        assertThat(reading.currencies().get(0).identities()).allSatisfy(identity ->
                assertThat(identity.failures()).isEmpty());
    }

    @Test
    @DisplayName("a payoff after a partial refund, at agreement and schedule version 2, for both products: the quote and"
            + " the execution read the version the refund made, the obligation closes PAID_OFF_EARLY, every account"
            + " zero, and the subledger and replay proofs hold - IDENTICAL - after")
    void aPayoffAfterAPartialRefundAtVersionTwoReplaysIdentical() throws Exception {
        // The Phase 12 -> 13 transition gate, A6-F4: BnplPayoffsTest#aPnPayoffAfterAPartialRefundPaysTheLatestVersion's
        // path, hermetic until now, over the real stores, triggers, ledger and proofs - pay-in-4 and pay-in-N.
        for (boolean payIn4 : List.of(true, false)) {
            String product = payIn4 ? "pay-in-4" : "pay-in-N";
            Bought bought = payIn4 ? bought() : bought(token -> BnplApplicationClient.payInN(token, 3));
            if (!payIn4) {
                // The purchase some ten days back, its dates accrued: the refund and the payoff each read accrued interest.
                toAge(bought, 1, -20);
                serviceStep(bought);
                assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_interest_accrual WHERE"
                        + " obligation_id = ?::uuid", bought.obligation().value().toString())).as(product).isPositive();
            }
            refund(bought, "partial", 100_00);
            assertThat(scalar("SELECT max(a.version) || '|' || (SELECT max(s.schedule_version) FROM bnpl.bnpl_schedule s"
                    + " WHERE s.obligation_id = " + bought.o() + ") FROM bnpl.bnpl_agreement a WHERE a.obligation_id = "
                    + bought.o())).as("%s: the refund made version 2 of both", product).isEqualTo("2|2");
            BnplPayoffs.Executed executed = payOff(bought, "payoff-" + product);
            assertThat(executed.quote().agreementVersion()).as(product).isEqualTo(2);
            assertThat(scalar("SELECT status || ':' || closure_reason FROM bnpl.bnpl_obligation WHERE id = "
                    + bought.o())).as(product).isEqualTo("CLOSED:PAID_OFF_EARLY");
            assertThat(scalar("SELECT agreement_version::text FROM bnpl.bnpl_repayment WHERE obligation_id = "
                    + bought.o() + " AND kind = 'PAYOFF'")).as("%s: paid off under version 2", product).isEqualTo("2");
            BnplProofs.Proven proven = proven(bought);
            assertThat(proven.subledger().holds()).as("%s: %s", product, proven.subledger()).isTrue();
            assertThat(proven.subledger().figures()).as(product)
                    .allSatisfy(figure -> assertThat(figure.ledger().isZero()).isTrue());
            assertThat(proven.replay().verdict()).as("%s: %s", product, proven.replay())
                    .isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        }
        assertHolds();
    }

    @Test
    @DisplayName("a proof during servicing is stable: readings on ten threads racing the sweep, repayments and refunds"
            + " over five rounds - every reading green in its own snapshot")
    void aProofDuringServicingIsStable() throws Exception {
        List<Bought> book = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Bought bought = bought();
            toAge(bought, 2, i);
            book.add(bought);
        }
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            for (int round = 0; round < 5; round++) {
                List<Future<?>> running = new ArrayList<>();
                List<Future<BnplProofs.Reading>> readings = new ArrayList<>();
                for (Bought bought : book) {
                    running.add(pool.submit(() -> serviceStep(bought)));
                }
                int which = round;
                running.add(pool.submit(() -> {
                    try {
                        repay(book.get(which % book.size()), "storm-" + which, 10_00);
                    } catch (RuntimeException refused) {
                        // a closed or racing obligation refuses; the proofs judge what committed
                    }
                    return null;
                }));
                for (int reader = 0; reader < 10; reader++) {
                    readings.add(pool.submit(() -> transactions.inSnapshot(proofs::prove)));
                }
                for (Future<?> step : running) {
                    step.get(120, TimeUnit.SECONDS);
                }
                for (Future<BnplProofs.Reading> reading : readings) {
                    BnplProofs.Reading seen = reading.get(120, TimeUnit.SECONDS);
                    assertThat(seen.failing()).as("round " + round).isZero();
                    assertThat(seen.divergences()).as("round " + round).isEmpty();
                    assertThat(seen.currencies()).allSatisfy(currency -> assertThat(currency.failures()).isEmpty());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Bought bought() throws Exception {
        return bought(BnplApplicationClient::payIn4);
    }

    /** A purchase of {@code GROSS} under the product and plan {@code body} applies for. */
    private Bought bought(java.util.function.Function<String, String> body) throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        // Exactly instalment 1, collected at the acceptance: the wallet is then empty, so the servicing step's automatic
        // collection (P12-TSK-019) takes nothing and each case's own repayments are what pay - the cases' arithmetic
        // assumed no collection, and a 5,000.00 wallet let the step pay every due instalment first (P12-TSK-028).
        world.topUpWallet(owner.party(), eur(GROSS / 4));
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(owner, merchant, GROSS, body);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        UUID obligation = UUID.fromString(scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE agreement_id = '"
                + agreement + "'"));
        return new Bought(BnplObligationId.of(obligation), BnplAgreementId.of(UUID.fromString(agreement)),
                BnplPurchaseId.of(UUID.fromString(BnplApplicationClient.field(accepted.body(), "purchaseId"))),
                owner.party(), merchant);
    }

    private void assertHolds() {
        BnplProofs.Reading reading = transactions.inSnapshot(proofs::prove);
        assertThat(reading.obligations()).isNotEmpty();
        assertThat(reading.failures()).isEmpty();
        assertThat(reading.divergences()).isEmpty();
        assertThat(reading.currencies()).allSatisfy(currency -> assertThat(currency.failures()).isEmpty());
    }

    private BnplProofs.Proven proven(Bought bought) {
        return transactions.inSnapshot(snapshot -> proofs.prove(snapshot, bought.obligation())).orElseThrow();
    }

    private void flipped(Bought bought, String flip, String restore, BnplReplayProof.Check check) {
        asOwner(flip);
        try {
            BnplProofs.Proven seen = proven(bought);
            assertThat(seen.replay().verdict()).as(flip).isEqualTo(BnplReplayProof.Verdict.DIVERGED);
            assertThat(seen.replay().checks()).as(flip).contains(check);
        } finally {
            asOwner(restore);
        }
        assertThat(proven(bought).replay().verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
    }

    private static String agreementTerms(Bought bought, String canonical) {
        return "UPDATE bnpl.bnpl_agreement SET canonical_terms = '" + canonical.replace("'", "''") + "', terms_sha256 ="
                + " sha256(convert_to('" + canonical.replace("'", "''") + "', 'UTF8')) WHERE obligation_id = "
                + bought.o() + " AND version = 1";
    }

    private LedgerAccountId account(Bought bought, AccountPurpose purpose) throws Exception {
        return LedgerAccountId.of(UUID.fromString(scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref = "
                + bought.o() + "::uuid AND purpose = '" + purpose.name() + "'")));
    }

    private void post(String key, List<JournalLine> lines) {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(BnplPurchaseWorld.flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            transactions.inTransaction(uow -> postingService.post(uow, new PostingCommand(key, today, today, key,
                    lines)));
        }
    }

    private ServicingOutcome serviceStep(Bought bought) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            transactions.inTransaction(uow -> accruals.accrue(uow, bought.obligation()));
            return transactions.inTransaction(uow -> servicing.service(uow, bought.obligation()));
        }
    }

    private BnplRepayments.Repaid repay(Bought bought, String key, long minor) {
        world.topUpWallet(bought.party(), eur(minor)); // the repayment's own money, paid in just before it
        return repayFunded(bought, key, minor);
    }

    /** The repayment alone - its money already in the wallet. */
    private BnplRepayments.Repaid repayFunded(Bought bought, String key, long minor) {
        Actor customer = new Actor(bought.party().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope actor = SecurityContext.enter(customer);
                CorrelationContext.Scope flow = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            return transactions.inTransaction(uow -> repayments.repay(uow, bought.agreement(), bought.party(),
                    new BnplRepayments.Request(key, eur(minor)), customer, BnplApplicationClient.correlation()));
        }
    }

    /** The owner's payoff: quoted today, the wallet funded with exactly the quote, then executed under it. */
    private BnplPayoffs.Executed payOff(Bought bought, String key) {
        Actor customer = new Actor(bought.party().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope actor = SecurityContext.enter(customer);
                CorrelationContext.Scope flow = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            BnplPayoffs.Quoted quoted = transactions.inTransaction(uow -> payoffs.quote(uow, bought.agreement(),
                    bought.party(), new BnplPayoffs.QuoteRequest(key, Optional.empty()),
                    BnplApplicationClient.correlation()));
            world.topUpWallet(bought.party(), quoted.quote().amount());
            return transactions.inTransaction(uow -> payoffs.execute(uow, bought.agreement(), bought.party(), key,
                    quoted.quote().id(), customer, BnplApplicationClient.correlation()));
        }
    }

    private BnplRefunds.Refunded refund(Bought bought, String key, long minor) {
        return refund(bought, key, minor, () -> { });
    }

    /** The refund, {@code beforeCommit} run inside its transaction after its last write - its locks still held. */
    private BnplRefunds.Refunded refund(Bought bought, String key, long minor, Runnable beforeCommit) {
        Actor merchant = new Actor(bought.merchant().id(), ActorType.MERCHANT);
        try (SecurityContext.Scope actor = SecurityContext.enter(merchant);
                CorrelationContext.Scope flow = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            return transactions.inTransaction(uow -> {
                BnplRefunds.Refunded refunded = refunds.atMerchant(uow, bought.purchase(), bought.merchant().ref(),
                        new BnplRefunds.Request(key, eur(minor), "goods returned", Optional.of("key-under-test")),
                        merchant, BnplApplicationClient.correlation());
                beforeCommit.run();
                return refunded;
            });
        }
    }

    private static long waitingOnB4() throws SQLException {
        try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement();
                java.sql.ResultSet row = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE"
                        + " wait_event_type = 'Lock' AND query LIKE '%bnpl.bnpl_obligation%FOR UPDATE%'")) {
            row.next();
            return row.getLong(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** The date shift to instalment {@code sequence} being {@code age} days past its due date today (`-019`'s fixture). */
    private static void toAge(Bought bought, int sequence, int age) throws Exception {
        long days = age + BnplApplicationClient.count("SELECT i.due_date - (now() AT TIME ZONE 'UTC')::date FROM"
                + " bnpl.bnpl_instalment i WHERE i.obligation_id = ?::uuid AND i.sequence = ?",
                bought.obligation().value().toString(), sequence);
        String o = bought.o();
        asOwner("UPDATE bnpl.purchase SET business_date = business_date - " + days + " WHERE id ="
                + " (SELECT purchase_id FROM bnpl.bnpl_obligation WHERE id = " + o + ");"
                + " UPDATE bnpl.bnpl_obligation SET purchase_date = purchase_date - " + days + " WHERE id = " + o
                + "; UPDATE bnpl.bnpl_schedule SET start_date = start_date - " + days + " WHERE obligation_id = " + o
                + "; UPDATE bnpl.bnpl_instalment SET due_date = due_date - " + days + " WHERE obligation_id = " + o
                + "; UPDATE bnpl.bnpl_billing SET value_date = value_date - " + days + ", period_start ="
                + " period_start - " + days + ", business_date = business_date - " + days + " WHERE obligation_id = "
                + o + "; UPDATE bnpl.bnpl_repayment SET value_date = value_date - " + days + " WHERE obligation_id = "
                + o + "; UPDATE ledger.journal_entry SET posting_date = posting_date - " + days + ", value_date ="
                + " value_date - " + days + " WHERE id IN (SELECT l.entry_id FROM ledger.journal_line l JOIN"
                + " ledger.ledger_account a ON a.id = l.ledger_account_id WHERE a.owner_ref = " + o + "::uuid)");
    }

    private static void asOwner(String sql) {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute(sql);
            }
            owner.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String scalar(String sql) throws Exception {
        return BnplApplicationClient.scalar(sql);
    }
}
