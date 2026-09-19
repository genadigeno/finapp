package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.ProjectionVerification.Verdict;
import com.finapp.ledger.TrialBalance;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.JdbcTransferStore;
import com.finapp.transfers.PermitAllUntilPhase13;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferParticipants;
import com.finapp.transfers.TransferResult;
import com.finapp.transfers.TransferStatus;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `P4-TST-001`: conservation under sustained concurrent movement — the phase's composition
 * demonstration, and `INV-CON-02`'s sustained form.
 *
 * <h2>Why a suite, when every piece is already proven</h2>
 *
 * <p>`P4-TSK-005`'s ten-way drain proved `INV-CON-02` <em>one-directionally</em>;
 * `P3-TST-001` proved the projection under sustained posting; `P3-TSK-019` proved the trial
 * balance safe under concurrent posting. Nothing had driven transfers, the verification
 * sweep and the trial-balance sweep <strong>at once</strong>, and the `P1-TSK-027` lesson is
 * that two green halves compose only when something drives them together. That composition
 * is this suite's deliverable.
 *
 * <h2>Overlap by construction, never by timing luck</h2>
 *
 * <p>The sweeper ends the storm (the `P3-TST-001` exit condition): it keeps sweeping until it
 * has completed {@link #MIN_SWEEPS_UNDER_LOAD} rounds <strong>and</strong> observed
 * {@link #MIN_TRANSFERS_UNDER_LOAD} committed transfer commands, so "the sweeps ran while
 * money was moving" is a property of the loop's own exit condition rather than of scheduling
 * (`P0-TST-004`'s rule — no sleeps anywhere). The round cap is a <strong>failure bound, never
 * a pass</strong>: if the movers have died, the loop must end so {@code future.get()} reports
 * the real cause.
 *
 * <h2>The trial-balance sweep is global, and that is safe by construction</h2>
 *
 * <p>{@link TrialBalance#sweep} has no per-account form, so it reads the whole journal —
 * including every other suite's committed rows in the shared container. That makes the claim
 * <em>stronger</em> rather than flakier: a committed imbalance is structurally impossible,
 * because `V004`'s deferred constraint triggers judge every entry at COMMIT for every writer
 * (`P3-TSK-005`), and the one suite that injects imbalance does so inside a transaction it
 * rolls back (`P3-TSK-019`). The projection verdicts are taken <strong>per account</strong>
 * instead — the `P3-TST-001` precedent — because a global {@code verify()} would couple this
 * test to every other suite's leftovers, which is a flake rather than a property.
 *
 * <h2>Conservation is three readings that must reconcile, not one count</h2>
 *
 * <p>The journal's own sum over both wallets; an <strong>independent recomputation</strong>
 * from {@code transfers.transfer} applying each completed movement to the starting balances;
 * and the outcome tally, where every non-{@code COMPLETED} transfer must be a committed
 * {@code FAILED(INSUFFICIENT_FUNDS)} — a domain outcome, which is `INV-CON-02`'s own wording.
 * Two tables that never see each other agreeing to the minor unit is `INV-LED-04`'s chain
 * (transfer → entry → lines) asserted as arithmetic.
 */
@Tag("database")
@DisplayName("conservation under sustained concurrent movement (P4-TST-001)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class TransferConservationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    /** Five moving each way: the bidirectional case the one-directional drain cannot reach. */
    private static final int MOVERS_PER_DIRECTION = 5;

    private static final long FUNDED_MINOR = 10_00;

    /**
     * Mixed amounts, deliberately <strong>aggressive against the 10.00 balances</strong>, so
     * the availability boundary is contested continuously rather than approached politely.
     *
     * <p><strong>This array is what a surviving mutation corrected.</strong> The first draft
     * used 1.00–3.00 against 10.00, and the availability decision derived <em>before</em> the
     * lock grant (the {@code P3-TST-002} shape) <strong>survived the whole storm</strong> —
     * because balances that never approach zero never expose a stale read, so the sustained
     * suite was proving less than its one-directional sibling. With 7.00 and 9.00 in the
     * rotation an account is frequently one transfer away from empty, which is where a
     * pre-lock snapshot and a post-lock one differ; 30.00 keeps a never-affordable amount in
     * the mix so a committed {@code FAILED(INSUFFICIENT_FUNDS)} is guaranteed rather than
     * hoped for, and 1.00 keeps the storm moving when both sides are low.
     */
    private static final long[] AMOUNTS_MINOR = {7_00, 1_00, 9_00, 30_00, 8_00, 3_00};

    /**
     * The floors that make "sustained" a fact rather than a word — the `P3-TST-001` lesson,
     * where a 10-sweep/30-entry floor was met in ~300ms on a warm container and was a gust.
     */
    private static final int MIN_SWEEPS_UNDER_LOAD = 25;

    private static final int MIN_TRANSFERS_UNDER_LOAD = 200;

    /**
     * Commands that must commit between one sweep round and the next.
     *
     * <p><strong>The first run of this test starved its own storm</strong>, and the finding
     * is worth keeping: an unpaced sweeper completed 10,000 whole-journal aggregations in
     * sixty seconds while only 74 transfers committed — it was competing with the movers for
     * the same container, so the "sustained" half of the demonstration was being crowded out
     * by the very sweeps that are supposed to observe it. Pacing on the <em>condition</em>
     * (the `P3-TST-001` rebuild half's idiom — spin on committed work, never on a clock)
     * makes every sweep provably straddle live traffic, which is stronger evidence than
     * sweeping as fast as the CPU allows.
     */
    private static final int TRANSFERS_BETWEEN_SWEEPS =
            MIN_TRANSFERS_UNDER_LOAD / MIN_SWEEPS_UNDER_LOAD;

    private final CustomerAccountStore<Connection> products = new JdbcCustomerAccountStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final AccountHolderVerification<Connection> holders =
            new VerifiedAccountHolder(new JdbcPartyStore());
    private final TransferParticipants<Connection> participants =
            new JdbcTransferParticipants(
                    new JdbcPartyStore(), new JdbcCustomerAccountStore(),
                    new JdbcLedgerAccountStore());
    private final ProjectionVerification verification =
            new ProjectionVerification(new JdbcBalanceDerivation());
    private final TrialBalance trialBalance = new TrialBalance();

    @Test
    @DisplayName("ten instances moving money both ways continuously: every trial-balance sweep"
            + " reads zero per currency, every projection verdict is CLEAN or IN_FLIGHT,"
            + " neither account is ever negative, and the pair's total is unchanged exactly")
    void valueIsConservedThroughoutSustainedBidirectionalMovement() throws Exception {
        Holder a = fundedHolder(FUNDED_MINOR);
        Holder b = fundedHolder(FUNDED_MINOR);
        long startingMinor = FUNDED_MINOR * 2;

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong committedCommands = new AtomicLong();
        // Every outcome the storm produced, counted by the movers themselves - and anything
        // that is NOT a domain outcome is captured with its SQLSTATE rather than swallowed,
        // because INV-CON-02's loser must fail with a DOMAIN outcome and an infrastructure
        // abort is not one. A test that retried such a failure away would hide it.
        Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(MOVERS_PER_DIRECTION * 2);
        List<Future<Void>> movers = new ArrayList<>();
        for (int i = 0; i < MOVERS_PER_DIRECTION; i++) {
            movers.add(pool.submit(mover(a, b, i, stop, committedCommands, outcomes)));
            movers.add(pool.submit(mover(b, a, i, stop, committedCommands, outcomes)));
        }

        long rounds = 0;
        try (Connection own = DatabaseRoles.application()) {
            // The sweeper ends the storm, so the overlap is the exit condition rather than
            // luck. The cap is a FAILURE bound: if the movers have died, fall through so
            // mover.get() below reports the real cause.
            while ((rounds < MIN_SWEEPS_UNDER_LOAD
                            || committedCommands.get() < MIN_TRANSFERS_UNDER_LOAD)
                    && rounds < 10_000) {
                rounds++;

                TrialBalance.Report trial = trialBalance.sweep(own);
                assertThat(trial.outOfBalance())
                        .as("mid-storm trial balance, round %s: zero per currency while money"
                                + " is moving both ways (INV-ACC-01)", rounds)
                        .isEmpty();
                assertThat(trial.currenciesVerified())
                        .as("the sweep must actually see a journal, or it asserts nothing over"
                                + " an empty set - the vacuity this repository has met"
                                + " repeatedly")
                        .isGreaterThanOrEqualTo(1);

                for (Holder holder : List.of(a, b)) {
                    assertThat(verification.verdictOf(own, LedgerAccountId.of(holder.walletId)))
                            .as("mid-storm projection verdict, round %s: the projection may be"
                                    + " mid-entry (IN_FLIGHT) but never wrong (DRIFTING)",
                                    rounds)
                            .isNotEqualTo(Verdict.DRIFTING);
                }

                // One statement, one snapshot: the pair read together is internally
                // consistent, so a leaked availability check surfaces as a negative DURING
                // the storm rather than only at its end.
                Map<UUID, Long> settled = settledOf(own, a.walletId, b.walletId);
                assertThat(settled.get(a.walletId))
                        .as("account A is never negative mid-storm, round %s", rounds)
                        .isGreaterThanOrEqualTo(0);
                assertThat(settled.get(b.walletId))
                        .as("account B is never negative mid-storm, round %s", rounds)
                        .isGreaterThanOrEqualTo(0);

                // Wait on the CONDITION - more committed commands - so the next sweep
                // provably runs against live traffic rather than racing ahead of it. The
                // deadline is a FAILURE bound, never a pass (the `P1-TSK-002` rule): if the
                // movers have died, fall through so mover.get() reports the real cause.
                long required =
                        Math.min(rounds * TRANSFERS_BETWEEN_SWEEPS, MIN_TRANSFERS_UNDER_LOAD);
                long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
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

        assertThat(rounds)
                .as("the sweeps really ran under load")
                .isGreaterThanOrEqualTo(MIN_SWEEPS_UNDER_LOAD);
        assertThat(committedCommands.get())
                .as("the storm really was a storm - outcomes: %s", outcomes)
                .isGreaterThanOrEqualTo(MIN_TRANSFERS_UNDER_LOAD);

        try (Connection app = DatabaseRoles.application()) {
            // Reading 1: the money's own rows. The pair's total is unchanged - what left one
            // wallet arrived in the other, to the minor unit.
            Map<UUID, Long> settled = settledOf(app, a.walletId, b.walletId);
            long settledA = settled.get(a.walletId);
            long settledB = settled.get(b.walletId);
            assertThat(settledA + settledB)
                    .as("value is neither created nor destroyed by any number of concurrent"
                            + " movements (INV-BAL-03, INV-CON-02)")
                    .isEqualTo(startingMinor);
            assertThat(settledA).as("A never went negative").isGreaterThanOrEqualTo(0);
            assertThat(settledB).as("B never went negative").isGreaterThanOrEqualTo(0);

            // Reading 2, INDEPENDENTLY RECOMPUTED: the transfer rows and the journal lines
            // are two records of the same movements that never see each other, and they must
            // agree exactly - INV-LED-04's chain (transfer -> entry -> lines) as arithmetic.
            long movedAtoB = completedMinorBetween(app, a, b);
            long movedBtoA = completedMinorBetween(app, b, a);
            assertThat(FUNDED_MINOR - movedAtoB + movedBtoA)
                    .as("A's journal position is exactly what its completed transfers say")
                    .isEqualTo(settledA);
            assertThat(FUNDED_MINOR - movedBtoA + movedAtoB)
                    .as("B's journal position is exactly what its completed transfers say")
                    .isEqualTo(settledB);

            // Reading 3: every loser is a COMMITTED DOMAIN OUTCOME, which is INV-CON-02's
            // own wording - not an exception, not a rollback, not an absence.
            long completed = transferCountByStatus(app, a, b, "COMPLETED");
            long failed = transferCountByStatus(app, a, b, "FAILED");
            assertThat(completed + failed)
                    .as("every command the movers committed produced a judged row")
                    .isEqualTo(committedCommands.get());
            assertThat(failedReasons(app, a, b))
                    .as("the only refusal reachable between two postable same-currency"
                            + " wallets is the affordability one")
                    .containsOnly("INSUFFICIENT_FUNDS");
            assertThat(failed)
                    .as("the designed-to-lose amounts really were exercised: a storm in which"
                            + " nothing ever lost would not have demonstrated INV-CON-02")
                    .isGreaterThan(0);
            assertThat(completed)
                    .as("and money really moved: a storm in which nothing ever succeeded would"
                            + " conserve value vacuously")
                    .isGreaterThan(0);
            assertThat(entriesForFailedTransfers(app, a, b))
                    .as("a refused transfer posts nothing")
                    .isZero();

            // Settled, the sweeps agree with the storm's end.
            assertThat(trialBalance.sweep(app).outOfBalance())
                    .as("settled: zero per currency")
                    .isEmpty();
            for (Holder holder : List.of(a, b)) {
                assertThat(verification.verdictOf(app, LedgerAccountId.of(holder.walletId)))
                        .as("settled: the projection equals replay-from-zero, watermark"
                                + " included")
                        .isEqualTo(Verdict.CLEAN);
            }

            // Anything the movers could not classify as a domain outcome is reported here
            // rather than absorbed: INV-CON-02's loser fails with a DOMAIN outcome, and an
            // infrastructure abort (a deadlock, say) is not one - conservation survives it,
            // because such a transaction writes nothing, but the contract does not.
            assertThat(outcomes.keySet())
                    .as("every outcome the storm produced was a domain outcome; anything else"
                            + " is a finding, not a flake: %s", outcomes)
                    .containsOnly("COMPLETED", "FAILED");
        }
    }

    // ------------------------------------------------------------------ the storm

    /**
     * One moving instance: own connection, own scopes, own transaction per command
     * (`P0-TST-009`), transferring one way until told to stop.
     *
     * <p>A domain outcome — {@code COMPLETED} or {@code FAILED} — is tallied after the
     * commit, so the tally is the independent record of what was durably judged. Anything
     * else is recorded <strong>by its SQLSTATE</strong> and the mover keeps going, so the
     * storm stays sustained and the final assertion can report what actually happened
     * rather than dying on the first one.
     */
    private Callable<Void> mover(
            Holder source,
            Holder destination,
            int index,
            AtomicBoolean stop,
            AtomicLong committedCommands,
            Map<String, AtomicLong> outcomes) {
        return () -> {
            TransferExecution execution = execution();
            int step = index;
            while (!stop.get()) {
                long minor = AMOUNTS_MINOR[Math.floorMod(step++, AMOUNTS_MINOR.length)];
                String key = "storm-" + IDS.next();
                try {
                    TransferStatus status =
                            asInstance(
                                    source,
                                    app -> {
                                        TransferResult result =
                                                execution
                                                        .execute(
                                                                app,
                                                                command(
                                                                        key,
                                                                        source,
                                                                        destination,
                                                                        usd(minor)));
                                        app.commit();
                                        return result.status();
                                    });
                    committedCommands.incrementAndGet();
                    tally(outcomes, status.name());
                } catch (Exception thrown) {
                    tally(outcomes, classify(thrown));
                }
            }
            return null;
        };
    }

    private static void tally(Map<String, AtomicLong> outcomes, String outcome) {
        outcomes.computeIfAbsent(outcome, ignored -> new AtomicLong()).incrementAndGet();
    }

    /** The {@code (SQLState XXXXX)} the platform's storage exceptions carry in their text. */
    private static final Pattern SQL_STATE = Pattern.compile("\\(SQLState ([0-9A-Za-z]+)\\)");

    /**
     * What a non-domain failure actually was: its SQLSTATE, so a deadlock reads
     * {@code SQL:40P01} rather than a class name. Named for the finding it would be, never
     * swallowed.
     *
     * <p>The state is read from the <strong>message</strong> rather than from a cause,
     * because {@code DatabaseFailure.describe} deliberately drops the {@link SQLException}
     * entirely (`P1-TSK-008`: PostgreSQL puts the whole refused row in the error's
     * {@code DETAIL}, so carrying the cause would carry a plaintext into every log) — it
     * keeps the operation and five characters of SQLSTATE, which is exactly enough to name
     * a failure class without naming a row.
     */
    private static String classify(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return "SQL:" + sql.getSQLState();
            }
            if (cause.getMessage() != null) {
                Matcher state = SQL_STATE.matcher(cause.getMessage());
                if (state.find()) {
                    return "SQL:" + state.group(1);
                }
            }
        }
        return thrown.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ wiring

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private static PostingService postingService() {
        return new PostingService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE);
    }

    /** The command as the composition root composes it, seams included (`P4-TSK-010`). */
    private TransferExecution execution() {
        return new TransferExecution(
                executor(),
                participants,
                ledgerAccounts,
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                postingService(),
                new JdbcTransferStore(),
                new PermitAllUntilPhase13<>(),
                new PermitAllUntilPhase13<>(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    // ------------------------------------------------------------------ fixtures

    private static final class Holder {
        final UUID partyId;
        final UUID productId;
        final UUID walletId;
        final Actor actor = new Actor(IDS.next().toString(), ActorType.CUSTOMER);

        Holder(UUID partyId, UUID productId, UUID walletId) {
            this.partyId = partyId;
            this.productId = productId;
            this.walletId = walletId;
        }
    }

    private Holder holder() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Storm Holder',"
                            + " now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        UUID product;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            product =
                    new AccountOpening(
                                    products,
                                    ledgerAccounts,
                                    holders,
                                    new JdbcAuditWriter(),
                                    new JdbcOutboxWriter(),
                                    IDS,
                                    CLOCK)
                            .open(app, party, ProductType.WALLET, USD)
                            .account()
                            .id()
                            .value();
            app.commit();
        }
        UUID wallet;
        try (Connection app = DatabaseRoles.application()) {
            wallet =
                    ledgerAccounts.findAllOwned(app, product).stream()
                            .filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET)
                            .findFirst()
                            .orElseThrow()
                            .id()
                            .value();
        }
        return new Holder(party, product, wallet);
    }

    /** A holder whose wallet holds {@code minorUnits}, credited from the clearing account. */
    private Holder fundedHolder(long minorUnits) throws Exception {
        Holder holder = holder();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(holder.actor);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount clearing =
                    ledgerAccounts
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                            .orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            postingService()
                    .post(
                            app,
                            new PostingCommand(
                                    "fund-" + IDS.next(),
                                    today,
                                    today,
                                    "funding",
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(),
                                                    Direction.DEBIT,
                                                    usd(minorUnits)),
                                            new JournalLine(
                                                    LedgerAccountId.of(holder.walletId),
                                                    Direction.CREDIT,
                                                    usd(minorUnits)))));
            app.commit();
        }
        return holder;
    }

    private TransferCommand command(
            String key, Holder source, Holder destination, Money amount) {
        return new TransferCommand(
                key, source.partyId, source.productId, destination.productId, amount, "storm");
    }

    private interface InTransaction<R> {
        R run(Connection app) throws Exception;
    }

    private <R> R asInstance(Holder caller, InTransaction<R> work) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(caller.actor);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            return work.run(app);
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    private static Money usd(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, USD);
    }

    // ------------------------------------------------------------------ counted in the tables

    /**
     * Both wallets' settled positions in <strong>one statement</strong>, so the pair is read
     * from one snapshot and a mid-storm reading is internally consistent — the money's own
     * rows, never the projection (`INV-BAL-05`).
     */
    private static Map<UUID, Long> settledOf(Connection connection, UUID... accounts)
            throws SQLException {
        Map<UUID, Long> settled = new java.util.HashMap<>();
        for (UUID account : accounts) {
            settled.put(account, 0L);
        }
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT ledger_account_id,"
                                + " COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor"
                                + " ELSE -amount_minor END), 0) AS settled"
                                + " FROM ledger.journal_line"
                                + " WHERE ledger_account_id = ANY (?)"
                                + " GROUP BY ledger_account_id")) {
            read.setArray(1, connection.createArrayOf("uuid", accounts));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    settled.put(rows.getObject(1, UUID.class), rows.getLong(2));
                }
            }
        }
        return settled;
    }

    /** The minor units that completed transfers moved from one holder to the other. */
    private static long completedMinorBetween(
            Connection connection, Holder source, Holder destination) throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM transfers.transfer"
                                + " WHERE status = 'COMPLETED' AND source_account_id = ?"
                                + " AND destination_account_id = ?")) {
            read.setObject(1, source.walletId);
            read.setObject(2, destination.walletId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long transferCountByStatus(
            Connection connection, Holder a, Holder b, String status) throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT count(*) FROM transfers.transfer"
                                + " WHERE status = ? AND source_account_id = ANY (?)")) {
            read.setString(1, status);
            read.setArray(
                    2, connection.createArrayOf("uuid", new UUID[] {a.walletId, b.walletId}));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static List<String> failedReasons(Connection connection, Holder a, Holder b)
            throws SQLException {
        List<String> reasons = new ArrayList<>();
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT DISTINCT failure_reason FROM transfers.transfer"
                                + " WHERE status = 'FAILED' AND source_account_id = ANY (?)")) {
            read.setArray(
                    1, connection.createArrayOf("uuid", new UUID[] {a.walletId, b.walletId}));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    reasons.add(rows.getString(1));
                }
            }
        }
        return reasons;
    }

    /** A refused transfer posts nothing: no entry may carry a FAILED transfer's identifier. */
    private static long entriesForFailedTransfers(Connection connection, Holder a, Holder b)
            throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT count(*) FROM transfers.transfer t"
                                + " WHERE t.status = 'FAILED'"
                                + " AND t.source_account_id = ANY (?)"
                                + " AND (t.journal_entry_id IS NOT NULL"
                                + "   OR EXISTS (SELECT 1 FROM ledger.journal_entry e"
                                + "              WHERE e.reference = t.id::text))")) {
            read.setArray(
                    1, connection.createArrayOf("uuid", new UUID[] {a.walletId, b.walletId}));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
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
