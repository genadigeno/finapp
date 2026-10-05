package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.FxRuleSetV1;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.app.telemetry.RuleSetMissingMetrics;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The FX provider's source over the REAL composition (`P9-TSK-011`, ADR-0078, PHASE_9_PLAN.md
 * section 12.9.2): startup composes {@code fx-sim-a.trade-report} on {@code fx-sim-a}'s own
 * position (the counterparty chart guard and the coverage proof both passing); a file in a currency
 * {@code fx-sim-a} does not settle is refused {@code CURRENCY_NOT_SETTLED} and retained; with no
 * active rule set a parsed report waits {@code PARSED} with backoff ({@code RuleSetMissing}) and
 * {@code rule_set.missing} reads 1; two controllers activate the FX v1; the report is then accepted
 * with nothing posted, its remittance opened on {@code fx-sim-a}'s OWN account, and the position
 * proof keyed per (purpose, counterparty, currency) explains it.
 */
@Tag("database")
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the FX provider's source over the real composition (P9-TSK-011)")
@SuppressWarnings("try")
class FxProviderSourceDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String SOURCE = "fx-sim-a.trade-report";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Autowired private SettlementSources sources;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private RuleSetAdministration administration;
    @Autowired private RuleSets ruleSets;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private PositionProof proof;
    @Autowired private DataSource dataSource;
    @Autowired private com.finapp.ledger.PostingService postingService;
    @Autowired private com.finapp.fx.FxSettlementExpectations fxSettlementExpectations;

    private static UUID waitingFile;

    @Test
    @Order(1)
    @DisplayName("startup composes fx-sim-a's source on its own position, in its five currencies, and declares the counterparty")
    void startupComposesTheSource() {
        assertThat(sources.dischargedBy(AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-a"))
                .hasValueSatisfying(source -> {
                    assertThat(source.code()).isEqualTo(SOURCE);
                    assertThat(source.settledCurrencies()).hasSize(5);
                });
        assertThat(CounterpartyClearings.declared()).singleElement()
                .satisfies(clearing -> assertThat(clearing.code()).isEqualTo("fx-sim-a"));
        assertThat(PositionProof.provenPurposes(sources)).contains(AccountPurpose.FX_PROVIDER_CLEARING);
    }

    @Test
    @Order(2)
    @DisplayName("a file in a currency fx-sim-a does not settle is REJECTED CURRENCY_NOT_SETTLED at the parse leg"
            + " and retained - no batch, nothing posted")
    void anUnsettledCurrencyIsRefused() throws SQLException {
        UUID fileId = received(report("CHF", "FXB-CHF-" + marker(), "-10.00", coverRef()));
        parsing.sweep();
        assertThat(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", fileId))
                .isEqualTo("REJECTED:CURRENCY_NOT_SETTLED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", fileId)).isZero();
        assertThat(count("SELECT count(*) FROM settlement.file_chunk WHERE file_id = ?", fileId))
                .as("the evidence is retained").isPositive();
    }

    @Test
    @Order(3)
    @DisplayName("with no active rule set a parsed report waits PARSED with backoff - RuleSetMissing, never a hot"
            + " loop - and rule_set.missing reads 1 for the source alone")
    void aMissingRuleSetWaitsWithBackoff() throws SQLException {
        waitingFile = received(report("EUR", "FXB-EUR-" + marker(), "-10.00", coverRef()));
        parsing.sweep();
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", waitingFile)).isEqualTo("PARSED");
        acceptance.sweep();
        acceptance.sweep();
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", waitingFile)).isEqualTo("PARSED");
        assertThat(count("SELECT accept_failures FROM settlement.file WHERE id = ?", waitingFile))
                .as("one failure: the backoff held the second sweep off").isEqualTo(1);
        assertThat(one("SELECT next_accept_at > now() FROM settlement.file WHERE id = ?", waitingFile)).isEqualTo(true);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RuleSetMissingMetrics(sources, settlementFileStore, ruleSets, dataSource::getConnection,
                Clock.systemUTC(), registry);
        assertThat(registry.get(RuleSetMissingMetrics.MISSING).tag("source", SOURCE).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(RuleSetMissingMetrics.MISSING).tag("source", "simulated-psp.settlement").gauge().value())
                .isZero();
    }

    @Test
    @Order(4)
    @DisplayName("two controllers activate the FX v1; the waiting report is then accepted with nothing posted, its"
            + " remittance opened on fx-sim-a's OWN EUR account, and the proof keyed per counterparty explains it")
    void theFirstVersionReleasesTheReport() throws Exception {
        Actor proposer = new Actor("op-fx-controller-a", ActorType.EMPLOYEE);
        Actor approver = new Actor("op-fx-controller-b", ActorType.EMPLOYEE);
        UUID ruleSetId = inTransaction(uow -> administration.propose(uow, FxRuleSetV1.proposal("The FX source's first version"),
                proposer, Instant.now(), CorrelationId.generate(IDS)).ruleSetId());
        inTransaction(uow -> administration.approve(uow, ruleSetId, approver, "Reviewed against O7",
                Instant.now(), CorrelationId.generate(IDS)));
        assertThat(one("SELECT version || ':' || status FROM reconciliation.rule_set WHERE id = ?", ruleSetId))
                .isEqualTo("1:ACTIVE");

        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement due = migrator.prepareStatement(
                        "UPDATE settlement.file SET next_accept_at = now() - interval '1 second' WHERE id = ?")) {
            due.setObject(1, waitingFile);
            due.executeUpdate();
        }
        acceptance.sweep();
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", waitingFile)).isEqualTo("ACCEPTED");
        UUID batch = (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", waitingFile);
        assertThat(one("SELECT posting_omitted FROM settlement.batch WHERE id = ?", batch))
                .as("a report of legs alone posts nothing").isEqualTo(true);

        UUID account;
        try (Connection app = DatabaseRoles.application()) {
            account = new JdbcLedgerAccountStore()
                    .findCounterpartyAccount(app, AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-a", EUR)
                    .orElseThrow().id().value();
        }
        assertThat(one("SELECT ledger_account_id FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                + " AND operation_ref = ?", batch.toString()))
                .as("the remittance opens on fx-sim-a's OWN account, never a shared one (INV-RAIL-04)")
                .isEqualTo(account);

        PositionProof.Report report;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            report = proof.sweep(app);
            app.rollback();
        }
        assertThat(report.verdicts())
                .filteredOn(verdict -> verdict.purpose() == AccountPurpose.FX_PROVIDER_CLEARING
                        && verdict.counterparty().equals(Optional.of("fx-sim-a"))
                        && verdict.currency().equals(EUR))
                .as("keyed per (purpose, counterparty, currency) and explained: the remittance less the open legs")
                .singleElement()
                .satisfies(verdict -> assertThat(verdict.explained()).isTrue());
    }

    @Test
    @Order(5)
    @DisplayName("the FxSettlementExpectations port opens a cover leg on fx-sim-a's OWN account, read off the posted"
            + " entry - direction, amount and date - under the source discharging (purpose, counterparty), dated by v1's"
            + " lag; the proof keeps the position explained")
    void thePortOpensACoverLeg() throws Exception {
        String cover = coverRef();
        UUID entry;
        com.finapp.ledger.LedgerAccountId providerAccount;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            Object[] posted = inTransaction(uow -> {
                com.finapp.ledger.LedgerAccount provider = new com.finapp.ledger.ChartOfAccounts<>(new JdbcLedgerAccountStore())
                        .resolve(uow, AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-a", EUR);
                com.finapp.ledger.LedgerAccount position = new com.finapp.ledger.ChartOfAccounts<>(new JdbcLedgerAccountStore())
                        .resolve(uow, AccountPurpose.FX_POSITION, EUR);
                java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
                com.finapp.ledger.PostingResult result = postingService.post(uow, new com.finapp.ledger.PostingCommand(
                        "fx-cover-test:" + cover, today, today, "fx-cover-test:" + cover, java.util.List.of(
                                new com.finapp.ledger.JournalLine(provider.id(), com.finapp.ledger.Direction.DEBIT,
                                        com.finapp.sharedkernel.money.Money.ofPersisted(12_345, EUR, 2)),
                                new com.finapp.ledger.JournalLine(position.id(), com.finapp.ledger.Direction.CREDIT,
                                        com.finapp.sharedkernel.money.Money.ofPersisted(12_345, EUR, 2)))));
                fxSettlementExpectations.open(uow, new com.finapp.fx.FxSettlementExpectations.Opening(
                        com.finapp.fx.FxSettlementExpectations.Kind.FX_BUY_LEG, cover, "fx-cover-test:" + cover,
                        AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-a", provider.id(), result.entryId(),
                        java.util.List.of(new com.finapp.fx.FxSettlementExpectations.Key(
                                com.finapp.fx.FxSettlementExpectations.ReferenceKind.COVER_REF, cover)),
                        CorrelationContext.current().orElseThrow()));
                return new Object[] {result.entryId().value(), provider.id()};
            });
            entry = (UUID) posted[0];
            providerAccount = (com.finapp.ledger.LedgerAccountId) posted[1];
        }
        assertThat(one("SELECT kind || ':' || direction || ':' || amount_minor || ':' || (expected_by - posting_date)"
                + " FROM reconciliation.expectation WHERE operation_ref = ? AND kind = 'FX_BUY_LEG'", cover))
                .as("INBOUND (the entry debited the provider's position), 123.45, dated by v1's 2-day lag")
                .isEqualTo("FX_BUY_LEG:INBOUND:12345:2");
        assertThat(one("SELECT ledger_account_id FROM reconciliation.expectation WHERE operation_ref = ?", cover))
                .isEqualTo(providerAccount.value());
        assertThat(one("SELECT journal_entry_id FROM reconciliation.expectation WHERE operation_ref = ?", cover))
                .isEqualTo(entry);
        assertThat(one("SELECT key_value FROM reconciliation.expectation_key k JOIN reconciliation.expectation e"
                + " ON e.id = k.expectation_id WHERE e.operation_ref = ? AND k.key_kind = 'COVER_REF'", cover))
                .as("the leg's key is its currency's (P9-TSK-012): a cover's two legs share T")
                .isEqualTo(cover + ":EUR");

        PositionProof.Report report;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            report = proof.sweep(app);
            app.rollback();
        }
        assertThat(report.verdicts())
                .filteredOn(verdict -> verdict.purpose() == AccountPurpose.FX_PROVIDER_CLEARING
                        && verdict.counterparty().equals(Optional.of("fx-sim-a")) && verdict.currency().equals(EUR))
                .singleElement()
                .satisfies(verdict -> assertThat(verdict.explained()).isTrue());
        assertThat(report.unattributedByPurpose().get(AccountPurpose.FX_PROVIDER_CLEARING))
                .as("completeness walks fx-sim-a's account: the leg's line is known").isZero();
    }

    // -----------------------------------------------------------------

    private static String report(String currency, String batchRef, String amount, String cover) {
        return "H,SIM_FX_CSV,1," + batchRef + "," + currency + ",2026-10-05\n"
                + "D,1,SOLD," + amount + ",,fxt_" + marker() + "," + cover + "\n"
                + "T,1," + amount + ",FXA-" + (10_000 + new SecureRandom().nextInt(80_000)) + "\n";
    }

    private UUID received(String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(SOURCE, DeliveryChannel.PULL, content.getBytes(StandardCharsets.UTF_8),
                            Optional.empty(), Actor.SYSTEM, SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private <R> R inTransaction(java.util.function.Function<Connection, R> work) throws SQLException {
        try (Connection uow = dataSource.getConnection()) {
            uow.setAutoCommit(false);
            try {
                R result = work.apply(uow);
                uow.commit();
                return result;
            } catch (RuntimeException failure) {
                uow.rollback();
                throw failure;
            }
        }
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p9t11-" + UUID.randomUUID()))
                .causing(CausationId.of("p9t11-cause"));
    }

    private static String marker() {
        return "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toLowerCase().replaceAll("[0-9]", "x");
    }

    private static String coverRef() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return "T-" + HexFormat.of().formatHex(bytes);
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }
}
