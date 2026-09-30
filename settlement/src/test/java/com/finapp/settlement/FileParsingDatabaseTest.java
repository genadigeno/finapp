package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The parse leg and the decline, against the real schema (`P8-TSK-008`, ADR-0066 §9,
 * `INV-SET-07`): a whole file becomes one batch with its lines, references, totals, edge,
 * history, audit and event in one transaction — or is rejected whole with its errors — while
 * our own failure leaves the evidence {@code RECEIVED} and loudly stuck; ten parsers produce
 * one batch; a conflicting re-issue is refused and retained; a decline frees the live key for
 * the genuine re-issue.
 *
 * <p>Everything runs as {@code finapp_app}; tampering uses the bootstrap superuser with the
 * append-only triggers disabled — the access encryption-at-rest exists for.
 */
@Tag("database")
@DisplayName("the parse leg and the decline (P8-TSK-008)")
class FileParsingDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor UPLOADER = new Actor("op-uploader", ActorType.EMPLOYEE);
    private static final Actor ATTESTER = new Actor("op-attester", ActorType.EMPLOYEE);
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of("p8-tsk-008-parse-test"));

    private static final byte[] KEY = new byte[32];
    private static final String SOURCE = "simulated-psp.settlement";

    /** Fresh application connections per transaction — each file's own, as in production. */
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

    private static Connection application;
    private static JdbcSettlementFileStore store;
    private static JdbcSettlementBatchStore batches;
    private static FileReception<Connection> reception;
    private static FileParsing parsing;
    private static FileDecline decline;
    private static FileAttestation<Connection> attestation;

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

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        SettlementSources sources =
                SettlementSources.of(
                        List.of(
                                new SettlementSourceDescriptor(
                                        SOURCE,
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}"))));
        store = new JdbcSettlementFileStore(new SettlementFileCipher(KEY, 1, new SecureRandom()));
        batches = new JdbcSettlementBatchStore(IDS);
        AuditWriter<Connection> audit = new JdbcAuditWriter();
        DeliveryScreen pspScreen = SimPspCsvFormat.INSTANCE::screen;
        reception =
                new FileReception<>(
                        sources,
                        store,
                        Map.of(SettlementFormatId.SIM_PSP_CSV, pspScreen),
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
        parsing = parsingWith(Map.of(SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE));
        decline =
                new FileDecline(
                        store,
                        batches,
                        IntakeOutcomeObserver.NONE,
                        new JdbcOutboxWriter(),
                        audit,
                        IDS,
                        CLOCK);
        attestation = new FileAttestation<>(store, audit, IDS, CLOCK);
    }

    private static FileParsing parsingWith(Map<SettlementFormatId, SettlementFormat> formats) {
        return new FileParsing(
                store,
                batches,
                formats,
                new FileParsing.Config(10, Duration.ofMinutes(1), Duration.ofHours(1)),
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

    // -----------------------------------------------------------------

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        FileParsingDatabaseTest.class.getResourceAsStream(
                                "/format/simpsp/golden-v1.csv"))) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The golden file re-keyed and re-worded so each test owns a distinct live identity. */
    private static byte[] variant(String batchRef, String marker) {
        String text = new String(golden(), StandardCharsets.UTF_8)
                .replace("PSPB-2026-09-25-01", batchRef)
                .replace("Desk sale", marker);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static UUID uploaded(byte[] content) throws SQLException {
        FileReception.Result result =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                SOURCE,
                                DeliveryChannel.UPLOAD,
                                content,
                                Optional.empty(),
                                UPLOADER,
                                TestAction.RECEIVED,
                                FLOW));
        application.commit();
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    private static String fileColumn(UUID fileId, String column) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT " + column + "::text FROM settlement.file WHERE id = ?")) {
            read.setObject(1, fileId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                String value = row.getString(1);
                application.rollback();
                return value;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                long value = row.getLong(1);
                application.rollback();
                return value;
            }
        }
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("a whole file becomes one batch - lines, typed references, folded totals,"
            + " the edge and its history in one transaction (INV-SET-07)")
    void aWholeFileBecomesOneBatch() throws SQLException {
        UUID fileId = uploaded(variant("PSPB-WHOLE-01", "Whole batch"));

        FileParsing.SweepResult swept = parsing.sweep();
        assertThat(swept.parsed()).isGreaterThanOrEqualTo(1);

        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", fileId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlement.line WHERE file_id = ?", fileId))
                .isEqualTo(10);
        assertThat(count(
                        "SELECT count(*) FROM settlement.line_reference r"
                                + " JOIN settlement.line l ON l.id = r.line_id"
                                + " WHERE l.file_id = ?",
                        fileId))
                .as("every typed reference landed with its line")
                .isEqualTo(13);
        SettlementBatchStore.BatchRow batch =
                batches.batchByFileId(application, fileId).orElseThrow();
        assertThat(batch.status()).isEqualTo(BatchStatus.PARSED);
        assertThat(batch.externalBatchRef()).isEqualTo("PSPB-WHOLE-01");
        assertThat(batch.netMinor()).isEqualTo(29_500L);
        assertThat(batch.lineCount()).isEqualTo(10);
        assertThat(batch.declaredLineCount()).isEqualTo(9);
        assertThat(batches.totalsOf(application, batch.id())).hasSize(9);
        application.rollback();
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND from_status = 'RECEIVED' AND to_status = 'PARSED'",
                        fileId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a content defect rejects the whole file: errors with line and field but"
            + " NO value, the verdict, the audit record and the published event - and no line"
            + " survives")
    void aContentDefectRejectsWhole() throws SQLException {
        String text = new String(variant("PSPB-REJ-01", "Rejected batch"),
                StandardCharsets.UTF_8);
        UUID fileId =
                uploaded(text.replace("100.00,1.75", "100.001,1.75")
                        .getBytes(StandardCharsets.UTF_8));

        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(captured);
        try {
            parsing.sweep();
        } finally {
            root.detachAppender(captured);
        }

        assertThat(fileColumn(fileId, "status")).isEqualTo("REJECTED");
        assertThat(fileColumn(fileId, "rejection_code")).isEqualTo("SCALE_MISMATCH");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", fileId))
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.line WHERE file_id = ?", fileId))
                .as("no line of a rejected file exists (INV-SET-07)")
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM settlement.ingestion_error WHERE file_id = ?"
                                + " AND error_code = 'SCALE_MISMATCH' AND line_no = 2"
                                + " AND field_name = 'amount'",
                        fileId))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementFileRejected' AND target_id = ?",
                        fileId.toString()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                + " 'settlement.SettlementFileRejected' AND aggregate_id = ?",
                        fileId))
                .as("the rejection is announced in the rejecting transaction (INV-EVT-01)")
                .isEqualTo(1);
        // The needle: the malformed VALUE reaches no error row, no verdict, no log line.
        assertThat(fileColumn(fileId, "rejection_detail")).doesNotContain("100.001");
        assertThat(captured.list)
                .allSatisfy(
                        event ->
                                assertThat(event.getFormattedMessage())
                                        .doesNotContain("100.001"));
    }

    @Test
    @DisplayName("our own failure never rejects evidence: the file stays RECEIVED with its"
            + " back-off and its history row, and the next sweep skips it until due")
    void ourFailureLeavesReceived() throws SQLException {
        UUID fileId = uploaded(variant("PSPB-OURS-01", "Our defect"));
        FileParsing failing =
                parsingWith(
                        Map.of(
                                SettlementFormatId.SIM_PSP_CSV,
                                new SettlementFormat() {
                                    @Override
                                    public SettlementFormatId id() {
                                        return SettlementFormatId.SIM_PSP_CSV;
                                    }

                                    @Override
                                    public int version() {
                                        return 1;
                                    }

                                    @Override
                                    public DeliveryScreen.Screening screen(byte[] content) {
                                        return SimPspCsvFormat.INSTANCE.screen(content);
                                    }

                                    @Override
                                    public Result parse(byte[] content) {
                                        throw new IllegalStateException("adapter defect");
                                    }
                                }));

        FileParsing.SweepResult swept = failing.sweep();
        assertThat(swept.failed()).isGreaterThanOrEqualTo(1);

        assertThat(fileColumn(fileId, "status")).isEqualTo("RECEIVED");
        assertThat(Integer.parseInt(fileColumn(fileId, "parse_failures")))
                .isGreaterThanOrEqualTo(1);
        assertThat(fileColumn(fileId, "next_parse_at")).isNotNull();
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND from_status = 'RECEIVED' AND to_status = 'RECEIVED'"
                                + " AND reason LIKE 'parse failed:%'",
                        fileId))
                .as("the platform's processing is recorded by the file's history")
                .isGreaterThanOrEqualTo(1);
        // Backed off: the same sweep finds nothing due for THIS file any more.
        FileParsing.SweepResult again = failing.sweep();
        assertThat(again.failed()).isZero();
        assertThat(fileColumn(fileId, "status")).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("a stored file failing its authenticated decryption is served to no parser:"
            + " RECEIVED, backed off, nothing built")
    void aTamperedFileIsServedToNoParser() throws Exception {
        UUID fileId = uploaded(variant("PSPB-TAMPER-01", "Tampered later"));
        try (Connection root = DatabaseRoles.bootstrap();
                Statement raw = root.createStatement()) {
            root.setAutoCommit(false);
            raw.execute("ALTER TABLE settlement.file_chunk DISABLE TRIGGER"
                    + " file_chunk_is_append_only");
            try {
                raw.execute("UPDATE settlement.file_chunk SET ciphertext = overlay(ciphertext"
                        + " PLACING '\\x00'::bytea FROM 1 FOR 1)"
                        + " WHERE file_id = '" + fileId + "'");
            } finally {
                raw.execute("ALTER TABLE settlement.file_chunk ENABLE TRIGGER"
                        + " file_chunk_is_append_only");
            }
            root.commit();
        }

        FileParsing.SweepResult swept = parsing.sweep();
        assertThat(swept.failed()).isGreaterThanOrEqualTo(1);
        assertThat(fileColumn(fileId, "status")).isEqualTo("RECEIVED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", fileId))
                .isZero();
    }

    @Test
    @DisplayName("two files declaring one batch: the second is rejected CONFLICTING_BATCH and"
            + " RETAINED; a decline of the first frees the live key and the genuine re-issue"
            + " is admitted")
    void conflictDeclineAndTheFreedKey() throws SQLException {
        UUID first = uploaded(variant("PSPB-LIVE-01", "First declaration"));
        parsing.sweep();
        assertThat(fileColumn(first, "status")).isEqualTo("PARSED");

        UUID second = uploaded(variant("PSPB-LIVE-01", "Second declaration"));
        parsing.sweep();
        assertThat(fileColumn(second, "status")).isEqualTo("REJECTED");
        assertThat(fileColumn(second, "rejection_code")).isEqualTo("CONFLICTING_BATCH");
        assertThat(count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?",
                        second))
                .as("the conflicting file is retained - readmissible after a repudiation")
                .isPositive();
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", second))
                .isZero();

        // The decline: file and batch move together, the live key frees at commit.
        FileDecline.Declined declined =
                decline.decline(application, first, "wrong day's report", UPLOADER, FLOW);
        application.commit();
        assertThat(declined.file().status()).isEqualTo(FileStatus.REJECTED);
        assertThat(fileColumn(first, "rejection_code")).isEqualTo("DECLINED");
        SettlementBatchStore.BatchRow firstBatch =
                batches.batchByFileId(application, first).orElseThrow();
        assertThat(firstBatch.status()).isEqualTo(BatchStatus.REJECTED);
        application.rollback();
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementFileDeclined' AND target_id = ?"
                                + " AND reason = 'wrong day''s report'",
                        first.toString()))
                .isEqualTo(1);

        UUID genuine = uploaded(variant("PSPB-LIVE-01", "Genuine re-issue"));
        parsing.sweep();
        assertThat(fileColumn(genuine, "status"))
                .as("the freed key takes the genuine re-issue")
                .isEqualTo("PARSED");
    }

    @Test
    @DisplayName("a RECEIVED file declines too; a terminal file refuses a second decline AND"
            + " attestation; a PARSED file is now attestable - the explicit relaxation")
    void declinesAndTheAttestationRelaxation() throws SQLException {
        UUID received = uploaded(variant("PSPB-DECL-01", "Declined before parse"));
        decline.decline(application, received, "not ours", UPLOADER, FLOW);
        application.commit();
        assertThat(fileColumn(received, "status")).isEqualTo("REJECTED");
        assertThatThrownBy(
                        () -> decline.decline(application, received, "again", UPLOADER, FLOW))
                .isInstanceOf(FileAttestation.SettlementFileNotAttestable.class);
        application.rollback();
        assertThatThrownBy(
                        () -> attestation.attest(application, received, ATTESTER, FLOW))
                .isInstanceOf(FileAttestation.SettlementFileNotAttestable.class);
        application.rollback();

        UUID parsed = uploaded(variant("PSPB-ATT-01", "Attested after parse"));
        parsing.sweep();
        assertThat(fileColumn(parsed, "status")).isEqualTo("PARSED");
        FileAttestation.Result attested =
                attestation.attest(application, parsed, ATTESTER, FLOW);
        application.commit();
        assertThat(attested).isInstanceOf(FileAttestation.Result.Attested.class);
    }

    @Test
    @DisplayName("the live unique is the ARBITER, not the pre-check: a second live batch"
            + " with one identity is refused by the database for any writer")
    void theLiveUniqueIsTheArbiter() throws SQLException {
        // The sequential conflict above is answered by the pre-check; a RACE the pre-check
        // cannot see is arbitrated by the partial unique alone, so it is probed directly -
        // two raw inserts past every domain guard, as the application role.
        UUID first = uploaded(variant("PSPB-UNIQ-01", "Unique probe A"));
        UUID second = uploaded(variant("PSPB-UNIQ-02", "Unique probe B"));
        String insert =
                "INSERT INTO settlement.batch (id, file_id, source_id, external_batch_ref,"
                        + " currency, status, business_date, format_id, format_version,"
                        + " line_count, declared_line_count, net_minor, net_scale,"
                        + " remittance_reference, created_at, status_changed_at,"
                        + " correlation_id)"
                        + " SELECT ?, ?, source_id, 'PSPB-UNIQ-RACE', 'EUR', 'PARSED',"
                        + " '2026-09-29', 'SIM_PSP_CSV', 1, 0, 0, 0, 2, 'PSP-REM-0001',"
                        + " now(), now(), 'uniq-probe' FROM settlement.file WHERE id = ?";
        try (PreparedStatement raw = application.prepareStatement(insert)) {
            raw.setObject(1, UUID.randomUUID());
            raw.setObject(2, first);
            raw.setObject(3, first);
            raw.executeUpdate();
        }
        application.commit();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement raw = application.prepareStatement(insert)) {
                                raw.setObject(1, UUID.randomUUID());
                                raw.setObject(2, second);
                                raw.setObject(3, second);
                                raw.executeUpdate();
                            }
                        })
                .as("one live batch per (source, reference, currency), for ANY writer")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("batch_live_identity");
        application.rollback();
    }

    @Test
    @DisplayName("ten parsers on one file produce one batch, ten lines, one edge - the claim"
            + " skips, the conditional and the uniques arbitrate")
    void tenParsersProduceOneBatch() throws Exception {
        UUID fileId = uploaded(variant("PSPB-RACE-01", "Raced batch"));

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            start.await();
                                            parsing.sweep();
                                        } catch (Throwable failure) {
                                            unexpected.add(failure);
                                        }
                                    }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(unexpected).isEmpty();
        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", fileId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlement.line WHERE file_id = ?", fileId))
                .isEqualTo(10);
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND to_status = 'PARSED'",
                        fileId))
                .as("one edge, however many instances swept")
                .isEqualTo(1);
    }
}
