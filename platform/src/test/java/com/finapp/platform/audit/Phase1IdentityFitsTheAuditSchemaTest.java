package com.finapp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.DatabaseRoles;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code P0-TSK-032}'s second acceptance clause, made falsifiable.
 *
 * <p>The clause is "the Phase 1 identity implementation requires no change to the audit schema" -
 * a claim about a phase that does not exist, which is the kind of statement that gets written
 * down, believed, and discovered to be false at the worst moment. It cannot be tested directly.
 * The property <em>behind</em> it can:
 *
 * <blockquote>
 * A record written by an actor of every non-{@code SYSTEM} type, carrying the shape of identifier
 * a real identity provider issues, persists today with no DDL.
 * </blockquote>
 *
 * <p>That is the Phase 1 substitution with the authentication removed. If it works now, what
 * Phase 1 adds is a filter that establishes a {@link SecurityContext} scope from an authenticated
 * principal - not a migration. If it does not, the gap is visible a whole phase early, which is
 * the only time it is cheap: {@code INV-HIST-01} means an audit table that has to change shape
 * after it holds records changes the shape of history too.
 *
 * <p>The identifiers are the point. They are not tidy UUIDs, because a real subject claim is not
 * one: an OIDC {@code sub} is opaque and issuer-defined, an employee directory identifier may be
 * a distinguished name, and a service credential is whatever the counterparty named it. Forcing
 * those into a typed {@code EntityId} is the trap {@link Actor}'s javadoc already refuses; this
 * asserts that refusing it actually paid off.
 */
@Tag("database")
class Phase1IdentityFitsTheAuditSchemaTest {

    private static final Instant OCCURRED = Instant.parse("2026-09-02T09:30:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(java.time.Clock.systemUTC(), new java.security.SecureRandom());

    /**
     * Marks this test's rows so they can be removed again.
     *
     * <p>Audit records are append-only to the application role by design ({@code INV-HIST-03}), so
     * a test that commits them leaves them there for ever - and this one commits on every run.
     * Cleanup is therefore the migrator's job and is scoped by this marker, never a wholesale
     * DELETE: the sibling suites share this table, and a test that tidies up other tests' evidence
     * is a test that can make an unrelated failure impossible to diagnose.
     */
    private static final String PROBE_TARGET = "phase1-fit-probe";

    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        clean();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
        clean();
    }

    private static void clean() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                java.sql.Statement statement = migrator.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM platform.audit_record WHERE target_type = '" + PROBE_TARGET + "'");
        }
    }

    @Test
    @DisplayName("an actor of every type Phase 1 introduces persists with no schema change")
    void everyActorTypeFitsToday() throws SQLException {
        // One row per non-SYSTEM type, each with the identifier shape its source really produces.
        record Candidate(ActorType type, String id, String why) {}
        var candidates =
                java.util.List.of(
                        new Candidate(
                                ActorType.CUSTOMER,
                                "auth0|65f3c1d0e8a94b2f7c1d0e8a",
                                "an OIDC subject claim: opaque, issuer-prefixed, not a UUID"),
                        new Candidate(
                                ActorType.EMPLOYEE,
                                "CN=Ada Lovelace,OU=Operations,DC=finapp,DC=example",
                                "a directory distinguished name, which is what an employee "
                                        + "directory actually issues"),
                        new Candidate(
                                ActorType.SERVICE,
                                "svc:acquirer-gateway:prod-2",
                                "a named integration credential belonging to a counterparty"));

        var writer = new JdbcAuditWriter();
        for (Candidate candidate : candidates) {
            AuditId id = AuditId.next(IDS);
            Actor actor = new Actor(candidate.id(), candidate.type());

            // Written exactly as production would: through the writer, as the application role,
            // in the caller's transaction. A direct INSERT as the migrator would prove the column
            // accepts the value and nothing about whether the application could ever put it there.
            writer.append(
                    application,
                    new AuditRecord(
                            id,
                            actor,
                            OCCURRED,
                            PlatformAuditAction.OUTBOX_EVENT_DISCARDED,
                            PROBE_TARGET,
                            "target-" + candidate.type(),
                            Optional.of("proving " + candidate.why()),
                            AuditOutcome.SUCCEEDED,
                            CorrelationId.of("phase1-fit"),
                            Optional.empty()));
            application.commit();

            assertThat(storedActor(id))
                    .as("%s must persist unchanged: %s", candidate.type(), candidate.why())
                    .isEqualTo(candidate.type() + "|" + candidate.id());
        }
    }

    @Test
    @DisplayName("the widest identifier the type permits is the widest the column permits")
    void theBoundsAgree() throws SQLException {
        // Actor.MAX_ID_LENGTH claims to mirror audit_record_actor_id_bounded. If the Java bound
        // were the looser of the two, Phase 1 would meet it as a constraint violation from three
        // layers down on whichever identity provider first issued a long subject claim - and it
        // would meet it in production, because no fixture here is that long by accident.
        AuditId id = AuditId.next(IDS);
        String widest = "x".repeat(Actor.MAX_ID_LENGTH);

        new JdbcAuditWriter()
                .append(
                        application,
                        new AuditRecord(
                                id,
                                new Actor(widest, ActorType.CUSTOMER),
                                OCCURRED,
                                PlatformAuditAction.OUTBOX_EVENT_DISCARDED,
                                PROBE_TARGET,
                                "target-widest",
                                Optional.of("the widest permitted identifier"),
                                AuditOutcome.SUCCEEDED,
                                CorrelationId.of("phase1-fit"),
                                Optional.empty()));
        application.commit();

        assertThat(storedActor(id)).isEqualTo(ActorType.CUSTOMER + "|" + widest);
    }

    @Test
    @DisplayName("the constraint already admits every declared type, so none is added later")
    void theCheckConstraintIsAlreadyComplete() throws SQLException {
        // The other half of "no schema change": a type declared in Java but missing from the
        // CHECK constraint would need a migration the first time Phase 1 used it. AuditEnumMigra-
        // tionTest asserts the two agree textually; this asserts the LIVE constraint accepts them,
        // which is the claim that actually matters and is not the same thing - the migration file
        // agreeing with the enum says nothing about what was applied to this database.
        for (ActorType type : ActorType.values()) {
            AuditId id = AuditId.next(IDS);
            new JdbcAuditWriter()
                    .append(
                            application,
                            new AuditRecord(
                                    id,
                                    new Actor("probe-" + type, type),
                                    OCCURRED,
                                    PlatformAuditAction.OUTBOX_EVENT_DISCARDED,
                                    PROBE_TARGET,
                                    "target-" + type,
                                    Optional.of("every declared type is already accepted"),
                                    AuditOutcome.SUCCEEDED,
                                    CorrelationId.of("phase1-fit"),
                                    Optional.empty()));
            application.commit();
            assertThat(storedActor(id)).isEqualTo(type + "|probe-" + type);
        }
    }

    private static String storedActor(AuditId id) throws SQLException {
        try (PreparedStatement select =
                application.prepareStatement(
                        "SELECT actor_type, actor_id FROM platform.audit_record WHERE audit_id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("the record must exist").isTrue();
                return rows.getString("actor_type") + "|" + rows.getString("actor_id");
            }
        }
    }
}
