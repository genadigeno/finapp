package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.IdentityDirectory;
import com.finapp.identity.IdentityDirectoryStore;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.JdbcIdentityDirectoryStore;
import com.finapp.identity.RoleName;
import com.finapp.platform.audit.JdbcAuditWriter;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The staff directory's reads on PostgreSQL (`P17-TSK-014`): who is in it (an identity that holds or held a role, never one that
 * never did), its declared order walked by keyset, its filters, one identity's detail, the lookup - and what each read leaves in
 * the audit trail, the login identifier never. Seeds are raw rows created in 2099, so they head a newest-first directory whatever
 * else the shared container holds.
 */
@Tag("database")
@DisplayName("the staff directory on PostgreSQL (P17-TSK-014)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class IdentityDirectoryDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor READER = new Actor("p17-014-reader", ActorType.EMPLOYEE);

    private final IdentityDirectory directory =
            new IdentityDirectory(new JdbcIdentityDirectoryStore(), new JdbcAuditWriter(), IDS, CLOCK);

    private String run;
    private Instant base;
    private UUID admin;
    private UUID revoked;
    private UUID suspended;
    private UUID neverStaff;
    private UUID mixed;

    @BeforeEach
    void seed() throws SQLException {
        run = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        // A distinct hour of 2099 per run: this run's identities head the directory and keep their own order.
        base = Instant.parse("2099-01-01T00:00:00Z").plusSeconds(3600L * (Math.abs(run.hashCode()) % 100_000));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            admin = identity(app, "admin", 1, "ACTIVE", null);
            revoked = identity(app, "revoked", 2, "ACTIVE", null);
            suspended = identity(app, "suspended", 3, "SUSPENDED", "ADMINISTRATOR");
            neverStaff = identity(app, "customer", 4, "ACTIVE", null);
            mixed = identity(app, "mixed", 5, "ACTIVE", null);
            role(app, admin, "ADMINISTRATOR", admin, false);
            role(app, revoked, "LEDGER_OPERATOR", admin, true);
            role(app, suspended, "RECONCILIATION_OPERATOR", admin, false);
            role(app, suspended, "KYC_REVIEWER", admin, false);
            role(app, mixed, "ACCOUNTANT", admin, true);
            role(app, mixed, "EVENT_OPERATOR", admin, false);
            app.commit();
        }
    }

    @Test
    @DisplayName("the directory: whoever holds or held a role, newest first by creation and id, each once across keyset pages; the"
            + " roles shown are the live ones; an identity that never held a role is not in it")
    void theDirectoryIsTheStaffInTheirDeclaredOrder() throws SQLException {
        List<IdentityDirectoryStore.Entry> mine = walk(Optional.empty(), Optional.empty()).stream()
                .filter(entry -> run(entry.identityId())).toList();

        assertThat(mine).extracting(entry -> entry.identityId().value())
                .containsExactly(mixed, suspended, revoked, admin)
                .doesNotContain(neverStaff);
        assertThat(mine.get(0).liveRoles()).as("a revoked holding is not a live role").containsExactly(RoleName.EVENT_OPERATOR);
        assertThat(mine.get(1).liveRoles()).containsExactly(RoleName.KYC_REVIEWER, RoleName.RECONCILIATION_OPERATOR);
        assertThat(mine.get(2).liveRoles()).as("held once, holds nothing now, and is still in the directory").isEmpty();
        assertThat(mine.get(1).status()).isEqualTo(IdentityStatus.SUSPENDED);
    }

    @Test
    @DisplayName("the filters: a role is a live holding of it, a status the identity's own")
    void theFiltersNarrowIt() throws SQLException {
        assertThat(ids(walk(Optional.of(RoleName.RECONCILIATION_OPERATOR), Optional.empty()))).containsExactly(suspended);
        assertThat(ids(walk(Optional.of(RoleName.LEDGER_OPERATOR), Optional.empty()))).as("a revoked holder holds it no more").isEmpty();
        assertThat(ids(walk(Optional.empty(), Optional.of(IdentityStatus.SUSPENDED)))).containsExactly(suspended);
        assertThat(ids(walk(Optional.of(RoleName.ADMINISTRATOR), Optional.of(IdentityStatus.ACTIVE)))).containsExactly(admin);
    }

    @Test
    @DisplayName("the detail: status, the suspension's instant and cause, the factor, the live roles with who granted them - and a"
            + " record naming the identity; an unknown identity is no read and records nothing")
    void theDetailIsIdentifierFirstAndRecorded() throws SQLException {
        String flow = "p17-014-detail-" + run;
        IdentityDirectoryStore.Detail detail = as(flow, uow -> directory.detail(uow, IdentityId.of(suspended))).orElseThrow();

        assertThat(detail.status()).isEqualTo(IdentityStatus.SUSPENDED);
        assertThat(detail.suspendedAt()).contains(base.plusSeconds(3 * 60 + 30));
        assertThat(detail.suspensionCause()).contains("ADMINISTRATOR");
        assertThat(detail.factorEnrolled()).isFalse();
        assertThat(detail.roles()).extracting(IdentityDirectoryStore.LiveRole::role)
                .containsExactlyInAnyOrder(RoleName.RECONCILIATION_OPERATOR, RoleName.KYC_REVIEWER);
        assertThat(detail.roles()).extracting(role -> role.assignedBy().value()).containsOnly(admin);
        assertThat(audits(flow)).containsExactly("identity.IdentityRead|" + suspended + "|roles=2, factorEnrolled=false");

        String unknownFlow = "p17-014-unknown-" + run;
        Optional<IdentityDirectoryStore.Detail> unknown = as(unknownFlow, uow -> directory.detail(uow, IdentityId.of(IDS.next())));
        assertThat(unknown).isEmpty();
        assertThat(audits(unknownFlow)).isEmpty();
    }

    @Test
    @DisplayName("the lookup: an exact login identifier answers its identity, anything else nothing - recorded either way, the"
            + " identifier never")
    void theLookupIsExactAndNeverRecordsTheIdentifier() throws SQLException {
        String login = "p17d.admin." + run;
        String flow = "p17-014-lookup-" + run;

        Optional<IdentityId> exact = as(flow, uow -> directory.lookup(uow, login));
        Optional<IdentityId> nobody = as(flow, uow -> directory.lookup(uow, "p17d.nobody." + run));
        Optional<IdentityId> folded = as(flow, uow -> directory.lookup(uow, "P17D.ADMIN." + run));
        Optional<IdentityId> malformed = as(flow, uow -> directory.lookup(uow, "not a login!"));
        assertThat(exact).contains(IdentityId.of(admin));
        assertThat(nobody).isEmpty();
        assertThat(folded).as("exact: no case folding").isEmpty();
        assertThat(malformed).as("a shape no identity carries").isEmpty();

        List<String> recorded = audits(flow);
        assertThat(recorded).containsExactly(
                "identity.IdentityLookedUp|" + admin + "|matched=true",
                "identity.IdentityLookedUp|no-match|matched=false",
                "identity.IdentityLookedUp|no-match|matched=false",
                "identity.IdentityLookedUp|no-match|matched=false");
        assertThat(String.join("\n", recorded)).doesNotContainIgnoringCase(run);
    }

    @Test
    @DisplayName("a page of the directory is recorded once, its filter and its count the summary")
    void aPageIsRecordedOnce() throws SQLException {
        String flow = "p17-014-page-" + run;
        List<IdentityDirectoryStore.Entry> page = as(flow, uow ->
                directory.page(uow, Optional.of(RoleName.KYC_REVIEWER), Optional.empty(), Optional.empty(), 3));

        assertThat(audits(flow)).containsExactly(
                "identity.IdentityDirectoryRead|staff-directory|role=KYC_REVIEWER, status=any, after=false, served=" + page.size());
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private List<IdentityDirectoryStore.Entry> walk(Optional<RoleName> role, Optional<IdentityStatus> status) throws SQLException {
        List<IdentityDirectoryStore.Entry> walked = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Optional<IdentityDirectoryStore.Cursor> after = Optional.empty();
        while (true) {
            Optional<IdentityDirectoryStore.Cursor> from = after;
            List<IdentityDirectoryStore.Entry> page = as("p17-014-walk-" + run, uow -> directory.page(uow, role, status, from, 2));
            assertThat(page.size()).isLessThanOrEqualTo(2);
            if (page.isEmpty() || page.stream().noneMatch(entry -> !entry.createdAt().isBefore(base))) {
                // Past this run's hour: the rest of the directory is other suites' and older.
                walked.addAll(page);
                assertThat(walked).isSortedAccordingTo(Comparator.comparing(IdentityDirectoryStore.Entry::createdAt).reversed()
                        .thenComparing(Comparator.comparing((IdentityDirectoryStore.Entry entry) -> entry.identityId().value()).reversed()));
                return walked;
            }
            for (IdentityDirectoryStore.Entry entry : page) {
                assertThat(seen.add(entry.identityId().value())).as("served once").isTrue();
            }
            walked.addAll(page);
            IdentityDirectoryStore.Entry tail = page.getLast();
            after = Optional.of(new IdentityDirectoryStore.Cursor(tail.createdAt(), tail.identityId().value()));
        }
    }

    private List<UUID> ids(List<IdentityDirectoryStore.Entry> entries) {
        return entries.stream().filter(entry -> run(entry.identityId())).map(entry -> entry.identityId().value()).toList();
    }

    private boolean run(IdentityId id) {
        return Set.of(admin, revoked, suspended, neverStaff, mixed).contains(id.value());
    }

    private <R> R as(String flow, Function<Connection, R> work) throws SQLException {
        try (SecurityContext.Scope actor = SecurityContext.enter(READER);
                CorrelationContext.Scope correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(flow)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    private static List<String> audits(String flow) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement select = root.prepareStatement("SELECT operation || '|' || target_id || '|' || coalesce(change_summary, '')"
                        + " FROM platform.audit_record WHERE correlation_id = ? ORDER BY occurred_at, audit_id")) {
            select.setString(1, flow);
            try (ResultSet rows = select.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
                return found;
            }
        }
    }

    /** An identity created a few minutes into this run's hour, minute by minute, so the directory's order is the seeds' own. */
    private UUID identity(Connection app, String label, int minute, String status, String cause) throws SQLException {
        UUID id = IDS.next();
        Instant created = base.plusSeconds(minute * 60L);
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at, suspension_cause)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, IDS.next());
            insert.setString(3, "p17d." + label + "." + run);
            insert.setString(4, status);
            insert.setTimestamp(5, Timestamp.from(created));
            insert.setTimestamp(6, Timestamp.from(created.plusSeconds(30)));
            insert.setString(7, cause);
            insert.executeUpdate();
        }
        return id;
    }

    private static void role(Connection app, UUID identity, String role, UUID by, boolean revokedSince) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at, revoked_by, revoked_at)"
                        + " VALUES (?, ?, ?, ?, now(), ?, CASE WHEN ? THEN now() END)")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, identity);
            insert.setString(3, role);
            insert.setObject(4, by);
            insert.setObject(5, revokedSince ? by : null);
            insert.setBoolean(6, revokedSince);
            insert.executeUpdate();
        }
    }
}
