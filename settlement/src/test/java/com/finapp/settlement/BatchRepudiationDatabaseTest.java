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
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Settlement's half of a repudiation against the real schema (`P8-TSK-023`, ADR-0065 §10): a
 * batch accepted by the real accept leg is read with its facts, moved {@code ACCEPTED →
 * REPUDIATED} once however many approvals race, announced with an identifiers-only event and
 * the approver's audit record - and its FILE stays {@code ACCEPTED}, its bytes untouched
 * ({@code INV-HIST-02}). The reconciliation half and the reversal ride the app tier's
 * composition.
 */
@Tag("database")
@DisplayName("the batch repudiation's settlement half (P8-TSK-023)")
class BatchRepudiationDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private static final Instant APPROVED_AT = Instant.parse("2026-09-30T08:00:00Z");
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor APPROVER = new Actor("op-approver", ActorType.EMPLOYEE);
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of("p8-tsk-023-repudiation-test"));
    private static final byte[] KEY = new byte[32];
    private static final String SOURCE = "simulated-psp.settlement";
    private static final UUID SOURCE_ID =
            UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");

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

    /** The reconciliation half is the app tier's: this intake records nothing it needs. */
    private static final class InertIntake implements AcceptedBatchIntake {

        @Override
        public Intaken intake(Connection unitOfWork, AcceptedBatch batch) {
            return new Intaken(UUID.randomUUID(), batch.lines().size(), true);
        }

        @Override
        public void recognised(
                Connection unitOfWork,
                AcceptedBatch batch,
                Intaken intaken,
                java.util.Optional<UUID> entryId) {}
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
    private static JdbcSettlementBatchStore batches;
    private static FileReception<Connection> reception;
    private static FileParsing parsing;
    private static BatchAcceptance acceptance;
    private static BatchRepudiation repudiation;

    private static SettlementSources sources() {
        return SettlementSources.of(
                List.of(
                        new SettlementSourceDescriptor(
                                SOURCE,
                                SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                Optional.of("PSP-REM-[0-9]{4,12}"))));
    }

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        JdbcSettlementFileStore store =
                new JdbcSettlementFileStore(new SettlementFileCipher(KEY, 1, new SecureRandom()));
        batches = new JdbcSettlementBatchStore(IDS);
        AuditWriter<Connection> audit = new JdbcAuditWriter();
        DeliveryScreen pspScreen = SimPspCsvFormat.INSTANCE::screen;
        reception =
                new FileReception<>(
                        sources(),
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
        parsing =
                new FileParsing(
                        store,
                        batches,
                        Map.of(SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE),
                        new FileParsing.Config(20, Duration.ofMinutes(1), Duration.ofHours(1)),
                        IntakeOutcomeObserver.NONE,
                        new JdbcOutboxWriter(),
                        audit,
                        IDS,
                        CLOCK,
                        RUNNER,
                        sources());
        acceptance =
                new BatchAcceptance(
                        store,
                        batches,
                        sources(),
                        new InertIntake(),
                        new PostingService(
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
                                PostingObserver.NONE),
                        new JdbcLedgerAccountStore(),
                        new BatchAcceptance.Config(10),
                        IntakeOutcomeObserver.NONE,
                        new JdbcOutboxWriter(),
                        audit,
                        IDS,
                        CLOCK,
                        RUNNER);
        repudiation = new BatchRepudiation(batches, new JdbcOutboxWriter(), audit, IDS);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // -----------------------------------------------------------------

    private static String report(String batchRef, String marker) {
        return "H,SIM_PSP_CSV,1," + batchRef + ",EUR,2026-09-25\n"
                + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,,PSP-CAP-" + marker
                + ",,,ORD-" + marker + ",Desk sale\n"
                + "D,2,REFUND,-40.25,,EUR,2026-09-25,,,PSP-REF-" + marker + ",,,,Refund\n"
                + "T,2,58.00,PSP-REM-20260925\n";
    }

    /** A pulled report, received and parsed: its batch stands PARSED. */
    private static UUID parsedFile(String batchRef, String marker) throws SQLException {
        FileReception.Result result =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                SOURCE,
                                DeliveryChannel.PULL,
                                report(batchRef, marker).getBytes(StandardCharsets.UTF_8),
                                Optional.empty(),
                                Actor.SYSTEM,
                                TestAction.RECEIVED,
                                FLOW));
        application.commit();
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        UUID fileId = ((FileReception.Result.New) result).fileId();
        // Other suites' files may share the queue: sweep until this one leaves RECEIVED.
        for (int sweep = 0; sweep < 100 && "RECEIVED".equals(fileColumn(fileId, "status"));
                sweep++) {
            parsing.sweep();
        }
        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");
        return fileId;
    }

    /** A pulled report accepted by the real accept leg: its batch stands ACCEPTED. */
    private static SettlementBatchStore.BatchRow acceptedBatch(String batchRef, String marker)
            throws SQLException {
        UUID fileId = parsedFile(batchRef, marker);
        for (int sweep = 0; sweep < 100 && "PARSED".equals(fileColumn(fileId, "status"));
                sweep++) {
            acceptance.sweep();
        }
        assertThat(fileColumn(fileId, "status")).isEqualTo("ACCEPTED");
        SettlementBatchStore.BatchRow batch =
                batches.batchByFileId(application, fileId).orElseThrow();
        application.rollback();
        assertThat(batch.status()).isEqualTo(BatchStatus.ACCEPTED);
        return batch;
    }

    private static boolean repudiated(UUID batchId, UUID resolutionId) throws SQLException {
        boolean moved =
                repudiation.markRepudiated(
                        application, batchId, resolutionId, APPROVER, APPROVED_AT, FLOW);
        application.commit();
        return moved;
    }

    private static String fileColumn(UUID fileId, String column) throws SQLException {
        return scalar("SELECT " + column + "::text FROM settlement.file WHERE id = ?", fileId);
    }

    private static String batchColumn(UUID batchId, String column) throws SQLException {
        return scalar("SELECT " + column + "::text FROM settlement.batch WHERE id = ?", batchId);
    }

    private static String scalar(String sql, Object arg) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            read.setObject(1, arg);
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

    private static List<String> strings(String sql, Object arg) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            read.setObject(1, arg);
            try (ResultSet rows = read.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                application.rollback();
                return values;
            }
        }
    }

    private static long repudiationEvents(UUID batchId) throws SQLException {
        return count(
                "SELECT count(*) FROM settlement.batch_event WHERE batch_id = ?"
                        + " AND to_status = 'REPUDIATED'",
                batchId);
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("read reports the batch's identity and its recognition entry - before the"
            + " edge and after it - and an unknown batch is empty")
    void readReportsTheFacts() throws SQLException {
        SettlementBatchStore.BatchRow batch = acceptedBatch("PSPB-REPUD-01", "RPA");
        UUID entryId = UUID.fromString(batchColumn(batch.id(), "journal_entry_id"));

        BatchRepudiation.RepudiableBatch read =
                repudiation.read(application, batch.id()).orElseThrow();
        application.rollback();
        assertThat(read)
                .isEqualTo(
                        new BatchRepudiation.RepudiableBatch(
                                batch.id(),
                                batch.fileId(),
                                SOURCE_ID,
                                BatchStatus.ACCEPTED,
                                Optional.of(entryId),
                                "EUR",
                                LocalDate.parse("2026-09-25")));

        assertThat(repudiated(batch.id(), UUID.randomUUID())).isTrue();
        BatchRepudiation.RepudiableBatch after =
                repudiation.read(application, batch.id()).orElseThrow();
        application.rollback();
        assertThat(after.status()).isEqualTo(BatchStatus.REPUDIATED);
        assertThat(after.recognitionEntryId())
                .as("the entry stands, now to be reversed - read whatever the status")
                .contains(entryId);
        assertThat(batches.acceptedRecognitionEntries(application))
                .as("the completeness proof still knows the repudiated batch's recognition")
                .contains(entryId);
        application.rollback();

        assertThat(repudiation.read(application, UUID.randomUUID())).isEmpty();
        application.rollback();
    }

    @Test
    @DisplayName("ten approvals racing on separate connections move the batch once: one true,"
            + " one history row, the acceptance facts unmoved")
    void tenRacingApprovalsRepudiateOnce() throws Exception {
        SettlementBatchStore.BatchRow batch = acceptedBatch("PSPB-REPUD-02", "RPB");
        String factsBefore =
                batchColumn(batch.id(), "source_sequence") + "/"
                        + batchColumn(batch.id(), "accepted_on") + "/"
                        + batchColumn(batch.id(), "journal_entry_id") + "/"
                        + batchColumn(batch.id(), "posting_omitted");

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            UUID resolutionId = UUID.randomUUID();
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (Connection own = DatabaseRoles.application()) {
                                            own.setAutoCommit(false);
                                            start.await();
                                            if (repudiation.markRepudiated(
                                                    own,
                                                    batch.id(),
                                                    resolutionId,
                                                    APPROVER,
                                                    APPROVED_AT,
                                                    FLOW)) {
                                                winners.incrementAndGet();
                                            }
                                            own.commit();
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
        assertThat(winners.get()).as("the conditional edge is the arbiter").isEqualTo(1);
        assertThat(repudiationEvents(batch.id())).isEqualTo(1);
        assertThat(batchColumn(batch.id(), "status")).isEqualTo("REPUDIATED");
        assertThat(batchColumn(batch.id(), "source_sequence") + "/"
                        + batchColumn(batch.id(), "accepted_on") + "/"
                        + batchColumn(batch.id(), "journal_entry_id") + "/"
                        + batchColumn(batch.id(), "posting_omitted"))
                .isEqualTo(factsBefore);
        assertThat(strings(
                        "SELECT from_status || '>' || to_status || '|' || actor || '|' || reason"
                                + " FROM settlement.batch_event WHERE batch_id = ?"
                                + " AND to_status = 'REPUDIATED'",
                        batch.id()))
                .singleElement()
                .asString()
                .startsWith("ACCEPTED>REPUDIATED|op-approver|resolution=");
    }

    @Test
    @DisplayName("a batch that is not ACCEPTED is not repudiated: false, and nothing written -"
            + " a PARSED batch, and one already REPUDIATED")
    void aBatchNotAcceptedIsNotRepudiated() throws SQLException {
        UUID parsedFileId = parsedFile("PSPB-REPUD-03", "RPC");
        SettlementBatchStore.BatchRow parsed =
                batches.batchByFileId(application, parsedFileId).orElseThrow();
        application.rollback();
        long historyBefore =
                count("SELECT count(*) FROM settlement.batch_event WHERE batch_id = ?",
                        parsed.id());

        assertThat(repudiated(parsed.id(), UUID.randomUUID())).isFalse();
        assertThat(batchColumn(parsed.id(), "status")).isEqualTo("PARSED");
        assertThat(count("SELECT count(*) FROM settlement.batch_event WHERE batch_id = ?",
                        parsed.id()))
                .isEqualTo(historyBefore);

        SettlementBatchStore.BatchRow twice = acceptedBatch("PSPB-REPUD-04", "RPD");
        assertThat(repudiated(twice.id(), UUID.randomUUID())).isTrue();
        assertThat(repudiated(twice.id(), UUID.randomUUID()))
                .as("the edge is taken once; a repeat finds the machine already moved")
                .isFalse();
        assertThat(repudiationEvents(twice.id())).isEqualTo(1);

        // Tidy: the parsed fixture leaves through the accept leg like any pulled file.
        for (int sweep = 0; sweep < 100 && "PARSED".equals(fileColumn(parsedFileId, "status"));
                sweep++) {
            acceptance.sweep();
        }
    }

    @Test
    @DisplayName("announce publishes an identifiers-only event on the file's stream and the"
            + " approver's audit record; the file stays ACCEPTED, its bytes untouched")
    void announceWritesTheEventAndTheAudit() throws SQLException {
        SettlementBatchStore.BatchRow batch = acceptedBatch("PSPB-REPUD-05", "RPE");
        String shaBefore = fileColumn(batch.fileId(), "encode(content_sha256, 'hex')");
        long chunksBefore =
                count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?",
                        batch.fileId());
        UUID resolutionId = UUID.randomUUID();
        UUID reversalEntryId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                repudiation.announce(
                                        application,
                                        batch.id(),
                                        resolutionId,
                                        Optional.of(reversalEntryId),
                                        APPROVER,
                                        APPROVED_AT,
                                        FLOW))
                .as("an announcement never precedes its edge")
                .isInstanceOf(SettlementStorageException.class);
        application.rollback();

        assertThat(repudiated(batch.id(), resolutionId)).isTrue();
        repudiation.announce(
                application,
                batch.id(),
                resolutionId,
                Optional.of(reversalEntryId),
                APPROVER,
                APPROVED_AT,
                FLOW);
        application.commit();

        List<String> payloads =
                strings(
                        "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                                + " WHERE event_type = 'settlement.SettlementBatchRepudiated'"
                                + " AND aggregate_id = ?",
                        batch.fileId());
        assertThat(payloads).as("one event, on the file's stream").hasSize(1);
        String payload = payloads.get(0);
        assertThat(Pattern.compile("\"([A-Za-z]+)\"\\s*:")
                        .matcher(payload)
                        .results()
                        .map(found -> found.group(1))
                        .toList())
                .as("identifiers only, never an amount: %s", payload)
                .containsExactly(
                        "batchId", "fileId", "sourceId", "resolutionId", "reversalEntryId");
        assertThat(payload)
                .contains(batch.id().toString())
                .contains(SOURCE_ID.toString())
                .contains(resolutionId.toString())
                .contains(reversalEntryId.toString());

        assertThat(strings(
                        "SELECT actor_id || '|' || change_summary FROM platform.audit_record"
                                + " WHERE operation = 'settlement.SettlementBatchRepudiated'"
                                + " AND target_id = ?",
                        batch.id().toString()))
                .singleElement()
                .asString()
                .startsWith("op-approver|")
                .contains("resolution=" + resolutionId)
                .contains("reversalEntry=" + reversalEntryId)
                .as("never an amount (INV-AUD-02)")
                .doesNotContain("58.00");

        assertThat(fileColumn(batch.fileId(), "status"))
                .as("only the batch moves: the evidence stays ACCEPTED (INV-HIST-02)")
                .isEqualTo("ACCEPTED");
        assertThat(fileColumn(batch.fileId(), "encode(content_sha256, 'hex')"))
                .isEqualTo(shaBefore);
        assertThat(count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?",
                        batch.fileId()))
                .isEqualTo(chunksBefore);
    }

    @Test
    @DisplayName("a repudiation with no reversal - a recognition honestly omitted - announces"
            + " no reversal entry")
    void anAnnouncementWithoutAReversalOmitsTheField() throws SQLException {
        SettlementBatchStore.BatchRow batch = acceptedBatch("PSPB-REPUD-06", "RPF");
        UUID resolutionId = UUID.randomUUID();
        assertThat(repudiated(batch.id(), resolutionId)).isTrue();
        repudiation.announce(
                application,
                batch.id(),
                resolutionId,
                Optional.empty(),
                APPROVER,
                APPROVED_AT,
                FLOW);
        application.commit();

        assertThat(strings(
                        "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                                + " WHERE event_type = 'settlement.SettlementBatchRepudiated'"
                                + " AND aggregate_id = ?",
                        batch.fileId()))
                .singleElement()
                .asString()
                .contains(resolutionId.toString())
                .doesNotContain("reversalEntryId");
        assertThat(strings(
                        "SELECT change_summary FROM platform.audit_record"
                                + " WHERE operation = 'settlement.SettlementBatchRepudiated'"
                                + " AND target_id = ?",
                        batch.id().toString()))
                .singleElement()
                .asString()
                .contains("reversalEntry=none");
    }
}
