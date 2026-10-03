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
import com.finapp.settlement.format.SettlementFormat;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The person-written settlement reasons at the domain rank (the Phase 8 → 9 transition, the
 * audit's {@code SEC-04} and the recorded debt row "Person-written reasons reach the database
 * unscreened"; {@code INV-PAY-02}, {@code INV-RAIL-03}): the readmission's, the verification's,
 * the decline's and the content read's reason each refuse a card number written with spaces or
 * dashes and an account identifier in its printed groups - before any lock or read - writing no
 * file, no history, no audit record, and echoing nothing. The database rank, for any other
 * writer, is {@code SettlementV012MigrationTest}.
 *
 * <p>Each fixture leaves terminal, so no other suite's parse or accept leg ever claims it.
 */
@Tag("database")
@DisplayName("the person-written settlement reasons refuse instrument shapes (SEC-04)")
class PersonWrittenReasonsDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = CLOCK.instant();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Actor UPLOADER = new Actor("op-reason-uploader", ActorType.EMPLOYEE);
    private static final Actor PERSON = new Actor("op-reason-writer", ActorType.EMPLOYEE);
    private static final Correlation FLOW =
            Correlation.startingWith(CorrelationId.of("phase-8-9-sec-04-reasons"));
    private static final byte[] KEY = new byte[32];
    private static final String SOURCE = "simulated-psp.settlement";

    /** SEC-03's two card spellings and the printed account identifier. */
    private static final List<String> NEEDLES =
            List.of(
                    "the customer read 4111 1111 1111 1111 to the desk",
                    "the customer read 4111-1111-1111-1111 to the desk",
                    "the merchant asks for GB82 WEST 1234 5698 7654 32 instead");

    private static Connection application;
    private static JdbcSettlementFileStore store;
    private static FileReception<Connection> reception;
    private static FileDecline decline;
    private static FileReadmission readmission;
    private static FileVerification verification;
    private static EvidenceContentReads<Connection> contentReads;

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
        JdbcSettlementBatchStore batches = new JdbcSettlementBatchStore(IDS);
        AuditWriter<Connection> audit = new JdbcAuditWriter();
        Map<SettlementFormatId, SettlementFormat> formats =
                Map.of(SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE);
        Map<SettlementFormatId, DeliveryScreen> screens =
                Map.of(SettlementFormatId.SIM_PSP_CSV, SimPspCsvFormat.INSTANCE::screen);
        reception =
                new FileReception<>(
                        sources, store, screens, NO_RECEPTION_OUTCOMES, audit, IDS, CLOCK);
        decline =
                new FileDecline(
                        store, batches, IntakeOutcomeObserver.NONE, new JdbcOutboxWriter(),
                        audit, IDS, CLOCK);
        readmission =
                new FileReadmission(
                        sources, store, batches, formats, screens, NO_RECEPTION_OUTCOMES, audit,
                        IDS);
        verification = new FileVerification(store, batches, formats, audit, IDS);
        contentReads = new EvidenceContentReads<>(store, audit, IDS, CLOCK);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    @Test
    @DisplayName("the readmission, the verification, the decline and the content read each refuse"
            + " a spaced card number, a dashed one and a printed account identifier - before any"
            + " lock, writing nothing, the message naming the rule and never the value")
    void everyPersonWrittenReasonRefusesTheNeedles() throws SQLException {
        UUID original = uploaded();
        assertThat(store.markRejected(application, original, FileStatus.RECEIVED,
                        RejectionCode.MALFORMED, Optional.of("MALFORMED"), NOW))
                .isTrue();
        application.commit();
        UUID received = uploaded();
        long historyBefore = history(original, received);
        long auditsBefore = audits(original, received);

        for (String needle : NEEDLES) {
            refused(() -> readmission.readmit(application, original, PERSON, needle, NOW, FLOW));
            refused(() -> verification.verify(application, received, PERSON, needle, NOW, FLOW));
            refused(() -> decline.decline(application, received, needle, PERSON, FLOW));
            refused(() -> contentReads.read(application, received, PERSON, needle, FLOW));
        }

        assertThat(count("SELECT count(*) FROM settlement.file WHERE readmits_file_id = ?",
                        original))
                .as("no readmission row")
                .isZero();
        assertThat(status(received)).as("the decline moved nothing").isEqualTo("RECEIVED");
        assertThat(history(original, received)).as("no history row").isEqualTo(historyBefore);
        assertThat(audits(original, received))
                .as("no audit record: not a readmission, verification, decline or read")
                .isEqualTo(auditsBefore);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE reason LIKE ? OR"
                        + " reason LIKE ? OR reason LIKE ?", "%4111%", "%1111-1111%",
                        "%WEST 1234%"))
                .as("no reason holding a needle rests in the audit trail")
                .isZero();

        // The same doors admit a clean reason: the rule refuses the shape, not the act.
        assertThat(contentReads.read(application, received, PERSON,
                        "break B-42 under review, ticket 7731", FLOW))
                .isInstanceOf(EvidenceContentReads.Outcome.Served.class);
        application.commit();
        decline.decline(application, received, "test fixture, not for acceptance", PERSON, FLOW);
        application.commit();
        assertThat(status(received)).isEqualTo("REJECTED");
    }

    private static void refused(ThrowingCallable door) throws SQLException {
        try {
            assertThatThrownBy(door)
                    .isInstanceOf(FileReadmission.ReasonRequired.class)
                    .hasMessageContaining("must not hold a card-number or bank-account shape")
                    .hasMessageNotContaining("4111")
                    .hasMessageNotContaining("1111")
                    .hasMessageNotContaining("WEST");
        } finally {
            application.rollback();
        }
    }

    // ----------------------------------------------------------------- fixtures

    /** A clean PSP report, letters-only references, uploaded through the real door. */
    private static UUID uploaded() throws SQLException {
        String marker = letters(12);
        byte[] report =
                ("H,SIM_PSP_CSV,1,PSPB-" + marker + ",EUR,2026-10-01\n"
                                + "D,1,SALE,100.00,1.75,EUR,2026-10-01,,,PSP-CAP-" + marker
                                + ",,,,Reason screen fixture\n"
                                + "T,1,98.25,PSP-REM-" + (100000 + RANDOM.nextInt(899999)) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
        FileReception.Result result =
                reception.receive(
                        application,
                        new FileReception.Delivery(
                                SOURCE, DeliveryChannel.UPLOAD, report, Optional.empty(),
                                UPLOADER, TestAction.RECEIVED, FLOW));
        application.commit();
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOM.nextInt(26)));
        }
        return letters.toString();
    }

    private static long history(UUID first, UUID second) throws SQLException {
        return count("SELECT count(*) FROM settlement.file_event WHERE file_id IN (?, ?)",
                first, second);
    }

    private static long audits(UUID first, UUID second) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE target_id IN (?, ?)",
                first.toString(), second.toString());
    }

    private static String status(UUID fileId) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT status FROM settlement.file WHERE id = ?")) {
            read.setObject(1, fileId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                String status = row.getString(1);
                application.rollback();
                return status;
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
}
