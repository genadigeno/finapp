package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.audit.AuditWriter;
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

/**
 * The second person's act and the one content path, against the real schema (`P8-TSK-003`,
 * ADR-0066 §2, §7): one attestation under ten racers, distinctness refused at BOTH ranks, and
 * every content access reasoned, audited, counted — served byte-identical or refused whole.
 *
 * <p>Everything here runs as {@code finapp_app}; tampering for the failed-read test disables
 * the append-only trigger as the bootstrap superuser, the reception test's threat model.
 */
@Tag("database")
@DisplayName("settlement file attestation and audited content reads (P8-TSK-003)")
class FileAttestationDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor UPLOADER = new Actor("op-uploader", ActorType.EMPLOYEE);
    private static final Actor ATTESTER = new Actor("op-attester", ActorType.EMPLOYEE);
    private static final Actor INVESTIGATOR = new Actor("op-investigator", ActorType.EMPLOYEE);
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of("p8-tsk-003-attestation-test"));

    private static final byte[] KEY = new byte[32];

    private static Connection application;
    private static JdbcSettlementFileStore store;
    private static FileReception<Connection> reception;
    private static FileAttestation<Connection> attestation;
    private static EvidenceContentReads<Connection> contentReads;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        SettlementSources sources =
                SettlementSources.of(
                        List.of(
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}"))));
        store = new JdbcSettlementFileStore(new SettlementFileCipher(KEY, 1, new SecureRandom()));
        AuditWriter<Connection> audit = new JdbcAuditWriter();
        reception =
                new FileReception<>(
                        sources,
                        store,
                        Map.of(),
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
        attestation = new FileAttestation<>(store, audit, IDS, CLOCK);
        contentReads = new EvidenceContentReads<>(store, audit, IDS, CLOCK);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // -----------------------------------------------------------------
    // The second person's act (INV-SET-07).
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a second person attests an upload: the fact lands once, audited; the same"
            + " attester converges; a third person is refused")
    void aSecondPersonAttestsOnce() throws SQLException {
        UUID fileId = uploaded("attest-me,alpha\n");
        long auditsBefore = attestationAudits();

        FileAttestation.Result first =
                attestation.attest(application, fileId, ATTESTER, FLOW);
        application.commit();
        assertThat(first).isInstanceOf(FileAttestation.Result.Attested.class);
        SettlementFileStore.Attestation standing =
                first.file().attestation().orElseThrow();
        assertThat(standing.attestedBy()).isEqualTo(ATTESTER.id());
        assertThat(standing.attestedAt()).isEqualTo(CLOCK.instant());
        assertThat(attestationAudits()).isEqualTo(auditsBefore + 1);

        FileAttestation.Result repeat =
                attestation.attest(application, fileId, ATTESTER, FLOW);
        application.commit();
        assertThat(repeat).isInstanceOf(FileAttestation.Result.AlreadyAttestedByYou.class);
        assertThat(attestationAudits())
                .as("a converging repeat writes no second record")
                .isEqualTo(auditsBefore + 1);

        assertThatThrownBy(
                        () ->
                                attestation.attest(
                                        application,
                                        fileId,
                                        new Actor("op-third", ActorType.EMPLOYEE),
                                        FLOW))
                .isInstanceOf(FileAttestation.SettlementFileNotAttestable.class);
        application.rollback();
    }

    @Test
    @DisplayName("self-attestation is refused at the domain, and a raw UPDATE setting the"
            + " attester to the receiver is refused by the CHECK")
    void selfAttestationIsRefusedAtBothRanks() throws SQLException {
        UUID fileId = uploaded("attest-self,beta\n");

        assertThatThrownBy(
                        () -> attestation.attest(application, fileId, UPLOADER, FLOW))
                .as("the domain rank (INV-SET-07)")
                .isInstanceOf(FileAttestation.AttestationBySubmitter.class);
        application.rollback();

        // The database rank binds even a writer that bypasses the domain entirely.
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement raw =
                                    application.prepareStatement(
                                            "UPDATE settlement.file SET attested_by ="
                                                    + " received_by, attested_at = now()"
                                                    + " WHERE id = ?")) {
                                raw.setObject(1, fileId);
                                raw.executeUpdate();
                            }
                        })
                .as("the CHECK rank (file_attester_is_second_person)")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("file_attester_is_second_person");
        application.rollback();
    }

    @Test
    @DisplayName("a PULL delivery is not attestable - its channel authenticates it - and an"
            + " unknown file is not found")
    void pullDeliveriesAndUnknownFilesAreRefused() throws SQLException {
        FileReception.Result pulled =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                "simulated-psp.settlement",
                                DeliveryChannel.PULL,
                                "pulled,gamma\n".getBytes(StandardCharsets.UTF_8),
                                Optional.empty(),
                                Actor.SYSTEM,
                                TestAction.RECEIVED,
                                FLOW));
        UUID pulledId = ((FileReception.Result.New) pulled).fileId();
        application.commit();

        assertThatThrownBy(() -> attestation.attest(application, pulledId, ATTESTER, FLOW))
                .isInstanceOf(FileAttestation.SettlementFileNotAttestable.class);
        application.rollback();

        assertThatThrownBy(
                        () ->
                                attestation.attest(
                                        application, UUID.randomUUID(), ATTESTER, FLOW))
                .isInstanceOf(FileAttestation.SettlementFileNotFound.class);
        application.rollback();
    }

    @Test
    @DisplayName("ten instances attesting one file at once: exactly one attestation, one"
            + " audit record - the conditional NULL to value is the arbiter")
    void tenRacingAttestersProduceOneAttestation() throws Exception {
        UUID fileId = uploaded("attest-race,delta\n");
        long auditsBefore = attestationAudits();

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<FileAttestation.Result> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> refused = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            Actor racer = new Actor("op-racer-" + i, ActorType.EMPLOYEE);
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (SimulatedInstance instance =
                                                SimulatedInstance.inAgreementWithTheServer()) {
                                            start.await();
                                            results.add(
                                                    attestation.attest(
                                                            instance.connection(),
                                                            fileId,
                                                            racer,
                                                            FLOW));
                                            instance.commit();
                                        } catch (FileAttestation
                                                        .SettlementFileNotAttestable loser) {
                                            refused.add(loser);
                                        } catch (Exception failure) {
                                            unexpected.add(failure);
                                        }
                                    }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(60_000);
        }
        assertThat(unexpected).isEmpty();
        assertThat(results)
                .as("exactly one racer records the attestation")
                .hasSize(1)
                .allMatch(FileAttestation.Result.Attested.class::isInstance);
        assertThat(refused).hasSize(racers - 1);
        assertThat(count(
                        "SELECT count(*) FROM settlement.file WHERE id = '" + fileId
                                + "' AND attested_by IS NOT NULL"))
                .isEqualTo(1);
        assertThat(attestationAudits())
                .as("one attestation, one record")
                .isEqualTo(auditsBefore + 1);
        application.commit();
    }

    // -----------------------------------------------------------------
    // The one content path (INV-REC-10): reasoned, audited, counted, byte-identical.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("content reads are reasoned, audited per read, and byte-identical to the"
            + " upload; a guessed id records nothing; a blank reason is refused")
    void contentReadsAreReasonedAuditedAndCounted() throws SQLException {
        byte[] content = "read-me,epsilon\n".getBytes(StandardCharsets.UTF_8);
        FileReception.Result landed =
                reception.receive(application, uploadOf(content));
        UUID fileId = ((FileReception.Result.New) landed).fileId();
        application.commit();
        long readsBefore = contentReadAudits("SUCCEEDED");

        EvidenceContentReads.Outcome first =
                contentReads.read(
                        application, fileId, INVESTIGATOR, "break B-42 under review", FLOW);
        application.commit();
        assertThat(first).isInstanceOf(EvidenceContentReads.Outcome.Served.class);
        assertThat(((EvidenceContentReads.Outcome.Served) first).content())
                .isEqualTo(content);

        EvidenceContentReads.Outcome second =
                contentReads.read(
                        application, fileId, INVESTIGATOR, "break B-42, second look", FLOW);
        application.commit();
        assertThat(second).isInstanceOf(EvidenceContentReads.Outcome.Served.class);

        assertThat(contentReadAudits("SUCCEEDED"))
                .as("one record per read, counted (INV-REC-10)")
                .isEqualTo(readsBefore + 2);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementFileContentRead' AND reason ="
                                + " 'break B-42 under review'"))
                .isEqualTo(1);

        long allReads = contentReadAudits("SUCCEEDED") + contentReadAudits("FAILED");
        assertThat(contentReads.read(application, UUID.randomUUID(), INVESTIGATOR, "guess", FLOW))
                .isInstanceOf(EvidenceContentReads.Outcome.Unknown.class);
        application.commit();
        assertThat(contentReadAudits("SUCCEEDED") + contentReadAudits("FAILED"))
                .as("a guessed identifier records nothing")
                .isEqualTo(allReads);

        assertThatThrownBy(
                        () -> contentReads.read(application, fileId, INVESTIGATOR, " ", FLOW))
                .isInstanceOf(IllegalArgumentException.class);
        application.rollback();
    }

    @Test
    @DisplayName("a tampered chunk's read serves nothing and is on the record as a FAILED"
            + " read with the investigator's reason")
    void aTamperedReadIsAuditedAsFailed() throws Exception {
        byte[] content = "tamper-read,zeta\n".getBytes(StandardCharsets.UTF_8);
        FileReception.Result landed = reception.receive(application, uploadOf(content));
        UUID fileId = ((FileReception.Result.New) landed).fileId();
        application.commit();
        long failedBefore = contentReadAudits("FAILED");

        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            bootstrap.setAutoCommit(true);
            try (Statement ddl = bootstrap.createStatement()) {
                ddl.execute("ALTER TABLE settlement.file_chunk DISABLE TRIGGER"
                        + " file_chunk_is_append_only");
                ddl.execute("UPDATE settlement.file_chunk SET ciphertext ="
                        + " overlay(ciphertext placing decode('00', 'hex') from 1)"
                        + " WHERE file_id = '" + fileId + "'");
                ddl.execute("ALTER TABLE settlement.file_chunk ENABLE TRIGGER"
                        + " file_chunk_is_append_only");
            }
        }

        EvidenceContentReads.Outcome outcome =
                contentReads.read(
                        application, fileId, INVESTIGATOR, "break B-43 under review", FLOW);
        application.commit();

        assertThat(outcome)
                .as("nothing is served (INV-HIST-02)")
                .isInstanceOf(EvidenceContentReads.Outcome.Corrupt.class);
        assertThat(contentReadAudits("FAILED"))
                .as("the failed access is itself on the record (INV-REC-10)")
                .isEqualTo(failedBefore + 1);
    }

    // -----------------------------------------------------------------

    /** The reception's audit word is `P8-TSK-003`'s upload action stand-in. */
    private enum TestAction implements com.finapp.platform.audit.AuditableAction {
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

    private static UUID uploaded(String content) throws SQLException {
        FileReception.Result result =
                reception.receive(
                        application, uploadOf(content.getBytes(StandardCharsets.UTF_8)));
        UUID fileId = ((FileReception.Result.New) result).fileId();
        application.commit();
        return fileId;
    }

    private static FileReception.Delivery uploadOf(byte[] content) {
        return new FileReception.Delivery(
                "simulated-psp.settlement",
                DeliveryChannel.UPLOAD,
                content,
                Optional.empty(),
                UPLOADER,
                TestAction.RECEIVED,
                FLOW);
    }

    private static long attestationAudits() throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record"
                + " WHERE operation = 'settlement.SettlementFileAttested'");
    }

    private static long contentReadAudits(String outcome) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record"
                + " WHERE operation = 'settlement.SettlementFileContentRead'"
                + " AND outcome = '" + outcome + "'");
    }

    private static long count(String sql) throws SQLException {
        try (Statement statement = application.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }
}
