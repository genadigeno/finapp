package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.objenesis.SpringObjenesis;

/**
 * The role revocation's world for a hermetic test (`P15-TSK-032`): {@link IdentityAdministration} over stores that are fakes
 * recording every call in order, the {@code FirstRoleEndsSessionsTest} shape. The live holdings are one in-memory table of
 * {@code (identity, role, ACTIVE?)} rows; what the database's statements do with them - which rows a locked read returns, and
 * which it waits for - is {@code RoleAssignmentConcurrencyDatabaseTest}'s and {@code IdentityAdministrationDatabaseTest}'s,
 * owed (C14).
 *
 * <p>{@link #serialised} models what {@code FOR UPDATE} gives the read of the granting holdings: one transaction at a time
 * from that read to its commit. Without it, each read sees the rows as they stood before the other wrote, which is what
 * {@code READ COMMITTED} gives two unlocked reads. The identity's own lock ({@code lockForRoleChange}) is recorded, not
 * modelled: the cases that race here revoke two different identities' roles, which that lock does not serialise.
 */
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
final class RevocationWorld {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T09:00:00Z"), ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final Connection UNIT_OF_WORK = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);

    /** A live holding, and whether its identity is {@code ACTIVE}. */
    record Row(IdentityId identity, RoleName role, boolean active) {}

    /** Every call that reached a store or the trail, in order. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<AuditRecord> written = Collections.synchronizedList(new ArrayList<>());
    /** The live holdings: what the database would answer. */
    final Set<Row> live = Collections.synchronizedSet(new HashSet<>());
    /** The granting roles each locked read of the holdings was asked for, in order. */
    final List<Set<RoleName>> grantingAsked = Collections.synchronizedList(new ArrayList<>());
    int liveSessions = 2;
    RuntimeException sessionRevocationFails;
    boolean serialised = true;
    /** Run inside the read of the granting holdings after the rows are read, before it answers: where a test makes two meet. */
    Runnable afterRead = () -> { };

    private final ReentrantLock rows = new ReentrantLock();

    void holds(IdentityId identity, RoleName role) {
        live.add(new Row(identity, role, true));
    }

    void holdsSuspended(IdentityId identity, RoleName role) {
        live.add(new Row(identity, role, false));
    }

    Set<RoleName> rolesOf(IdentityId identity) {
        synchronized (live) {
            Set<RoleName> roles = new HashSet<>();
            live.stream().filter(row -> row.identity().equals(identity)).forEach(row -> roles.add(row.role()));
            return roles;
        }
    }

    /** Revokes as the door runs it: under the request's scopes, by {@code actor}, one transaction committed at the end. */
    IdentityAdministration.RoleRevocation revoke(IdentityId subject, RoleName role, IdentityId actor) {
        try (var correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("the-revocation")));
                var acting = SecurityContext.enter(new Actor(actor.value().toString(), ActorType.EMPLOYEE))) {
            return administration(true).revokeRole(UNIT_OF_WORK, subject, role, actor, "the incident closed, OPS-8812");
        } finally {
            // The commit: the locked rows are released with the transaction.
            if (rows.isHeldByCurrentThread()) {
                rows.unlock();
            }
        }
    }

    @SuppressWarnings("unchecked")
    IdentityAdministration administration(boolean subjectExists) {
        IdentityStore<Connection> identities = (IdentityStore<Connection>) Proxy.newProxyInstance(IdentityStore.class.getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        calls.add("findById");
                        return subjectExists ? Optional.of(new SpringObjenesis().newInstance(Identity.class)) : Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        RoleAssignmentStore<Connection> assignments = (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(
                RoleAssignmentStore.class.getClassLoader(), new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
                    assertThat(args[0]).as("%s on the revocation's unit of work", method.getName()).isSameAs(UNIT_OF_WORK);
                    switch (method.getName()) {
                        case "lockForRoleChange" -> {
                            calls.add("lockForRoleChange");
                            return null;
                        }
                        case "liveRolesOf" -> {
                            calls.add("liveRolesOf");
                            return Set.copyOf(rolesOf((IdentityId) args[1]));
                        }
                        case "lockActiveHoldingsOf" -> {
                            calls.add("lockActiveHoldingsOf");
                            Set<RoleName> granting = (Set<RoleName>) args[1];
                            grantingAsked.add(Set.copyOf(granting));
                            if (serialised) {
                                rows.lock();
                            }
                            Set<RoleAssignmentStore.Holding> active = new HashSet<>();
                            synchronized (live) {
                                for (Row row : live) {
                                    if (granting.contains(row.role()) && row.active()) {
                                        active.add(new RoleAssignmentStore.Holding(row.identity(), row.role()));
                                    }
                                }
                            }
                            afterRead.run();
                            return Set.copyOf(active);
                        }
                        case "revoke" -> {
                            calls.add("revoke " + args[2]);
                            assertThat(args[3]).as("revoked by the actor").isNotEqualTo(args[1]);
                            return live.removeIf(row -> row.identity().equals(args[1]) && row.role() == args[2]);
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
        SessionStore<Connection> sessionStore = (SessionStore<Connection>) Proxy.newProxyInstance(
                SessionStore.class.getClassLoader(), new Class<?>[] {SessionStore.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("revokeAllFor")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    assertThat(args[0]).as("the session revocation joins the revocation's unit of work").isSameAs(UNIT_OF_WORK);
                    calls.add("revokeAllFor " + args[1]);
                    if (sessionRevocationFails != null) {
                        throw sessionRevocationFails;
                    }
                    int ended = liveSessions;
                    liveSessions = 0;
                    return ended;
                });
        MfaEnrolmentStore<Connection> enrolments = (MfaEnrolmentStore<Connection>) Proxy.newProxyInstance(
                MfaEnrolmentStore.class.getClassLoader(), new Class<?>[] {MfaEnrolmentStore.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException("a revocation reads no factor");
                });
        com.finapp.platform.audit.AuditWriter<Connection> trail = (uow, record) -> {
            calls.add("audit " + record.operation().code());
            written.add(record);
        };
        return new IdentityAdministration(IDS, CLOCK, identities, new Authorization(assignments, IDS, CLOCK, trail),
                new SessionRevocation(sessionStore, IDS, CLOCK, trail), trail,
                (uow, envelope, payload, mediaType) -> {
                    throw new UnsupportedOperationException("a revocation announces no event");
                }, enrolments);
    }
}
