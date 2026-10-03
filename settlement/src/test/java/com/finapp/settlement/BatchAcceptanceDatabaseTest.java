package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingResult;
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
import java.sql.Statement;
import java.sql.Timestamp;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The accept leg against the real schema and the real ledger (`P8-TSK-009`, ADR-0065 §2),
 * with a RECORDING intake — the reconciliation half rides the app tier's composition: the
 * entry equals the fee fold and nothing else posts; eligibility is authentication at three
 * ranks; the sequence is gapless because acceptance is one transaction; a retired source
 * rejects RETAINED; a failure after the posting rolls back WHOLE and the re-sweep converges
 * on the same sequence; a later-day replay converges; ten acceptors produce one acceptance.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the accept leg (P8-TSK-009)")
class BatchAcceptanceDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor UPLOADER = new Actor("op-uploader", ActorType.EMPLOYEE);
    private static final Actor ATTESTER = new Actor("op-attester", ActorType.EMPLOYEE);
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of("p8-tsk-009-accept-test"));
    private static final byte[] KEY = new byte[32];
    private static final String SOURCE = "simulated-psp.settlement";
    /** A throwaway source THIS suite seeds and retires, so the shared one stays open. */
    private static final String RETIRING_SOURCE = "retiring-psp.settlement";
    private static final UUID RETIRING_SOURCE_ID =
            UUID.fromString("01a0e2bc-8200-7009-8000-000000000009");
    /** The accept leg's first back-off step (MI-7), doubling per failure. */
    private static final Duration BACKOFF_BASE = Duration.ofMinutes(1);

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

    /** Records every handover; optionally throws once — the failure-injection seam. */
    private static final class RecordingIntake implements AcceptedBatchIntake {
        final ConcurrentLinkedQueue<AcceptedBatch> batches = new ConcurrentLinkedQueue<>();

        @Override
        public Intaken intake(Connection unitOfWork, AcceptedBatch batch) {
            batches.add(batch);
            return new Intaken(UUID.randomUUID(), batch.lines().size(), true);
        }

        @Override
        public void recognised(
                Connection unitOfWork,
                AcceptedBatch batch,
                Intaken intaken,
                java.util.Optional<UUID> entryId) {}
    }

    private static Connection application;
    private static JdbcSettlementFileStore store;
    private static JdbcSettlementBatchStore batches;
    private static FileReception<Connection> reception;
    private static FileParsing parsing;
    private static FileAttestation<Connection> attestation;
    private static RecordingIntake intake;
    private static BatchAcceptance acceptance;

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
                                Optional.of("PSP-REM-[0-9]{4,12}")),
                        new SettlementSourceDescriptor(
                                RETIRING_SOURCE,
                                SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                // A position of its own: one declared source per position
                                // (INV-SET-05), and this register composes only these two.
                                Optional.of(AccountPurpose.INSTANT_CLEARING),
                                Optional.of("PSP-REM-[0-9]{4,12}"))));
    }

    private static PostingService postingService(Clock clock) {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        clock,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                clock,
                PostingObserver.NONE);
    }

    private static BatchAcceptance acceptanceWith(
            AcceptedBatchIntake intakePort,
            SettlementBatchStore<Connection> batchStore,
            Clock clock) {
        return acceptanceWith(intakePort, batchStore, clock, RUNNER);
    }

    private static BatchAcceptance acceptanceWith(
            AcceptedBatchIntake intakePort,
            SettlementBatchStore<Connection> batchStore,
            Clock clock,
            TransactionRunner runner) {
        return new BatchAcceptance(
                store,
                batchStore,
                sources(),
                intakePort,
                postingService(clock),
                new JdbcLedgerAccountStore(),
                new BatchAcceptance.Config(10, BACKOFF_BASE, Duration.ofHours(1)),
                IntakeOutcomeObserver.NONE,
                new JdbcOutboxWriter(),
                new JdbcAuditWriter(),
                IDS,
                clock,
                runner);
    }

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        store = new JdbcSettlementFileStore(new SettlementFileCipher(KEY, 1, new SecureRandom()));
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
        attestation = new FileAttestation<>(store, audit, IDS, CLOCK);
        intake = new RecordingIntake();
        acceptance = acceptanceWith(intake, batches, CLOCK);
        seedRetiringSource();
    }

    /** The throwaway source, seeded as the migrator: retirement stays in this suite. */
    private static void seedRetiringSource() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                Statement seed = migrator.createStatement()) {
            migrator.setAutoCommit(false);
            seed.execute(
                    "INSERT INTO settlement.source (id, code, kind, status, next_sequence)"
                            + " VALUES ('" + RETIRING_SOURCE_ID + "', '" + RETIRING_SOURCE
                            + "', 'PSP_SETTLEMENT_REPORT', 'ACTIVE', 1)"
                            + " ON CONFLICT (code) DO NOTHING");
            migrator.commit();
        }
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // -----------------------------------------------------------------

    private static String report(String source, String batchRef, String marker) {
        return "H,SIM_PSP_CSV,1," + batchRef + ",EUR,2026-09-25\n"
                + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,,PSP-CAP-" + marker
                + ",,,ORD-" + marker + ",Desk sale\n"
                + "D,2,REFUND,-40.25,,EUR,2026-09-25,,,PSP-REF-" + marker + ",,,,Refund\n"
                + "D,3,PROMO_BONUS,5.00,,EUR,2026-09-25,,,MISC-" + marker + ",,,,Promo\n"
                + "T,3,63.00,PSP-REM-20260925\n";
    }

    private static UUID pulled(String source, String content) throws SQLException {
        FileReception.Result result =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                source,
                                DeliveryChannel.PULL,
                                content.getBytes(StandardCharsets.UTF_8),
                                Optional.empty(),
                                Actor.SYSTEM,
                                TestAction.RECEIVED,
                                FLOW));
        application.commit();
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    private static UUID uploaded(String content) throws SQLException {
        FileReception.Result result =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                SOURCE,
                                DeliveryChannel.UPLOAD,
                                content.getBytes(StandardCharsets.UTF_8),
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

    private static SettlementBatchStore.BatchRow batchOf(UUID fileId) throws SQLException {
        SettlementBatchStore.BatchRow batch =
                batches.batchByFileId(application, fileId).orElseThrow();
        application.rollback();
        return batch;
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("a pulled report is accepted whole: the entry equals the fee fold and"
            + " nothing else posts, the facts land once, the intake got the batch, the"
            + " event and audit are on the record")
    void aPulledReportIsAcceptedWhole() throws SQLException {
        UUID fileId = pulled(SOURCE, report(SOURCE, "PSPB-ACC-01", "A01"));
        parsing.sweep();
        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");

        BatchAcceptance.SweepResult swept = acceptance.sweep();
        assertThat(swept.accepted()).isGreaterThanOrEqualTo(1);

        assertThat(fileColumn(fileId, "status")).isEqualTo("ACCEPTED");
        SettlementBatchStore.BatchRow batch = batchOf(fileId);
        assertThat(batch.status()).isEqualTo(BatchStatus.ACCEPTED);
        UUID entryId =
                UUID.fromString(
                        columnOfBatch(batch.id(), "journal_entry_id"));
        assertThat(columnOfBatch(batch.id(), "posting_omitted")).isEqualTo("false");
        assertThat(columnOfBatch(batch.id(), "accepted_on")).isEqualTo("2026-09-29");

        // The entry: DR PROCESSING_COSTS 1.75 / CR SETTLEMENT_CLEARING 1.75 - and NOTHING
        // else. The transaction lines' value is already in the position (ADR-0065 §2).
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_line WHERE entry_id = ?",
                        entryId))
                .isEqualTo(2);
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_line l"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE l.entry_id = ? AND l.amount_minor = 175"
                                + " AND l.direction = 'DEBIT'"
                                + " AND a.purpose = 'PROCESSING_COSTS'",
                        entryId))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_line l"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE l.entry_id = ? AND l.amount_minor = 175"
                                + " AND l.direction = 'CREDIT'"
                                + " AND a.purpose = 'SETTLEMENT_CLEARING'",
                        entryId))
                .isEqualTo(1);

        // The intake got the whole batch, net and position included.
        AcceptedBatchIntake.AcceptedBatch handed =
                intake.batches.stream()
                        .filter(b -> b.fileId().equals(fileId))
                        .findFirst()
                        .orElseThrow();
        // Four CANONICAL lines from three records: the SALE's fee split into its own
        // PROCESSING_FEE line at parse (P8-TSK-008), and the intake copies canonical lines.
        assertThat(handed.lines()).hasSize(4);
        assertThat(handed.net().minorUnits()).isEqualTo(6_300L);
        assertThat(handed.positionPurpose()).contains(AccountPurpose.SETTLEMENT_CLEARING);
        assertThat(handed.remittanceReference()).contains("PSP-REM-20260925");
        assertThat(handed.statement()).as("a report carries no statement facts").isEmpty();
        assertThat(handed.acceptedOn()).isEqualTo(LocalDate.parse("2026-09-29"));

        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementBatchAccepted' AND target_id = ?",
                        batch.id().toString()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                + " 'settlement.SettlementBatchAccepted' AND aggregate_id = ?",
                        fileId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unattested upload is never accepted - the leg skips it, a raw UPDATE to"
            + " ACCEPTED is refused by the CHECK - and the second person's attestation"
            + " makes it eligible")
    void anUnattestedUploadIsNeverAccepted() throws SQLException {
        UUID fileId = uploaded(report(SOURCE, "PSPB-ACC-02", "A02"));
        parsing.sweep();
        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");

        acceptance.sweep();
        assertThat(fileColumn(fileId, "status"))
                .as("inert until its second person (INV-SET-07)")
                .isEqualTo("PARSED");

        // The database rank, past every domain guard, as the MIGRATOR.
        try (Connection migrator = DatabaseRoles.migrator();
                Statement raw = migrator.createStatement()) {
            migrator.setAutoCommit(false);
            assertThatThrownBy(
                            () ->
                                    raw.execute(
                                            "UPDATE settlement.file SET status = 'ACCEPTED',"
                                                    + " status_changed_at = now()"
                                                    + " WHERE id = '" + fileId + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("file_accepted_upload_is_attested");
            migrator.rollback();
        }

        attestation.attest(application, fileId, ATTESTER, FLOW);
        application.commit();
        acceptance.sweep();
        assertThat(fileColumn(fileId, "status")).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("a retired source's parsed file is rejected SOURCE_RETIRED and RETAINED,"
            + " its batch with it, the event published")
    void aRetiredSourceRejectsRetained() throws SQLException {
        UUID fileId =
                pulled(RETIRING_SOURCE, report(RETIRING_SOURCE, "PSPB-RET-01", "R01"));
        parsing.sweep();
        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");

        try (PreparedStatement retire =
                application.prepareStatement(
                        "UPDATE settlement.source SET status = 'RETIRED' WHERE id = ?")) {
            retire.setObject(1, RETIRING_SOURCE_ID);
            retire.executeUpdate();
        }
        application.commit();

        BatchAcceptance.SweepResult swept = acceptance.sweep();
        assertThat(swept.rejected()).isGreaterThanOrEqualTo(1);
        assertThat(fileColumn(fileId, "status")).isEqualTo("REJECTED");
        assertThat(fileColumn(fileId, "rejection_code")).isEqualTo("SOURCE_RETIRED");
        assertThat(batchOf(fileId).status()).isEqualTo(BatchStatus.REJECTED);
        assertThat(count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?",
                        fileId))
                .as("retained (§5.1): the evidence outlives its door")
                .isPositive();
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                + " 'settlement.SettlementFileRejected' AND aggregate_id = ?",
                        fileId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failure after the posting rolls the WHOLE acceptance back - file PARSED,"
            + " no entry, the sequence released - and the re-sweep converges on the same"
            + " sequence")
    void aFailureAfterThePostingRollsBackWhole() throws SQLException {
        UUID fileId = pulled(SOURCE, report(SOURCE, "PSPB-ACC-03", "A03"));
        parsing.sweep();

        long sequenceBefore =
                count("SELECT next_sequence FROM settlement.source WHERE code = ?", SOURCE);

        AtomicBoolean fail = new AtomicBoolean(true);
        @SuppressWarnings("unchecked")
        SettlementBatchStore<Connection> failing =
                (SettlementBatchStore<Connection>)
                        java.lang.reflect.Proxy.newProxyInstance(
                                SettlementBatchStore.class.getClassLoader(),
                                new Class<?>[] {SettlementBatchStore.class},
                                (proxy, method, args) -> {
                                    if ("markAccepted".equals(method.getName())
                                            && fail.getAndSet(false)) {
                                        // AFTER the posting call (the design's order): the
                                        // injection the backlog names.
                                        throw new IllegalStateException("injected failure");
                                    }
                                    try {
                                        return method.invoke(batches, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
        BatchAcceptance broken = acceptanceWith(intake, failing, CLOCK);
        BatchAcceptance.SweepResult swept = broken.sweep();
        assertThat(swept.failed()).isGreaterThanOrEqualTo(1);

        assertThat(fileColumn(fileId, "status"))
                .as("nothing partial (INV-SET-07): the file is re-claimable")
                .isEqualTo("PARSED");
        assertThat(batchOf(fileId).status()).isEqualTo(BatchStatus.PARSED);
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope ="
                                + " ?",
                        "ledger.post:" + BatchAcceptance.POSTING_KEY_PREFIX
                                + batchOf(fileId).id()))
                .as("the posting rolled back with the acceptance")
                .isZero();
        assertThat(count("SELECT next_sequence FROM settlement.source WHERE code = ?", SOURCE))
                .as("a rolled-back acceptance releases its number - the gapless guarantee")
                .isEqualTo(sequenceBefore);
        // Our failure, recorded beside the rollback and backed off (MI-7) - never a verdict.
        assertThat(fileColumn(fileId, "accept_failures")).isEqualTo("1");
        assertThat(count(
                        "SELECT count(*) FROM settlement.file WHERE id = ? AND next_accept_at = ?",
                        fileId,
                        Timestamp.from(CLOCK.instant().plus(BACKOFF_BASE))))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND from_status = 'PARSED' AND to_status = 'PARSED'"
                                + " AND reason = 'accept failed: IllegalStateException'",
                        fileId))
                .isEqualTo(1);

        // The retry waits out the first back-off step, then converges.
        BatchAcceptance.SweepResult retried =
                acceptanceWith(intake, batches, Clock.offset(CLOCK, BACKOFF_BASE)).sweep();
        assertThat(retried.accepted()).isGreaterThanOrEqualTo(1);
        assertThat(fileColumn(fileId, "status")).isEqualTo("ACCEPTED");
        assertThat(Long.parseLong(columnOfBatch(batchOf(fileId).id(), "source_sequence")))
                .isEqualTo(sequenceBefore);
    }

    @Test
    @DisplayName("the leg driven again on a later clock day converges, and the posting"
            + " command replayed with the STORED dates converges - no second entry, no"
            + " conflict")
    void aLaterDayReplayConverges() throws SQLException {
        UUID fileId = pulled(SOURCE, report(SOURCE, "PSPB-ACC-04", "A04"));
        parsing.sweep();
        acceptance.sweep();
        assertThat(fileColumn(fileId, "status")).isEqualTo("ACCEPTED");
        SettlementBatchStore.BatchRow batch = batchOf(fileId);
        UUID entryId = UUID.fromString(columnOfBatch(batch.id(), "journal_entry_id"));
        LocalDate acceptedOn = LocalDate.parse(columnOfBatch(batch.id(), "accepted_on"));

        Clock laterDay =
                Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
        BatchAcceptance later = acceptanceWith(intake, batches, laterDay);
        later.sweep();
        // THIS file is no candidate on any later day: the conditional already decided.
        // (The sweep may legitimately accept OTHER suites' leftover eligible files.)
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND to_status = 'ACCEPTED'",
                        fileId))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                                + " = ?",
                        "ledger.post:" + BatchAcceptance.POSTING_KEY_PREFIX + batch.id()))
                .isEqualTo(1);

        // The command itself, replayed on the later clock with the STORED dates: the
        // fingerprint binds only what the row holds, so it converges (INV-SET-04; the
        // MerchantPayoutOutcomes warning made a test).
        PostingResult replayed;
        try (com.finapp.platform.security.SecurityContext.Scope platform =
                        com.finapp.platform.security.SecurityContext.enterSystem();
                com.finapp.platform.correlation.CorrelationContext.Scope scope =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            replayed =
                    RUNNER.inTransaction(
                            uow ->
                                    postingService(laterDay)
                                            .post(
                                                    uow,
                                                    new PostingCommand(
                                                            BatchAcceptance
                                                                            .POSTING_KEY_PREFIX
                                                                    + batch.id(),
                                                            acceptedOn,
                                                            batch.businessDate(),
                                                            batch.id().toString(),
                                                            recognitionLines(batch))));
        }
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.entryId().value()).isEqualTo(entryId);
    }

    @Test
    @DisplayName("ten acceptors on one file produce one acceptance; on ten files of one"
            + " source, ten gapless sequences")
    void tenAcceptorsProduceOneAcceptanceAndGaplessSequences() throws Exception {
        // Drain any earlier suite's eligible leftovers first, so the raced window holds
        // exactly these ten files and their sequences read consecutively.
        parsing.sweep();
        acceptance.sweep();
        List<UUID> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            files.add(pulled(SOURCE, report(SOURCE, "PSPB-RACE-" + i, "RC" + i)));
        }
        parsing.sweep();

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
                                            acceptance.sweep();
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

        List<Long> sequences = new ArrayList<>();
        for (UUID fileId : files) {
            assertThat(fileColumn(fileId, "status")).isEqualTo("ACCEPTED");
            SettlementBatchStore.BatchRow batch = batchOf(fileId);
            sequences.add(Long.parseLong(columnOfBatch(batch.id(), "source_sequence")));
            assertThat(count(
                            "SELECT count(*) FROM ledger.journal_entry WHERE"
                                    + " idempotency_scope = ?",
                            "ledger.post:" + BatchAcceptance.POSTING_KEY_PREFIX + batch.id()))
                    .as("one entry per batch, however many acceptors")
                    .isEqualTo(1);
            assertThat(count(
                            "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                    + " AND to_status = 'ACCEPTED'",
                            fileId))
                    .isEqualTo(1);
        }
        assertThat(sequences).doesNotHaveDuplicates();
        long min = sequences.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = sequences.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(max - min)
                .as("gapless: ten acceptances, ten consecutive numbers")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("ten files whose acceptance always fails no longer hold the window: each is"
            + " counted, backed off and noted on its history while it stays PARSED, the next"
            + " sweep accepts the good file behind them, and they return only once due, the"
            + " step doubled (the Phase 8 -> 9 transition, MI-7)")
    void filesThatAlwaysFailBackOffAndLeaveTheWindow() throws SQLException {
        drainDueLeftovers();
        List<UUID> poisoned = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            poisoned.add(pulled(SOURCE, report(SOURCE, "PSPB-POISON-" + i, "PZ" + i)));
        }
        UUID good = pulled(SOURCE, report(SOURCE, "PSPB-BEHIND-01", "BH1"));
        List<UUID> mine = new ArrayList<>(poisoned);
        mine.add(good);
        for (int sweep = 0; sweep < 5 && !allIn(mine, "PARSED"); sweep++) {
            parsing.sweep();
        }
        assertThat(allIn(mine, "PARSED")).isTrue();

        // Our defect on every attempt at the ten - the shape of a currency whose position the
        // chart never seeded - and the recording intake for anything else.
        Set<UUID> failing = Set.copyOf(poisoned);
        AcceptedBatchIntake poisonedIntake =
                new AcceptedBatchIntake() {
                    @Override
                    public Intaken intake(Connection unitOfWork, AcceptedBatch batch) {
                        if (failing.contains(batch.fileId())) {
                            throw new SettlementStorageException(
                                    "injected: no position for this currency");
                        }
                        return intake.intake(unitOfWork, batch);
                    }

                    @Override
                    public void recognised(
                            Connection unitOfWork,
                            AcceptedBatch batch,
                            Intaken intaken,
                            Optional<UUID> entryId) {}
                };

        BatchAcceptance.SweepResult first =
                acceptanceWith(poisonedIntake, batches, CLOCK).sweep();
        assertThat(first.candidates()).as("the ten, oldest first, fill the window").isEqualTo(10);
        assertThat(first.failed()).isEqualTo(10);
        assertThat(fileColumn(good, "status")).isEqualTo("PARSED");
        for (UUID fileId : poisoned) {
            assertThat(fileColumn(fileId, "status"))
                    .as("our failure never rejects evidence")
                    .isEqualTo("PARSED");
            assertThat(fileColumn(fileId, "accept_failures")).isEqualTo("1");
            assertThat(nextAcceptAtIs(fileId, CLOCK.instant().plus(BACKOFF_BASE))).isTrue();
            assertThat(count(
                            "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                    + " AND from_status = 'PARSED' AND to_status = 'PARSED'"
                                    + " AND reason = 'accept failed: SettlementStorageException'",
                            fileId))
                    .isEqualTo(1);
        }

        BatchAcceptance.SweepResult second =
                acceptanceWith(poisonedIntake, batches, CLOCK).sweep();
        assertThat(fileColumn(good, "status"))
                .as("the file behind the ten is accepted on the next tick - never starved")
                .isEqualTo("ACCEPTED");
        assertThat(second.failed()).as("no backed-off file is tried before it is due").isZero();

        // Due again past the first step: tried again, and the step doubles.
        Clock pastTheStep = Clock.offset(CLOCK, BACKOFF_BASE);
        for (int sweep = 0; sweep < 5 && !allFailedTwice(poisoned); sweep++) {
            acceptanceWith(poisonedIntake, batches, pastTheStep).sweep();
        }
        for (UUID fileId : poisoned) {
            assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");
            assertThat(fileColumn(fileId, "accept_failures")).isEqualTo("2");
            assertThat(nextAcceptAtIs(
                            fileId, pastTheStep.instant().plus(BACKOFF_BASE.multipliedBy(2))))
                    .isTrue();
        }

        // Leave nothing eligible behind: the ten, healed, are accepted once due.
        Clock healed = Clock.offset(CLOCK, Duration.ofHours(2));
        for (int sweep = 0; sweep < 5 && !allIn(poisoned, "ACCEPTED"); sweep++) {
            acceptanceWith(intake, batches, healed).sweep();
        }
        assertThat(allIn(poisoned, "ACCEPTED")).isTrue();
    }

    @Test
    @DisplayName("an accept failure is recorded only while the file is still PARSED: another"
            + " instance accepts it the moment our rollback lets go, its edge committing while"
            + " our failure transaction waits on the row - nothing follows the ACCEPTED edge"
            + " (MI-7, the MI-5 rule at the accept leg)")
    void anAcceptFailureNeverFollowsAnotherInstancesEdge() throws Exception {
        drainDueLeftovers();
        UUID fileId = pulled(SOURCE, report(SOURCE, "PSPB-MI7-RACE", "MR1"));
        for (int sweep = 0; sweep < 5 && !allIn(List.of(fileId), "PARSED"); sweep++) {
            parsing.sweep();
        }
        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");

        CountDownLatch bHoldsTheClaim = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        AtomicBoolean ourAcceptFailed = new AtomicBoolean();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Thread> others = new ArrayList<>();

        // Instance B: the recording intake, holding its claim on THIS file until A's failure
        // transaction is observed waiting on the row.
        BatchAcceptance instanceB =
                acceptanceWith(
                        new AcceptedBatchIntake() {
                            @Override
                            public Intaken intake(Connection unitOfWork, AcceptedBatch batch) {
                                if (batch.fileId().equals(fileId)) {
                                    bHoldsTheClaim.countDown();
                                    awaitOrFail(releaseB, "B is released");
                                }
                                return intake.intake(unitOfWork, batch);
                            }

                            @Override
                            public void recognised(
                                    Connection unitOfWork,
                                    AcceptedBatch batch,
                                    Intaken intaken,
                                    Optional<UUID> entryId) {}
                        },
                        batches,
                        CLOCK);
        // Instance A: its acceptance of THIS file fails - our defect. Its runner opens the
        // failure transaction only once B holds the claim A's rollback released, and lets B
        // commit only once that transaction is observed waiting on the row.
        TransactionRunner instanceARunner =
                new TransactionRunner() {
                    @Override
                    public <R> R inTransaction(Function<Connection, R> work) {
                        if (ourAcceptFailed.getAndSet(false)) {
                            others.add(
                                    Thread.ofPlatform()
                                            .start(
                                                    () -> {
                                                        try {
                                                            instanceB.sweep();
                                                        } catch (Throwable failure) {
                                                            unexpected.add(failure);
                                                        }
                                                    }));
                            awaitOrFail(bHoldsTheClaim, "B claims what A's rollback released");
                            others.add(
                                    Thread.ofPlatform()
                                            .start(
                                                    () -> {
                                                        try {
                                                            awaitAFileWriterBlocked();
                                                        } catch (Throwable failure) {
                                                            unexpected.add(failure);
                                                        } finally {
                                                            releaseB.countDown();
                                                        }
                                                    }));
                        }
                        return RUNNER.inTransaction(work);
                    }
                };
        BatchAcceptance instanceA =
                acceptanceWith(
                        new AcceptedBatchIntake() {
                            @Override
                            public Intaken intake(Connection unitOfWork, AcceptedBatch batch) {
                                if (batch.fileId().equals(fileId)) {
                                    ourAcceptFailed.set(true);
                                    throw new SettlementStorageException("injected: our defect");
                                }
                                return intake.intake(unitOfWork, batch);
                            }

                            @Override
                            public void recognised(
                                    Connection unitOfWork,
                                    AcceptedBatch batch,
                                    Intaken intaken,
                                    Optional<UUID> entryId) {}
                        },
                        batches,
                        CLOCK,
                        instanceARunner);

        instanceA.sweep();
        for (Thread other : others) {
            other.join();
        }
        assertThat(unexpected).isEmpty();
        assertThat(others).as("the interleaving ran: A failed, B claimed").hasSize(2);

        assertThat(fileColumn(fileId, "status")).as("B's acceptance stands").isEqualTo("ACCEPTED");
        assertThat(fileColumn(fileId, "accept_failures"))
                .as("no failure is counted against a file that moved")
                .isEqualTo("0");
        assertThat(fileColumn(fileId, "next_accept_at")).isNull();
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ? AND seq >"
                                + " (SELECT seq FROM settlement.file_event WHERE file_id = ?"
                                + " AND to_status = 'ACCEPTED')",
                        fileId,
                        fileId))
                .as("nothing follows the ACCEPTED edge in the file's history")
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND from_status = 'PARSED' AND to_status = 'PARSED'",
                        fileId))
                .isZero();
    }

    // -----------------------------------------------------------------

    /** Sweeps until nothing is due: each leftover accepted, or - our failure - backed off. */
    private static void drainDueLeftovers() {
        for (int sweep = 0; sweep < 20; sweep++) {
            if (acceptance.sweep().candidates() == 0) {
                return;
            }
        }
    }

    private static boolean allIn(List<UUID> fileIds, String status) throws SQLException {
        for (UUID fileId : fileIds) {
            if (!status.equals(fileColumn(fileId, "status"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allFailedTwice(List<UUID> fileIds) throws SQLException {
        for (UUID fileId : fileIds) {
            if (!"2".equals(fileColumn(fileId, "accept_failures"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean nextAcceptAtIs(UUID fileId, Instant expected) throws SQLException {
        return count(
                        "SELECT count(*) FROM settlement.file WHERE id = ? AND next_accept_at = ?",
                        fileId,
                        Timestamp.from(expected))
                == 1;
    }

    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("never happened: " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted awaiting: " + what, interrupted);
        }
    }

    /** The {@code P0-TST-004} idiom: the waiting side observed Lock-waiting, never assumed. */
    private static void awaitAFileWriterBlocked() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE datname = current_database()"
                                        + " AND wait_event_type = 'Lock'"
                                        + " AND query LIKE '%settlement.file%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "A's failure transaction never waited on the file row B holds - without the"
                        + " lock its write is judged against a stale read");
    }

    private static String columnOfBatch(UUID batchId, String column) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT " + column + "::text FROM settlement.batch WHERE id = ?")) {
            read.setObject(1, batchId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                String value = row.getString(1);
                application.rollback();
                return value;
            }
        }
    }

    private static List<com.finapp.ledger.JournalLine> recognitionLines(
            SettlementBatchStore.BatchRow batch) {
        List<SettlementBatchStore.LineRow> lines =
                RUNNER.inTransaction(uow -> batches.linesOf(uow, batch.id()));
        var accounts = new JdbcLedgerAccountStore();
        var costs =
                RUNNER.inTransaction(
                        uow ->
                                accounts.findOperational(
                                                uow,
                                                AccountPurpose.PROCESSING_COSTS,
                                                batch.currency())
                                        .orElseThrow());
        var position =
                RUNNER.inTransaction(
                        uow ->
                                accounts.findOperational(
                                                uow,
                                                AccountPurpose.SETTLEMENT_CLEARING,
                                                batch.currency())
                                        .orElseThrow());
        return BatchRecognition.recognise(
                        lines, batch.currency(), batch.netScale(), costs.id(), position.id())
                .entryLines();
    }
}
