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
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The door, against the real schema (`P8-TSK-002`, ADR-0066): bytes round-trip
 * checksum-verified, what must never enter leaves only metadata, duplicates converge on the
 * content address — ten ways at once — and a tampered or transplanted chunk serves nothing.
 *
 * <p>Everything here runs as {@code finapp_app}, so the narrowed grants are exercised, not
 * described; tampering for the read-verification tests disables the append-only triggers as
 * the bootstrap superuser, exactly the access an attacker with the database's credentials
 * would have — which is the threat encryption-at-rest exists for.
 */
@Tag("database")
@DisplayName("settlement file reception (P8-TSK-002)")
class FileReceptionDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor OPERATOR = new Actor("op-alice", ActorType.EMPLOYEE);

    private static final byte[] KEY = new byte[32];

    private static Connection application;
    private static SettlementSources sources;
    private static JdbcSettlementFileStore store;
    private static RecordingObserver observer;
    private static FileReception<Connection> reception;

    /** The door's audit word is the channel's (`P8-TSK-003`); tests pass a stand-in. */
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

    private static final class RecordingObserver implements ReceptionOutcomeObserver {
        final List<String> events = new ArrayList<>();

        @Override
        public synchronized void received(
                String sourceCode, SettlementFileStore.ReceiptOutcome outcome) {
            events.add(sourceCode + ":" + outcome);
        }

        @Override
        public synchronized void refused(String sourceCode, RefusalReason reason) {
            events.add(sourceCode + ":" + reason);
        }
    }

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        sources = register();
        store = new JdbcSettlementFileStore(new SettlementFileCipher(KEY, 1, new SecureRandom()));
        observer = new RecordingObserver();
        reception =
                new FileReception<>(
                        sources, store, Map.of(), observer, auditWriter(), IDS, CLOCK);
    }

    private static SettlementSources register() {
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
                                "simulated-bank.statement",
                                SourceKind.BANK_STATEMENT,
                                SettlementFormatId.SIM_STATEMENT_TAGGED,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.empty(),
                                Optional.empty())));
    }

    @SuppressWarnings("unchecked")
    private static AuditWriter<Connection> auditWriter() {
        return new JdbcAuditWriter();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // -----------------------------------------------------------------
    // Round trips (INV-HIST-02): stored bytes equal received bytes, verified on read.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("bytes round-trip at one byte, at the chunk boundary and at the 8 MiB bound,"
            + " checksum-verified")
    void bytesRoundTrip() throws SQLException {
        for (int size :
                new int[] {
                    1,
                    JdbcSettlementFileStore.CHUNK_BYTES,
                    JdbcSettlementFileStore.CHUNK_BYTES + 1,
                    SettlementFile.MAX_CONTENT_LENGTH
                }) {
            byte[] content = screenCleanContent(size);
            FileReception.Result result = reception.receive(application, upload(content));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            UUID fileId = ((FileReception.Result.New) result).fileId();
            assertThat(store.readContent(application, fileId))
                    .as("%d bytes round-trip", size)
                    .isEqualTo(content);
            assertThat(chunkCountOf(fileId))
                    .isEqualTo((size + JdbcSettlementFileStore.CHUNK_BYTES - 1)
                            / JdbcSettlementFileStore.CHUNK_BYTES);
            application.commit();
        }
    }

    // -----------------------------------------------------------------
    // The bounds: refused with nothing stored but the audit record.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a delivery over 8 MiB, and one over 50,000 records, stores nothing but its"
            + " audit record")
    void overBoundDeliveriesStoreNothing() throws SQLException {
        long filesBefore = count("SELECT count(*) FROM settlement.file");
        long refusalsBefore = count("SELECT count(*) FROM settlement.refused_delivery");
        long auditsBefore = refusalAudits();

        byte[] tooLarge = screenCleanContent(SettlementFile.MAX_CONTENT_LENGTH + 1);
        assertThat(reception.receive(application, upload(tooLarge)))
                .isEqualTo(new FileReception.Result.Refused(
                        RefusalReason.FILE_TOO_LARGE, Optional.empty(), Optional.empty()));

        byte[] tooManyLines = "a\n".repeat(SettlementFile.MAX_LINES + 1)
                .getBytes(StandardCharsets.UTF_8);
        assertThat(reception.receive(application, upload(tooManyLines)))
                .isEqualTo(new FileReception.Result.Refused(
                        RefusalReason.TOO_MANY_LINES, Optional.empty(), Optional.empty()));
        application.commit();

        assertThat(count("SELECT count(*) FROM settlement.file")).isEqualTo(filesBefore);
        assertThat(count("SELECT count(*) FROM settlement.refused_delivery"))
                .as("an over-bound delivery leaves no refusal row - the audit record alone")
                .isEqualTo(refusalsBefore);
        assertThat(refusalAudits()).isEqualTo(auditsBefore + 2);
    }

    // -----------------------------------------------------------------
    // The screen (INV-PAY-02, INV-RAIL-03): metadata only, the value nowhere.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a card number leaves exactly one refusal row naming its line - and the"
            + " digits rest in no settlement column")
    void aPanLeavesOnlyMetadata() throws SQLException {
        String pan = "4111111111111111";
        byte[] dirty =
                ("id,amount,reference\n1,100," + pan + "\n").getBytes(StandardCharsets.UTF_8);
        long filesBefore = count("SELECT count(*) FROM settlement.file");

        FileReception.Result result = reception.receive(application, upload(dirty));
        application.commit();

        assertThat(result)
                .isEqualTo(new FileReception.Result.Refused(
                        RefusalReason.PRIMARY_ACCOUNT_NUMBER,
                        Optional.of(2),
                        Optional.empty()));
        assertThat(count("SELECT count(*) FROM settlement.file")).isEqualTo(filesBefore);
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT reason, line_no FROM settlement.refused_delivery"
                                + " WHERE reason = 'PRIMARY_ACCOUNT_NUMBER'"
                                + " AND correlation_id = 'p8-tsk-002-reception-test'")) {
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt("line_no")).isEqualTo(2);
            }
        }
        assertThat(needleInSettlementColumns(pan))
                .as("the refused value rests in no settlement column (ADR-0066 §4)")
                .isEmpty();
        application.commit();
    }

    @Test
    @DisplayName("an account identifier is refused, its needle absent from every settlement"
            + " column and from captured logs")
    void anAccountIdentifierLeavesOnlyMetadata() throws SQLException {
        String needle = "DE44500105175407324931";
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try {
            FileReception.Result result =
                    reception.receive(
                            application,
                            uploadTo(
                                    "simulated-bank.statement",
                                    ("STMT\npaid by " + needle + " today\n")
                                            .getBytes(StandardCharsets.UTF_8)));
            application.commit();
            assertThat(result)
                    .isEqualTo(
                            new FileReception.Result.Refused(
                            RefusalReason.ACCOUNT_IDENTIFIER,
                            Optional.of(2),
                            Optional.empty()));
        } finally {
            root.detachAppender(captured);
        }
        assertThat(needleInSettlementColumns(needle)).isEmpty();
        assertThat(captured.list)
                .as("the identifier reaches no log line (INV-RAIL-03)")
                .noneMatch(event -> event.getFormattedMessage().contains(needle));
        application.commit();
    }

    // -----------------------------------------------------------------
    // Duplicates converge on the content address - sequentially and ten ways at once.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the same bytes twice: one file, a NEW and a DUPLICATE receipt, one audit")
    void aSequentialDuplicateConverges() throws SQLException {
        byte[] content = "duplicate-me,alpha\n".getBytes(StandardCharsets.UTF_8);
        long auditsBefore = receptionAudits();

        FileReception.Result first = reception.receive(application, upload(content));
        application.commit();
        FileReception.Result second = reception.receive(application, upload(content));
        application.commit();

        UUID fileId = ((FileReception.Result.New) first).fileId();
        assertThat(second).isEqualTo(new FileReception.Result.Duplicate(fileId));
        assertThat(receiptsOf(fileId)).containsExactlyInAnyOrder("NEW", "DUPLICATE");
        assertThat(receptionAudits())
                .as("a duplicate records its receipt and no audit")
                .isEqualTo(auditsBefore + 1);
    }

    @Test
    @DisplayName("ten instances delivering one file at once: one row, one NEW receipt, nine"
            + " DUPLICATE - the content unique is the arbiter")
    void tenRacingDeliveriesConverge() throws Exception {
        byte[] content = "race-me,beta\n".getBytes(StandardCharsets.UTF_8);
        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<FileReception.Result> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (SimulatedInstance instance =
                                                SimulatedInstance.inAgreementWithTheServer()) {
                                            start.await();
                                            results.add(
                                                    reception.receive(
                                                            instance.connection(),
                                                            upload(content)));
                                            instance.commit();
                                        } catch (Exception failure) {
                                            failures.add(failure);
                                        }
                                    }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(60_000);
        }
        assertThat(failures).isEmpty();
        assertThat(results).hasSize(racers);
        List<FileReception.Result.New> landed =
                results.stream()
                        .filter(FileReception.Result.New.class::isInstance)
                        .map(FileReception.Result.New.class::cast)
                        .toList();
        assertThat(landed).as("exactly one racer lands the file").hasSize(1);
        UUID fileId = landed.get(0).fileId();
        assertThat(results.stream().filter(FileReception.Result.Duplicate.class::isInstance))
                .hasSize(racers - 1)
                .allMatch(
                        duplicate ->
                                ((FileReception.Result.Duplicate) duplicate)
                                        .existingFileId()
                                        .equals(fileId));
        assertThat(receiptsOf(fileId))
                .hasSize(racers)
                .containsOnlyOnce("NEW");
        assertThat(count(
                        "SELECT count(*) FROM settlement.file f JOIN settlement.file_receipt r"
                                + " ON r.file_id = f.id WHERE r.outcome = 'NEW'"
                                + " AND f.id = '" + fileId + "'"))
                .isEqualTo(1);
        application.commit();
    }

    // -----------------------------------------------------------------
    // Read verification (INV-HIST-02): tampered, transplanted - nothing served.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("one flipped ciphertext byte, and two chunks swapped between files, each"
            + " serve nothing")
    void tamperedAndTransplantedChunksServeNothing() throws Exception {
        UUID fileA = landed("tamper-a,gamma\n");
        UUID fileB = landed("tamper-b,delta\n");
        application.commit();

        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            bootstrap.setAutoCommit(true);
            try (Statement ddl = bootstrap.createStatement()) {
                ddl.execute("ALTER TABLE settlement.file_chunk DISABLE TRIGGER"
                        + " file_chunk_is_append_only");
                // A flipped first byte on A; B's chunk replaced by A's original (a transplant).
                ddl.execute("UPDATE settlement.file_chunk SET ciphertext ="
                        + " overlay(ciphertext placing decode('00', 'hex') from 1)"
                        + " WHERE file_id = '" + fileA + "'");
                ddl.execute("UPDATE settlement.file_chunk c SET ciphertext = a.ciphertext,"
                        + " nonce = a.nonce FROM (SELECT ciphertext, nonce FROM"
                        + " settlement.file_chunk WHERE file_id = '" + fileB + "') a"
                        + " WHERE c.file_id = '" + fileA + "'");
                ddl.execute("ALTER TABLE settlement.file_chunk ENABLE TRIGGER"
                        + " file_chunk_is_append_only");
            }
        }

        assertThatThrownBy(() -> store.readContent(application, fileA))
                .as("a transplanted chunk fails its associated data, and serves nothing")
                .isInstanceOf(SettlementStorageException.class)
                .hasMessageContaining("INV-HIST-02")
                .hasMessageNotContaining("tamper-");
        application.rollback();

        // Truncation: the GCM tag cannot see a chunk that is GONE, which is exactly what the
        // whole-plaintext checksum is for (ADR-0066 §6's third question).
        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            bootstrap.setAutoCommit(true);
            try (Statement ddl = bootstrap.createStatement()) {
                ddl.execute("ALTER TABLE settlement.file_chunk DISABLE TRIGGER"
                        + " file_chunk_is_append_only");
                ddl.execute("DELETE FROM settlement.file_chunk WHERE file_id = '" + fileB + "'");
                ddl.execute("ALTER TABLE settlement.file_chunk ENABLE TRIGGER"
                        + " file_chunk_is_append_only");
            }
        }
        assertThatThrownBy(() -> store.readContent(application, fileB))
                .as("a truncated file does not hash to its stored address, and serves nothing")
                .isInstanceOf(SettlementStorageException.class)
                .hasMessageContaining("INV-HIST-02")
                .hasMessageNotContaining("tamper-");
        application.rollback();
    }

    // -----------------------------------------------------------------

    private static UUID landed(String content) throws SQLException {
        FileReception.Result result =
                reception.receive(
                        application, upload(content.getBytes(StandardCharsets.UTF_8)));
        return ((FileReception.Result.New) result).fileId();
    }

    /** Screen-clean content of exactly {@code size} bytes: letters, no digits, one record. */
    private static byte[] screenCleanContent(int size) {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) ('a' + (i % 26));
        }
        return content;
    }

    private static FileReception.Delivery upload(byte[] content) {
        return uploadTo("simulated-psp.settlement", content);
    }

    private static FileReception.Delivery uploadTo(String source, byte[] content) {
        return new FileReception.Delivery(
                source,
                DeliveryChannel.UPLOAD,
                content,
                Optional.empty(),
                OPERATOR,
                TestAction.RECEIVED,
                Correlation.startingWith(CorrelationId.of("p8-tsk-002-reception-test")));
    }

    private static long count(String sql) throws SQLException {
        try (Statement statement = application.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static long refusalAudits() throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record"
                + " WHERE operation = 'settlement.SettlementDeliveryRefused'");
    }

    private static long receptionAudits() throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record"
                + " WHERE operation = 'settlement.TestFileReceived'");
    }

    private static List<String> receiptsOf(UUID fileId) throws SQLException {
        List<String> outcomes = new ArrayList<>();
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT outcome FROM settlement.file_receipt WHERE file_id = ?")) {
            read.setObject(1, fileId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    outcomes.add(rows.getString(1));
                }
            }
        }
        return outcomes;
    }

    /**
     * Every text-representable column of every settlement table, swept for the needle — the
     * {@code INV-PAY-02} sweep's shape at this schema, ciphertext included (a hex cast).
     */
    private static List<String> needleInSettlementColumns(String needle) throws SQLException {
        List<String> hits = new ArrayList<>();
        List<String[]> columns = new ArrayList<>();
        try (Statement statement = application.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT table_name, column_name, data_type"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_schema = 'settlement'")) {
            while (rows.next()) {
                columns.add(
                        new String[] {
                            rows.getString(1), rows.getString(2), rows.getString(3)
                        });
            }
        }
        for (String[] column : columns) {
            if ("flyway_schema_history".equals(column[0])) {
                continue;
            }
            String rendered =
                    "bytea".equals(column[2])
                            ? "encode(\"" + column[1] + "\", 'escape')"
                            : "\"" + column[1] + "\"::text";
            String sql =
                    "SELECT count(*) FROM settlement.\"" + column[0] + "\" WHERE "
                            + rendered + " LIKE '%" + needle + "%'";
            try (Statement statement = application.createStatement();
                    ResultSet row = statement.executeQuery(sql)) {
                row.next();
                if (row.getLong(1) > 0) {
                    hits.add(column[0] + "." + column[1]);
                }
            }
        }
        return hits;
    }

    private static long chunkCountOf(UUID fileId) throws SQLException {
        return count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = '"
                + fileId + "'");
    }
}
