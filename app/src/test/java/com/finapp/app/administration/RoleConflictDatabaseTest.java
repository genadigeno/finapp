package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The segregation-of-duties refusal on PostgreSQL (`P17-TSK-016`): a grant of a role whose partner the identity holds is refused
 * and grants nothing, whichever half comes first; two grants of a pair's halves racing for one identity never both succeed - the
 * check reads after the identity's lock - and the review names a holding that predates the rule, recorded.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("the segregation-of-duties refusal and its review on PostgreSQL (P17-TSK-016)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RoleConflictDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Autowired private IdentityAdministration administration;

    @Test
    @DisplayName("a role whose partner is held is refused, either way round, and nothing is granted")
    void aConflictingGrantIsRefused() throws Exception {
        IdentityId admin = identity(false);
        IdentityId subject = identity(true);

        assertThat(grant(admin, subject, RoleName.ACCOUNTANT)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);
        assertThat(grant(admin, subject, RoleName.FINANCIAL_CONTROLLER)).isEqualTo(IdentityAdministration.RoleGrant.CONFLICT);
        assertThat(grant(admin, subject, RoleName.KYC_REVIEWER)).as("a role with no partner held").isEqualTo(IdentityAdministration.RoleGrant.GRANTED);
        assertThat(grant(admin, subject, RoleName.ACCOUNTANT)).as("the held role again: no conflict with itself")
                .isEqualTo(IdentityAdministration.RoleGrant.ALREADY_HELD);
        assertThat(liveRoles(subject)).containsExactlyInAnyOrder("ACCOUNTANT", "KYC_REVIEWER");

        IdentityId other = identity(true);
        assertThat(grant(admin, other, RoleName.RECONCILIATION_OPERATOR)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);
        assertThat(grant(admin, other, RoleName.LEDGER_OPERATOR)).as("the checker and the checked").isEqualTo(IdentityAdministration.RoleGrant.CONFLICT);
        assertThat(grant(admin, other, RoleName.RECONCILIATION_CONTROLLER)).isEqualTo(IdentityAdministration.RoleGrant.CONFLICT);
        assertThat(liveRoles(other)).containsExactly("RECONCILIATION_OPERATOR");
    }

    @Test
    @DisplayName("ten rounds of a pair's two halves granted to one identity at once: never both, one granted and one refused")
    void racingHalvesNeverBothSucceed() throws Exception {
        ExecutorService instances = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                IdentityId subject = identity(true);
                IdentityId firstAdmin = identity(false);
                IdentityId secondAdmin = identity(false);
                CountDownLatch start = new CountDownLatch(1);
                Future<IdentityAdministration.RoleGrant> analyst = instances.submit(() -> {
                    start.await();
                    return grant(firstAdmin, subject, RoleName.RISK_ANALYST);
                });
                Future<IdentityAdministration.RoleGrant> officer = instances.submit(() -> {
                    start.await();
                    return grant(secondAdmin, subject, RoleName.RISK_OFFICER);
                });
                start.countDown();
                List<IdentityAdministration.RoleGrant> outcomes = List.of(analyst.get(), officer.get());

                assertThat(outcomes).as("round %d", round)
                        .containsExactlyInAnyOrder(IdentityAdministration.RoleGrant.GRANTED, IdentityAdministration.RoleGrant.CONFLICT);
                assertThat(liveRoles(subject)).as("round %d: one half held", round).hasSize(1);
            }
        } finally {
            instances.shutdownNow();
        }
    }

    @Test
    @DisplayName("the review names a conflicting holding from before the rule, since the later grant, and records the read")
    void theReviewNamesPreRuleConflicts() throws Exception {
        IdentityId admin = identity(false);
        IdentityId legacy = identity(true);
        try (Connection app = DatabaseRoles.application()) {
            // Granted around the door, as before the rule: the conflict the review exists to find.
            role(app, legacy, "BNPL_SERVICING_AGENT", admin, "now() - INTERVAL '2 days'");
            role(app, legacy, "BNPL_OFFICER", admin, "now() - INTERVAL '1 day'");
        }
        String flow = "p17-016-review-" + UUID.randomUUID();

        IdentityAdministration.SodConflicts review = as(admin, flow, uow -> administration.sodConflicts(uow, java.util.Optional.empty(), 200));

        List<String> named = review.conflicts().stream().filter(conflict -> conflict.identityId().equals(legacy))
                .map(conflict -> conflict.first() + "/" + conflict.second()).toList();
        assertThat(named).containsExactly("BNPL_SERVICING_AGENT/BNPL_OFFICER");
        assertThat(review.conflicts().stream().filter(conflict -> conflict.identityId().equals(legacy)).findFirst().orElseThrow().since())
                .as("since the later grant").isAfter(CLOCK.instant().minusSeconds(2 * 86_400 - 3_600));
        assertThat(single("SELECT count(*) FROM platform.audit_record WHERE correlation_id = '" + flow
                + "' AND operation = 'identity.SodConflictsRead'")).isEqualTo("1");
        assertThat(liveRoles(legacy)).as("named, never revoked").containsExactlyInAnyOrder("BNPL_SERVICING_AGENT", "BNPL_OFFICER");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private IdentityAdministration.RoleGrant grant(IdentityId admin, IdentityId subject, RoleName role) throws SQLException {
        return as(admin, "p17-016-grant-" + UUID.randomUUID(),
                uow -> administration.assignRole(uow, subject, role, admin, "P17-TSK-016 segregation of duties"));
    }

    @FunctionalInterface
    private interface Work<R> {
        R apply(Connection unitOfWork) throws SQLException;
    }

    private static <R> R as(IdentityId who, String flow, Work<R> work) throws SQLException {
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(flow)));
                SecurityContext.Scope actor = SecurityContext.enter(new Actor(who.value().toString(), ActorType.EMPLOYEE));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    /** An identity, with a confirmed factor when it will be granted roles (every role that can act needs one). */
    private static IdentityId identity(boolean withFactor) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'SoD Person', now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party,
                    "sod" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            if (withFactor) {
                execute(app, "INSERT INTO identity.mfa_enrolment (id, identity_id, type, secret_ciphertext, secret_nonce, key_version,"
                        + " algorithm, digits, period_seconds, status, created_at, confirmed_at)"
                        + " VALUES (?, ?, 'TOTP', ?, ?, 1, 'SHA1', 6, 30, 'ACTIVE', now(), now())",
                        IDS.next(), identity, new byte[32], new byte[12]);
            }
        }
        return IdentityId.of(identity);
    }

    private static void role(Connection app, IdentityId identity, String role, IdentityId by, String at) throws SQLException {
        execute(app, "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) VALUES (?, ?, ?, ?, "
                + at + ")", IDS.next(), identity.value(), role, by.value());
    }

    private static List<String> liveRoles(IdentityId identity) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement select = root.prepareStatement(
                        "SELECT role_name FROM identity.role_assignment WHERE identity_id = ? AND revoked_at IS NULL")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                List<String> roles = new ArrayList<>();
                while (rows.next()) {
                    roles.add(rows.getString(1));
                }
                return roles;
            }
        }
    }

    private static String single(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); PreparedStatement select = root.prepareStatement(sql);
                ResultSet rows = select.executeQuery()) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
