package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import com.finapp.settlement.format.simstatement.SimStatementTaggedFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The bank statement against the real schema and the real ledger (`P8-TSK-016`, ADR-0065 §3,
 * ADR-0066 §§3, 5; {@code INV-SET-06}, {@code INV-SET-05}): settlement `V005`'s rules refuse
 * every raw writer — a statement without its facts, facts on a report, a report without its
 * remittance, a sequence below one, a net that is not closing minus opening, attribution on a
 * non-bank line, a second live statement of one sequence; the golden statement parses with its
 * continuity facts and each line attributed through the compiled register; a second statement of
 * a live sequence is refused {@code CONFLICTING_BATCH} and RETAINED; acceptance recognises cash
 * in ONE entry — cash against each attributed position, the bank's fee and the unattributed
 * credit's suspense — and hands the intake the statement's continuity, the accepted predecessor
 * and successor read under the source row lock.
 *
 * <p>A RECORDING intake stands in for reconciliation (the app tier composes the real one).
 * The tests are ordered: one account's chain, sequence by sequence, in the shared container.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the bank statement: facts, attribution, continuity and cash (P8-TSK-016)")
class BankStatementDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of("p8-tsk-016-statement-test"));
    private static final byte[] KEY = new byte[32];
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static final String BANK = "simulated-bank.statement";
    // The seeded source rows (settlement V002).
    private static final UUID BANK_SOURCE_ID =
            UUID.fromString("01a0e2bc-8200-7004-8000-000000000004");
    private static final UUID PSP_SOURCE_ID =
            UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID SCHEME_SOURCE_ID =
            UUID.fromString("01a0e2bc-8200-7002-8000-000000000002");
    private static final UUID PAYOUT_SOURCE_ID =
            UUID.fromString("01a0e2bc-8200-7003-8000-000000000003");

    /** The golden statement's own identity line, re-keyed so each delivery is distinct. */
    private static final String GOLDEN_REF_LINE = ":20:SB-STMT-20260925-EUR";

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    private static final TransactionRunner RUNNER =
            new TransactionRunner() {
                @Override
                public <R> R inTransaction(Function<Connection, R> work) {
                    try (Connection connection = DatabaseRoles.application()) {
                        connection.setAutoCommit(false);
                        try {
                            R result = work.apply(connection);
                            connection.commit();
                            return result;
                        } catch (RuntimeException failure) {
                            connection.rollback();
                            throw failure;
                        }
                    } catch (SQLException infrastructure) {
                        throw new IllegalStateException(infrastructure);
                    }
                }
            };

    /** Records every handover and every post-posting call - both halves of the port. */
    private static final class RecordingIntake implements AcceptedBatchIntake {
        final ConcurrentLinkedQueue<AcceptedBatch> batches = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Recognised> recognised = new ConcurrentLinkedQueue<>();

        record Recognised(UUID batchId, Optional<UUID> entryId) {}

        @Override
        public Intaken intake(Connection unitOfWork, AcceptedBatch batch) {
            batches.add(batch);
            int unattributed =
                    (int)
                            batch.lines().stream()
                                    .filter(
                                            line ->
                                                    line.type() == SettlementLineType.BANK_CREDIT
                                                            || line.type()
                                                                    == SettlementLineType
                                                                            .BANK_DEBIT)
                                    .filter(line -> line.attributedSourceId().isEmpty())
                                    .count();
            return new Intaken(IDS.next(), batch.lines().size(), false, unattributed, 0);
        }

        @Override
        public void recognised(
                Connection unitOfWork,
                AcceptedBatch batch,
                Intaken intaken,
                Optional<UUID> entryId) {
            recognised.add(new Recognised(batch.batchId(), entryId));
        }
    }

    private enum TestAction implements AuditableAction {
        RECEIVED;

        @Override
        public String code() {
            return "settlement.TestFileReceived";
        }

        @Override
        public String description() {
            return "test stand-in for the door's reception action";
        }

        @Override
        public boolean requiresReason() {
            return false;
        }
    }

    private static Connection application;
    private static JdbcSettlementFileStore store;
    private static JdbcSettlementBatchStore batches;
    private static FileReception<Connection> reception;
    private static FileParsing parsing;
    private static RecordingIntake intake;
    private static BatchAcceptance acceptance;

    // The chain under test, carried across the ordered tests.
    private static UUID seq1File;
    private static UUID seq1Batch;
    private static UUID seq2Batch;

    /** The composed register's shape: three report patterns and the bank that attributes. */
    private static SettlementSources sources() {
        return SettlementSources.of(
                List.of(
                        new SettlementSourceDescriptor(
                                "simulated-psp.settlement",
                                SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                Optional.of("PSP-REM-[0-9]{4,12}")),
                        new SettlementSourceDescriptor(
                                "simulated-scheme.cycle-report",
                                SourceKind.SCHEME_CYCLE_REPORT,
                                SettlementFormatId.SIM_SCHEME_JSON,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.of(AccountPurpose.INSTANT_CLEARING),
                                Optional.of("SCH-REM-[0-9]{4,12}")),
                        new SettlementSourceDescriptor(
                                "simulated-payout.settlement",
                                SourceKind.PAYOUT_PROVIDER_REPORT,
                                SettlementFormatId.SIM_PAYOUT_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.of(AccountPurpose.PAYOUT_CLEARING),
                                Optional.of("PAY-REM-[0-9]{4,12}")),
                        new SettlementSourceDescriptor(
                                BANK,
                                SourceKind.BANK_STATEMENT,
                                SettlementFormatId.SIM_STATEMENT_TAGGED,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.empty(),
                                Optional.empty())));
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

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        SettlementSources sources = sources();
        store = new JdbcSettlementFileStore(new SettlementFileCipher(KEY, 1, new SecureRandom()));
        batches = new JdbcSettlementBatchStore(IDS);
        AuditWriter<Connection> audit = new JdbcAuditWriter();
        SimStatementTaggedFormat bank =
                new SimStatementTaggedFormat(Map.of(EUR, "SIMBANK-EUR-01"));
        DeliveryScreen pspScreen = SimPspCsvFormat.INSTANCE::screen;
        DeliveryScreen bankScreen = bank::screen;
        reception =
                new FileReception<>(
                        sources,
                        store,
                        Map.of(
                                SettlementFormatId.SIM_PSP_CSV, pspScreen,
                                SettlementFormatId.SIM_STATEMENT_TAGGED, bankScreen),
                        new ReceptionOutcomeObserver() {
                            @Override
                            public void received(
                                    String sourceCode,
                                    SettlementFileStore.ReceiptOutcome outcome) {}

                            @Override
                            public void refused(String sourceCode, RefusalReason reason) {}
                        },
                        audit,
                        IDS,
                        CLOCK);
        // The PSP format too: an earlier suite's leftover report is parsed honestly by this
        // sweep rather than recorded as the platform's failure.
        parsing =
                new FileParsing(
                        store,
                        batches,
                        Map.<SettlementFormatId, SettlementFormat>of(
                                SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE,
                                SettlementFormatId.SIM_STATEMENT_TAGGED, bank),
                        new FileParsing.Config(200, Duration.ofMinutes(1), Duration.ofHours(1)),
                        IntakeOutcomeObserver.NONE,
                        new JdbcOutboxWriter(),
                        audit,
                        IDS,
                        CLOCK,
                        RUNNER,
                        sources);
        intake = new RecordingIntake();
        acceptance =
                new BatchAcceptance(
                        store,
                        batches,
                        sources,
                        intake,
                        postingService(),
                        new JdbcLedgerAccountStore(),
                        new BatchAcceptance.Config(200),
                        IntakeOutcomeObserver.NONE,
                        new JdbcOutboxWriter(),
                        new JdbcAuditWriter(),
                        IDS,
                        CLOCK,
                        RUNNER);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // ----------------------------------------------------------------- V005 at the database

    @Test
    @Order(1)
    @DisplayName("V005 refuses every raw writer: a statement without its facts or with half of"
            + " them, facts on a report, a report without its remittance, a statement with one,"
            + " sequence 0, a net that is not closing minus opening, attribution on a non-bank"
            + " line - and a second LIVE statement of one sequence")
    void theStatementRulesBindEveryWriter() throws SQLException {
        try {
            probeTheStatementRules();
        } finally {
            // Nothing a probe wrote survives - not even on a failed assertion, which would
            // otherwise ride the next ordered test's commit.
            application.rollback();
        }
    }

    private static void probeTheStatementRules() throws SQLException {
        // Three uncommitted deliveries give the raw rows their files; everything rolls back.
        List<UUID> files = new ArrayList<>();
        for (String probe : List.of("A", "B", "C")) {
            files.add(receivedUncommitted(goldenWithRef("SB-STMT-PROBE-" + probe)));
        }
        UUID file = files.get(0);
        String bank = SettlementFormatId.SIM_STATEMENT_TAGGED.name();
        String psp = SettlementFormatId.SIM_PSP_CSV.name();

        assertRefused("batch_statement_iff_bank_format", CHECK_VIOLATION,
                () -> rawBatch(IDS.next(), file, "SB-PROBE-01", bank, "PARSED", 0,
                        "PSP-REM-0001", null, null, null));
        assertRefused("batch_statement_facts_whole", CHECK_VIOLATION,
                () -> rawBatch(IDS.next(), file, "SB-PROBE-02", bank, "PARSED", 0, null, 1L,
                        0L, null));
        assertRefused("batch_statement_iff_bank_format", CHECK_VIOLATION,
                () -> rawBatch(IDS.next(), file, "SB-PROBE-03", psp, "PARSED", 0, null, 1L, 0L,
                        0L));
        assertRefused("batch_remittance_iff_report", CHECK_VIOLATION,
                () -> rawBatch(IDS.next(), file, "SB-PROBE-04", psp, "PARSED", 0, null, null,
                        null, null));
        assertRefused("batch_remittance_iff_report", CHECK_VIOLATION,
                () -> rawBatch(IDS.next(), file, "SB-PROBE-05", bank, "PARSED", 0,
                        "PSP-REM-0001", 1L, 0L, 0L));
        assertRefused("batch_statement_sequence_positive", CHECK_VIOLATION,
                () -> rawBatch(IDS.next(), file, "SB-PROBE-06", bank, "PARSED", 0, null, 0L,
                        0L, 0L));
        assertRefused("batch_statement_net_is_its_balances", CHECK_VIOLATION,
                () -> rawBatch(IDS.next(), file, "SB-PROBE-07", bank, "PARSED", 50, null, 1L,
                        0L, 100L));

        // The live statement-sequence unique is the arbiter any writer converges on.
        long sequence = 900_001L;
        UUID live = IDS.next();
        rawBatch(live, file, "SB-PROBE-LIVE-A", bank, "PARSED", 0, null, sequence, 0L, 0L);
        assertRefused("batch_live_statement_sequence", UNIQUE_VIOLATION,
                () -> rawBatch(IDS.next(), files.get(1), "SB-PROBE-LIVE-B", bank, "PARSED", 0,
                        null, sequence, 0L, 0L));
        rawBatch(IDS.next(), files.get(2), "SB-PROBE-LIVE-C", bank, "REJECTED", 0, null,
                sequence, 0L, 0L);

        // Attribution is a bank credit's or debit's alone.
        assertRefused("line_attribution_is_a_bank_line", CHECK_VIOLATION,
                () -> rawLine(live, file, 1, "BANK_FEE", "OUTBOUND", PSP_SOURCE_ID));
        assertRefused("line_attribution_is_a_bank_line", CHECK_VIOLATION,
                () -> rawLine(live, file, 2, "CAPTURE", "INBOUND", PSP_SOURCE_ID));
        rawLine(live, file, 3, "BANK_CREDIT", "INBOUND", PSP_SOURCE_ID);
        rawLine(live, file, 4, "BANK_DEBIT", "OUTBOUND", PAYOUT_SOURCE_ID);
        rawLine(live, file, 5, "BANK_FEE", "OUTBOUND", null);
    }

    // ----------------------------------------------------------------- the parse leg

    @Test
    @Order(2)
    @DisplayName("the golden statement parses whole: sequence 1, opening 0, closing 130.25, no"
            + " remittance of its own - and each credit or debit attributed through the"
            + " compiled register, the unreferenced credit and the fee to nobody")
    void theGoldenStatementParsesWithItsFactsAndAttribution() throws SQLException {
        seq1File = pulled(goldenWithRef("SB-STMT-DBT-SEQ1-EUR"));
        parse(seq1File);
        assertThat(fileColumn(seq1File, "status")).isEqualTo("PARSED");

        SettlementBatchStore.BatchRow batch = batchOf(seq1File);
        seq1Batch = batch.id();
        assertThat(batch.formatId()).isEqualTo(SettlementFormatId.SIM_STATEMENT_TAGGED);
        assertThat(batch.sourceId()).isEqualTo(BANK_SOURCE_ID);
        assertThat(batch.externalBatchRef()).isEqualTo("SB-STMT-DBT-SEQ1-EUR");
        assertThat(batch.remittanceReference()).isEmpty();
        assertThat(batch.statement())
                .contains(new SettlementBatchStore.StatementRow(1, 0, 13_025));
        assertThat(batch.netMinor()).isEqualTo(13_025L);
        assertThat(batch.netScale()).isEqualTo(2);
        assertThat(batch.lineCount()).isEqualTo(5);
        assertThat(batch.declaredLineCount()).isEqualTo(5);
        assertThat(columnOfBatch(seq1Batch, "statement_sequence")).isEqualTo("1");
        assertThat(columnOfBatch(seq1Batch, "opening_minor")).isEqualTo("0");
        assertThat(columnOfBatch(seq1Batch, "closing_minor")).isEqualTo("13025");
        assertThat(columnOfBatch(seq1Batch, "remittance_reference")).isNull();

        Map<Integer, String> attribution = new LinkedHashMap<>();
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT line_no, line_type, attributed_source_id::text AS attributed"
                                + " FROM settlement.line WHERE batch_id = ? ORDER BY line_no")) {
            read.setObject(1, seq1Batch);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    attribution.put(
                            rows.getInt("line_no"),
                            rows.getString("line_type") + ":" + rows.getString("attributed"));
                }
            }
        }
        application.rollback();
        assertThat(attribution)
                .containsExactly(
                        Map.entry(1, "BANK_CREDIT:" + PSP_SOURCE_ID),
                        Map.entry(2, "BANK_CREDIT:" + SCHEME_SOURCE_ID),
                        Map.entry(3, "BANK_DEBIT:" + PAYOUT_SOURCE_ID),
                        Map.entry(4, "BANK_CREDIT:null"),
                        Map.entry(5, "BANK_FEE:null"));
        assertThat(count(
                        "SELECT count(*) FROM settlement.line_reference r"
                                + " JOIN settlement.line l ON l.id = r.line_id"
                                + " WHERE l.batch_id = ? AND r.kind = 'REMITTANCE_REF'",
                        seq1Batch))
                .as("the structured reference rides out typed; the narrative never does")
                .isEqualTo(3);

        List<SettlementBatchStore.TotalRow> totals = batches.totalsOf(application, seq1Batch);
        application.rollback();
        assertThat(totals)
                .containsExactlyInAnyOrder(
                        new SettlementBatchStore.TotalRow(
                                SettlementLineType.BANK_CREDIT, LineDirection.INBOUND, 3,
                                14_325, 2),
                        new SettlementBatchStore.TotalRow(
                                SettlementLineType.BANK_DEBIT, LineDirection.OUTBOUND, 1,
                                1_250, 2),
                        new SettlementBatchStore.TotalRow(
                                SettlementLineType.BANK_FEE, LineDirection.OUTBOUND, 1, 50,
                                2));
    }

    @Test
    @Order(3)
    @DisplayName("a second statement of the same account, currency and sequence - under"
            + " another statement reference - is refused CONFLICTING_BATCH and RETAINED")
    void aSecondStatementOfALiveSequenceIsConflicting() throws SQLException {
        UUID duplicate = pulled(goldenWithRef("SB-STMT-DBT-SEQ1-DUP"));
        parse(duplicate);

        assertThat(fileColumn(duplicate, "status")).isEqualTo("REJECTED");
        assertThat(fileColumn(duplicate, "rejection_code")).isEqualTo("CONFLICTING_BATCH");
        assertThat(count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?",
                        duplicate))
                .as("retained (ADR-0066 section 5): readmissible after a repudiation")
                .isPositive();
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", duplicate))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                + " 'settlement.SettlementFileRejected' AND aggregate_id = ?",
                        duplicate))
                .isEqualTo(1);
        assertThat(batchOf(seq1File).status())
                .as("the standing statement is untouched")
                .isEqualTo(BatchStatus.PARSED);
    }

    // ----------------------------------------------------------------- the accept leg

    @Test
    @Order(4)
    @DisplayName("acceptance recognises cash in ONE entry - DR CASH_AT_BANK the net, each"
            + " attributed position the opposite of its lines, DR PROCESSING_COSTS the fee, CR"
            + " SUSPENSE the unattributed credit - hands the intake the statement's continuity"
            + " and calls recognised once with the entry")
    void acceptanceRecognisesCash() throws SQLException {
        accept(seq1File);
        assertThat(fileColumn(seq1File, "status")).isEqualTo("ACCEPTED");
        assertThat(batchOf(seq1File).status()).isEqualTo(BatchStatus.ACCEPTED);
        assertThat(columnOfBatch(seq1Batch, "posting_omitted")).isEqualTo("false");
        UUID entryId = UUID.fromString(columnOfBatch(seq1Batch, "journal_entry_id"));

        AcceptedBatchIntake.AcceptedBatch handed = handedFor(seq1File);
        assertThat(handed.batchId()).isEqualTo(seq1Batch);
        assertThat(handed.sourceId()).isEqualTo(BANK_SOURCE_ID);
        assertThat(handed.positionPurpose())
                .as("the statement is where remittances LAND: no position of its own")
                .isEmpty();
        assertThat(handed.remittanceReference()).isEmpty();
        assertThat(handed.net()).isEqualTo(eur(13_025));
        assertThat(handed.statement())
                .hasValueSatisfying(
                        continuity -> {
                            assertThat(continuity.sequence()).isEqualTo(1L);
                            assertThat(continuity.opening()).isEqualTo(eur(0));
                            assertThat(continuity.closing()).isEqualTo(eur(13_025));
                            assertThat(continuity.predecessor()).isEmpty();
                            assertThat(continuity.successor()).isEmpty();
                        });
        List<AcceptedBatchIntake.CanonicalLine> lines = handed.lines();
        assertThat(lines).hasSize(5);
        assertAttributed(lines.get(0), SettlementLineType.BANK_CREDIT, PSP_SOURCE_ID,
                AccountPurpose.SETTLEMENT_CLEARING);
        assertAttributed(lines.get(1), SettlementLineType.BANK_CREDIT, SCHEME_SOURCE_ID,
                AccountPurpose.INSTANT_CLEARING);
        assertAttributed(lines.get(2), SettlementLineType.BANK_DEBIT, PAYOUT_SOURCE_ID,
                AccountPurpose.PAYOUT_CLEARING);
        assertUnattributed(lines.get(3), SettlementLineType.BANK_CREDIT);
        assertUnattributed(lines.get(4), SettlementLineType.BANK_FEE);

        assertThat(entryLines(entryId))
                .as("143.25 debited, 143.25 credited: cash follows the bank's own statement")
                .containsExactlyInAnyOrder(
                        "CASH_AT_BANK:EUR:DEBIT:13025",
                        "SETTLEMENT_CLEARING:EUR:CREDIT:9825",
                        "INSTANT_CLEARING:EUR:CREDIT:4000",
                        "PAYOUT_CLEARING:EUR:DEBIT:1250",
                        "PROCESSING_COSTS:EUR:DEBIT:50",
                        "SUSPENSE_UNMATCHED:EUR:CREDIT:500");
        assertThat(string("SELECT posting_date::text || '|' || value_date::text FROM"
                + " ledger.journal_entry WHERE id = ?", entryId))
                .as("posted on accepted_on, value-dated the statement's own business date")
                .isEqualTo("2026-09-29|2026-09-25");
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                        "ledger.post:" + BatchAcceptance.POSTING_KEY_PREFIX + seq1Batch))
                .isEqualTo(1);

        assertThat(intake.recognised)
                .filteredOn(call -> call.batchId().equals(seq1Batch))
                .as("recognised is called once, after the posting, with its entry")
                .singleElement()
                .satisfies(call -> assertThat(call.entryId()).contains(entryId));
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementBatchAccepted' AND target_id = ?",
                        seq1Batch.toString()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                + " 'settlement.SettlementBatchAccepted' AND aggregate_id = ?",
                        seq1File))
                .isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("sequence 2 accepted after 1 is handed its predecessor - sequence 1's batch and"
            + " signed balances - read under the source row lock")
    void theNextStatementSeesItsPredecessor() throws SQLException {
        UUID file =
                pulled(statement("SB-STMT-DBT-SEQ2-EUR", 2, "2026-09-25", "130.25",
                        "2026-09-26", "140.25", "2026-09-26,C,10.00,PSP-REM-20260926"));
        parse(file);
        accept(file);
        assertThat(fileColumn(file, "status")).isEqualTo("ACCEPTED");
        seq2Batch = batchOf(file).id();

        AcceptedBatchIntake.AcceptedBatch handed = handedFor(file);
        assertThat(handed.statement())
                .hasValueSatisfying(
                        continuity -> {
                            assertThat(continuity.sequence()).isEqualTo(2L);
                            assertThat(continuity.opening()).isEqualTo(eur(13_025));
                            assertThat(continuity.closing()).isEqualTo(eur(14_025));
                            assertThat(continuity.predecessor())
                                    .contains(
                                            new AcceptedBatchIntake.Neighbour(
                                                    seq1Batch, 1, eur(0), eur(13_025)));
                            assertThat(continuity.successor()).isEmpty();
                        });
        UUID entryId = UUID.fromString(columnOfBatch(seq2Batch, "journal_entry_id"));
        assertThat(entryLines(entryId))
                .containsExactlyInAnyOrder(
                        "CASH_AT_BANK:EUR:DEBIT:1000",
                        "SETTLEMENT_CLEARING:EUR:CREDIT:1000");
    }

    @Test
    @Order(6)
    @DisplayName("sequence 4 accepted before 3 has no predecessor; sequence 3 then sees 2 behind"
            + " it and 4 ahead of it - the successor whose gap it may fill; a net outflow"
            + " credits cash")
    void aLaterStatementFirstThenTheOneBetween() throws SQLException {
        UUID fourth =
                pulled(statement("SB-STMT-DBT-SEQ4-EUR", 4, "2026-09-27", "145.25",
                        "2026-09-28", "143.00", "2026-09-28,D,2.25,PAY-REM-20260928"));
        parse(fourth);
        accept(fourth);
        assertThat(fileColumn(fourth, "status")).isEqualTo("ACCEPTED");
        UUID seq4Batch = batchOf(fourth).id();
        assertThat(handedFor(fourth).statement())
                .hasValueSatisfying(
                        continuity -> {
                            assertThat(continuity.sequence()).isEqualTo(4L);
                            assertThat(continuity.predecessor())
                                    .as("sequence 3 is not accepted yet: the chain has a hole")
                                    .isEmpty();
                            assertThat(continuity.successor()).isEmpty();
                        });
        assertThat(entryLines(UUID.fromString(columnOfBatch(seq4Batch, "journal_entry_id"))))
                .containsExactlyInAnyOrder(
                        "CASH_AT_BANK:EUR:CREDIT:225",
                        "PAYOUT_CLEARING:EUR:DEBIT:225");

        UUID third =
                pulled(statement("SB-STMT-DBT-SEQ3-EUR", 3, "2026-09-26", "140.25",
                        "2026-09-27", "145.25", "2026-09-27,C,5.00,PSP-REM-20260927"));
        parse(third);
        accept(third);
        assertThat(fileColumn(third, "status")).isEqualTo("ACCEPTED");
        assertThat(handedFor(third).statement())
                .hasValueSatisfying(
                        continuity -> {
                            assertThat(continuity.sequence()).isEqualTo(3L);
                            assertThat(continuity.predecessor())
                                    .contains(
                                            new AcceptedBatchIntake.Neighbour(
                                                    seq2Batch, 2, eur(13_025), eur(14_025)));
                            assertThat(continuity.successor())
                                    .contains(
                                            new AcceptedBatchIntake.Neighbour(
                                                    seq4Batch, 4, eur(14_525), eur(14_300)));
                        });
    }

    // ----------------------------------------------------------------- fixtures

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static String golden() {
        try (var stream =
                Objects.requireNonNull(
                        BankStatementDatabaseTest.class.getResourceAsStream(
                                "/format/simstatement/golden-v1.txt"))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The golden statement under another statement reference - a distinct delivery. */
    private static String goldenWithRef(String statementRef) {
        String text = golden();
        assertThat(text).contains(GOLDEN_REF_LINE);
        return text.replace(GOLDEN_REF_LINE, ":20:" + statementRef);
    }

    /** One of our account's EUR statements, credit balances, in the frozen v1 shape. */
    private static String statement(
            String statementRef,
            long sequence,
            String openingDate,
            String opening,
            String closingDate,
            String closing,
            String... lines) {
        StringBuilder text =
                new StringBuilder()
                        .append(":20:").append(statementRef).append('\n')
                        .append(":25:SIMBANK-EUR-01\n")
                        .append(":28C:").append(sequence).append('\n')
                        .append(":60F:C,").append(openingDate).append(",EUR,").append(opening)
                        .append('\n');
        for (String line : lines) {
            text.append(":61:").append(line).append('\n');
        }
        return text.append(":62F:C,").append(closingDate).append(",EUR,").append(closing)
                .append('\n')
                .toString();
    }

    private static FileReception.Delivery pull(String content) {
        return new FileReception.Delivery(
                BANK,
                DeliveryChannel.PULL,
                content.getBytes(StandardCharsets.UTF_8),
                Optional.empty(),
                Actor.SYSTEM,
                TestAction.RECEIVED,
                FLOW);
    }

    /** A pulled delivery, committed: authenticated by its channel, eligible once parsed. */
    private static UUID pulled(String content) throws SQLException {
        FileReception.Result result = reception.receive(application, pull(content));
        application.commit();
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    /** A delivery left in the caller's open transaction - a raw row's file, rolled back. */
    private static UUID receivedUncommitted(String content) {
        FileReception.Result result = reception.receive(application, pull(content));
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    /** Sweeps until the file leaves RECEIVED - an earlier suite's leftovers may share a tick. */
    private static void parse(UUID fileId) throws SQLException {
        for (int sweep = 0; sweep < 5 && "RECEIVED".equals(fileColumn(fileId, "status"));
                sweep++) {
            parsing.sweep();
        }
    }

    /** Sweeps until the file leaves PARSED. */
    private static void accept(UUID fileId) throws SQLException {
        for (int sweep = 0; sweep < 5 && "PARSED".equals(fileColumn(fileId, "status"));
                sweep++) {
            acceptance.sweep();
        }
    }

    private static AcceptedBatchIntake.AcceptedBatch handedFor(UUID fileId) {
        List<AcceptedBatchIntake.AcceptedBatch> handed =
                intake.batches.stream().filter(batch -> batch.fileId().equals(fileId)).toList();
        assertThat(handed).as("the intake is handed the batch once").hasSize(1);
        return handed.get(0);
    }

    private static void assertAttributed(
            AcceptedBatchIntake.CanonicalLine line,
            SettlementLineType type,
            UUID source,
            AccountPurpose position) {
        assertThat(line.type()).isEqualTo(type);
        assertThat(line.attributedSourceId()).contains(source);
        assertThat(line.attributedPosition())
                .as("the register names the attributed source's position (INV-SET-05)")
                .contains(position);
    }

    private static void assertUnattributed(
            AcceptedBatchIntake.CanonicalLine line, SettlementLineType type) {
        assertThat(line.type()).isEqualTo(type);
        assertThat(line.attributedSourceId()).isEmpty();
        assertThat(line.attributedPosition()).isEmpty();
    }

    private static List<String> entryLines(UUID entryId) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT a.purpose, a.currency, l.direction, l.amount_minor"
                                + " FROM ledger.journal_line l"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE l.entry_id = ?")) {
            read.setObject(1, entryId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    lines.add(
                            rows.getString("purpose")
                                    + ":" + rows.getString("currency").trim()
                                    + ":" + rows.getString("direction")
                                    + ":" + rows.getLong("amount_minor"));
                }
            }
        }
        application.rollback();
        return lines;
    }

    private static final String RAW_BATCH =
            "INSERT INTO settlement.batch (id, file_id, source_id, external_batch_ref, currency,"
                    + " status, business_date, format_id, format_version, line_count,"
                    + " declared_line_count, net_minor, net_scale, remittance_reference,"
                    + " created_at, status_changed_at, correlation_id, statement_sequence,"
                    + " opening_minor, closing_minor)"
                    + " VALUES (?, ?, ?, ?, 'EUR', ?, DATE '2026-09-25', ?, 1, 0, 0, ?, 2, ?,"
                    + " now(), now(), 'p8-tsk-016-probe', ?, ?, ?)";

    /** A batch row past every domain guard, as the application role. */
    private static void rawBatch(
            UUID batchId,
            UUID fileId,
            String externalRef,
            String formatId,
            String status,
            long netMinor,
            String remittanceReference,
            Long sequence,
            Long openingMinor,
            Long closingMinor)
            throws SQLException {
        try (PreparedStatement insert = application.prepareStatement(RAW_BATCH)) {
            insert.setObject(1, batchId);
            insert.setObject(2, fileId);
            insert.setObject(3, BANK_SOURCE_ID);
            insert.setString(4, externalRef);
            insert.setString(5, status);
            insert.setString(6, formatId);
            insert.setLong(7, netMinor);
            insert.setString(8, remittanceReference);
            insert.setObject(9, sequence, Types.BIGINT);
            insert.setObject(10, openingMinor, Types.BIGINT);
            insert.setObject(11, closingMinor, Types.BIGINT);
            insert.executeUpdate();
        }
    }

    /** A canonical line past every domain guard, as the application role. */
    private static void rawLine(
            UUID batchId,
            UUID fileId,
            int lineNo,
            String lineType,
            String direction,
            UUID attributedSource)
            throws SQLException {
        try (PreparedStatement insert =
                application.prepareStatement(
                        "INSERT INTO settlement.line (id, batch_id, file_id, line_no, line_type,"
                                + " direction, amount_minor, amount_scale, currency,"
                                + " business_date, settlement_date, value_date,"
                                + " raw_record_sha256, canonical_fingerprint,"
                                + " attributed_source_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, 50, 2, 'EUR', DATE '2026-09-25',"
                                + " NULL, DATE '2026-09-25', ?, ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, batchId);
            insert.setObject(3, fileId);
            insert.setInt(4, lineNo);
            insert.setString(5, lineType);
            insert.setString(6, direction);
            insert.setBytes(7, new byte[32]);
            insert.setBytes(8, new byte[32]);
            insert.setObject(9, attributedSource, Types.OTHER);
            insert.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface Probe {
        void run() throws SQLException;
    }

    /** The refusal, by SQLSTATE and constraint name, rolled back to a clean savepoint. */
    private static void assertRefused(String constraint, String sqlState, Probe probe)
            throws SQLException {
        Savepoint clean = application.setSavepoint();
        try {
            assertThatThrownBy(probe::run)
                    .as("%s refuses the row for any writer", constraint)
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            refused -> {
                                assertThat(refused.getSQLState()).isEqualTo(sqlState);
                                assertThat(refused.getMessage()).contains(constraint);
                            });
        } finally {
            application.rollback(clean);
        }
    }

    private static SettlementBatchStore.BatchRow batchOf(UUID fileId) throws SQLException {
        SettlementBatchStore.BatchRow batch =
                batches.batchByFileId(application, fileId).orElseThrow();
        application.rollback();
        return batch;
    }

    private static String fileColumn(UUID fileId, String column) throws SQLException {
        return string("SELECT " + column + "::text FROM settlement.file WHERE id = ?", fileId);
    }

    private static String columnOfBatch(UUID batchId, String column) throws SQLException {
        return string("SELECT " + column + "::text FROM settlement.batch WHERE id = ?", batchId);
    }

    private static String string(String sql, Object... args) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                String value = row.getString(1);
                application.rollback();
                return value;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return Long.parseLong(string(sql, args));
    }
}
