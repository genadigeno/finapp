package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.merchant.IllegalMerchantTransitionException;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantNotEligibleException;
import com.finapp.merchant.MerchantOnboarding;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The onboarding and administration commands against the real schema (`P6-TSK-003`): the KYB
 * gate writing nothing on refusal, the merchant and its books committing together
 * ({@code INV-MER-02}), the one-key race counted in the tables ({@code INV-IDEM-01}), the
 * machine's edges bound for every writer, and the live-schema half of the no-balance claim.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest
@DisplayName("merchant onboarding and administration (P6-TSK-003)")
class MerchantOnboardingDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final com.finapp.merchant.MerchantStore<Connection> store =
            new com.finapp.merchant.JdbcMerchantStore();

    @Autowired private MerchantOnboarding onboarding;
    @Autowired private MerchantAdministration administration;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("onboarding commits the merchant AND its books together - row, payable"
            + " account, audit, event, one transaction (INV-MER-02)")
    void onboardingCommitsTheMerchantAndItsBooksTogether() throws Exception {
        UUID party = verifiedOrganisation();

        MerchantOnboarding.OnboardingResult result = onboard(party, someKey());

        assertThat(result.replayed()).isFalse();
        UUID merchant = result.merchantId().value();
        assertThat(count("SELECT count(*) FROM merchant.merchant WHERE id = ?", merchant))
                .isEqualTo(1);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT account_type, normal_balance, owner_kind, purpose,"
                                        + " currency, status FROM ledger.ledger_account"
                                        + " WHERE owner_ref = ?")) {
            read.setObject(1, merchant);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the payable account exists").isTrue();
                assertThat(row.getString(1)).isEqualTo("LIABILITY");
                assertThat(row.getString(2)).isEqualTo("CREDIT");
                assertThat(row.getString(3)).isEqualTo("MERCHANT");
                assertThat(row.getString(4)).isEqualTo("MERCHANT_PAYABLE");
                assertThat(row.getString(5)).isEqualTo("EUR");
                assertThat(row.getString(6)).isEqualTo("ACTIVE");
                assertThat(row.next()).as("exactly one payable account").isFalse();
            }
        }
        assertThat(
                        count(
                                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                        + " 'merchant.MerchantOnboarded' AND target_id = ?",
                                merchant.toString()))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                        + " 'merchant.MerchantOnboarded' AND aggregate_id = ?",
                                merchant))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the KYB refusal writes NOTHING, and conflates its causes: person party,"
            + " unverified organisation, unknown party - one refusal each way")
    void theKybRefusalWritesNothing() throws Exception {
        long merchants = count("SELECT count(*) FROM merchant.merchant");
        long accounts =
                count("SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'MERCHANT'");

        for (UUID ineligible :
                List.of(verifiedPerson(), pendingOrganisation(), UUID.randomUUID())) {
            assertThatExceptionOfType(MerchantNotEligibleException.class)
                    .isThrownBy(() -> onboard(ineligible, someKey()));
        }

        assertThat(count("SELECT count(*) FROM merchant.merchant")).isEqualTo(merchants);
        assertThat(
                        count(
                                "SELECT count(*) FROM ledger.ledger_account WHERE owner_kind ="
                                        + " 'MERCHANT'"))
                .isEqualTo(accounts);
    }

    @Test
    @DisplayName("ten instances onboarding with ONE key produce one merchant, one payable"
            + " account, one audit record, one event - counted (INV-IDEM-01)")
    void tenInstancesWithOneKeyProduceOneMerchant() throws Exception {
        UUID party = verifiedOrganisation();
        String key = someKey();

        ExecutorService instances = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MerchantOnboarding.OnboardingResult>> answers = new ArrayList<>();
        // ONE operator retried by ten instances - the actor is part of the fingerprint
        // (INV-IDEM-03 binds the key to who asked), so the race shares it.
        Actor operator = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        try {
            for (int i = 0; i < 10; i++) {
                answers.add(
                        instances.submit(
                                () -> {
                                    start.await();
                                    return as(operator, uow ->
                                            onboarding.onboard(
                                                    uow,
                                                    new MerchantOnboarding.OnboardMerchantCommand(
                                                            key, party, "Acme GmbH", "Acme",
                                                            EUR)));
                                }));
            }
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            int acting = 0;
            for (Future<MerchantOnboarding.OnboardingResult> answer : answers) {
                MerchantOnboarding.OnboardingResult result = answer.get();
                ids.add(result.merchantId().value());
                if (!result.replayed()) {
                    acting++;
                }
            }
            assertThat(ids).as("every caller was handed the one merchant").hasSize(1);
            assertThat(acting).as("exactly one call acted").isEqualTo(1);
            UUID merchant = ids.iterator().next();
            assertThat(count("SELECT count(*) FROM merchant.merchant WHERE id = ?", merchant))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref"
                                            + " = ?",
                                    merchant))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT count(*) FROM platform.audit_record WHERE operation"
                                            + " = 'merchant.MerchantOnboarded' AND target_id"
                                            + " = ?",
                                    merchant.toString()))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT count(*) FROM platform.outbox_event WHERE"
                                            + " event_type = 'merchant.MerchantOnboarded' AND"
                                            + " aggregate_id = ?",
                                    merchant))
                    .isEqualTo(1);
        } finally {
            instances.shutdownNow();
        }
    }

    @Test
    @DisplayName("a reused key with a different request is the distinct conflict, not a"
            + " silent replay (INV-IDEM-03)")
    void aReusedKeyWithADifferentRequestConflicts() throws Exception {
        UUID party = verifiedOrganisation();
        String key = someKey();
        onboard(party, key);

        UUID otherParty = verifiedOrganisation();
        assertThatExceptionOfType(IdempotencyConflictException.class)
                .isThrownBy(() -> onboard(otherParty, key));
    }

    @Test
    @DisplayName("suspend, reinstate and close walk the machine with the reason recorded"
            + " VERBATIM, and each retry converges (INV-AUD-03)")
    void theStandingMovesWalkTheMachineWithReasons() throws Exception {
        UUID merchant = onboard(verifiedOrganisation(), someKey()).merchantId().value();
        MerchantId id = MerchantId.of(merchant);
        String reason = "chargeback ratio breached the programme threshold " + suffix();

        asOperator(uow -> administration.suspend(uow, id, reason));
        assertThat(statusOf(merchant)).isEqualTo("SUSPENDED");
        assertThat(
                        count(
                                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                        + " 'merchant.MerchantSuspended' AND target_id = ? AND"
                                        + " reason = ?",
                                merchant.toString(),
                                reason))
                .as("the operator's words, verbatim")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_event WHERE merchant_id"
                                        + " = ? AND from_status = 'ACTIVE' AND to_status ="
                                        + " 'SUSPENDED'",
                                merchant))
                .isEqualTo(1);

        // The retry converges: no second history row, no second audit record.
        asOperator(uow -> administration.suspend(uow, id, reason));
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_event WHERE merchant_id"
                                        + " = ?",
                                merchant))
                .isEqualTo(1);

        asOperator(uow -> administration.reinstate(uow, id, "dispute resolved " + suffix()));
        assertThat(statusOf(merchant)).isEqualTo("ACTIVE");
        asOperator(uow -> administration.close(uow, id, "relationship ended " + suffix()));
        assertThat(statusOf(merchant)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("the machine refuses the illegal edges at the aggregate - closing a"
            + " suspended merchant folds two decisions into one act")
    void theMachineRefusesIllegalEdges() throws Exception {
        UUID merchant = onboard(verifiedOrganisation(), someKey()).merchantId().value();
        MerchantId id = MerchantId.of(merchant);
        asOperator(uow -> administration.suspend(uow, id, "under review " + suffix()));

        assertThatExceptionOfType(IllegalMerchantTransitionException.class)
                .isThrownBy(() -> asOperator(uow -> administration.close(uow, id, "no")));
        assertThat(statusOf(merchant)).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("raw SQL cannot walk an illegal edge or edit identity - V002's trigger binds"
            + " every writer (INV-LIFE-02, the third layer)")
    void rawSqlCannotWalkAnIllegalEdge() throws Exception {
        UUID merchant = onboard(verifiedOrganisation(), someKey()).merchantId().value();
        MerchantId id = MerchantId.of(merchant);
        asOperator(uow -> administration.suspend(uow, id, "parking " + suffix()));
        asOperator(uow -> administration.reinstate(uow, id, "resolved " + suffix()));
        asOperator(uow -> administration.close(uow, id, "ended " + suffix()));

        // The MIGRATOR, deliberately: the application role's narrowed grant already refuses
        // the frozen columns (the first layer), so the trigger's every-writer claim is only
        // proven against the writer the grants cannot bind (the P1-TSK-020 layering).
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE merchant.merchant SET status = 'ACTIVE',"
                                                    + " status_changed_at = now() WHERE id = ?",
                                            merchant))
                    .withMessageContaining("machine's edges");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE merchant.merchant SET legal_name = 'Mallory"
                                                    + " Ltd' WHERE id = ?",
                                            merchant))
                    .withMessageContaining("frozen");
        }
    }

    @Test
    @DisplayName("a STALE snapshot's move is refused by the row count, writing nothing - the"
            + " store's convergence contract, and the only probe that reaches it")
    void aStaleSnapshotsMoveIsRefused() throws Exception {
        // WHY THIS TEST EXISTS, recorded because the battery is what found the gap: the
        // conditional `WHERE status = ?` in JdbcMerchantStore.transition() is a SECOND layer
        // behind the `FOR UPDATE` lock, and dropping it SURVIVED every other test here. With
        // the lock held the racer blocks, re-reads the committed state and converges before
        // the clause can matter - so the clause is genuinely unreachable through the command.
        // It is not decoration: it binds the caller that reads WITHOUT the lock, which is
        // exactly the caller a later task adds by accident (INV-CON-01). The probe drives the
        // store's own contract directly with a stale `before`, which is what that caller
        // produces - the P5-TSK-015 shape, where the gap was closed where it was found.
        UUID merchant = onboard(verifiedOrganisation(), someKey()).merchantId().value();
        MerchantId id = MerchantId.of(merchant);

        Merchant stale =
                asOperator(
                        uow ->
                                store.findById(uow, id)
                                        .orElseThrow(
                                                () -> new IllegalStateException("just onboarded")));
        asOperator(uow -> administration.suspend(uow, id, "another operator got here first"));

        Boolean landed =
                asOperator(uow -> store.transition(uow, stale, stale.close(CLOCK)));

        assertThat(landed).as("the row count refuses a move from a state the row has left").isFalse();
        assertThat(statusOf(merchant))
                .as("the stale writer moved nothing")
                .isEqualTo("SUSPENDED");
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_event WHERE merchant_id"
                                        + " = ? AND to_status = 'CLOSED'",
                                merchant))
                .as("and wrote no history for a transition that never happened")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("no balance column exists in the live merchant schema (INV-MER-02's sweep)")
    void noBalanceColumnExistsInTheLiveSchema() throws Exception {
        assertThat(
                        count(
                                "SELECT count(*) FROM information_schema.columns WHERE"
                                        + " table_schema = 'merchant' AND (column_name ILIKE"
                                        + " '%balance%' OR column_name ILIKE '%payable%' OR"
                                        + " column_name ILIKE '%amount%')"))
                .as("the payable is a ledger position and exists nowhere else")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------

    private MerchantOnboarding.OnboardingResult onboard(UUID party, String key)
            throws Exception {
        return asOperator(
                uow ->
                        onboarding.onboard(
                                uow,
                                new MerchantOnboarding.OnboardMerchantCommand(
                                        key, party, "Acme GmbH", "Acme", EUR)));
    }

    /** One operator act: correlation + EMPLOYEE actor + one transaction, the app's shape. */
    private <R> R asOperator(Function<Connection, R> work) {
        return as(new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), work);
    }

    private <R> R as(Actor acting, Function<Connection, R> work) {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(acting)) {
            return new TransactionTemplate(transactionManager)
                    .execute(
                            status -> {
                                Connection unitOfWork =
                                        DataSourceUtils.getConnection(dataSource);
                                try {
                                    return work.apply(unitOfWork);
                                } finally {
                                    DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                                }
                            });
        }
    }

    /** An ORGANISATION party whose live customer is ACTIVE - the KYB projection, seeded. */
    private static UUID verifiedOrganisation() throws SQLException {
        return partyWithCustomer("ORGANISATION", "ACTIVE");
    }

    private static UUID pendingOrganisation() throws SQLException {
        return partyWithCustomer("ORGANISATION", "PENDING");
    }

    private static UUID verifiedPerson() throws SQLException {
        return partyWithCustomer("PERSON", "ACTIVE");
    }

    private static UUID partyWithCustomer(String kind, String status) throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, '" + kind + "', 'Acme Holdings', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, '" + status + "',"
                            + " now() - interval '1 hour', now())",
                    IDS.next(),
                    party);
        }
        return party;
    }

    private static String statusOf(UUID merchant) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM merchant.merchant WHERE id = ?")) {
            read.setObject(1, merchant);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
