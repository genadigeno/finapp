package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.payments.RailOperations;
import com.finapp.payments.SimulatedCorridorAdapter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.WaitingPayoutReturns;
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
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * The corridor rail, its position and its source over the REAL composition (`P9-TSK-014`, ADR-0080,
 * ADR-0082, PHASE_9_PLAN.md section 12.9.2): startup composes the rail, its declaration, the
 * counterparty's three seeded accounts and {@code corridor-sim-a.settlement} together; a EUR corridor
 * file is refused {@code CURRENCY_NOT_SETTLED}; two controllers activate the corridor v1 through the
 * first-version door and a corridor report is then accepted with nothing posted; and <strong>identical
 * provider references in the merchant payout source and the corridor source</strong> stay apart - the
 * merchant sweep's page holds only its own line, the corridor's scope only the corridor's.
 *
 * <p>Ordered with the Phase 9 suites (after the default ones, before the residue judgement): its
 * waiting returns stay waiting, each beside its own batch's remittance, so every position stays
 * explained at rest.
 */
@Tag("database")
@SpringBootTest
@Order(Integer.MAX_VALUE - 1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the corridor rail, its position and its source over the real composition (P9-TSK-014)")
@SuppressWarnings("try")
class CorridorSourceDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String SOURCE = "corridor-sim-a.settlement";
    private static final String MERCHANT_SOURCE = "simulated-payout.settlement";
    private static final AccountPurpose CORRIDOR_POSITION =
            SimulatedCorridorAdapter.RAIL.capabilities().clearingPurpose().orElseThrow();

    @Autowired private SettlementSources sources;
    @Autowired private RailOperations railOperations;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private RuleSetAdministration administration;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private WaitingPayoutReturns waitingPayoutReturns;
    @Autowired private DataSource dataSource;

    @Test
    @Order(1)
    @DisplayName("startup composes the rail, its declaration, corridor-sim-a's three seeded accounts and its"
            + " source together - and no adapter is configured, so nothing can be sent")
    void startupComposesTheRailItsPositionAndItsSource() throws SQLException {
        assertThat(sources.dischargedBy(CORRIDOR_POSITION, "corridor-sim-a")).hasValueSatisfying(source -> {
            assertThat(source.code()).isEqualTo(SOURCE);
            assertThat(source.settledCurrencies()).extracting(CurrencyCode::code)
                    .containsExactlyInAnyOrder("USD", "JPY", "BHD");
        });
        assertThat(railOperations.corridorDeclaration(SimulatedCorridorAdapter.RAIL.id()))
                .contains(SimulatedCorridorAdapter.DECLARATION);
        assertThat(railOperations.corridorRail(SimulatedCorridorAdapter.RAIL.id()))
                .as("no corridor URL in this composition: the rail is declared, never wired").isEmpty();
        assertThat(railOperations.pushRail(SimulatedCorridorAdapter.RAIL.id())).isEmpty();
        assertThat(com.finapp.app.settlement.CounterpartyClearings.declared())
                .extracting(com.finapp.ledger.CounterpartyClearing::code)
                .contains("corridor-sim-a", "fx-sim-a");
        try (Connection app = DatabaseRoles.application()) {
            for (String currency : List.of("USD", "JPY", "BHD")) {
                assertThat(new JdbcLedgerAccountStore().findCounterpartyAccount(
                                app, CORRIDOR_POSITION, "corridor-sim-a", CurrencyCode.of(currency)))
                        .as("corridor-sim-a's %s account, seeded by ledger V024", currency)
                        .isPresent();
            }
        }
        assertThat(PositionProof.provenPurposes(sources)).contains(CORRIDOR_POSITION);
        // Since P9-TSK-020 the outbound credit's completion posts to it, and since P9-TSK-022 the corridor's reports and
        // the bank's statements discharge it and reconciliation parks a line's value from it: nothing else ever does.
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id JOIN ledger.journal_entry e ON e.id = l.entry_id WHERE a.purpose = ?"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:outbound-credit:%'"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:recon-%'"
                // P9-TSK-023: an applied cross-border return debits the position.
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:crossborder-return:%'"
                + " AND e.id NOT IN (SELECT journal_entry_id FROM settlement.batch WHERE journal_entry_id IS NOT NULL)",
                CORRIDOR_POSITION.name()))
                .as("the position exists; only a completion, a return, a report, a statement or a parking posts to it - found %s",
                        one("SELECT coalesce(string_agg(DISTINCT e.idempotency_scope, ', '), '') FROM ledger.journal_line l"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id JOIN ledger.journal_entry e"
                                + " ON e.id = l.entry_id WHERE a.purpose = ? AND e.idempotency_scope NOT LIKE"
                                + " 'ledger.post:outbound-credit:%' AND e.idempotency_scope NOT LIKE 'ledger.post:recon-%'"
                                + " AND e.idempotency_scope NOT LIKE 'ledger.post:crossborder-return:%'"
                                + " AND e.id NOT IN (SELECT journal_entry_id FROM"
                                + " settlement.batch WHERE journal_entry_id IS NOT NULL)", CORRIDOR_POSITION.name()))
                .isZero();
    }

    @Test
    @Order(2)
    @DisplayName("a EUR corridor file is REJECTED CURRENCY_NOT_SETTLED at the parse leg and retained - no batch")
    void aEurFileIsRefused() throws SQLException {
        UUID fileId = received(SOURCE, corridorReport("EUR", "BOUNCED", "5.00", "xp_" + marker(), e()));
        parsing.sweep();
        assertThat(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", fileId))
                .isEqualTo("REJECTED:CURRENCY_NOT_SETTLED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", fileId)).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("two controllers activate the corridor v1 through the first-version door; a corridor report is"
            + " then accepted with nothing posted, its remittance on corridor-sim-a's OWN USD account")
    void theFirstVersionAdmitsTheReport() throws Exception {
        Actor proposer = new Actor("op-corridor-controller-a", ActorType.EMPLOYEE);
        Actor approver = new Actor("op-corridor-controller-b", ActorType.EMPLOYEE);
        // The first version through the first-version door - unless a suite sharing the container (P9-TSK-020's and
        // later, whose completions need it) already activated it the same way.
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                CorridorRuleSetV1.SOURCE) == 0) {
            UUID ruleSetId = inTransaction(uow -> administration.propose(uow,
                    CorridorRuleSetV1.proposal("The corridor source's first version"),
                    proposer, Instant.now(), CorrelationId.generate(IDS)).ruleSetId());
            inTransaction(uow -> administration.approve(uow, ruleSetId, approver, "Reviewed against O7",
                    Instant.now(), CorrelationId.generate(IDS)));
            assertThat(one("SELECT version || ':' || status FROM reconciliation.rule_set WHERE id = ?", ruleSetId))
                    .isEqualTo("1:ACTIVE");
        }
        assertThat(one("SELECT min(version) || ':' || count(*) FROM reconciliation.rule_set WHERE source_id = ?"
                + " AND status = 'ACTIVE'", CorridorRuleSetV1.SOURCE)).isEqualTo("1:1");

        UUID batch = accepted(SOURCE, corridorReport("USD", "CREDITED", "-250.00", "xp_" + marker(), e()));
        assertThat(one("SELECT posting_omitted FROM settlement.batch WHERE id = ?", batch))
                .as("a report of credits alone posts nothing").isEqualTo(true);
        UUID account;
        try (Connection app = DatabaseRoles.application()) {
            account = new JdbcLedgerAccountStore()
                    .findCounterpartyAccount(app, CORRIDOR_POSITION, "corridor-sim-a", CurrencyCode.of("USD"))
                    .orElseThrow().id().value();
        }
        assertThat(one("SELECT ledger_account_id FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                + " AND operation_ref = ?", batch.toString()))
                .as("the remittance opens on corridor-sim-a's OWN account (INV-RAIL-04)")
                .isEqualTo(account);
        matchUntilQuiet();
        assertThat(one("SELECT i.status FROM reconciliation.external_item i JOIN reconciliation.reconciliation_batch r"
                + " ON r.id = i.run_id WHERE r.batch_id = ? AND i.line_type = 'PAYOUT_EXECUTED'", batch))
                .as("no outbound credit exists yet: zero lines matched").isEqualTo("UNMATCHED");
    }

    @Test
    @Order(4)
    @DisplayName("identical provider references in the merchant payout source and the corridor source: the"
            + " merchant sweep's page holds only its own line, the corridor's scope only the corridor's - each"
            + " re-read under its share lock in its own scope alone")
    void identicalReferencesStayInTheirFamilies() throws Exception {
        String shared = "xp_" + marker();
        UUID merchantBatch = accepted(MERCHANT_SOURCE,
                "H,SIM_PAYOUT_CSV,1,PAYDAY-" + marker() + ",EUR,2026-10-05\n"
                        + "D,1,RETURNED,7.00,," + shared + ",,\n"
                        + "T,1,7.00,PAY-REM-" + remittanceDigits() + "\n");
        UUID corridorBatch = accepted(SOURCE, corridorReport("USD", "BOUNCED", "7.00", shared, e()));
        matchUntilQuiet();
        UUID merchantItem = onlyReturnedItem(merchantBatch);
        UUID corridorItem = onlyReturnedItem(corridorBatch);
        assertThat(one("SELECT count(DISTINCT k.key_value) FROM reconciliation.external_item_key k"
                + " WHERE k.key_kind = 'PAYOUT_PROVIDER_REF' AND k.item_id IN (?, ?)", merchantItem, corridorItem))
                .as("one provider reference, two items").isEqualTo(1L);

        WaitingPayoutReturns corridorScope =
                ReconciliationBeans.waitingReturnsOf(CORRIDOR_POSITION, settlementFileStore, sources);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                Set<UUID> merchantPage = everyWaiting(app, waitingPayoutReturns);
                Set<UUID> corridorPage = everyWaiting(app, corridorScope);
                assertThat(merchantPage).contains(merchantItem).doesNotContain(corridorItem);
                assertThat(corridorPage).contains(corridorItem).doesNotContain(merchantItem);
                assertThat(waitingPayoutReturns.lockWaiting(app, corridorItem))
                        .as("the merchant worker cannot even lock a corridor return").isEmpty();
                assertThat(corridorScope.lockWaiting(app, merchantItem)).isEmpty();
                assertThat(waitingPayoutReturns.lockWaiting(app, merchantItem)).hasValueSatisfying(
                        waiting -> assertThat(waiting.providerReference()).contains(shared));
                assertThat(corridorScope.lockWaiting(app, corridorItem)).hasValueSatisfying(
                        waiting -> assertThat(waiting.providerReference()).contains(shared));
            } finally {
                app.rollback();
            }
        }
    }

    // -----------------------------------------------------------------

    private static Set<UUID> everyWaiting(Connection app, WaitingPayoutReturns reader) {
        Set<UUID> seen = new java.util.HashSet<>();
        Optional<UUID> after = Optional.empty();
        while (true) {
            List<WaitingPayoutReturns.WaitingReturn> page = reader.page(app, after, 50);
            page.forEach(waiting -> seen.add(waiting.itemId()));
            if (page.size() < 50) {
                return seen;
            }
            after = Optional.of(page.get(page.size() - 1).itemId());
        }
    }

    private static String corridorReport(String currency, String code, String amount, String providerRef, String e) {
        return "H,SIM_CORRIDOR_CSV,1,XB-" + marker() + "," + currency + ",2026-10-05\n"
                + "D,1," + code + "," + amount + ",," + providerRef + "," + e + "\n"
                + "T,1," + amount + ",XBA-" + remittanceDigits() + "\n";
    }

    private UUID accepted(String source, String content) throws SQLException {
        UUID fileId = received(source, content);
        parsing.sweep();
        acceptance.sweep();
        assertThat(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED:-");
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", fileId);
    }

    private UUID received(String source, String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(source, DeliveryChannel.PULL, content.getBytes(StandardCharsets.UTF_8),
                            Optional.empty(), Actor.SYSTEM, SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static UUID onlyReturnedItem(UUID batch) throws SQLException {
        List<UUID> items = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT i.id FROM reconciliation.external_item i JOIN reconciliation.reconciliation_batch r"
                                + " ON r.id = i.run_id WHERE r.batch_id = ? AND i.line_type = 'PAYOUT_RETURNED'")) {
            read.setObject(1, batch);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    items.add(rows.getObject(1, UUID.class));
                }
            }
        }
        assertThat(items).as("the report's one returned line").hasSize(1);
        return items.get(0);
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
        return Correlation.startingWith(CorrelationId.of("p9t14-" + UUID.randomUUID()))
                .causing(CausationId.of("p9t14-cause"));
    }

    /** Letters only: no digit run can hide in a reference built from it. */
    private static String marker() {
        return "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).replaceAll("[0-9]", "q");
    }

    private static String e() {
        return "XB-" + marker();
    }

    private static int remittanceDigits() {
        return 10_000 + new SecureRandom().nextInt(80_000);
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
