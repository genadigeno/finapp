package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.Suspense;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Parks over the REAL composition (`P8-TSK-010`, ADR-0070): a real accepted batch's item
 * parked with its break through the wired beans, and every verdict holding at once — the
 * position identity across the park ({@code INV-REC-06}: the parked remainder leaves both
 * of its sides), the suspense identity with the named Phase 7 term (exact whether or not
 * other suites' parkings share the container), the park entry among the completeness
 * verifier's known entries, and ownership's detector flipping on a planted defect —
 * observed in the plant's own uncommitted transaction, so the shared container never
 * inherits it (the `P8-TSK-007` precedent).
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest
@DisplayName("suspense over the composed wiring (P8-TSK-010)")
class ReconciliationSuspenseDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String SOURCE = "simulated-psp.settlement";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate DECIDED_ON = LocalDate.parse("2026-09-29");

    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private PositionProof proof;
    @Autowired private BreakRegister breakRegister;
    @Autowired private Suspense suspense;
    @Autowired private com.finapp.reconciliation.InternalReferenceLookup lookup;
    @Autowired private TransactionRunner settlementTransactionRunner;

    @Test
    @DisplayName("the wired lookup answers over the live schema: a reference nothing"
            + " internal names is UNKNOWN on every finder's path - never an error, never"
            + " a guess (the state mappings' exhaustive proof is the hermetic suite's)")
    void theWiredLookupAnswersUnknownForTheUnnamed() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            String nobody = "NOBODY-" + suffix();
            com.finapp.reconciliation.InternalReferenceLookup.InternalReference answer =
                    lookup.classify(
                            app,
                            new com.finapp.reconciliation.InternalReferenceLookup
                                    .LookupSubject(
                                    Optional.of("instant-sim"),
                                    java.util.Map.of(
                                            com.finapp.reconciliation.KeyKind
                                                    .PSP_CAPTURE_REF, nobody,
                                            com.finapp.reconciliation.KeyKind
                                                    .PSP_REFUND_REF, nobody,
                                            com.finapp.reconciliation.KeyKind.OUR_REF,
                                            nobody,
                                            com.finapp.reconciliation.KeyKind
                                                    .DISPUTE_CB_REF, nobody,
                                            com.finapp.reconciliation.KeyKind.SCHEME_REF,
                                            nobody,
                                            com.finapp.reconciliation.KeyKind
                                                    .END_TO_END_REF, nobody,
                                            com.finapp.reconciliation.KeyKind
                                                    .PAYOUT_PROVIDER_REF, nobody)));
            assertThat(answer.classification())
                    .isEqualTo(com.finapp.reconciliation.InternalClassification.UNKNOWN);
            app.rollback();
        }
    }

    @Test
    @DisplayName("a park through the wired beans holds every verdict at once: the position"
            + " identity, the suspense identity with the Phase 7 term, the known lines and"
            + " the ownership reading")
    void aParkHoldsEveryProof() throws SQLException {
        PositionProof.Report before = sweep();
        long unattributedClearingBefore =
                before.unattributedByPurpose()
                        .getOrDefault(AccountPurpose.SETTLEMENT_CLEARING, 0L);
        long unattributedSuspenseBefore =
                before.unattributedByPurpose()
                        .getOrDefault(AccountPurpose.SUSPENSE_UNMATCHED, 0L);

        String marker = suffix();
        UUID batchId = acceptedBatch(marker);
        UUID itemId =
                UUID.fromString(
                        scalar("SELECT i.id::text FROM reconciliation.external_item i"
                                + " JOIN reconciliation.reconciliation_batch r ON r.id ="
                                + " i.run_id WHERE r.batch_id = ? AND i.line_type ="
                                + " 'CAPTURE'", batchId));
        UUID sourceId =
                UUID.fromString(
                        scalar("SELECT source_id::text FROM settlement.batch WHERE id = ?",
                                batchId));
        UUID position =
                UUID.fromString(
                        scalar("SELECT id::text FROM ledger.ledger_account WHERE purpose ="
                                + " 'SETTLEMENT_CLEARING' AND currency = 'EUR'"));

        // The deciding transaction: the break raised and the item parked together
        // (INV-REC-09) - the grace leg's shape, driven directly until P8-TSK-013.
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            settlementTransactionRunner.inTransaction(
                    uow -> {
                        UUID ruleSet =
                                UUID.fromString(
                                        scalarOn(uow,
                                                "SELECT id::text FROM"
                                                        + " reconciliation.rule_set WHERE"
                                                        + " source_id = ? AND status ="
                                                        + " 'ACTIVE'", sourceId));
                        BreakRegister.Raised raised =
                                breakRegister.raise(
                                        uow,
                                        new BreakRegister.NewBreak(
                                                IDS.next(),
                                                BreakType.UNKNOWN_EXTERNAL,
                                                BreakCause.GRACE_EXPIRED,
                                                BreakRegister.Subject.externalItem(itemId),
                                                sourceId,
                                                ruleSet,
                                                Money.ofPersisted(100_00, EUR, 2),
                                                Optional.of(ExpectationDirection.INBOUND),
                                                Optional.empty(),
                                                Optional.of(InternalClassification.UNKNOWN),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Actor.SYSTEM,
                                                Instant.now(CLOCK),
                                                CorrelationId.generate(IDS)));
                        assertThat(raised.created()).isTrue();
                        Suspense.ParkResult parked =
                                suspense.park(
                                        uow,
                                        new Suspense.ParkCommand(
                                                sourceId,
                                                DECIDED_ON,
                                                List.of(
                                                        new Suspense.ParkedItem(
                                                                itemId,
                                                                raised.breakId(),
                                                                Money.ofPersisted(
                                                                        100_00, EUR, 2),
                                                                position)),
                                                Actor.SYSTEM,
                                                Instant.now(CLOCK),
                                                CorrelationId.generate(IDS)));
                        assertThat(parked.parked()).hasSize(1);
                        return null;
                    });
        }

        assertThat(scalar("SELECT status FROM reconciliation.external_item WHERE id = ?",
                itemId))
                .isEqualTo("PARKED");

        // Across the park, both identities hold over THIS suite's writes - the parked
        // remainder leaves both sides of the position identity, and the suspense identity
        // (ADR-0070 §7, CR-DR = CREDIT - DEBIT + Phase 7) moves by exactly what the park
        // owns. Judged as unchanged residuals: the shared container carries other suites'
        // not-yet-adopted history, which an absolute reading judged here by class order.
        PositionProof.Report report = sweep();
        PositionResiduals.assertUnchanged(before, report, "across a park");
        assertThat(report.unattributedByPurpose()
                        .getOrDefault(AccountPurpose.SETTLEMENT_CLEARING, 0L))
                .as("the park entry's clearing line is a known line (ADR-0067 §9)")
                .isEqualTo(unattributedClearingBefore);
        assertThat(report.unattributedByPurpose()
                        .getOrDefault(AccountPurpose.SUSPENSE_UNMATCHED, 0L))
                .as("the park entry's suspense line is a known line; only Phase 7's"
                        + " parkings stay honestly unattributed until -020")
                .isEqualTo(unattributedSuspenseBefore);
        assertThat(report.suspenseUnowned())
                .as("every unit of parked value has an open owner (INV-REC-09)")
                .isZero();
        assertThat(report.suspenseOpenItems())
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("a break resolved over value it still owns flips suspense.unowned - the"
            + " detector's reading, observed in the plant's own uncommitted transaction")
    void theOwnershipReadingFlipsOnAPlantedDefect() throws SQLException {
        String marker = suffix();
        UUID batchId = acceptedBatch(marker);
        UUID itemId =
                UUID.fromString(
                        scalar("SELECT i.id::text FROM reconciliation.external_item i"
                                + " JOIN reconciliation.reconciliation_batch r ON r.id ="
                                + " i.run_id WHERE r.batch_id = ? AND i.line_type ="
                                + " 'CAPTURE'", batchId));
        UUID sourceId =
                UUID.fromString(
                        scalar("SELECT source_id::text FROM settlement.batch WHERE id = ?",
                                batchId));
        UUID position =
                UUID.fromString(
                        scalar("SELECT id::text FROM ledger.ledger_account WHERE purpose ="
                                + " 'SETTLEMENT_CLEARING' AND currency = 'EUR'"));
        UUID breakId;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            breakId =
                    settlementTransactionRunner.inTransaction(
                            uow -> {
                                UUID ruleSet =
                                        UUID.fromString(
                                                scalarOn(uow,
                                                        "SELECT id::text FROM"
                                                                + " reconciliation.rule_set"
                                                                + " WHERE source_id = ? AND"
                                                                + " status = 'ACTIVE'",
                                                        sourceId));
                                BreakRegister.Raised raised =
                                        breakRegister.raise(
                                                uow,
                                                new BreakRegister.NewBreak(
                                                        IDS.next(),
                                                        BreakType.UNKNOWN_EXTERNAL,
                                                        BreakCause.GRACE_EXPIRED,
                                                        BreakRegister.Subject.externalItem(
                                                                itemId),
                                                        sourceId,
                                                        ruleSet,
                                                        Money.ofPersisted(100_00, EUR, 2),
                                                        Optional.of(
                                                                ExpectationDirection
                                                                        .INBOUND),
                                                        Optional.empty(),
                                                        Optional.of(
                                                                InternalClassification
                                                                        .UNKNOWN),
                                                        Optional.empty(),
                                                        Optional.empty(),
                                                        Optional.empty(),
                                                        Actor.SYSTEM,
                                                        Instant.now(CLOCK),
                                                        CorrelationId.generate(IDS)));
                                suspense.park(
                                        uow,
                                        new Suspense.ParkCommand(
                                                sourceId,
                                                DECIDED_ON,
                                                List.of(
                                                        new Suspense.ParkedItem(
                                                                itemId,
                                                                raised.breakId(),
                                                                Money.ofPersisted(
                                                                        100_00, EUR, 2),
                                                                position)),
                                                Actor.SYSTEM,
                                                Instant.now(CLOCK),
                                                CorrelationId.generate(IDS)));
                                return raised.breakId();
                            });
        }

        // The plant, never committed: the legal-but-unproduced OPEN -> RESOLVED edge over
        // value the break still owns, observed on the planter's own connection.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (PreparedStatement plant =
                    app.prepareStatement(
                            "UPDATE reconciliation.break SET status = 'RESOLVED',"
                                    + " resolved_at = now(), status_changed_at = now()"
                                    + " WHERE id = ?")) {
                plant.setObject(1, breakId);
                assertThat(plant.executeUpdate()).isEqualTo(1);
            }
            PositionProof.Report flipped = proof.sweep(app);
            assertThat(flipped.suspenseUnowned())
                    .as("a break closed over value it still owns is exactly what the"
                            + " reading detects (ADR-0070 §7)")
                    .isGreaterThanOrEqualTo(1L);
            app.rollback();
        }
        assertThat(sweep().suspenseUnowned())
                .as("the plant never reached the shared container")
                .isZero();
    }

    // -----------------------------------------------------------------

    private UUID acceptedBatch(String marker) throws SQLException {
        UUID fileId =
                pulled("H,SIM_PSP_CSV,1,PSPB-SU-" + marker + ",EUR,2026-09-25\n"
                        + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,,PSP-CAP-" + marker
                        + ",,,,Sale\n"
                        + "T,1,98.25,PSP-REM-15" + digits(marker) + "\n");
        parsing.sweep();
        acceptance.sweep();
        assertThat(scalar("SELECT status FROM settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED");
        return UUID.fromString(
                scalar("SELECT id::text FROM settlement.batch WHERE file_id = ?", fileId));
    }

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

    private static String scalar(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return scalarOn(app, sql, args);
        }
    }

    private static String scalarOn(Connection connection, String sql, Object... args) {
        try (PreparedStatement read = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                return row.getString(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not read: " + sql, failure);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT)
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
