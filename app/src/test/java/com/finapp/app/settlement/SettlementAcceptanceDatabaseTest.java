package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.OpeningPosition;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.TransactionRunner;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance over the REAL composition (`P8-TSK-009`): the port joined to reconciliation's
 * writers in `app`, the run born pinned to the ACTIVE rule set, one {@code PENDING} item per
 * canonical line with its typed keys, the {@code REMITTANCE} expectation of |N| dated
 * {@code value date + funding_lag_days}, and the position identity holding WITH EVERY ITEM
 * PENDING — balance = open remainders − open item remainders — while the completeness
 * verifier knows every recognition entry ({@code INV-REC-06} extended, ADR-0067 §9).
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest
@DisplayName("acceptance's intake and the extended proof (P8-TSK-009)")
class SettlementAcceptanceDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String SOURCE = "simulated-psp.settlement";

    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private PositionProof proof;
    @Autowired private OpeningPosition openingPosition;
    @Autowired private TransactionRunner settlementTransactionRunner;

    // -----------------------------------------------------------------

    private UUID pulled(String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            FileReception.Result result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    reception.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    SOURCE,
                                                    DeliveryChannel.PULL,
                                                    content.getBytes(StandardCharsets.UTF_8),
                                                    Optional.empty(),
                                                    Actor.SYSTEM,
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static String scalar(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private PositionProof.Report sweep() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return proof.sweep(app);
            } finally {
                app.rollback();
            }
        }
    }

    private UUID acceptedBatch(String batchRef, String report) throws SQLException {
        UUID fileId = pulled(report);
        parsing.sweep();
        acceptance.sweep();
        assertThat(scalar("SELECT status FROM settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED");
        return UUID.fromString(
                scalar("SELECT id FROM settlement.batch WHERE file_id = ?", fileId));
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the intake is whole: the run pinned to the ACTIVE rule set, one PENDING"
            + " item per canonical line with its typed keys, the REMITTANCE of |N| dated"
            + " value date + funding lag, and the identity holds with every item pending")
    void theIntakeIsWholeAndTheIdentityHolds() throws SQLException {
        String marker = suffix();
        String batchRef = "PSPB-APP-" + marker;
        UUID batchId =
                acceptedBatch(
                        batchRef,
                        "H,SIM_PSP_CSV,1," + batchRef + ",EUR,2026-09-25\n"
                                + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,,PSP-CAP-" + marker
                                + ",44400012345678901,,ORD-" + marker + ",Desk sale\n"
                                + "D,2,REFUND,-40.25,,EUR,2026-09-25,,,PSP-REF-" + marker
                                + ",,,,Refund\n"
                                + "T,2,58.00,PSP-REM-11" + digits(marker) + "\n");

        // The run: kind BATCH, pinned to the source's ACTIVE rule set, its sequence's own.
        String runId =
                scalar("SELECT id FROM reconciliation.reconciliation_batch WHERE batch_id = ?",
                        batchId);
        assertThat(runId).isNotNull();
        assertThat(scalar(
                        "SELECT r.status || ':' || r.kind || ':' || r.item_count::text"
                                + " FROM reconciliation.reconciliation_batch r WHERE id = ?::uuid",
                        runId))
                .isEqualTo("OPEN:BATCH:3");
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.reconciliation_batch r"
                                + " JOIN reconciliation.rule_set s ON s.id = r.rule_set_id"
                                + " WHERE r.id = ?::uuid AND s.status = 'ACTIVE'",
                        runId))
                .as("the deciding rule set is pinned at birth (INV-HIST-04)")
                .isEqualTo(1);

        // The items: one per canonical line (the fee split's third included), PENDING,
        // disposition zero, keys copied.
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.external_item WHERE run_id ="
                                + " ?::uuid AND status = 'PENDING' AND allocated_minor = 0"
                                + " AND parked_minor = 0 AND offset_minor = 0",
                        runId))
                .isEqualTo(3);
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.external_item_key k"
                                + " JOIN reconciliation.external_item i ON i.id = k.item_id"
                                + " WHERE i.run_id = ?::uuid AND k.key_kind ="
                                + " 'PSP_CAPTURE_REF' AND k.key_value = ?",
                        runId,
                        "PSP-CAP-" + marker))
                .isEqualTo(1);

        // The remittance: |N| = 58.00 INBOUND, keyed by the trailer's reference, expected
        // value date + funding_lag_days (seeded 2), pinned to the same rule set, NO entry.
        assertThat(scalar(
                        "SELECT direction || ':' || amount_minor::text || ':' || expected_by::text"
                                + " || ':' || (journal_entry_id IS NULL)::text"
                                + " FROM reconciliation.expectation"
                                + " WHERE kind = 'REMITTANCE' AND operation_ref = ?",
                        batchId.toString()))
                .isEqualTo("INBOUND:5800:2026-09-27:true");
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation_key k"
                                + " JOIN reconciliation.expectation e ON e.id = k.expectation_id"
                                + " WHERE e.kind = 'REMITTANCE' AND e.operation_ref = ?"
                                + " AND k.key_kind = 'REMITTANCE_REF'",
                        batchId.toString()))
                .isEqualTo(1);

        // The identity, with every item pending: balance = remainders - items, and the
        // completeness verifier knows the recognition entry (unattributed 0).
        PositionProof.Report report = sweep();
        for (PositionProof.PositionVerdict verdict : report.verdicts()) {
            assertThat(verdict.explained())
                    .as("%s %s: %s = %s - %s (INV-REC-06 extended at acceptance)",
                            verdict.purpose(), verdict.currency(), verdict.ledgerBalance(),
                            verdict.openRemainders(), verdict.openItems())
                    .isTrue();
        }
        assertThat(report.unattributedByPurpose()
                        .getOrDefault(AccountPurpose.SETTLEMENT_CLEARING, 0L))
                .isZero();
        assertThat(report.unattributedByPurpose()
                        .getOrDefault(AccountPurpose.PROCESSING_COSTS, 0L))
                .as("the recognition entry's expense line is known too (ADR-0067 §9)")
                .isZero();
    }

    @Test
    @DisplayName("a zero net opens no remittance, and a zero fee omits the entry honestly -"
            + " each accepted whole")
    void zeroNetAndZeroFee() throws SQLException {
        // Zero NET: T_in - T_out - F = 101.75 - 100.00 - 1.75 = 0; the fee still posts.
        String zeroNet = suffix();
        UUID zeroNetBatch =
                acceptedBatch(
                        "PSPB-ZN-" + zeroNet,
                        "H,SIM_PSP_CSV,1,PSPB-ZN-" + zeroNet + ",EUR,2026-09-25\n"
                                + "D,1,SALE,101.75,1.75,EUR,2026-09-25,,,PSP-CAP-" + zeroNet
                                + ",,,,Sale\n"
                                + "D,2,REFUND,-100.00,,EUR,2026-09-25,,,PSP-REF-" + zeroNet
                                + ",,,,Refund\n"
                                + "T,2,0.00,PSP-REM-12" + digits(zeroNet) + "\n");
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation WHERE kind ="
                                + " 'REMITTANCE' AND operation_ref = ?",
                        zeroNetBatch.toString()))
                .as("nothing for the bank to discharge - no remittance (the design's own"
                        + " confirmation)")
                .isZero();
        assertThat(scalar(
                        "SELECT (journal_entry_id IS NOT NULL)::text || ':' || posting_omitted::text"
                                + " FROM settlement.batch WHERE id = ?",
                        zeroNetBatch))
                .isEqualTo("true:false");

        // Zero FEE: the entry omitted HONESTLY; the remittance still opens.
        String zeroFee = suffix();
        UUID zeroFeeBatch =
                acceptedBatch(
                        "PSPB-ZF-" + zeroFee,
                        "H,SIM_PSP_CSV,1,PSPB-ZF-" + zeroFee + ",EUR,2026-09-25\n"
                                + "D,1,SALE,100.00,,EUR,2026-09-25,,,PSP-CAP-" + zeroFee
                                + ",,,,Sale\n"
                                + "T,1,100.00,PSP-REM-13" + digits(zeroFee) + "\n");
        assertThat(scalar(
                        "SELECT (journal_entry_id IS NULL)::text || ':' || posting_omitted::text"
                                + " FROM settlement.batch WHERE id = ?",
                        zeroFeeBatch))
                .isEqualTo("true:true");
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation WHERE kind ="
                                + " 'REMITTANCE' AND operation_ref = ?",
                        zeroFeeBatch.toString()))
                .isEqualTo(1);

        PositionProof.Report report = sweep();
        for (PositionProof.PositionVerdict verdict : report.verdicts()) {
            assertThat(verdict.explained()).as("%s %s", verdict.purpose(), verdict.currency())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the register rebuilds from the books alone: a remittance is settlement"
            + " evidence's promise, and the backfill re-derives it from the accepted batch"
            + " row through the live intake's own opener (ADR-0067 §8 after P8-TSK-009)")
    void theRegisterRebuildsFromTheBooksAlone() throws SQLException {
        String marker = suffix();
        UUID batchId =
                acceptedBatch(
                        "PSPB-RB-" + marker,
                        "H,SIM_PSP_CSV,1,PSPB-RB-" + marker + ",EUR,2026-09-25\n"
                                + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,,PSP-CAP-" + marker
                                + ",,,,Sale\n"
                                + "T,1,98.25,PSP-REM-14" + digits(marker) + "\n");

        // The remittance's substance, before the register is emptied.
        String substanceSql =
                "SELECT kind || ':' || posting_key || ':' || source_id::text || ':'"
                        + " || position_purpose || ':' || ledger_account_id::text || ':'"
                        + " || direction || ':' || amount_minor::text || ':' || currency"
                        + " || ':' || scale::text || ':' || posting_date::text || ':'"
                        + " || expected_by::text || ':' || rule_set_id::text || ':' || status"
                        + " FROM reconciliation.expectation"
                        + " WHERE kind = 'REMITTANCE' AND operation_ref = ?";
        String before = scalar(substanceSql, batchId.toString());
        assertThat(before).isNotNull();

        // The register emptied as the platform's own root (the storm's block: history's
        // shape, not a production path).
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            execute(root, "ALTER TABLE reconciliation.expectation_key DISABLE TRIGGER"
                    + " expectation_key_is_append_only");
            execute(root, "ALTER TABLE reconciliation.expectation_event DISABLE TRIGGER"
                    + " expectation_event_is_append_only");
            execute(root, "ALTER TABLE reconciliation.expectation DISABLE TRIGGER"
                    + " expectation_is_never_deleted");
            try {
                // Since P8-TSK-011 an expectation history names - an allocation or a
                // candidate snapshot - is held by those rows' foreign keys, exactly the
                // immutability the records claim; the emptied-register equivalence is
                // judged over the rest (another suite's matched fixtures may stand in
                // the shared container, and this suite's remittance is never allocated).
                String unheld = " NOT IN (SELECT expectation_id FROM"
                        + " reconciliation.allocation UNION SELECT expectation_id FROM"
                        + " reconciliation.match_candidate)";
                execute(root, "DELETE FROM reconciliation.expectation_key WHERE"
                        + " expectation_id" + unheld);
                execute(root, "DELETE FROM reconciliation.expectation_event WHERE"
                        + " expectation_id" + unheld);
                execute(root, "DELETE FROM reconciliation.expectation WHERE id" + unheld);
            } finally {
                execute(root, "ALTER TABLE reconciliation.expectation ENABLE TRIGGER"
                        + " expectation_is_never_deleted");
                execute(root, "ALTER TABLE reconciliation.expectation_event ENABLE TRIGGER"
                        + " expectation_event_is_append_only");
                execute(root, "ALTER TABLE reconciliation.expectation_key ENABLE TRIGGER"
                        + " expectation_key_is_append_only");
            }
            root.commit();
        }
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation WHERE kind ="
                                + " 'REMITTANCE' AND operation_ref = ?",
                        batchId.toString()))
                .isZero();

        // The backfill, through the domain (the door's authorization is the opening
        // suite's own proof; here the claim is re-derivability).
        OpeningPosition.Adopted adopted;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            adopted = openingPosition.record(
                    "rebuild-" + marker,
                    "rebuilding the register from the books alone (P8-TSK-009)");
        }
        assertThat(adopted.remittances())
                .as("the walk re-derived this world's accepted batches at least")
                .isGreaterThanOrEqualTo(1);

        // Byte-for-byte substance: net, dates, account, rule set and reference all come
        // from the batch row, so the re-derived row IS the live row.
        assertThat(scalar(substanceSql, batchId.toString()))
                .as("the re-derived remittance is the live opener's own row")
                .isEqualTo(before);
        assertThat(count(
                        "SELECT count(*) FROM reconciliation.expectation_key k"
                                + " JOIN reconciliation.expectation e ON e.id = k.expectation_id"
                                + " WHERE e.kind = 'REMITTANCE' AND e.operation_ref = ?"
                                + " AND k.key_kind = 'REMITTANCE_REF'",
                        batchId.toString()))
                .isEqualTo(1);

        // And the identity holds again, every verdict - section 8's recovery claim with
        // settlement evidence in the world.
        PositionProof.Report report = sweep();
        for (PositionProof.PositionVerdict verdict : report.verdicts()) {
            assertThat(verdict.explained())
                    .as("rebuilt: %s %s explained", verdict.purpose(), verdict.currency())
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)
                .replace('-', 'X');
    }

    /** The remittance shape wants digits; a marker's letters become their code points. */
    private static String digits(String marker) {
        StringBuilder digits = new StringBuilder();
        for (char c : marker.toCharArray()) {
            digits.append(Character.getNumericValue(c) % 10);
        }
        return digits.substring(0, Math.min(8, digits.length()));
    }
}
