package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The intake's gate claims the Phase 8 exit review reads, closed against the real schema
 * (`P8-DOC-001`):
 *
 * <ul>
 *   <li><strong>Atomicity by failure injection</strong> — the store (or the audit writer) is
 *       decorated to throw AFTER its write has landed, and the unit of work rolls back as the
 *       production transaction runner does: nothing of the reception, the refusal or the
 *       attestation survives, and the same act then succeeds as if never tried.
 *   <li><strong>§7 C2</strong> — ten parsers over ten DIFFERENT files declaring one PSP batch
 *       identity, each past the pre-check before any inserts (a barrier): the live unique
 *       arbitrates, one live batch, nine {@code REJECTED(CONFLICTING_BATCH)} retained.
 *   <li><strong>§7 C3/C4</strong> — five attesters racing five decliners on one file: one
 *       terminal outcome, at most one attestation, every loser answered
 *       {@code settlement.FileNotAttestable}.
 * </ul>
 *
 * <p>Everything runs as {@code finapp_app}, each simulated instance on its own connection.
 */
@Tag("database")
@DisplayName("intake atomicity under injected failure, and the C2/C4 races (P8-DOC-001)")
class IntakeAtomicityAndRacesDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor UPLOADER = new Actor("op-intake-uploader", ActorType.EMPLOYEE);
    private static final Actor ATTESTER = new Actor("op-intake-attester", ActorType.EMPLOYEE);
    private static final String CORRELATION = "p8-doc-001-intake-gate";
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of(CORRELATION));
    private static final String SOURCE = "simulated-psp.settlement";
    private static final byte[] KEY = new byte[32];

    /** Fresh application connection per transaction, rolled back on any RuntimeException. */
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
    private static SettlementSources sources;
    private static JdbcSettlementFileStore store;
    private static JdbcSettlementBatchStore batches;
    private static AuditWriter<Connection> audit;
    private static FileReception<Connection> reception;

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

    /** The injected failure: thrown only after the decorated write has landed. */
    private static final class InjectedFailure extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        InjectedFailure(String where) {
            super("injected after " + where);
        }
    }

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        sources =
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
        audit = new JdbcAuditWriter();
        reception = receptionWith(store, audit);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // ================================================================= 1. atomicity

    @Test
    @DisplayName("reception: a failure after the file row AND its encrypted chunks are written"
            + " leaves no file, chunk, receipt, event or audit - and the same bytes then land"
            + " NEW")
    void aReceptionFailingAfterItsChunksLeavesNothing() throws SQLException {
        byte[] content = variant("PSPB-ATOM-RCV-01", "Atomic reception");
        AtomicReference<UUID> landed = new AtomicReference<>();
        SettlementFileStore<Connection> failing =
                failAfter(
                        store,
                        "insert",
                        result -> {
                            if (result instanceof SettlementFileStore.Stored.New stored) {
                                landed.set(stored.fileId());
                            }
                        });

        assertThatThrownBy(
                        () ->
                                RUNNER.inTransaction(
                                        uow ->
                                                receptionWith(failing, audit)
                                                        .receive(uow, upload(content))))
                .isInstanceOf(InjectedFailure.class);

        UUID fileId = landed.get();
        assertThat(fileId)
                .as("the decorated insert ran: the file row and its chunks WERE written")
                .isNotNull();
        assertNothingOfTheFileSurvived(fileId, content);

        FileReception.Result again = RUNNER.inTransaction(uow -> reception.receive(uow, upload(content)));
        assertThat(again)
                .as("the content address is free: the rolled-back reception left no duplicate")
                .isInstanceOf(FileReception.Result.New.class);
    }

    @Test
    @DisplayName("reception: a failure after its LAST write (the channel's audit record)"
            + " leaves no file, chunk, receipt, birth event or audit")
    void aReceptionFailingAfterItsAuditLeavesNothing() throws SQLException {
        byte[] content = variant("PSPB-ATOM-RCV-02", "Atomic reception audit");
        AtomicReference<String> audited = new AtomicReference<>();
        AuditWriter<Connection> failingAudit =
                auditFailingAfter(TestAction.RECEIVED.code(), audited);

        assertThatThrownBy(
                        () ->
                                RUNNER.inTransaction(
                                        uow ->
                                                receptionWith(store, failingAudit)
                                                        .receive(uow, upload(content))))
                .isInstanceOf(InjectedFailure.class);

        UUID fileId = UUID.fromString(audited.get());
        assertNothingOfTheFileSurvived(fileId, content);
        FileReception.Result again =
                RUNNER.inTransaction(uow -> reception.receive(uow, upload(content)));
        assertThat(again)
                .as("the same bytes land NEW once nothing interferes")
                .isInstanceOf(FileReception.Result.New.class);
    }

    @Test
    @DisplayName("refusal: a failure after the refused_delivery row - and again after the"
            + " refusal's audit record - leaves no refusal row and no audit")
    void aRefusalFailingAfterItsRowLeavesNothing() throws SQLException {
        // A card number: the conservative screen refuses it PRIMARY_ACCOUNT_NUMBER.
        byte[] dirty =
                "id,amount,reference\n1,100,4111111111111111\nintake-gate\n"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] sha = sha256(dirty);
        String shaHex = java.util.HexFormat.of().formatHex(sha);
        long refusalAuditsBefore = refusalAuditsNaming(shaHex);

        // (a) after the row, before the audit.
        AtomicReference<Object> wrote = new AtomicReference<>();
        SettlementFileStore<Connection> failingStore =
                failAfter(store, "recordRefusal", result -> wrote.set(Boolean.TRUE));
        assertThatThrownBy(
                        () ->
                                RUNNER.inTransaction(
                                        uow ->
                                                plainReceptionWith(failingStore, audit)
                                                        .receive(uow, upload(dirty))))
                .isInstanceOf(InjectedFailure.class);
        assertThat(wrote.get()).as("the refused_delivery row WAS written").isNotNull();
        assertThat(count("SELECT count(*) FROM settlement.refused_delivery"
                        + " WHERE content_sha256 = ?", sha))
                .as("no refusal row survives a failure after it (atomic refusal)")
                .isZero();
        assertThat(refusalAuditsNaming(shaHex))
                .as("no refusal audit survives")
                .isEqualTo(refusalAuditsBefore);

        // (b) after the audit, the refusal's last write.
        AtomicReference<String> audited = new AtomicReference<>();
        assertThatThrownBy(
                        () ->
                                RUNNER.inTransaction(
                                        uow ->
                                                plainReceptionWith(
                                                                store,
                                                                auditFailingAfter(
                                                                        SettlementAuditAction
                                                                                .SETTLEMENT_DELIVERY_REFUSED
                                                                                .code(),
                                                                        audited))
                                                        .receive(uow, upload(dirty))))
                .isInstanceOf(InjectedFailure.class);
        assertThat(audited.get()).as("the refusal's audit record WAS written").isNotNull();
        assertThat(count("SELECT count(*) FROM settlement.refused_delivery"
                        + " WHERE content_sha256 = ?", sha))
                .as("no refusal row survives a failure after its audit (atomic refusal)")
                .isZero();
        assertThat(refusalAuditsNaming(shaHex))
                .as("no refusal audit survives a failure after it")
                .isEqualTo(refusalAuditsBefore);

        // The control: unhindered, the same delivery leaves exactly one of each.
        FileReception.Result refused =
                RUNNER.inTransaction(uow -> plainReceptionWith(store, audit).receive(uow, upload(dirty)));
        assertThat(refused).isInstanceOf(FileReception.Result.Refused.class);
        assertThat(count("SELECT count(*) FROM settlement.refused_delivery"
                        + " WHERE content_sha256 = ?", sha))
                .isEqualTo(1);
        assertThat(refusalAuditsNaming(shaHex)).isEqualTo(refusalAuditsBefore + 1);
    }

    @Test
    @DisplayName("attestation: a failure after the NULL to value edge (before the audit), and"
            + " again after the audit, leaves the file unattested and unaudited - and the"
            + " same person then attests it")
    void anAttestationFailingAfterItsEdgeLeavesNothing() throws SQLException {
        UUID fileId = uploaded(variant("PSPB-ATOM-ATT-01", "Atomic attestation"));

        // (a) after the conditional edge, before the audit.
        AtomicReference<Object> edge = new AtomicReference<>();
        SettlementFileStore<Connection> failingStore =
                failAfter(store, "recordAttestation", result -> edge.set(result));
        assertThatThrownBy(
                        () ->
                                RUNNER.inTransaction(
                                        uow ->
                                                new FileAttestation<>(
                                                                failingStore, audit, IDS, CLOCK)
                                                        .attest(uow, fileId, ATTESTER, FLOW)))
                .isInstanceOf(InjectedFailure.class);
        assertThat(edge.get()).as("the conditional edge WAS recorded").isEqualTo(Boolean.TRUE);
        assertThat(fileColumn(fileId, "attested_by"))
                .as("no attestation survives a failure between edge and audit (INV-SET-07)")
                .isNull();
        assertThat(attestationAudits(fileId)).as("no attestation audit survives").isZero();

        // (b) after the audit.
        AtomicReference<String> audited = new AtomicReference<>();
        assertThatThrownBy(
                        () ->
                                RUNNER.inTransaction(
                                        uow ->
                                                new FileAttestation<>(
                                                                store,
                                                                auditFailingAfter(
                                                                        SettlementAuditAction
                                                                                .SETTLEMENT_FILE_ATTESTED
                                                                                .code(),
                                                                        audited),
                                                                IDS,
                                                                CLOCK)
                                                        .attest(uow, fileId, ATTESTER, FLOW)))
                .isInstanceOf(InjectedFailure.class);
        assertThat(audited.get()).isEqualTo(fileId.toString());
        assertThat(fileColumn(fileId, "attested_by"))
                .as("no attestation survives a failure after its audit")
                .isNull();
        assertThat(attestationAudits(fileId)).isZero();

        FileAttestation.Result attested =
                RUNNER.inTransaction(
                        uow ->
                                new FileAttestation<>(store, audit, IDS, CLOCK)
                                        .attest(uow, fileId, ATTESTER, FLOW));
        assertThat(attested)
                .as("the act was never on the record: the same person attests it afresh")
                .isInstanceOf(FileAttestation.Result.Attested.class);
        assertThat(attestationAudits(fileId)).isEqualTo(1);
    }

    // ================================================================= 2. §7 C2

    @Test
    @DisplayName("C2: ten parsers over ten DIFFERENT files declaring one PSP batch identity,"
            + " all past the pre-check at once - one live batch, nine REJECTED"
            + " CONFLICTING_BATCH and retained")
    void tenParsersOverTenConflictingFilesLeaveOneLiveBatch() throws Exception {
        String batchRef = "PSPB-C2-RACE-01";
        int racers = 10;
        List<UUID> files = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            files.add(uploaded(variant(batchRef, "C2 declaration " + i)));
        }

        // Every parser evaluates the pre-check before ANY of them inserts: the pre-check sees
        // nothing, so only the live unique can arbitrate (SettlementBatchStore.LiveBatchConflict).
        CyclicBarrier pastThePreCheck = new CyclicBarrier(racers);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<FileParsing.SweepResult> results = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (UUID fileId : files) {
            FileParsing parser = parserOf(fileId, batchRef, pastThePreCheck);
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            start.await();
                                            results.add(parser.sweep());
                                        } catch (Throwable failure) {
                                            unexpected.add(failure);
                                        }
                                    }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(120_000);
        }

        assertThat(unexpected).isEmpty();
        assertThat(pastThePreCheck.isBroken())
                .as("all ten parsers passed the pre-check together - the race was real")
                .isFalse();
        assertThat(results.stream().mapToInt(FileParsing.SweepResult::failed).sum())
                .as("no parser counted the conflict as its own failure")
                .isZero();
        assertThat(results.stream().mapToInt(FileParsing.SweepResult::parsed).sum())
                .isEqualTo(1);
        assertThat(results.stream().mapToInt(FileParsing.SweepResult::rejected).sum())
                .isEqualTo(racers - 1);

        assertThat(count("SELECT count(*) FROM settlement.batch WHERE external_batch_ref = ?",
                        batchRef))
                .as("C2: exactly one live batch per (source, reference, currency)")
                .isEqualTo(1);
        long parsed = 0;
        long conflicting = 0;
        for (UUID fileId : files) {
            String status = fileColumn(fileId, "status");
            if ("PARSED".equals(status)) {
                parsed++;
                assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?",
                                fileId))
                        .isEqualTo(1);
            } else {
                assertThat(status).isEqualTo("REJECTED");
                assertThat(fileColumn(fileId, "rejection_code"))
                        .as("C2: each loser is REJECTED(CONFLICTING_BATCH), never left RECEIVED")
                        .isEqualTo("CONFLICTING_BATCH");
                assertThat(count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?",
                                fileId))
                        .as("C2: a conflicting file is retained whole")
                        .isPositive();
                assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?",
                                fileId))
                        .isZero();
                assertThat(count("SELECT count(*) FROM settlement.line WHERE file_id = ?",
                                fileId))
                        .as("C2: no line of a losing file survives its dead transaction")
                        .isZero();
                assertThat(count(
                                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                        + " 'settlement.SettlementFileRejected'"
                                        + " AND target_id = ?",
                                fileId.toString()))
                        .isEqualTo(1);
                assertThat(count(
                                "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                        + " 'settlement.SettlementFileRejected'"
                                        + " AND aggregate_id = ?",
                                fileId))
                        .isEqualTo(1);
                conflicting++;
            }
        }
        assertThat(parsed).isEqualTo(1);
        assertThat(conflicting).isEqualTo(racers - 1);
    }

    // ================================================================= 3. §7 C4

    @Test
    @DisplayName("C4: five attesters racing five decliners on a RECEIVED upload - one terminal"
            + " outcome (one decline), at most one attestation, every loser answered")
    void attestRacingDeclineOnAReceivedFile() throws Exception {
        UUID fileId = uploaded(variant("PSPB-C4-RCV-01", "C4 received"));
        attestAgainstDecline(fileId, false);
    }

    @Test
    @DisplayName("C4: five attesters racing five decliners on a PARSED upload - one terminal"
            + " outcome, its batch REJECTED with it, every loser answered")
    void attestRacingDeclineOnAParsedFile() throws Exception {
        String batchRef = "PSPB-C4-PRS-01";
        UUID fileId = uploaded(variant(batchRef, "C4 parsed"));
        parserOf(fileId, batchRef, null).sweep();
        assertThat(fileColumn(fileId, "status")).isEqualTo("PARSED");
        attestAgainstDecline(fileId, true);
    }

    private static void attestAgainstDecline(UUID fileId, boolean parsed) throws Exception {
        FileAttestation<Connection> attestation = new FileAttestation<>(store, audit, IDS, CLOCK);
        FileDecline decline =
                new FileDecline(
                        store,
                        batches,
                        IntakeOutcomeObserver.NONE,
                        new JdbcOutboxWriter(),
                        audit,
                        IDS,
                        CLOCK);
        int each = 5;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Object> won = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> answered = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < each * 2; i++) {
            boolean attester = i % 2 == 0;
            Actor actor =
                    new Actor((attester ? "op-c4-attester-" : "op-c4-decliner-") + i,
                            ActorType.EMPLOYEE);
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (SimulatedInstance instance =
                                                SimulatedInstance.inAgreementWithTheServer()) {
                                            start.await();
                                            Object result =
                                                    attester
                                                            ? attestation.attest(
                                                                    instance.connection(),
                                                                    fileId, actor, FLOW)
                                                            : decline.decline(
                                                                    instance.connection(),
                                                                    fileId, "C4 race",
                                                                    actor, FLOW);
                                            instance.commit();
                                            won.add(result);
                                        } catch (FileAttestation
                                                        .SettlementFileNotAttestable loser) {
                                            answered.add(attester ? "attest" : "decline");
                                        } catch (Throwable failure) {
                                            unexpected.add(failure);
                                        }
                                    }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(60_000);
        }

        assertThat(unexpected).as("no racer met an unanswered failure (a 500)").isEmpty();
        long declines = won.stream().filter(FileDecline.Declined.class::isInstance).count();
        long attestations =
                won.stream().filter(FileAttestation.Result.Attested.class::isInstance).count();
        assertThat(won.size() + answered.size())
                .as("every one of the ten racers was answered")
                .isEqualTo(each * 2);
        assertThat(declines).as("C4: exactly one decline wins - one terminal outcome").isEqualTo(1);
        assertThat(attestations).as("C4: at most one attestation stands").isBetween(0L, 1L);
        assertThat(won.size()).isEqualTo(declines + attestations);
        assertThat(answered.stream().filter("decline"::equals).count())
                .as("the four losing decliners are answered FileNotAttestable")
                .isEqualTo(each - 1);
        assertThat(answered.stream().filter("attest"::equals).count())
                .as("every losing attester is answered FileNotAttestable")
                .isEqualTo(each - attestations);

        assertThat(fileColumn(fileId, "status")).isEqualTo("REJECTED");
        assertThat(fileColumn(fileId, "rejection_code")).isEqualTo("DECLINED");
        assertThat(count("SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                        + " AND to_status = 'REJECTED'", fileId))
                .as("C4: one edge into the terminal state, however many raced")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'settlement.SettlementFileDeclined' AND target_id = ?",
                        fileId.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                        + " 'settlement.SettlementFileRejected' AND aggregate_id = ?", fileId))
                .isEqualTo(1);
        assertThat(attestationAudits(fileId))
                .as("an attestation's audit exists exactly when its winner does")
                .isEqualTo(attestations);
        assertThat(fileColumn(fileId, "attested_by") != null)
                .as("the attestation column agrees with the winners")
                .isEqualTo(attestations == 1);
        if (parsed) {
            assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?"
                            + " AND status = 'REJECTED'", fileId))
                    .as("the parsed file's batch left with it, in the decline's transaction")
                    .isEqualTo(1);
        }
    }

    // ================================================================= fixtures

    private static FileReception<Connection> receptionWith(
            SettlementFileStore<Connection> files, AuditWriter<Connection> writer) {
        DeliveryScreen pspScreen = SimPspCsvFormat.INSTANCE::screen;
        return new FileReception<>(
                sources,
                files,
                Map.of(SettlementFormatId.SIM_PSP_CSV, pspScreen),
                quietObserver(),
                writer,
                IDS,
                CLOCK);
    }

    /** The conservative screen only - what refuses a card number on any line. */
    private static FileReception<Connection> plainReceptionWith(
            SettlementFileStore<Connection> files, AuditWriter<Connection> writer) {
        return new FileReception<>(sources, files, Map.of(), quietObserver(), writer, IDS, CLOCK);
    }

    private static ReceptionOutcomeObserver quietObserver() {
        return new ReceptionOutcomeObserver() {
            @Override
            public void received(String sourceCode, SettlementFileStore.ReceiptOutcome outcome) {}

            @Override
            public void refused(String sourceCode, RefusalReason reason) {}
        };
    }

    /**
     * A parser whose candidate list is exactly {@code fileId} — so ten parsers race over ten
     * files, not over one list — and, when {@code barrier} is given, whose pre-check for
     * {@code batchRef} waits for every other parser's before answering.
     */
    @SuppressWarnings("unchecked")
    private static FileParsing parserOf(UUID fileId, String batchRef, CyclicBarrier barrier) {
        SettlementFileStore<Connection> files =
                (SettlementFileStore<Connection>)
                        Proxy.newProxyInstance(
                                SettlementFileStore.class.getClassLoader(),
                                new Class<?>[] {SettlementFileStore.class},
                                (proxy, method, args) ->
                                        "dueForParse".equals(method.getName())
                                                ? List.of(fileId)
                                                : invoke(store, method, args));
        SettlementBatchStore<Connection> batchStore =
                (SettlementBatchStore<Connection>)
                        Proxy.newProxyInstance(
                                SettlementBatchStore.class.getClassLoader(),
                                new Class<?>[] {SettlementBatchStore.class},
                                (proxy, method, args) -> {
                                    Object result = invoke(batches, method, args);
                                    if (barrier != null
                                            && "liveBatchStands".equals(method.getName())
                                            && batchRef.equals(args[2])) {
                                        barrier.await(60, TimeUnit.SECONDS);
                                    }
                                    return result;
                                });
        return new FileParsing(
                files,
                batchStore,
                Map.of(SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE),
                new FileParsing.Config(10, Duration.ofMinutes(1), Duration.ofHours(1)),
                IntakeOutcomeObserver.NONE,
                new JdbcOutboxWriter(),
                new JdbcAuditWriter(),
                IDS,
                CLOCK,
                RUNNER,
                sources);
    }

    /** The store, with {@code method} throwing {@link InjectedFailure} AFTER its write. */
    @SuppressWarnings("unchecked")
    private static SettlementFileStore<Connection> failAfter(
            SettlementFileStore<Connection> delegate,
            String method,
            java.util.function.Consumer<Object> witness) {
        InvocationHandler handler =
                (proxy, called, args) -> {
                    Object result = invoke(delegate, called, args);
                    if (method.equals(called.getName())) {
                        witness.accept(result);
                        throw new InjectedFailure(method);
                    }
                    return result;
                };
        return (SettlementFileStore<Connection>)
                Proxy.newProxyInstance(
                        SettlementFileStore.class.getClassLoader(),
                        new Class<?>[] {SettlementFileStore.class},
                        handler);
    }

    /** The audit writer, throwing AFTER it appends a record of {@code operation}. */
    private static AuditWriter<Connection> auditFailingAfter(
            String operation, AtomicReference<String> target) {
        return (uow, record) -> {
            audit.append(uow, record);
            if (operation.equals(operationOf(record))) {
                target.set(record.targetId());
                throw new InjectedFailure("audit " + operation);
            }
        };
    }

    private static String operationOf(AuditRecord record) {
        return record.operation().code();
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static void assertNothingOfTheFileSurvived(UUID fileId, byte[] content)
            throws SQLException {
        assertThat(count("SELECT count(*) FROM settlement.file WHERE id = ?", fileId))
                .as("no file row survives the failed reception (atomic reception)")
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.file WHERE content_sha256 = ?",
                        (Object) sha256(content)))
                .as("nothing holds the content address")
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?", fileId))
                .as("no encrypted chunk survives (atomic reception)")
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.file_receipt WHERE file_id = ?",
                        fileId))
                .as("no receipt survives (atomic reception)")
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.file_event WHERE file_id = ?", fileId))
                .as("no birth event survives")
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?",
                        fileId.toString()))
                .as("no reception audit survives")
                .isZero();
    }

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        IntakeAtomicityAndRacesDatabaseTest.class.getResourceAsStream(
                                "/format/simpsp/golden-v1.csv"))) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The golden file re-keyed and re-worded: a distinct content address per call site. */
    private static byte[] variant(String batchRef, String marker) {
        String text = new String(golden(), StandardCharsets.UTF_8)
                .replace("PSPB-2026-09-25-01", batchRef)
                .replace("Desk sale", marker);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static FileReception.Delivery upload(byte[] content) {
        return new FileReception.Delivery(
                SOURCE,
                DeliveryChannel.UPLOAD,
                content,
                Optional.empty(),
                UPLOADER,
                TestAction.RECEIVED,
                FLOW);
    }

    private static UUID uploaded(byte[] content) {
        FileReception.Result result =
                RUNNER.inTransaction(uow -> reception.receive(uow, upload(content)));
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    private static long attestationAudits(UUID fileId) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'settlement.SettlementFileAttested' AND target_id = ?", fileId.toString());
    }

    private static long refusalAuditsNaming(String shaHex) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'settlement.SettlementDeliveryRefused' AND change_summary LIKE ?",
                "%sha256=" + shaHex + "%");
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

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
