package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Statement continuity and the unattributed opener against the real schema (`P8-TSK-016`,
 * {@code INV-SET-06}, {@code INV-REC-09}, ADR-0069 §2, ADR-0070 §2): a first statement opening
 * at zero raises nothing; a non-zero first opening is one CRITICAL {@code OPENING_BALANCE} on the
 * statement's run, converging on a re-judgement; a missing or mis-stitched predecessor is
 * {@code STATEMENT_GAP}; the statement that fills a gap closes it {@code EVIDENCED} naming
 * itself — no decision — with {@code reconciliation.BreakResolved}, and a fill that does not
 * stitch leaves the gap OPEN; and an unattributed bank line is born {@code PARKED} beside its
 * owning break and opens ONE owned {@code BANK_UNATTRIBUTED} suspense item per line, per side,
 * carrying the recognition's entry — no park, no position.
 *
 * <p>Every test rolls back: the bank source's runs are never committed, so no other suite's
 * matcher ever sees them (the shared-container discipline without a private source).
 */
@Tag("database")
@DisplayName("statement continuity and the unattributed opener (P8-TSK-016)")
class StatementChainDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID BANK_SOURCE =
            UUID.fromString("01a0e2bc-8200-7004-8000-000000000004");
    private static final UUID PSP_SOURCE =
            UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final LocalDate STATEMENT_DAY = LocalDate.parse("2026-09-25");
    private static final LocalDate ACCEPTED_ON = LocalDate.parse("2026-09-29");
    /** Acceptance sequences far above every other suite's, never committed anyway. */
    private static final AtomicLong SEQUENCES =
            new AtomicLong(3_000_000L + System.nanoTime() % 1_000_000L);

    private static Connection application;
    private static JdbcBreakRegister register;
    private static StatementChain chain;
    private static Suspense suspense;
    private static JdbcRuleSets ruleSets;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        register = new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        chain = new StatementChain(register, ResolutionFixtures.resolutions(IDS, CLOCK), IDS);
        suspense = new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS);
        ruleSets = new JdbcRuleSets();
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
    }

    @AfterEach
    void rollBack() throws SQLException {
        application.rollback();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE);
    }

    // ----------------------------------------------------------------- the chain

    @Test
    @DisplayName("the first statement opening at zero raises nothing - the account opened"
            + " empty")
    void theFirstStatementAtZeroRaisesNothing() throws SQLException {
        UUID batch = IDS.next();
        UUID run = bankRun(batch, 0);

        StatementChain.Outcome outcome =
                chain.judge(application,
                        statement(batch, run, 1, 0, 13_025, Optional.empty(), Optional.empty()));

        assertThat(outcome.raised()).isZero();
        assertThat(outcome.gapFilled()).isFalse();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE run_id = ?", run))
                .isZero();
    }

    @Test
    @DisplayName("a non-zero first opening is ONE CRITICAL SETTLEMENT_MISMATCH/OPENING_BALANCE"
            + " on the statement's run at the opening's value - and judging again converges")
    void aNonZeroFirstOpeningRaisesOnceAndConverges() throws SQLException {
        UUID batch = IDS.next();
        UUID run = bankRun(batch, 0);
        StatementChain.Statement first =
                statement(batch, run, 1, 10_00, 50_00, Optional.empty(), Optional.empty());

        StatementChain.Outcome raised = chain.judge(application, first);
        assertThat(raised.raised()).isEqualTo(1);
        assertThat(raised.gapFilled()).isFalse();

        Object[] seam = row("SELECT type, cause, status, severity, value_at_issue_minor,"
                + " currency, scale FROM reconciliation.break WHERE run_id = ?", run);
        assertThat(seam[0]).isEqualTo("SETTLEMENT_MISMATCH");
        assertThat(seam[1]).isEqualTo("OPENING_BALANCE");
        assertThat(seam[2]).isEqualTo("OPEN");
        assertThat(seam[3]).as("a statement cause is CRITICAL (the severity seat)")
                .isEqualTo("CRITICAL");
        assertThat(seam[4]).isEqualTo(10_00L);
        assertThat(String.valueOf(seam[5]).trim()).isEqualTo("EUR");
        assertThat(((Number) seam[6]).intValue()).isEqualTo(2);

        StatementChain.Outcome again = chain.judge(application, first);
        assertThat(again.raised()).as("the one-open seat per (type, run) converges").isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE run_id = ?", run))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break_event ev JOIN"
                + " reconciliation.break b ON b.id = ev.break_id WHERE b.run_id = ?"
                + " AND ev.event_type = 'RAISED'", run))
                .as("the converging judgement records no second history row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a PRESENT predecessor whose closing is not this opening is STATEMENT_GAP at"
            + " the difference - never OPENING_BALANCE, which is the first statement's alone")
    void aMisStitchedOpeningIsAGapOnTheRun() throws SQLException {
        UUID batch = IDS.next();
        UUID run = bankRun(batch, 0);
        StatementChain.Link seq1 = link(IDS.next(), 1, 0, 130_25);

        StatementChain.Outcome outcome =
                chain.judge(application,
                        statement(batch, run, 2, 130_00, 140_00, Optional.of(seq1),
                                Optional.empty()));

        assertThat(outcome.raised()).isEqualTo(1);
        Object[] seam = row("SELECT type, cause, severity, value_at_issue_minor FROM"
                + " reconciliation.break WHERE run_id = ?", run);
        assertThat(seam[0]).isEqualTo("SETTLEMENT_MISMATCH");
        assertThat(seam[1]).isEqualTo("STATEMENT_GAP");
        assertThat(seam[2]).isEqualTo("CRITICAL");
        assertThat(seam[3]).as("|130.00 - 130.25|").isEqualTo(25L);
    }

    @Test
    @DisplayName("a missing predecessor is STATEMENT_GAP on the successor's run; the statement"
            + " that fills it closes it EVIDENCED naming itself - no decision - with"
            + " reconciliation.BreakResolved, and a replayed fill converges")
    void aFilledGapClosesEvidenced() throws SQLException {
        UUID seq1Batch = IDS.next();
        UUID seq3Batch = IDS.next();
        UUID seq3Run = bankRun(seq3Batch, 0);

        StatementChain.Outcome gap =
                chain.judge(application,
                        statement(seq3Batch, seq3Run, 3, 145_25, 143_00, Optional.empty(),
                                Optional.empty()));
        assertThat(gap.raised()).isEqualTo(1);
        UUID gapBreak = (UUID) one("SELECT id FROM reconciliation.break WHERE run_id = ?"
                + " AND type = 'SETTLEMENT_MISMATCH' AND cause = 'STATEMENT_GAP'", seq3Run);
        assertThat(string("SELECT status || ':' || severity || ':' ||"
                + " value_at_issue_minor::text FROM reconciliation.break WHERE id = ?",
                gapBreak))
                .as("a hole the chain cannot see across, at the absolute opening")
                .isEqualTo("OPEN:CRITICAL:14525");

        // Sequence 2 arrives: it stitches to 1 behind it and 3 ahead of it.
        UUID seq2Batch = IDS.next();
        UUID seq2Run = bankRun(seq2Batch, 0);
        StatementChain.Statement seq2 =
                statement(seq2Batch, seq2Run, 2, 140_25, 145_25,
                        Optional.of(link(seq1Batch, 1, 0, 140_25)),
                        Optional.of(link(seq3Batch, 3, 145_25, 143_00)));
        StatementChain.Outcome fill = chain.judge(application, seq2);

        assertThat(fill.raised()).as("seq 2's own seam holds").isZero();
        assertThat(fill.gapFilled()).isTrue();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE run_id = ?",
                seq2Run)).isZero();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", gapBreak))
                .isEqualTo("RESOLVED");
        assertThat(one("SELECT resolved_at FROM reconciliation.break WHERE id = ?", gapBreak))
                .isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                gapBreak)).isEqualTo(1);
        Object[] resolution = row("SELECT kind, status, reason_code, proposed_by_type,"
                + " four_eyes, decision_id, narrative, proposed_amount_minor, journal_entry_id"
                + " FROM reconciliation.resolution WHERE break_id = ?", gapBreak);
        assertThat(resolution[0]).isEqualTo("EVIDENCED");
        assertThat(resolution[1]).isEqualTo("APPROVED");
        assertThat(resolution[2]).isEqualTo("EVIDENCE_RECEIVED");
        assertThat(resolution[3]).isEqualTo("SYSTEM");
        assertThat(resolution[4]).isEqualTo(false);
        assertThat(resolution[5])
                .as("no decision is made at a gap-fill: the filling statement is the evidence")
                .isNull();
        assertThat(resolution[6]).isEqualTo("statement=" + seq2Batch);
        assertThat(resolution[7]).isEqualTo(145_25L);
        assertThat(resolution[8]).as("a gap-fill posts nothing").isNull();
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?"
                + " AND event_type = 'RESOLVED'", gapBreak)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", gapBreak))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.BreakResolvedByEvidence' AND change_summary LIKE"
                + " '%statement=" + seq2Batch + "%'")).isEqualTo(1);

        // Replayed: the gap is already RESOLVED - the writer records NOTHING.
        StatementChain.Outcome replay = chain.judge(application, seq2);
        assertThat(replay.raised()).isZero();
        assertThat(replay.gapFilled()).isFalse();
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                gapBreak)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", gapBreak))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a fill whose closing does NOT stitch the successor's opening leaves the gap"
            + " OPEN - the chain is still not the truth")
    void aFillThatDoesNotStitchLeavesTheGapOpen() throws SQLException {
        UUID seq6Batch = IDS.next();
        UUID seq6Run = bankRun(seq6Batch, 0);
        chain.judge(application,
                statement(seq6Batch, seq6Run, 6, 200_00, 210_00, Optional.empty(),
                        Optional.empty()));
        UUID gapBreak = (UUID) one("SELECT id FROM reconciliation.break WHERE run_id = ?"
                + " AND cause = 'STATEMENT_GAP'", seq6Run);

        UUID seq5Batch = IDS.next();
        UUID seq5Run = bankRun(seq5Batch, 0);
        StatementChain.Outcome fill =
                chain.judge(application,
                        statement(seq5Batch, seq5Run, 5, 190_00, 199_99,
                                Optional.of(link(IDS.next(), 4, 0, 190_00)),
                                Optional.of(link(seq6Batch, 6, 200_00, 210_00))));

        assertThat(fill.gapFilled()).isFalse();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", gapBreak))
                .isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                gapBreak)).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", gapBreak))
                .isZero();
    }

    // ----------------------------------------------------------------- the unattributed opener

    @Test
    @DisplayName("an unattributed bank credit is born PARKED beside its owning break, then"
            + " opens ONE owned BANK_UNATTRIBUTED suspense item carrying the entry - CREDIT, no"
            + " park, no position; a second or an attributed line is refused")
    void anUnattributedCreditOpensOneOwnedItem() throws SQLException {
        UUID run = bankRun(IDS.next(), 2);
        UUID unattributed = IDS.next();
        UUID attributed = IDS.next();
        items.birthAll(application, PLATFORM,
                List.of(
                        bankItem(unattributed, run, 1, ExternalLineType.BANK_CREDIT,
                                ExpectationDirection.INBOUND, 5_00, Optional.empty()),
                        bankItem(attributed, run, 2, ExternalLineType.BANK_CREDIT,
                                ExpectationDirection.INBOUND, 7_00, Optional.of(PSP_SOURCE))));
        UUID owner = raiseUnattributed(unattributed, ExpectationDirection.INBOUND, 5_00);

        suspense.bornParked(application,
                List.of(new Suspense.Unattributed(unattributed, owner)),
                PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS));

        assertThat(string("SELECT status || ':' || parked_minor::text FROM"
                + " reconciliation.external_item WHERE id = ?", unattributed))
                .as("its whole value parked in the transaction that raised its owner")
                .isEqualTo("PARKED:500");
        assertThat(count("SELECT count(*) FROM reconciliation.external_item_event WHERE"
                + " item_id = ? AND from_status = 'PENDING' AND to_status = 'PARKED'",
                unattributed)).isEqualTo(1);

        UUID entry = IDS.next();
        int opened = suspense.openUnattributed(application, run, ACCEPTED_ON, entry,
                Instant.now(CLOCK), CorrelationId.generate(IDS));

        assertThat(opened).as("the attributed line waits for the matcher").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id IN (?, ?)", unattributed, attributed)).isEqualTo(1);
        Object[] item = row("SELECT origin, side, amount_minor, park_id, position_account_id,"
                + " origin_ref, entry_id, break_id, status, released_minor,"
                + " opened_on::text FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", unattributed);
        assertThat(item[0]).isEqualTo("BANK_UNATTRIBUTED");
        assertThat(item[1]).isEqualTo("CREDIT");
        assertThat(item[2]).isEqualTo(5_00L);
        assertThat(item[3]).as("no park row: it entered suspense from CASH").isNull();
        assertThat(item[4]).as("no position: nobody's pattern explained it").isNull();
        assertThat(item[5]).isEqualTo(unattributed.toString());
        assertThat(item[6]).as("the recognition's entry, whole").isEqualTo(entry);
        assertThat(item[7]).as("owned by the break raised beside it (INV-REC-09)")
                .isEqualTo(owner);
        assertThat(item[8]).isEqualTo("OPEN");
        assertThat(item[9]).isEqualTo(0L);
        assertThat(item[10]).isEqualTo("2026-09-29");

        assertThatThrownBy(
                        () ->
                                suspense.bornParked(application,
                                        List.of(new Suspense.Unattributed(unattributed, owner)),
                                        PLATFORM, Instant.now(CLOCK),
                                        CorrelationId.generate(IDS)))
                .as("an item already PARKED is no fresh unattributed line")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an unattributed bank line");

        UUID attributedOwner =
                register.raise(application,
                                newBreak(BreakType.UNKNOWN_EXTERNAL, BreakCause.GRACE_EXPIRED,
                                        attributed, ExpectationDirection.INBOUND, 7_00))
                        .breakId();
        assertThatThrownBy(
                        () ->
                                suspense.bornParked(application,
                                        List.of(new Suspense.Unattributed(attributed,
                                                attributedOwner)),
                                        PLATFORM, Instant.now(CLOCK),
                                        CorrelationId.generate(IDS)))
                .as("an attributed line is the matcher's, never born parked")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an unattributed bank line");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                attributed)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("an unattributed line parks only under an existing open owner - a missing"
            + " break is refused before anything moves (INV-REC-09)")
    void anUnattributedLineWithoutItsOwnerIsRefused() throws SQLException {
        UUID run = bankRun(IDS.next(), 1);
        UUID line = IDS.next();
        items.birthAll(application, PLATFORM,
                List.of(bankItem(line, run, 1, ExternalLineType.BANK_CREDIT,
                        ExpectationDirection.INBOUND, 3_00, Optional.empty())));

        assertThatThrownBy(
                        () ->
                                suspense.bornParked(application,
                                        List.of(new Suspense.Unattributed(line, IDS.next())),
                                        PLATFORM, Instant.now(CLOCK),
                                        CorrelationId.generate(IDS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INV-REC-09");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                line)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("an unattributed credit and debit of equal size open TWO items, one per side,"
            + " each owned by its own break - never netted; the debit's owner is CRITICAL")
    void theTwoSidesAreNeverNetted() throws SQLException {
        UUID run = bankRun(IDS.next(), 2);
        UUID in = IDS.next();
        UUID out = IDS.next();
        items.birthAll(application, PLATFORM,
                List.of(
                        bankItem(in, run, 1, ExternalLineType.BANK_CREDIT,
                                ExpectationDirection.INBOUND, 3_00, Optional.empty()),
                        bankItem(out, run, 2, ExternalLineType.BANK_DEBIT,
                                ExpectationDirection.OUTBOUND, 3_00, Optional.empty())));
        UUID inOwner = raiseUnattributed(in, ExpectationDirection.INBOUND, 3_00);
        UUID outOwner = raiseUnattributed(out, ExpectationDirection.OUTBOUND, 3_00);
        assertThat(string("SELECT severity FROM reconciliation.break WHERE id = ?", outOwner))
                .as("money that LEFT with no owner")
                .isEqualTo("CRITICAL");

        suspense.bornParked(application,
                List.of(new Suspense.Unattributed(out, outOwner),
                        new Suspense.Unattributed(in, inOwner)),
                PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS));
        int opened = suspense.openUnattributed(application, run, ACCEPTED_ON, IDS.next(),
                Instant.now(CLOCK), CorrelationId.generate(IDS));

        assertThat(opened).isEqualTo(2);
        assertThat(string("SELECT side || ':' || amount_minor::text || ':' || origin FROM"
                + " reconciliation.suspense_item WHERE break_id = ?", inOwner))
                .isEqualTo("CREDIT:300:BANK_UNATTRIBUTED");
        assertThat(string("SELECT side || ':' || amount_minor::text || ':' || origin FROM"
                + " reconciliation.suspense_item WHERE break_id = ?", outOwner))
                .isEqualTo("DEBIT:300:BANK_UNATTRIBUTED");
    }

    // ----------------------------------------------------------------- fixtures

    private UUID bankRuleSet() {
        return ruleSets.activeFor(application, BANK_SOURCE).id();
    }

    /** A bank statement's BATCH run, never committed. */
    private UUID bankRun(UUID batchId, int itemCount) {
        UUID runId = IDS.next();
        runs.birth(
                application,
                new ReconciliationRuns.NewRun(
                        runId,
                        BANK_SOURCE,
                        Optional.of(batchId),
                        RunKind.BATCH,
                        bankRuleSet(),
                        STATEMENT_DAY,
                        Optional.of(SEQUENCES.incrementAndGet()),
                        itemCount,
                        Optional.empty(),
                        Optional.empty(),
                        PLATFORM,
                        Instant.now(CLOCK),
                        CorrelationId.generate(IDS)));
        return runId;
    }

    private StatementChain.Statement statement(
            UUID batchId,
            UUID runId,
            long sequence,
            long openingMinor,
            long closingMinor,
            Optional<StatementChain.Link> predecessor,
            Optional<StatementChain.Link> successor) {
        return new StatementChain.Statement(
                batchId,
                runId,
                BANK_SOURCE,
                bankRuleSet(),
                sequence,
                eur(openingMinor),
                eur(closingMinor),
                predecessor,
                successor,
                PLATFORM,
                Instant.now(CLOCK),
                CorrelationId.generate(IDS));
    }

    private static StatementChain.Link link(
            UUID batchId, long sequence, long openingMinor, long closingMinor) {
        return new StatementChain.Link(batchId, sequence, eur(openingMinor), eur(closingMinor));
    }

    /** A bank line's working copy: in the attributed source's position, or in none. */
    private static ExternalItems.NewItem bankItem(
            UUID id,
            UUID runId,
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            Optional<UUID> attributedSource) {
        byte[] fingerprint = new byte[32];
        new SecureRandom().nextBytes(fingerprint);
        return new ExternalItems.NewItem(
                id,
                runId,
                BANK_SOURCE,
                IDS.next(),
                lineNo,
                type,
                direction,
                eur(minor),
                attributedSource.map(source -> AccountPurpose.SETTLEMENT_CLEARING),
                attributedSource,
                STATEMENT_DAY,
                Optional.empty(),
                Optional.of(STATEMENT_DAY),
                fingerprint,
                attributedSource.isPresent()
                        ? Map.of(ItemKeyKind.REMITTANCE_REF, "PSP-REM-" + lineNo + "0260925")
                        : Map.of(),
                Instant.now(CLOCK),
                CorrelationId.generate(IDS));
    }

    private UUID raiseUnattributed(UUID itemId, ExpectationDirection direction, long minor) {
        BreakRegister.Raised raised =
                register.raise(application,
                        newBreak(BreakType.UNKNOWN_EXTERNAL, BreakCause.BANK_LINE_UNATTRIBUTED,
                                itemId, direction, minor));
        assertThat(raised.created()).isTrue();
        return raised.breakId();
    }

    private BreakRegister.NewBreak newBreak(
            BreakType type,
            BreakCause cause,
            UUID itemId,
            ExpectationDirection direction,
            long minor) {
        return new BreakRegister.NewBreak(
                IDS.next(),
                type,
                cause,
                BreakRegister.Subject.externalItem(itemId),
                BANK_SOURCE,
                bankRuleSet(),
                eur(minor),
                Optional.of(direction),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                PLATFORM,
                Instant.now(CLOCK),
                CorrelationId.generate(IDS));
    }

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static Object[] row(String sql, Object... args) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                Object[] values = new Object[row.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = row.getObject(i + 1);
                }
                return values;
            }
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        return row(sql, args)[0];
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static String string(String sql, Object... args) throws SQLException {
        return String.valueOf(one(sql, args));
    }
}
