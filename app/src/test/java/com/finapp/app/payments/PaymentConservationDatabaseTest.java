package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.Direction;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.ProjectionVerification.Verdict;
import com.finapp.ledger.TrialBalance;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.EvidenceCipher;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcProviderEvidenceStore;
import com.finapp.payments.JdbcRefundStore;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentCapture;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentRefund;
import com.finapp.payments.RefundExceedsCaptureException;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `P5-TST-003`: conservation under concurrent captures and refunds — the phase's composition
 * demonstration, and the first time money <em>entering</em> the platform and money
 * <em>leaving</em> it are driven at once.
 *
 * <h2>Why a suite, when every piece is already proven</h2>
 *
 * <p>`P5-TSK-010` counted a ten-way capture race; `P5-TSK-015` counted a ten-way refund race
 * against one capture's bound; `P4-TST-001` drove transfers under the sweeps. Nothing has
 * driven <strong>captures and refunds against the same wallets at the same time</strong>, and
 * that composition is where two mechanisms that were designed apart finally meet: the capture
 * outcome takes the wallet's account lock to post, while the refund takes
 * <strong>attempt → account</strong> in the order `P5-TSK-015` pinned after `P5-TSK-013`
 * found a live {@code 40P01}. If that order is wrong anywhere, this is the suite that finds
 * it — as a deadlock, which is not a domain outcome and fails the test rather than being
 * retried away.
 *
 * <p><strong>And that claim was false until this suite's own gate probed it.</strong>
 * Inverting the refund's lock order <em>survived the whole storm</em>, because refunders
 * were drawing their subjects from a queue the capturers filled <em>after</em>
 * {@code capture()} returned: a capture and a refund could therefore never contend for one
 * attempt row, which is the only interleaving that deadlocks. Refunders now discover
 * attempts <strong>from the database</strong>, whatever state they are in — the operator's
 * view, and the realistic one — so a refund routinely arrives at an attempt that is
 * mid-capture. It is refused by the machine ({@code payments.NotRefundable}, a domain
 * outcome) on the correct code, and with the order inverted it deadlocks instead: SQLSTATE
 * {@code 40P01}, observed.
 *
 * <h2>Conservation has a different shape here, and getting it right is the point</h2>
 *
 * <p>A transfer moves value between two wallets, so `P4-TST-001` could assert *the pair's
 * total is unchanged*. A capture <strong>brings value in</strong>: DR
 * {@code SETTLEMENT_CLEARING} / CR the wallet, and a completed refund posts the inverse. The
 * closed pair is therefore <em>(wallet, clearing)</em>, and conservation is three readings:
 *
 * <ol>
 *   <li>the wallets' own journal positions, which must equal captured − refunded;
 *   <li>an <strong>independent recomputation</strong> from {@code payments.payment_attempt}
 *       and {@code payments.refund} — two tables that never see each other, agreeing to the
 *       minor unit ({@code INV-LED-04}'s chain as arithmetic);
 *   <li>the clearing account's <strong>delta</strong>, which must be exactly the negative of
 *       the wallets' — measured before and after, never as an absolute, because the clearing
 *       account is shared with every other suite in the container (the `P4-TST-001`
 *       global-read lesson).
 * </ol>
 *
 * <h2>The amounts contest the availability boundary, deliberately</h2>
 *
 * <p>`P4-TST-001`'s first draft chose amounts too polite to approach zero, and a real
 * mutation survived its whole storm. The Phase-5 equivalent would be refunding small
 * fractions of large captures: the capture bound is never approached, no hold ever fails, and
 * the storm proves less than the single-threaded suites it is composing. So refunds here are
 * <strong>near the whole capture</strong>, several race for one capture's budget, and one
 * amount is deliberately unaffordable — and the suite asserts that <strong>both</strong>
 * refusal kinds actually occurred, so "the boundary was contested" is a checked fact rather
 * than an intention.
 */
@Tag("database")
@DisplayName("conservation under concurrent captures and refunds (P5-TST-003)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PaymentConservationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final byte[] PSP_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);

    /**
     * TWO wallets, deliberately — and this number is what a failing run corrected.
     *
     * <p>The first draft spread five capturers and five refunders over three wallets, and the
     * availability boundary was <strong>never contested</strong>: 15 captures, 13 completed
     * refunds, 39 bound refusals and not one {@code HoldExceedsAvailableBalance}. The reason
     * is worth keeping, because it is the mechanism's own design working against the test:
     * the capture bound refuses <em>before any hold is placed</em> ({@code P5-TSK-015}'s
     * "the honest 422 BEFORE any hold"), so refunders wasting turns on exhausted attempts
     * never reach the availability check at all. Concentrating the storm on two wallets, and
     * pointing refunders at RECENT captures that still have budget, puts several holds on one
     * wallet at once — which is where {@code INV-BAL-04} speaks.
     */
    private static final int WALLETS = 2;

    private static final int CAPTURERS = 4;

    /** More drain than fill: a wallet that only ever grows never approaches zero. */
    private static final int REFUNDERS = 6;

    /**
     * Spenders, and <strong>why the storm needs them at all - the runs that taught this
     * suite its own subject.</strong>
     *
     * <p>Three runs failed on the same assertion: the availability boundary was never
     * contested, whatever the amounts. The reason is structural, and worth more than the
     * assertion that found it: <em>a refund can only ever take money that is still in the
     * wallet</em>, because {@code INV-PAY-05} bounds it by its own capture and nothing else
     * was spending. The sum of what every in-flight refund may hold is therefore exactly the
     * settled balance - available reaches zero and never goes below it, so
     * {@code INV-BAL-04} has nothing to refuse. The capture bound was making the availability
     * bound <strong>unreachable</strong>.
     *
     * <p>So the storm gained the actor it was missing: a customer <em>spending</em> the money
     * a capture credited. That is the realistic condition (a wallet is spendable), it is what
     * `PaymentRefundDatabaseTest`'s unfunded case models single-threaded, and it is what
     * makes a refund's hold meet a wallet that can no longer fund it.
     *
     * <p><strong>And the spend must leave the wallet SET, which the run after that one
     * taught.</strong> The first spender moved money between the storm's own wallets, which
     * nets to zero across the set: the wallets' total still only ever grew, and availability
     * was still never contested. A spend to {@code FEE_REVENUE} - an operational account,
     * which is what a customer's spending actually reaches - drains the set, so the wallets
     * hover near empty while refunds are in flight against them. Conservation gains a term
     * rather than losing a claim: the wallets hold captured - refunded - fees, and the
     * clearing delta is still exactly the capture/refund pair.
     */
    private static final int SPENDERS = 2;

    private static final long SPEND_MINOR = 4_00;

    /** Every capture is this, so the refund amounts below can be reasoned about exactly. */
    private static final long CAPTURE_MINOR = 10_00;

    /**
     * Refund amounts, deliberately <strong>aggressive against a 10.00 capture</strong>.
     *
     * <p><strong>The amounts are what two failing runs corrected, and the arithmetic is the
     * finding.</strong> A refund of 9.00 against a 10.00 capture leaves the wallet holding
     * ~1.00 per captured payment — so a single 9.00 hold still fits under the settled
     * balance and {@code INV-BAL-04} never speaks, which is exactly what the first two runs
     * observed (45 bound refusals, zero availability refusals). Refunding the capture
     * <strong>in full</strong> is what keeps a wallet's settled balance near zero, so the
     * NEXT refund's hold — placed before the wire call, against a wallet whose money is
     * already reserved by refunds in flight — is the one the availability check must refuse.
     * 5.00 keeps genuine partials in the mix (two can share one capture), and 12.00 can
     * never be afforded by any capture, guaranteeing a committed {@code RefundExceedsCapture}
     * rather than hoping for one. The bound refuses <em>before</em> any hold is placed, so
     * these two refusals are reached by different routes and both must be seen.
     */
    private static final long[] REFUND_MINOR = {3_00, 3_00, 2_00, 3_00, 12_00};

    /** How far back a refunder will reach: the freshest captures, which still have budget. */
    private static final int RECENT_CAPTURES = 6;

    /** The floors that make "sustained" a fact rather than a word — with a provider in the path. */
    private static final int MIN_SWEEPS_UNDER_LOAD = 25;

    private static final int MIN_COMMANDS_UNDER_LOAD = 150;

    private static final int COMMANDS_BETWEEN_SWEEPS =
            MIN_COMMANDS_UNDER_LOAD / MIN_SWEEPS_UNDER_LOAD;

    private static SimulatedProvider psp;

    private final StormRunner runner = new StormRunner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcRefundStore refunds = new JdbcRefundStore();
    private final JdbcProviderEvidenceStore evidence =
            new JdbcProviderEvidenceStore(
                    new EvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom()), IDS);
    private final JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcPaymentParticipants participants =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(),
                    new JdbcCustomerAccountStore(),
                    ledgerAccounts,
                    new JdbcPaymentMethodStore());
    private volatile List<Wallet> stormWallets = List.of();

    private final ProjectionVerification verification =
            new ProjectionVerification(new JdbcBalanceDerivation());
    private final TrialBalance trialBalance = new TrialBalance();

    @BeforeAll
    static void startProvider() {
        psp = SimulatedProvider.start();
        // A provider that mints a DISTINCT reference per operation, because every
        // provider-reference column is UNIQUE (`P5-TST-003`: a fixed body makes the second
        // concurrent capture a 23505 - a harness limit, recorded by P5-TSK-015).
        psp.succeedsWithMintedReference(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "psp_a");
        psp.succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
        psp.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_r");
    }

    @AfterAll
    static void stopProvider() {
        psp.close();
    }

    @Test
    @DisplayName("ten instances capturing and refunding continuously: every trial-balance sweep"
            + " reads zero per currency, every projection verdict is CLEAN or IN_FLIGHT, no"
            + " wallet is ever negative, and wallet + clearing reconcile exactly to"
            + " captured - refunded")
    void valueIsConservedThroughSustainedCapturesAndRefunds() throws Exception {
        List<Wallet> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            wallets.add(openWallet());
        }
        stormWallets = List.copyOf(wallets);
        long clearingBefore = clearingPosition();
        long feesBefore = feePosition();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong committedCommands = new AtomicLong();
        AtomicLong capturesCommitted = new AtomicLong();
        // Every capture that committed, for the refunders to chase. A queue rather than a
        // list: the refunders poll it concurrently, and an attempt may be refunded by
        // several of them at once - which is the contention this suite exists to create.
        ConcurrentLinkedQueue<Captured> captured = new ConcurrentLinkedQueue<>();
        // PRIMED, deliberately: one capture per wallet before the sweeps begin, so the first
        // trial-balance round reads a journal that exists (a sweep over an empty set asserts
        // nothing - the vacuity this repository has met repeatedly) and the refunders have
        // something to contend for from their first turn rather than spinning.
        for (Wallet wallet : wallets) {
            captured.add(driveCapture(wallet));
            capturesCommitted.incrementAndGet();
            committedCommands.incrementAndGet();
        }
        // Outcomes counted by the movers themselves. Anything that is NOT a domain outcome
        // is captured with its class and SQLSTATE rather than swallowed: a 40P01 deadlock
        // between a capture's posting and a refund's hold is EXACTLY what this composition
        // could surface, and a test that retried it away would hide the finding.
        Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();

        ExecutorService pool =
                Executors.newFixedThreadPool(CAPTURERS + REFUNDERS + SPENDERS);
        List<Future<Void>> movers = new ArrayList<>();
        for (int i = 0; i < CAPTURERS; i++) {
            movers.add(
                    pool.submit(
                            capturer(wallets, i, stop, committedCommands, capturesCommitted,
                                    captured, outcomes)));
        }
        for (int i = 0; i < REFUNDERS; i++) {
            movers.add(
                    pool.submit(
                            refunder(i, stop, committedCommands, captured, outcomes)));
        }
        for (int i = 0; i < SPENDERS; i++) {
            movers.add(pool.submit(spender(wallets, i, stop, committedCommands, outcomes)));
        }

        long rounds = 0;
        try (Connection own = DatabaseRoles.application()) {
            // The sweeper ends the storm, so the overlap is the exit condition rather than
            // luck (the P3-TST-001/P4-TST-001 rule - no sleeps anywhere). The cap is a
            // FAILURE bound: if the movers have died, fall through so mover.get() reports it.
            while ((rounds < MIN_SWEEPS_UNDER_LOAD
                            || committedCommands.get() < MIN_COMMANDS_UNDER_LOAD)
                    && rounds < 10_000) {
                rounds++;

                TrialBalance.Report trial = trialBalance.sweep(own);
                assertThat(trial.outOfBalance())
                        .as("mid-storm trial balance, round %s: zero per currency while money"
                                + " is entering by capture and leaving by refund"
                                + " (INV-ACC-01)", rounds)
                        .isEmpty();
                assertThat(trial.currenciesVerified())
                        .as("the sweep must actually see a journal, or it asserts nothing")
                        .isGreaterThanOrEqualTo(1);

                for (Wallet wallet : wallets) {
                    assertThat(verification.verdictOf(own, wallet.account()))
                            .as("mid-storm projection verdict, round %s: mid-entry"
                                    + " (IN_FLIGHT) is legitimate, wrong (DRIFTING) is not",
                                    rounds)
                            .isNotEqualTo(Verdict.DRIFTING);
                    assertThat(settledOf(own, wallet.account()))
                            .as("no wallet is ever negative mid-storm, round %s - the refund"
                                    + " holds reserve what the refund may take (INV-BAL-04)",
                                    rounds)
                            .isGreaterThanOrEqualTo(0);
                }

                // Wait on the CONDITION - more committed commands - so the next sweep
                // provably straddles live traffic. The deadline is a FAILURE bound.
                long required =
                        Math.min(rounds * COMMANDS_BETWEEN_SWEEPS, MIN_COMMANDS_UNDER_LOAD);
                long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
                while (committedCommands.get() < required && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
            }
        } finally {
            stop.set(true);
            pool.shutdown();
        }
        for (Future<Void> mover : movers) {
            mover.get(); // Propagates any failure a mover could not classify.
        }

        assertThat(rounds).as("the sweeps really ran under load")
                .isGreaterThanOrEqualTo(MIN_SWEEPS_UNDER_LOAD);
        assertThat(committedCommands.get())
                .as("the storm really was a storm - outcomes: %s", outcomes)
                .isGreaterThanOrEqualTo(MIN_COMMANDS_UNDER_LOAD);

        // THE BOUNDARY WAS CONTESTED, and it is a checked fact (the P4-TST-001 lesson,
        // pre-applied): both refusal kinds must have occurred, or the amounts were polite
        // and this storm proved less than the suites it composes.
        assertThat(outcomes.keySet())
                .as("the capture bound must have refused a refund - outcomes: %s", outcomes)
                .contains(RefundExceedsCaptureException.class.getSimpleName());
        assertThat(outcomes.keySet())
                .as("the availability boundary must have refused a hold: with refunds in"
                        + " flight a wallet's available balance reaches zero, which is where"
                        + " INV-BAL-04 speaks - outcomes: %s", outcomes)
                .contains(HoldExceedsAvailableBalanceException.class.getSimpleName());

        try (Connection app = DatabaseRoles.application()) {
            // Reading 1: the money's own rows.
            long walletsSettled = 0;
            for (Wallet wallet : wallets) {
                long settled = settledOf(app, wallet.account());
                assertThat(settled).as("no wallet ended negative").isGreaterThanOrEqualTo(0);
                walletsSettled += settled;
            }

            // Reading 2, INDEPENDENTLY RECOMPUTED from two payment tables that never see the
            // journal: every captured minor unit that was not refunded is still in a wallet.
            long capturedMinor = capturedMinorFor(app, wallets);
            long refundedMinor = completedRefundMinorFor(app, wallets);
            long feeDelta = positionOf(app, "FEE_REVENUE") - feesBefore;
            assertThat(walletsSettled)
                    .as("the wallets hold exactly captured - refunded - spent, to the minor"
                            + " unit: three tables that never see each other (attempt,"
                            + " refund, journal) agreeing as arithmetic (INV-LED-04's chain)")
                    .isEqualTo(capturedMinor - refundedMinor - feeDelta);

            // Reading 3: the clearing DELTA is exactly the negative of the wallets' - the
            // closed pair. Measured as a delta because the clearing account is shared with
            // every other suite in this container (the P4-TST-001 global-read lesson).
            long clearingDelta = clearingPosition(app) - clearingBefore;
            assertThat(clearingDelta)
                    .as("every unit a capture credited to a wallet was debited from"
                            + " clearing, and every refunded unit went back - the CAPTURE"
                            + " PAIR is closed whatever the customer then spent (INV-BAL-03;"
                            + " INV-SET-01: the counterpart is a clearing position, never"
                            + " settled cash). Each side is read in ITS OWN normal-balance"
                            + " direction (the projection's sign convention), so a closed"
                            + " pair reads as equal magnitudes rather than opposite signs -"
                            + " the wallet's credit and the clearing account's debit are the"
                            + " same movement seen from the two ends")
                    .isEqualTo(capturedMinor - refundedMinor);

            // The storm was two-sided: value really came in and really went back out.
            assertThat(capturedMinor).as("captures really happened").isGreaterThan(0);
            assertThat(refundedMinor).as("refunds really completed").isGreaterThan(0);

            // Every refund row is a judged outcome, and no refund exceeded its capture.
            assertThat(refundStatusCounts(app, wallets).keySet())
                    .as("every refund row ended judged - no DISPATCHED left behind, because"
                            + " every wire call in this storm was answered")
                    .doesNotContain(RefundStatus.DISPATCHED.name());
            assertThat(overRefundedAttempts(app, wallets))
                    .as("no attempt was refunded past its capture (INV-PAY-05) under any"
                            + " interleaving")
                    .isZero();

            // No hold outlived the storm: every refund released or posted.
            for (Wallet wallet : wallets) {
                assertThat(activeHolds(app, wallet.account()))
                        .as("no hold is left standing: every judged refund released its"
                                + " reservation or turned it into a posting")
                        .isZero();
            }

            // Settled, the sweeps agree with the storm's end.
            assertThat(trialBalance.sweep(app).outOfBalance())
                    .as("settled: zero per currency")
                    .isEmpty();
            for (Wallet wallet : wallets) {
                assertThat(verification.verdictOf(app, wallet.account()))
                        .as("settled: the projection equals replay-from-zero")
                        .isEqualTo(Verdict.CLEAN);
            }
        }
    }

    // -----------------------------------------------------------------
    // The movers
    // -----------------------------------------------------------------

    private record Wallet(UUID party, UUID method, LedgerAccountId account) {}

    private record Captured(PaymentIntentId intent, PaymentAttemptId attempt, Wallet wallet) {}

    /** Drives fresh payments end to end: create, confirm, capture — money entering. */
    private Callable<Void> capturer(
            List<Wallet> wallets,
            int index,
            AtomicBoolean stop,
            AtomicLong committed,
            AtomicLong captures,
            ConcurrentLinkedQueue<Captured> captured,
            Map<String, AtomicLong> outcomes) {
        return () -> {
            int round = 0;
            while (!stop.get()) {
                Wallet wallet = wallets.get((index + round) % wallets.size());
                round++;
                try {
                    captured.add(driveCapture(wallet));
                    captures.incrementAndGet();
                    committed.incrementAndGet();
                    count(outcomes, "captured");
                } catch (RuntimeException failure) {
                    // A capturer has no legitimate domain refusal: its wallet is postable,
                    // its instrument attached, its provider answering. Anything here is the
                    // finding (a deadlock, a storage abort), so it fails the test.
                    count(outcomes, describe(failure));
                    throw failure;
                }
            }
            return null;
        };
    }

    /** Refunds captured payments — money leaving, contending for each capture's budget. */
    private Callable<Void> refunder(
            int index,
            AtomicBoolean stop,
            AtomicLong committed,
            ConcurrentLinkedQueue<Captured> captured,
            Map<String, AtomicLong> outcomes) {
        return () -> {
            int round = 0;
            while (!stop.get()) {
                // THE ATTEMPTS ARE DISCOVERED FROM THE DATABASE, not from the capturers'
                // post-hoc queue - and this is what the gate corrected. A queue populated
                // only AFTER capture() returns can never hand a refunder an attempt that is
                // MID-CAPTURE, so a capture and a refund never contended for the same
                // attempt row and the inverted-lock-order mutation SURVIVED the whole storm.
                // Reading the rows the way a real operator would (any attempt of this
                // wallet, whatever state it is in) is what makes the interleaving this
                // suite's javadoc claims actually reachable: the refund finds a
                // CAPTURE_DISPATCHED attempt, is refused by the machine - a domain outcome -
                // and with the order inverted deadlocks against the capture instead.
                List<Captured> visible = recentAttempts();
                if (visible.isEmpty()) {
                    Thread.onSpinWait();
                    continue;
                }
                Captured subject;
                // Refunders chase RECENT captures - the tail of the queue - so their turns
                // land on attempts that still have budget and therefore reach the HOLD,
                // rather than being turned away by the capture bound before the availability
                // check is ever consulted (the finding that corrected this suite). Several
                // refunders on one recent capture is the contention that makes the bound and
                // the hold race each other rather than take turns.
                subject = visible.get((index + round) % visible.size());
                long amount = REFUND_MINOR[round % REFUND_MINOR.length];
                round++;
                try (CorrelationContext.Scope flow =
                                CorrelationContext.enter(
                                        Correlation.startingWith(CorrelationId.generate(IDS)));
                        SecurityContext.Scope actor =
                                SecurityContext.enter(
                                        new Actor(UUID.randomUUID().toString(),
                                                ActorType.CUSTOMER))) {
                    PaymentRefund.RefundResult result =
                            refund().refund(
                                            subject.intent(),
                                            Money.ofMinorUnits(amount, EUR),
                                            "storm refund " + index,
                                            "storm-rfd-" + UUID.randomUUID());
                    committed.incrementAndGet();
                    count(outcomes, "refund:" + result.status());
                } catch (com.finapp.payments.PaymentNotRefundableException notYet) {
                    // A DOMAIN outcome: the attempt is mid-capture (or never captured), so
                    // there is nothing to refund yet. This is the turn that creates the
                    // capture-versus-refund contention on one attempt row.
                    committed.incrementAndGet();
                    count(outcomes, notYet.getClass().getSimpleName());
                } catch (RefundExceedsCaptureException bounded) {
                    // A DOMAIN outcome: the capture's budget is exhausted, nothing written.
                    committed.incrementAndGet();
                    count(outcomes, bounded.getClass().getSimpleName());
                } catch (HoldExceedsAvailableBalanceException unfunded) {
                    // A DOMAIN outcome: the wallet's available balance cannot reserve this
                    // refund right now, because other refunds hold it (INV-BAL-04).
                    committed.incrementAndGet();
                    count(outcomes, unfunded.getClass().getSimpleName());
                } catch (RuntimeException failure) {
                    count(outcomes, describe(failure));
                    throw failure;
                }
            }
            return null;
        };
    }

    /**
     * The customer spending what a capture credited - between the storm's own wallets, so the
     * set's total is untouched while individual wallets are drained. Bounded by a read of the
     * available balance in the same transaction, so a spend never drives a wallet negative: a
     * posting is not availability-checked, only a hold is, which is the whole point of the
     * hold.
     */
    private Callable<Void> spender(
            List<Wallet> wallets,
            int index,
            AtomicBoolean stop,
            AtomicLong committed,
            Map<String, AtomicLong> outcomes) {
        return () -> {
            int round = 0;
            while (!stop.get()) {
                Wallet from = wallets.get((index + round) % wallets.size());
                round++;
                try (CorrelationContext.Scope flow =
                                CorrelationContext.enter(
                                        Correlation.startingWith(CorrelationId.generate(IDS)));
                        SecurityContext.Scope actor =
                                SecurityContext.enter(
                                        new Actor(from.party().toString(),
                                                ActorType.CUSTOMER))) {
                    boolean spent = runner.inTransaction(uow -> spendOnce(uow, from));
                    if (spent) {
                        committed.incrementAndGet();
                        count(outcomes, "spent");
                    } else {
                        Thread.onSpinWait();
                    }
                } catch (RuntimeException failure) {
                    count(outcomes, describe(failure));
                    throw failure;
                }
            }
            return null;
        };
    }

    private boolean spendOnce(Connection uow, Wallet from) {
        // LOCK, then look - the HoldService protocol (ADR-0039), because a posting is NOT
        // availability-checked and two spenders reading the same availability would both
        // post. That is this mover's own race, not the platform's: the check belongs to the
        // hold, and a spender that wants to respect availability must serialise the way the
        // hold does. (The first run without this lock drove a wallet to -1.00, which is the
        // storm finding its own defect rather than the platform's.)
        ledgerAccounts.lockForUpdate(uow, from.account()).orElseThrow();
        if (availableOf(uow, from.account()) < SPEND_MINOR + 1_00) {
            return false;
        }
        Money amount = Money.ofMinorUnits(SPEND_MINOR, EUR);
        LedgerAccountId fees =
                new ChartOfAccounts<>(ledgerAccounts)
                        .resolve(uow, com.finapp.ledger.AccountPurpose.FEE_REVENUE, EUR)
                        .id();
        postingService()
                .post(
                        uow,
                        new PostingCommand(
                                "storm-spend:" + UUID.randomUUID(),
                                LocalDate.now(CLOCK),
                                LocalDate.now(CLOCK),
                                "storm spend",
                                List.of(
                                        new JournalLine(from.account(), Direction.DEBIT, amount),
                                        new JournalLine(fees, Direction.CREDIT, amount))));
        return true;
    }

    /** Settled minus active holds, read inside the caller's transaction. */
    private long availableOf(Connection uow, LedgerAccountId account) {
        try {
            return settledOf(uow, account) - heldOf(uow, account);
        } catch (SQLException failure) {
            throw new IllegalStateException("reading availability failed", failure);
        }
    }

    private static long heldOf(Connection app, LedgerAccountId account) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger.hold"
                                + " WHERE ledger_account_id = ? AND status = 'ACTIVE'")) {
            read.setObject(1, account.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /**
     * The newest attempts of the storm's wallets, WHATEVER their state — the refunders' view
     * of the world, and deliberately not the capturers' queue (see the refunder's comment).
     */
    private List<Captured> recentAttempts() {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT a.id, a.intent_id FROM payments.payment_attempt a"
                                        + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                        + " WHERE i.wallet_account_id = ANY (?)"
                                        + " ORDER BY a.created_at DESC LIMIT ?")) {
            read.setArray(1, accountArray(app, stormWallets));
            read.setInt(2, RECENT_CAPTURES);
            try (ResultSet rows = read.executeQuery()) {
                List<Captured> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(
                            new Captured(
                                    PaymentIntentId.of(rows.getObject(2, UUID.class)),
                                    com.finapp.payments.PaymentAttemptId.of(
                                            rows.getObject(1, UUID.class)),
                                    null));
                }
                return found;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("reading the storm's attempts failed", failure);
        }
    }

    private static void count(Map<String, AtomicLong> outcomes, String key) {
        outcomes.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    /** Class and SQLSTATE — never the message, which can carry hosts and identifiers. */
    private static String describe(RuntimeException failure) {
        StringBuilder described = new StringBuilder(failure.getClass().getSimpleName());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                described.append('(').append(sql.getSQLState()).append(')');
                break;
            }
        }
        return described.toString();
    }

    // -----------------------------------------------------------------
    // Compositions — the wired shapes, never doubles
    // -----------------------------------------------------------------

    /**
     * One payment end to end: create, confirm, capture — the real chain, as the customer.
     * Shared by the priming and by every capturer, so the storm drives exactly what the
     * warm-up drove.
     */
    private Captured driveCapture(Wallet wallet) {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor =
                        SecurityContext.enter(
                                new Actor(wallet.party().toString(), ActorType.CUSTOMER))) {
            PaymentCreation.CreationResult created =
                    runner.inTransaction(
                            uow ->
                                    creation()
                                            .create(
                                                    uow,
                                                    new PaymentCreation.CreatePaymentCommand(
                                                            wallet.party(),
                                                            wallet.method(),
                                                            Money.ofMinorUnits(
                                                                    CAPTURE_MINOR, EUR),
                                                            "storm-" + UUID.randomUUID())));
            confirmation().confirm(wallet.party(), created.intent());
            PaymentAttemptId attempt =
                    runner.inTransaction(
                            uow ->
                                    attempts.findForIntent(uow, created.intent())
                                            .orElseThrow()
                                            .id());
            capture().capture(attempt);
            return new Captured(created.intent(), attempt, wallet);
        }
    }

    private PaymentCreation creation() {
        return new PaymentCreation(
                executor(), participants, intents, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK);
    }

    private PaymentConfirmation confirmation() {
        return new PaymentConfirmation(
                runner, intents, attempts, evidence, participants, adapter(), outcomes(),
                new JdbcAuditWriter(), IDS, CLOCK);
    }

    private PaymentCapture capture() {
        return new PaymentCapture(
                runner, intents, attempts, evidence, adapter(), outcomes(),
                new JdbcAuditWriter(), IDS, CLOCK);
    }

    private PaymentRefund refund() {
        return new PaymentRefund(
                runner, executor(), intents, attempts, refunds, evidence, holdService(),
                adapter(), outcomes(), new JdbcAuditWriter(), IDS, CLOCK);
    }

    private PaymentOutcomes outcomes() {
        return new PaymentOutcomes(
                intents, attempts, refunds, holdService(), postingService(),
                new ChartOfAccounts<>(ledgerAccounts),
                // THE PRODUCTION SEAM (P6-TSK-005): the composition production posts through,
                // not the wallet one directly - so "no fee pin, two lines" is proven where it
                // matters. Every payment in this suite is a top-up and falls back.
                new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                ledgerAccounts,
                                new ChartOfAccounts<>(ledgerAccounts),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletTopUpComposition(),
                        // No completion: these suites' payments belong to no checkout
                        // session, and the production consumer is wired in CheckoutBeans.
                        landed -> {}),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK);
    }

    private HoldService holdService() {
        return new HoldService(
                ledgerAccounts, new JdbcBalanceDerivation(), new JdbcHoldStore(),
                new JdbcBalanceProjection(), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                IDS, CLOCK);
    }

    private PostingService postingService() {
        return new PostingService(
                executor(), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, CLOCK,
                PostingObserver.NONE);
    }

    private SimulatedCardPspAdapter adapter() {
        return new SimulatedCardPspAdapter(
                URI.create(psp.baseUrl()), Duration.ofSeconds(5), PSP_KEY);
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    // -----------------------------------------------------------------
    // Fixtures and readings
    // -----------------------------------------------------------------

    private Wallet openWallet() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Storm Holder', now() - interval '2 hour')",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer, party);
            execute(app,
                    "INSERT INTO paymentmethods.payment_method (id, party_id, token_reference,"
                            + " brand, display_suffix, expiry_month, expiry_year, status,"
                            + " created_at) VALUES (?, ?, ?, 'Visa', '4242', 12, 2030,"
                            + " 'ACTIVE', now())",
                    method, party, "tok-storm-" + UUID.randomUUID());
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor =
                        SecurityContext.enter(
                                new Actor(party.toString(), ActorType.CUSTOMER))) {
            runner.inTransaction(
                    uow ->
                            new AccountOpening(
                                            new JdbcCustomerAccountStore(),
                                            ledgerAccounts,
                                            new VerifiedAccountHolder(new JdbcPartyStore()),
                                            new JdbcAuditWriter(),
                                            new JdbcOutboxWriter(),
                                            IDS,
                                            CLOCK)
                                    .open(uow, party, ProductType.WALLET, EUR));
        }
        // The wallet through the SAME port production resolves it with - never a test query
        // that could agree with a broken resolution.
        LedgerAccountId account =
                runner.inTransaction(
                        uow -> participants.walletOwnedBy(uow, party).orElseThrow().account());
        return new Wallet(party, method, account);
    }

    private long feePosition() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return positionOf(app, "FEE_REVENUE");
        }
    }

    private long clearingPosition() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return clearingPosition(app);
        }
    }

    /** An operational account's settled position — read as a DELTA by the caller. */
    private long clearingPosition(Connection app) throws SQLException {
        return positionOf(app, "SETTLEMENT_CLEARING");
    }

    private static long positionOf(Connection app, String purpose) throws SQLException {
        try (PreparedStatement read =
                        app.prepareStatement(
                                "SELECT COALESCE(SUM(b.posted_minor), 0)"
                                        + " FROM ledger.account_balance b"
                                        + " JOIN ledger.ledger_account a ON a.id ="
                                        + "   b.ledger_account_id"
                                        + " WHERE a.purpose = ? AND b.currency = 'EUR'")) {
            read.setString(1, purpose);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long settledOf(Connection app, LedgerAccountId account) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT COALESCE(SUM(posted_minor), 0) FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?")) {
            read.setObject(1, account.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long capturedMinorFor(Connection app, List<Wallet> wallets)
            throws SQLException {
        return sumFor(
                app,
                wallets,
                "SELECT COALESCE(SUM(a.captured_amount_minor), 0)"
                        + " FROM payments.payment_attempt a"
                        + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + " WHERE a.status = 'CAPTURED' AND i.wallet_account_id = ANY (?)");
    }

    private static long completedRefundMinorFor(Connection app, List<Wallet> wallets)
            throws SQLException {
        return sumFor(
                app,
                wallets,
                "SELECT COALESCE(SUM(r.amount_minor), 0) FROM payments.refund r"
                        + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                        + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + " WHERE r.status = 'COMPLETED' AND i.wallet_account_id = ANY (?)");
    }

    private static long overRefundedAttempts(Connection app, List<Wallet> wallets)
            throws SQLException {
        return sumFor(
                app,
                wallets,
                "SELECT count(*) FROM ("
                        + "  SELECT a.id FROM payments.payment_attempt a"
                        + "  JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + "  JOIN payments.refund r ON r.attempt_id = a.id"
                        + "  WHERE i.wallet_account_id = ANY (?) AND r.status <> 'FAILED'"
                        + "  GROUP BY a.id, a.captured_amount_minor"
                        + "  HAVING SUM(r.amount_minor) > a.captured_amount_minor) breaches");
    }

    private static Map<String, Long> refundStatusCounts(Connection app, List<Wallet> wallets)
            throws SQLException {
        Map<String, Long> counts = new ConcurrentHashMap<>();
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT r.status, count(*) FROM payments.refund r"
                                + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE i.wallet_account_id = ANY (?) GROUP BY r.status")) {
            read.setArray(1, accountArray(app, wallets));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    counts.put(rows.getString(1), rows.getLong(2));
                }
            }
        }
        return counts;
    }

    private static long activeHolds(Connection app, LedgerAccountId account)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?"
                                + " AND status = 'ACTIVE'")) {
            read.setObject(1, account.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long sumFor(Connection app, List<Wallet> wallets, String sql)
            throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql)) {
            read.setArray(1, accountArray(app, wallets));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static java.sql.Array accountArray(Connection app, List<Wallet> wallets)
            throws SQLException {
        UUID[] ids = wallets.stream().map(w -> w.account().value()).toArray(UUID[]::new);
        return app.createArrayOf("uuid", ids);
    }

    /** One transaction per call, the sibling suites' runner (each mover gets its own). */
    private static final class StormRunner implements com.finapp.payments.TransactionRunner {
        @Override
        public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
            try (Connection unitOfWork = DatabaseRoles.application()) {
                try {
                    unitOfWork.setAutoCommit(false);
                    R result = work.apply(unitOfWork);
                    unitOfWork.commit();
                    return result;
                } catch (RuntimeException failure) {
                    unitOfWork.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("transaction plumbing failed", failure);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
