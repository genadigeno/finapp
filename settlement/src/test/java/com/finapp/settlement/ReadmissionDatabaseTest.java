package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.settlement.format.ParsedLine;
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
import java.util.HexFormat;
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

/**
 * Readmission, its authentication rule and the re-parse verification, against the real schema
 * (`P8-TSK-022`, ADR-0066 §8, `INV-SET-07`).
 *
 * <p><strong>The database rank first</strong>, with raw SQL as {@code finapp_app} past every
 * domain guard: a readmission whose original passes nothing on — a never-attested upload, or a
 * {@code DECLINED} file however it was authenticated — is never {@code ACCEPTED} unattested, nor
 * attested by any submitter along its chain; a pulled or attested original passes its
 * authentication on, through an intermediate readmission too. Each positive {@code ACCEPTED}
 * is proven inside a transaction and rolled back, and every batch-less fixture is retired
 * {@code REJECTED}: nothing here is left for another suite's accept leg to claim.
 *
 * <p><strong>Then the domain</strong>: {@link FileReadmission}'s refusals, the new row with its
 * re-encrypted chunks, receipt, reasoned birth and audit; the attestation's domain rank across
 * the chain; the {@code CONFLICTING_BATCH} recovery end to end; the screen's refusal as a
 * committed result; ten racing readmissions; and {@link FileVerification}'s verdicts.
 *
 * <p>Report content uses letters-only random references: the door screen rightly refuses
 * card-length digit runs, and only the refusal test plants one on purpose.
 */
@Tag("database")
@DisplayName("readmission, its authentication rule and the re-parse verification (P8-TSK-022)")
class ReadmissionDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = CLOCK.instant();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Actor UPLOADER = new Actor("op-uploader", ActorType.EMPLOYEE);
    private static final Actor ATTESTER = new Actor("op-attester", ActorType.EMPLOYEE);
    private static final Actor READMITTER = new Actor("op-readmitter", ActorType.EMPLOYEE);
    private static final Actor SECOND_READMITTER =
            new Actor("op-readmitter-two", ActorType.EMPLOYEE);
    private static final Actor THIRD = new Actor("op-third-person", ActorType.EMPLOYEE);
    private static final Actor INVESTIGATOR = new Actor("op-investigator", ActorType.EMPLOYEE);
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of("p8-tsk-022-readmission-test"));

    private static final byte[] KEY = new byte[32];
    private static final String SOURCE = "simulated-psp.settlement";

    /** A throwaway source THIS suite seeds and retires, so the shared one stays open (T-5). */
    private static final String RETIRING_SOURCE = "retiring-readmit-psp.settlement";
    private static final UUID RETIRING_SOURCE_ID =
            UUID.fromString("01a0e2bc-8200-7009-8000-00000000000c");

    private static final String ACCEPT =
            "UPDATE settlement.file SET status = 'ACCEPTED', status_changed_at = now()"
                    + " WHERE id = ?";
    private static final String ATTEST =
            "UPDATE settlement.file SET attested_by = ?, attested_at = now() WHERE id = ?";

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
    private static SettlementSources sources;
    private static FileReception<Connection> reception;
    private static FileReception<Connection> lenientReception;
    private static FileParsing parsing;
    private static FileDecline decline;
    private static FileAttestation<Connection> attestation;
    private static FileReadmission readmission;
    private static FileVerification verification;

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

    private static final ReceptionOutcomeObserver NO_RECEPTION_OUTCOMES =
            new ReceptionOutcomeObserver() {
                @Override
                public void received(
                        String sourceCode, SettlementFileStore.ReceiptOutcome outcome) {}

                @Override
                public void refused(String sourceCode, RefusalReason reason) {}
            };

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
                                        Optional.of("PSP-REM-[0-9]{4,12}")),
                                new SettlementSourceDescriptor(
                                        RETIRING_SOURCE,
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        // A position of its own: one declared source per
                                        // position (INV-SET-05); only this register holds it.
                                        Optional.of(AccountPurpose.INSTANT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}"))));
        store = new JdbcSettlementFileStore(new SettlementFileCipher(KEY, 1, new SecureRandom()));
        batches = new JdbcSettlementBatchStore(IDS);
        AuditWriter<Connection> audit = new JdbcAuditWriter();
        Map<SettlementFormatId, SettlementFormat> formats =
                Map.of(SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE);
        Map<SettlementFormatId, DeliveryScreen> screens =
                Map.of(SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE::screen);
        reception =
                new FileReception<>(
                        sources, store, screens, NO_RECEPTION_OUTCOMES, audit, IDS, CLOCK);
        // A door whose screen finds nothing - how bytes a later screen refuses came to be
        // stored under an earlier one (the refusal test's original).
        DeliveryScreen findsNothing =
                content ->
                        new DeliveryScreen.Screening(
                                (int) new String(content, StandardCharsets.UTF_8).lines().count(),
                                Optional.empty());
        lenientReception =
                new FileReception<>(
                        sources,
                        store,
                        Map.of(SettlementFormatId.SIM_PSP_CSV, findsNothing),
                        NO_RECEPTION_OUTCOMES,
                        audit,
                        IDS,
                        CLOCK);
        parsing =
                new FileParsing(
                        store,
                        batches,
                        formats,
                        new FileParsing.Config(10, Duration.ofMinutes(1), Duration.ofHours(1)),
                        IntakeOutcomeObserver.NONE,
                        new JdbcOutboxWriter(),
                        audit,
                        IDS,
                        CLOCK,
                        RUNNER,
                        sources);
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
        readmission =
                new FileReadmission(
                        sources,
                        store,
                        batches,
                        formats,
                        screens,
                        NO_RECEPTION_OUTCOMES,
                        audit,
                        IDS);
        verification = new FileVerification(store, batches, formats, audit, IDS);
        seedRetiringSource();
    }

    /** The throwaway source, seeded as the migrator: retirement stays in this suite (T-5). */
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
    // The database rank: raw SQL as finapp_app, past every domain guard.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("database rank: a never-attested original's readmission is refused ACCEPTED"
            + " unattested, refused the readmitter's and the uploader's attestation, and is"
            + " accepted once a third person attests it")
    void aNeverAttestedOriginalPassesNothingOn() throws SQLException {
        UUID original = uploaded(freshReport());
        rejected(original, FileStatus.RECEIVED, RejectionCode.MALFORMED);
        UUID readmitted = rawReadmission(original, READMITTER);
        rawParsed(readmitted);

        assertThat(store.inheritsAuthentication(application, readmitted)).isFalse();
        assertThat(store.submitters(application, readmitted))
                .containsExactlyInAnyOrder(READMITTER.id(), UPLOADER.id());
        assertThat(store.lockEligibleById(application, readmitted))
                .as("the accept leg's claim reads the trigger's own rule")
                .isEmpty();
        application.rollback();

        assertThatThrownBy(() -> raw(ACCEPT, readmitted))
                .as("never ACCEPTED unattested (INV-SET-07)")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("inherits no authentication");
        application.rollback();
        assertThatThrownBy(() -> raw(ATTEST, READMITTER.id(), readmitted))
                .as("the readmitter is a submitter")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("second person to every submitter");
        application.rollback();
        assertThatThrownBy(() -> raw(ATTEST, UPLOADER.id(), readmitted))
                .as("the original's uploader is a submitter - readmission is no way round"
                        + " the second person")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("second person to every submitter");
        application.rollback();

        assertThat(raw(ATTEST, THIRD.id(), readmitted)).isEqualTo(1);
        application.commit();
        assertThat(store.lockEligibleById(application, readmitted))
                .as("attested by a third person, the claim takes it")
                .isPresent();
        assertThat(raw(ACCEPT, readmitted)).isEqualTo(1);
        assertThat(column(readmitted, "status")).isEqualTo("ACCEPTED");
        application.rollback(); // Proven; the batch-less fixture is never left eligible.
        retired(readmitted);
    }

    @Test
    @DisplayName("database rank: a pulled original passes its authentication on - through an"
            + " intermediate readmission too - and so does an attested upload")
    void pulledAndAttestedOriginalsPassTheirAuthenticationOn() throws SQLException {
        UUID pulledOriginal = pulled(freshReport());
        rejected(pulledOriginal, FileStatus.RECEIVED, RejectionCode.MALFORMED);
        UUID fromPull = rawReadmission(pulledOriginal, READMITTER);
        rawParsed(fromPull);
        assertThat(store.inheritsAuthentication(application, fromPull)).isTrue();
        assertThat(store.submitters(application, fromPull))
                .as("a pull has no deliverer")
                .containsExactly(READMITTER.id());
        assertThat(store.lockEligibleById(application, fromPull)).isPresent();
        assertThat(raw(ACCEPT, fromPull)).as("ACCEPTED unattested").isEqualTo(1);
        application.rollback();

        // The intermediate readmission, itself unattested and rejected, passes the pull on.
        rejected(fromPull, FileStatus.PARSED, RejectionCode.MALFORMED);
        UUID second = rawReadmission(fromPull, SECOND_READMITTER);
        rawParsed(second);
        assertThat(store.inheritsAuthentication(application, second)).isTrue();
        assertThat(raw(ACCEPT, second)).isEqualTo(1);
        application.rollback();
        retired(second);

        UUID attestedOriginal = uploaded(freshReport());
        attestation.attest(application, attestedOriginal, ATTESTER, FLOW);
        application.commit();
        rejected(attestedOriginal, FileStatus.RECEIVED, RejectionCode.MALFORMED);
        UUID fromAttested = rawReadmission(attestedOriginal, READMITTER);
        rawParsed(fromAttested);
        assertThat(store.inheritsAuthentication(application, fromAttested)).isTrue();
        assertThat(raw(ACCEPT, fromAttested)).isEqualTo(1);
        application.rollback();
        retired(fromAttested);
    }

    @Test
    @DisplayName("database rank: a DECLINED original's readmission inherits nothing, though the"
            + " original was attested - it is accepted only once a second person attests it")
    void aDeclinedOriginalPassesNothingOn() throws SQLException {
        UUID original = uploaded(freshReport());
        attestation.attest(application, original, ATTESTER, FLOW);
        application.commit();
        decline.decline(application, original, "the wrong day's report", ATTESTER, FLOW);
        application.commit();
        assertThat(column(original, "rejection_code")).isEqualTo("DECLINED");
        application.rollback();

        UUID readmitted = rawReadmission(original, READMITTER);
        rawParsed(readmitted);
        assertThat(store.inheritsAuthentication(application, readmitted))
                .as("a decline is a judgement against the file: nothing passes on")
                .isFalse();
        assertThat(store.lockEligibleById(application, readmitted)).isEmpty();
        application.rollback();
        assertThatThrownBy(() -> raw(ACCEPT, readmitted))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("inherits no authentication");
        application.rollback();

        assertThat(raw(ATTEST, THIRD.id(), readmitted)).isEqualTo(1);
        application.commit();
        assertThat(raw(ACCEPT, readmitted)).isEqualTo(1);
        application.rollback();
        retired(readmitted);
    }

    @Test
    @DisplayName("database rank: along a chain, every earlier readmitter is a submitter, and a"
            + " DECLINED root passes nothing on through an intermediate readmission")
    void everySubmitterAlongTheChainIsRefused() throws SQLException {
        UUID original = uploaded(freshReport());
        attestation.attest(application, original, ATTESTER, FLOW);
        application.commit();
        decline.decline(application, original, "declined by mistake", UPLOADER, FLOW);
        application.commit();
        UUID first = rawReadmission(original, READMITTER);
        rejected(first, FileStatus.RECEIVED, RejectionCode.MALFORMED);
        UUID second = rawReadmission(first, SECOND_READMITTER);
        rawParsed(second);

        assertThat(store.submitters(application, second))
                .containsExactlyInAnyOrder(
                        SECOND_READMITTER.id(), READMITTER.id(), UPLOADER.id());
        assertThat(store.inheritsAuthentication(application, second)).isFalse();
        application.rollback();
        for (Actor submitter : List.of(SECOND_READMITTER, READMITTER, UPLOADER)) {
            assertThatThrownBy(() -> raw(ATTEST, submitter.id(), second))
                    .as("%s submitted these bytes", submitter.id())
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("second person to every submitter");
            application.rollback();
        }
        assertThatThrownBy(() -> raw(ACCEPT, second))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("inherits no authentication");
        application.rollback();

        assertThat(raw(ATTEST, THIRD.id(), second)).isEqualTo(1);
        application.commit();
        assertThat(raw(ACCEPT, second)).isEqualTo(1);
        application.rollback();
        retired(second);
    }

    // -----------------------------------------------------------------
    // The domain: FileReadmission.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("readmission refuses a file still in its machine, a SOURCE_RETIRED rejection,"
            + " an unknown file and an unreasoned act - writing nothing")
    void readmissionRefusesWhatItCannotRecover() throws SQLException {
        UUID received = uploaded(freshReport());
        assertThatThrownBy(() -> readmit(received, READMITTER))
                .isInstanceOf(FileReadmission.FileNotRejected.class);
        application.rollback();

        UUID retiredSource = uploaded(freshReport());
        rejected(retiredSource, FileStatus.RECEIVED, RejectionCode.SOURCE_RETIRED);
        assertThatThrownBy(() -> readmit(retiredSource, READMITTER))
                .as("a re-opened source is a NEW source; its re-issue uses its own door")
                .isInstanceOf(FileReadmission.FileNotRejected.class);
        application.rollback();

        assertThatThrownBy(() -> readmit(UUID.randomUUID(), READMITTER))
                .isInstanceOf(FileAttestation.SettlementFileNotFound.class);
        application.rollback();

        UUID original = uploaded(freshReport());
        rejected(original, FileStatus.RECEIVED, RejectionCode.MALFORMED);
        assertThatThrownBy(
                        () ->
                                readmission.readmit(
                                        application, original, READMITTER, " ", NOW, FLOW))
                .isInstanceOf(FileReadmission.ReasonRequired.class);
        application.rollback();
        assertThat(readmissionsOf(received) + readmissionsOf(retiredSource)
                        + readmissionsOf(original))
                .as("every refusal wrote nothing")
                .isZero();
    }

    @Test
    @DisplayName("a retired SOURCE's readmission is refused settlement.SourceRetired with"
            + " nothing written - no new file, no receipt, no audit record (the Phase 8 -> 9"
            + " transition, T-5): a retired source's door is shut for its readmissions too")
    void aRetiredSourcesReadmissionWritesNothing() throws SQLException {
        byte[] content = freshReport();
        FileReception.Result received =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                RETIRING_SOURCE,
                                DeliveryChannel.UPLOAD,
                                content,
                                Optional.empty(),
                                UPLOADER,
                                TestAction.RECEIVED,
                                FLOW));
        application.commit();
        UUID original = ((FileReception.Result.New) received).fileId();
        rejected(original, FileStatus.RECEIVED, RejectionCode.MALFORMED);

        assertThat(raw(
                        "UPDATE settlement.source SET status = 'RETIRED' WHERE id = ?",
                        RETIRING_SOURCE_ID))
                .isEqualTo(1);
        application.commit();

        long filesBefore = count("SELECT count(*) FROM settlement.file");
        long receiptsBefore = count("SELECT count(*) FROM settlement.file_receipt");
        long auditsBefore = count("SELECT count(*) FROM platform.audit_record");
        application.rollback();

        assertThatThrownBy(() -> readmit(original, READMITTER))
                .isInstanceOf(FileReception.SettlementSourceRetired.class)
                .hasMessageContaining("settlement.SourceRetired")
                .hasMessageContaining(RETIRING_SOURCE);
        application.rollback();

        assertThat(readmissionsOf(original)).isZero();
        assertThat(count("SELECT count(*) FROM settlement.file")).isEqualTo(filesBefore);
        assertThat(count("SELECT count(*) FROM settlement.file_receipt"))
                .isEqualTo(receiptsBefore);
        assertThat(count("SELECT count(*) FROM platform.audit_record"))
                .as("a shut door is the caller's 4xx to audit, never a half-written act")
                .isEqualTo(auditsBefore);
        application.rollback();
        // The original stays REJECTED - terminal - so no other suite's accept leg claims it.
    }

    @Test
    @DisplayName("a readmission is a new row re-presenting the original's bytes, re-encrypted"
            + " under its own id, with its receipt, reasoned birth and audit - and a second"
            + " readmission of the same original is refused")
    void aReadmissionIsANewRowOverTheOriginalsBytes() throws SQLException {
        byte[] content = freshReport();
        UUID original = uploaded(content);
        rejected(original, FileStatus.RECEIVED, RejectionCode.MALFORMED);
        String reason = "the v1 parser misread the fee column " + letters(6);

        FileReadmission.Result result =
                readmission.readmit(application, original, READMITTER, reason, NOW, FLOW);
        application.commit();

        assertThat(result).isInstanceOf(FileReadmission.Readmitted.class);
        FileReadmission.Readmitted readmitted = (FileReadmission.Readmitted) result;
        assertThat(readmitted.readmitsFileId()).isEqualTo(original);
        assertThat(readmitted.inheritsAuthentication())
                .as("a never-attested upload passes nothing on")
                .isFalse();
        UUID fileId = readmitted.fileId();
        SettlementFileStore.FileRow row = store.fileById(application, fileId).orElseThrow();
        SettlementFileStore.FileRow originalRow =
                store.fileById(application, original).orElseThrow();
        assertThat(row.receivedVia()).isEqualTo(DeliveryChannel.READMISSION);
        assertThat(row.status()).isEqualTo(FileStatus.RECEIVED);
        assertThat(row.readmitsFileId()).contains(original);
        assertThat(row.receivedBy()).contains(READMITTER.id());
        assertThat(row.formatId()).isEqualTo(SettlementFormatId.SIM_PSP_CSV);
        assertThat(row.formatVersion()).isEqualTo(1);
        assertThat(row.contentSha256()).isEqualTo(originalRow.contentSha256());

        byte[] served = store.readContent(application, fileId);
        assertThat(served).as("the original's bytes, verified").isEqualTo(content);
        assertThat(ParsedLine.sha256(served)).isEqualTo(originalRow.contentSha256());
        assertThat(store.readContent(application, original))
                .as("the original is untouched")
                .isEqualTo(content);
        assertThat(chunk(fileId))
                .as("re-encrypted under the readmission's own id, never copied")
                .isNotEqualTo(chunk(original));
        assertThat(originalRow.status()).isEqualTo(FileStatus.REJECTED);
        assertThat(originalRow.rejectionCode()).contains(RejectionCode.MALFORMED);
        application.rollback();

        assertThat(count(
                        "SELECT count(*) FROM settlement.file_receipt WHERE file_id = ?"
                                + " AND outcome = 'NEW' AND channel = 'READMISSION'"
                                + " AND actor = ?",
                        fileId, READMITTER.id()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_event WHERE file_id = ?"
                                + " AND from_status IS NULL AND to_status = 'RECEIVED'"
                                + " AND actor = ? AND reason = ?",
                        fileId, READMITTER.id(), reason))
                .as("born reasoned")
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementFileReadmitted' AND target_id = ?"
                                + " AND reason = ? AND change_summary LIKE ?"
                                + " AND change_summary LIKE ?",
                        fileId.toString(),
                        reason,
                        "%original=" + original + "%",
                        "%sha256=" + HexFormat.of().formatHex(row.contentSha256()) + "%"))
                .isEqualTo(1);

        assertThatThrownBy(() -> readmit(original, SECOND_READMITTER))
                .as("a file is readmitted once")
                .isInstanceOf(FileReadmission.FileAlreadyReadmitted.class);
        application.rollback();
        assertThat(readmissionsOf(original)).isEqualTo(1);
    }

    @Test
    @DisplayName("attestation's domain rank: an inheriting readmission has nothing to attest; a"
            + " DECLINED original's readmission waits for a person distinct from every"
            + " submitter")
    void theAttestationsDomainRankReadsTheChain() throws SQLException {
        UUID pulledOriginal = pulled(freshReport());
        rejected(pulledOriginal, FileStatus.RECEIVED, RejectionCode.UNSUPPORTED_FORMAT);
        FileReadmission.Readmitted fromPull = readmitted(pulledOriginal, READMITTER);
        assertThat(fromPull.inheritsAuthentication()).isTrue();
        assertThatThrownBy(() -> attestation.attest(application, fromPull.fileId(), THIRD, FLOW))
                .isInstanceOf(FileAttestation.SettlementFileNotAttestable.class);
        application.rollback();
        tidied(fromPull.fileId());

        UUID declined = uploaded(freshReport());
        decline.decline(application, declined, "declined in error", UPLOADER, FLOW);
        application.commit();
        FileReadmission.Readmitted fromDecline = readmitted(declined, READMITTER);
        assertThat(fromDecline.inheritsAuthentication()).isFalse();
        for (Actor submitter : List.of(READMITTER, UPLOADER)) {
            assertThatThrownBy(
                            () ->
                                    attestation.attest(
                                            application, fromDecline.fileId(), submitter, FLOW))
                    .as("%s submitted these bytes", submitter.id())
                    .isInstanceOf(FileAttestation.AttestationBySubmitter.class);
            application.rollback();
        }
        FileAttestation.Result attested =
                attestation.attest(application, fromDecline.fileId(), THIRD, FLOW);
        application.commit();
        assertThat(attested).isInstanceOf(FileAttestation.Result.Attested.class);
        tidied(fromDecline.fileId());
    }

    @Test
    @DisplayName("a CONFLICTING_BATCH original is refused while the conflict stands, readmitted"
            + " once the standing batch is declined, and its readmission parses to lines the"
            + " verification MATCHES")
    void theConflictingBatchRecovery() throws SQLException {
        String batchRef = "PSPB-" + letters(12);
        UUID first = uploaded(report(batchRef, "First " + letters(10)));
        settled(first);
        assertThat(column(first, "status")).isEqualTo("PARSED");
        application.rollback();
        UUID second = uploaded(report(batchRef, "Second " + letters(10)));
        settled(second);
        assertThat(column(second, "rejection_code")).isEqualTo("CONFLICTING_BATCH");
        application.rollback();

        assertThatThrownBy(() -> readmit(second, READMITTER))
                .as("readmitted now, it would only be rejected again")
                .isInstanceOf(FileReadmission.ConflictingBatchStands.class);
        application.rollback();

        decline.decline(application, first, "a fabricated batch", UPLOADER, FLOW);
        application.commit();
        FileReadmission.Readmitted recovered = readmitted(second, READMITTER);
        settled(recovered.fileId());
        assertThat(column(recovered.fileId(), "status"))
                .as("the freed identity takes the genuine file")
                .isEqualTo("PARSED");
        application.rollback();

        FileVerification.Verified verified =
                verification.verify(
                        application,
                        recovered.fileId(),
                        INVESTIGATOR,
                        "re-parse check after readmission",
                        NOW,
                        FLOW);
        application.commit();
        assertThat(verified.verdict()).isEqualTo("MATCHES");
        assertThat(verified.linesCompared()).isEqualTo(10);
        assertThat(verified.firstDifferingLine()).isEmpty();
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementFileVerified' AND target_id = ?"
                                + " AND reason = ? AND outcome = 'SUCCEEDED'",
                        recovered.fileId().toString(),
                        "re-parse check after readmission"))
                .as("the decryption is audited per verification, with its reason")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlement.line WHERE file_id = ?",
                        recovered.fileId()))
                .as("a verification never writes a line")
                .isEqualTo(10);

        FileVerification.Verified rejectedOne =
                verification.verify(application, second, INVESTIGATOR, "why rejected", NOW, FLOW);
        application.commit();
        assertThat(rejectedOne.verdict()).isEqualTo("NOT_PARSED");
        assertThat(rejectedOne.linesCompared()).isZero();
    }

    @Test
    @DisplayName("the verification is under the RECORDED version only, names the first line a"
            + " different parse disagrees on, and refuses an unknown file and an unreasoned act")
    void theVerificationsOtherVerdicts() throws SQLException {
        String batchRef = "PSPB-" + letters(12);
        UUID parsed = uploaded(report(batchRef, "Verified " + letters(10)));
        settled(parsed);
        assertThat(column(parsed, "status")).isEqualTo("PARSED");
        application.rollback();

        FileVerification noFormat =
                new FileVerification(store, batches, Map.of(), new JdbcAuditWriter(), IDS);
        assertThat(noFormat.verify(application, parsed, INVESTIGATOR, "no v1", NOW, FLOW)
                        .verdict())
                .isEqualTo("FORMAT_VERSION_UNAVAILABLE");
        application.rollback();

        // A "v1" that parses other bytes: the first record's descriptor differs, so line one's
        // raw digest does.
        byte[] other = report(batchRef, "Different " + letters(10));
        SettlementFormat disagreeing =
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
                        return SimPspCsvFormat.INSTANCE.parse(other);
                    }
                };
        FileVerification.Verified differs =
                new FileVerification(
                                store,
                                batches,
                                Map.of(SettlementFormatId.SIM_PSP_CSV, disagreeing),
                                new JdbcAuditWriter(),
                                IDS)
                        .verify(application, parsed, INVESTIGATOR, "a drifted parser", NOW, FLOW);
        application.rollback();
        assertThat(differs.verdict()).isEqualTo("DIFFERS");
        assertThat(differs.linesCompared()).isEqualTo(1);
        assertThat(differs.firstDifferingLine()).isPresent();

        UUID guessed = UUID.randomUUID();
        assertThatThrownBy(
                        () -> verification.verify(application, guessed, INVESTIGATOR, "guess",
                                NOW, FLOW))
                .as("a guessed id records nothing")
                .isInstanceOf(FileAttestation.SettlementFileNotFound.class);
        application.rollback();
        assertThatThrownBy(
                        () -> verification.verify(application, parsed, INVESTIGATOR, "",
                                NOW, FLOW))
                .isInstanceOf(FileReadmission.ReasonRequired.class);
        application.rollback();
    }

    @Test
    @DisplayName("a tampered file's verification compares nothing and is on the record as a"
            + " FAILED access with its reason")
    void aTamperedFilesVerificationIsAuditedAsFailed() throws Exception {
        UUID parsed = uploaded(freshReport());
        settled(parsed);
        try (Connection root = DatabaseRoles.bootstrap();
                Statement raw = root.createStatement()) {
            root.setAutoCommit(false);
            raw.execute("ALTER TABLE settlement.file_chunk DISABLE TRIGGER"
                    + " file_chunk_is_append_only");
            try {
                raw.execute("UPDATE settlement.file_chunk SET ciphertext = overlay(ciphertext"
                        + " PLACING '\\x00'::bytea FROM 1 FOR 1)"
                        + " WHERE file_id = '" + parsed + "'");
            } finally {
                raw.execute("ALTER TABLE settlement.file_chunk ENABLE TRIGGER"
                        + " file_chunk_is_append_only");
            }
            root.commit();
        }

        FileVerification.Verified verified =
                verification.verify(
                        application, parsed, INVESTIGATOR, "break B-77 under review", NOW, FLOW);
        application.commit();
        assertThat(verified.verdict()).isEqualTo("CORRUPT");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementFileVerified' AND target_id = ?"
                                + " AND outcome = 'FAILED' AND reason = ?",
                        parsed.toString(),
                        "break B-77 under review"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("bytes the current screen refuses refuse the readmission as a RESULT: the"
            + " metadata row (channel READMISSION) and its audit commit, never the value, and no"
            + " readmission is stored")
    void theCurrentScreenRefusesTheReadmission() throws SQLException {
        String card = "4111111111111111";
        byte[] content = report("PSPB-" + letters(12), "Card " + card);
        FileReception.Result landed =
                lenientReception.receive(
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
        UUID original = ((FileReception.Result.New) landed).fileId();
        rejected(original, FileStatus.RECEIVED, RejectionCode.MALFORMED);
        String sha = HexFormat.of().formatHex(ParsedLine.sha256(content));

        FileReadmission.Result result =
                readmission.readmit(
                        application, original, READMITTER, "recover the file", NOW, FLOW);
        application.commit();

        assertThat(result).isInstanceOf(FileReadmission.Refused.class);
        FileReadmission.Refused refused = (FileReadmission.Refused) result;
        assertThat(refused.reason()).isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
        assertThat(refused.fieldName()).contains("descriptor");
        assertThat(count(
                        "SELECT count(*) FROM settlement.refused_delivery"
                                + " WHERE content_sha256 = decode(?, 'hex')"
                                + " AND channel = 'READMISSION' AND actor = ?"
                                + " AND reason = 'PRIMARY_ACCOUNT_NUMBER'",
                        sha, READMITTER.id()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementDeliveryRefused'"
                                + " AND change_summary LIKE ? AND change_summary LIKE ?"
                                + " AND change_summary NOT LIKE ?",
                        "%channel=READMISSION%",
                        "%sha256=" + sha + "%",
                        "%" + card + "%"))
                .isEqualTo(1);
        assertThat(readmissionsOf(original)).as("the original stands alone").isZero();
        assertThat(column(original, "status")).isEqualTo("REJECTED");
        application.rollback();
    }

    @Test
    @DisplayName("ten instances readmitting one original at once: one readmission, nine"
            + " refusals - the row lock serialises, the unique arbitrates")
    void tenRacingReadmissionsLandOne() throws Exception {
        UUID original = uploaded(freshReport());
        rejected(original, FileStatus.RECEIVED, RejectionCode.CONTROL_TOTAL_MISMATCH);

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<FileReadmission.Result> landed = new ConcurrentLinkedQueue<>();
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
                                            landed.add(
                                                    readmission.readmit(
                                                            instance.connection(),
                                                            original,
                                                            racer,
                                                            "raced recovery",
                                                            NOW,
                                                            FLOW));
                                            instance.commit();
                                        } catch (FileReadmission.FileAlreadyReadmitted loser) {
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
        assertThat(landed)
                .as("exactly one racer readmits")
                .hasSize(1)
                .allMatch(FileReadmission.Readmitted.class::isInstance);
        assertThat(refused).hasSize(racers - 1);
        assertThat(readmissionsOf(original)).isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementFileReadmitted'"
                                + " AND change_summary LIKE ?",
                        "%original=" + original + "%"))
                .as("one readmission, one record")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        ReadmissionDatabaseTest.class.getResourceAsStream(
                                "/format/simpsp/golden-v1.csv"))) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The golden file re-keyed and re-worded, so each test owns a distinct identity. */
    private static byte[] report(String batchRef, String marker) {
        String text = new String(golden(), StandardCharsets.UTF_8)
                .replace("PSPB-2026-09-25-01", batchRef)
                .replace("Desk sale", marker);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] freshReport() {
        return report("PSPB-" + letters(12), "Readmit " + letters(12));
    }

    /** Letters only: no digit run the door screen could read as a card number. */
    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOM.nextInt(26)));
        }
        return letters.toString();
    }

    private static UUID uploaded(byte[] content) throws SQLException {
        return delivered(content, DeliveryChannel.UPLOAD, UPLOADER);
    }

    private static UUID pulled(byte[] content) throws SQLException {
        return delivered(content, DeliveryChannel.PULL, Actor.SYSTEM);
    }

    private static UUID delivered(byte[] content, DeliveryChannel channel, Actor by)
            throws SQLException {
        FileReception.Result result =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                SOURCE,
                                channel,
                                content,
                                Optional.empty(),
                                by,
                                TestAction.RECEIVED,
                                FLOW));
        application.commit();
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    /** A verdict recorded as the parse leg would - how an earlier version's mistake looks. */
    private static void rejected(UUID fileId, FileStatus from, RejectionCode code)
            throws SQLException {
        assertThat(store.markRejected(
                        application, fileId, from, code, Optional.of(code.name()), NOW))
                .isTrue();
        application.commit();
    }

    private static FileReadmission.Result readmit(UUID originalFileId, Actor actor) {
        String reason = "recover a wrongly rejected file";
        return readmission.readmit(application, originalFileId, actor, reason, NOW, FLOW);
    }

    private static FileReadmission.Readmitted readmitted(UUID originalFileId, Actor actor)
            throws SQLException {
        FileReadmission.Result result = readmit(originalFileId, actor);
        application.commit();
        assertThat(result).isInstanceOf(FileReadmission.Readmitted.class);
        return (FileReadmission.Readmitted) result;
    }

    /** Sweeps until the file leaves RECEIVED - other suites' files may share the queue. */
    private static void settled(UUID fileId) throws SQLException {
        for (int sweep = 0; sweep < 100; sweep++) {
            parsing.sweep();
            String status = column(fileId, "status");
            application.rollback();
            if (!"RECEIVED".equals(status)) {
                return;
            }
        }
        throw new AssertionError("settlement file " + fileId + " never left RECEIVED");
    }

    /** A readmission inserted raw as finapp_app - the database rank, no domain in the way. */
    private static UUID rawReadmission(UUID originalFileId, Actor readmitter)
            throws SQLException {
        UUID id = UUID.randomUUID();
        assertThat(raw(
                        "INSERT INTO settlement.file (id, source_id, received_via, status,"
                                + " business_date, format_id, format_version, content_sha256,"
                                + " content_length, line_count, key_version, received_by,"
                                + " readmits_file_id, received_at, status_changed_at,"
                                + " correlation_id)"
                                + " SELECT ?, source_id, 'READMISSION', 'RECEIVED',"
                                + " business_date, format_id, format_version, content_sha256,"
                                + " content_length, line_count, key_version, ?, id, now(),"
                                + " now(), 'p8-tsk-022-raw' FROM settlement.file WHERE id = ?",
                        id, readmitter.id(), originalFileId))
                .isEqualTo(1);
        application.commit();
        return id;
    }

    private static void rawParsed(UUID fileId) throws SQLException {
        assertThat(raw(
                        "UPDATE settlement.file SET status = 'PARSED', status_changed_at = now()"
                                + " WHERE id = ? AND status = 'RECEIVED'",
                        fileId))
                .isEqualTo(1);
        application.commit();
    }

    /** A batch-less fixture leaves terminal, so no other suite's accept leg ever claims it. */
    private static void retired(UUID fileId) throws SQLException {
        assertThat(raw(
                        "UPDATE settlement.file SET status = 'REJECTED', rejection_code ="
                                + " 'DECLINED', rejection_detail = 'retired test fixture',"
                                + " status_changed_at = now() WHERE id = ?",
                        fileId))
                .isEqualTo(1);
        application.commit();
    }

    /** A domain-made readmission declined, so no other suite's accept leg takes it. */
    private static void tidied(UUID fileId) throws SQLException {
        decline.decline(application, fileId, "test fixture, not for acceptance", THIRD, FLOW);
        application.commit();
    }

    private static int raw(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement.executeUpdate();
        }
    }

    /** One column as text, in the caller's open transaction. */
    private static String column(UUID fileId, String column) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT " + column + "::text FROM settlement.file WHERE id = ?")) {
            read.setObject(1, fileId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static byte[] chunk(UUID fileId) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT ciphertext FROM settlement.file_chunk"
                                + " WHERE file_id = ? AND seq = 0")) {
            read.setObject(1, fileId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getBytes(1);
            }
        }
    }

    private static long readmissionsOf(UUID originalFileId) throws SQLException {
        return count(
                "SELECT count(*) FROM settlement.file WHERE readmits_file_id = ?",
                originalFileId);
    }

    /** A count, read and then released - callers read only after they commit. */
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
}
