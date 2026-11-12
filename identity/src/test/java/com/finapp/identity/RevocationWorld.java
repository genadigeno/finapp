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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The role revocation's and the administrator's suspension's world for a hermetic test (`P15-TSK-032`):
 * {@link IdentityAdministration} over stores that are fakes recording every call in order, the {@code FirstRoleEndsSessionsTest}
 * shape. The live holdings are an in-memory table of {@code (identity, role)} rows and the suspended identities a set; what the
 * database's statements do with them - which rows a locked read returns, and which it waits for - is
 * {@code RoleAssignmentConcurrencyDatabaseTest}'s, {@code IdentityAdministrationDatabaseTest}'s and
 * {@code LastAdministratorDatabaseTest}'s, owed (C14).
 *
 * <p><strong>{@code READ COMMITTED}, modelled.</strong> Each act runs as one transaction on its own thread: its writes - a role
 * row marked, a status moved - are held until it ends, and every read sees only what other acts committed. With
 * {@link #serialised}, the lock on the granting holdings ({@code lockHoldingsOf}) is one transaction at a time from the lock to
 * the commit, which is what {@code FOR UPDATE} gives; without it, each act reads the table as it stood before the other
 * committed. {@link #meetAtTheLock} holds the first act to reach that lock until the other has reached it too, so whatever
 * either read before the lock was read before either committed: a race is decided by what the code reads where, not by which
 * thread was faster. The identity's own
 * lock ({@code lockForRoleChange}) is recorded, not modelled: the cases that race here act on two different identities.
 */
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
final class RevocationWorld {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T09:00:00Z"), ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final Connection UNIT_OF_WORK = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);

    /** A live holding. */
    record Row(IdentityId identity, RoleName role) {}

    /** Every call that reached a store, the trail or the outbox, in order. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<AuditRecord> written = Collections.synchronizedList(new ArrayList<>());
    /** The committed live holdings: what the database would answer. */
    final Set<Row> live = Collections.synchronizedSet(new HashSet<>());
    /** The committed suspended identities. */
    final Set<IdentityId> suspended = Collections.synchronizedSet(new HashSet<>());
    /** The granting roles each lock of the holdings was asked for, in order. */
    final List<Set<RoleName>> grantingAsked = Collections.synchronizedList(new ArrayList<>());
    int liveSessions = 2;
    RuntimeException sessionRevocationFails;
    boolean serialised = true;
    boolean meetAtTheLock;
    /** Run when a count of the granting holdings has been read, before it answers: where a test makes two counts meet. */
    Runnable afterRead = () -> { };

    private final ReentrantLock holdings = new ReentrantLock();
    private final CountDownLatch otherReachedTheHoldings = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean firstLockTaken = new java.util.concurrent.atomic.AtomicBoolean();
    /** This thread's transaction's writes, applied at its commit. */
    private final ThreadLocal<List<Runnable>> pending = ThreadLocal.withInitial(ArrayList::new);

    void holds(IdentityId identity, RoleName role) {
        live.add(new Row(identity, role));
    }

    void holdsSuspended(IdentityId identity, RoleName role) {
        live.add(new Row(identity, role));
        suspended.add(identity);
    }

    Set<RoleName> rolesOf(IdentityId identity) {
        synchronized (live) {
            Set<RoleName> roles = new HashSet<>();
            live.stream().filter(row -> row.identity().equals(identity)).forEach(row -> roles.add(row.role()));
            return roles;
        }
    }

    /** The committed identities holding a role that grants roles and not suspended. */
    Set<IdentityId> activeGranters() {
        synchronized (live) {
            Set<IdentityId> granters = new HashSet<>();
            live.stream().filter(row -> Authorization.grantsRoles(row.role()) && !suspended.contains(row.identity()))
                    .forEach(row -> granters.add(row.identity()));
            return granters;
        }
    }

    /** Revokes as the door does: under the request's scopes, by {@code actor}, one transaction committed at the end. */
    IdentityAdministration.RoleRevocation revoke(IdentityId subject, RoleName role, IdentityId actor) {
        return inATransaction(actor, () -> administration(true)
                .revokeRole(UNIT_OF_WORK, subject, role, actor, "the incident closed, OPS-8812"));
    }

    /** Suspends as the door does ({@code ADMINISTRATOR}), or as risk's action does ({@code RISK}). */
    IdentityAdministration.Suspension suspend(IdentityId subject, IdentityId actor, SuspensionCause cause) {
        return inATransaction(actor, () -> administration(true)
                .suspend(UNIT_OF_WORK, subject, actor, "the account is compromised, OPS-9001", cause));
    }

    private <T> T inATransaction(IdentityId actor, Supplier<T> act) {
        pending.get().clear();
        boolean committed = false;
        try (var correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("the-revocation")));
                var acting = SecurityContext.enter(new Actor(actor.value().toString(), ActorType.EMPLOYEE))) {
            T outcome = act.get();
            committed = true;
            return outcome;
        } finally {
            if (committed) {
                pending.get().forEach(Runnable::run);
            }
            pending.get().clear();
            // The commit, or the rollback: the locked rows are released with the transaction.
            if (holdings.isHeldByCurrentThread()) {
                holdings.unlock();
            }
        }
    }

    @SuppressWarnings("unchecked")
    IdentityAdministration administration(boolean subjectExists) {
        IdentityStore<Connection> identities = (IdentityStore<Connection>) Proxy.newProxyInstance(IdentityStore.class.getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findById" -> {
                            calls.add("findById");
                            if (!subjectExists) {
                                return Optional.empty();
                            }
                            IdentityId id = (IdentityId) args[1];
                            return Optional.of(Identity.rehydrate(id, UUID.randomUUID(), new LoginIdentifier("revocation-world"),
                                    suspended.contains(id) ? IdentityStatus.SUSPENDED : IdentityStatus.ACTIVE,
                                    CLOCK.instant().minusSeconds(3600), CLOCK.instant().minusSeconds(3600)));
                        }
                        case "moveStatus" -> {
                            IdentityId id = (IdentityId) args[1];
                            calls.add("moveStatus " + ((Identity) args[3]).status());
                            assertThat(((Optional<SuspensionCause>) args[4])).isPresent();
                            if (suspended.contains(id)) {
                                return false;
                            }
                            pending.get().add(() -> suspended.add(id));
                            return true;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
        RoleAssignmentStore<Connection> assignments = (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(
                RoleAssignmentStore.class.getClassLoader(), new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
                    assertThat(args[0]).as("%s on the act's unit of work", method.getName()).isSameAs(UNIT_OF_WORK);
                    switch (method.getName()) {
                        case "lockForRoleChange" -> {
                            calls.add("lockForRoleChange");
                            return null;
                        }
                        case "liveRolesOf" -> {
                            calls.add("liveRolesOf");
                            return Set.copyOf(rolesOf((IdentityId) args[1]));
                        }
                        case "lockHoldingsOf" -> {
                            calls.add("lockHoldingsOf");
                            grantingAsked.add(Set.copyOf((Set<RoleName>) args[1]));
                            boolean first = firstLockTaken.compareAndSet(false, true);
                            if (!first) {
                                otherReachedTheHoldings.countDown();
                            }
                            if (serialised) {
                                holdings.lock();
                            }
                            if (meetAtTheLock && first) {
                                // Hold the first act here until the other has reached the lock - everything it reads before
                                // the lock is then read before this act commits - or give up waiting.
                                otherReachedTheHoldings.await(5, TimeUnit.SECONDS);
                            }
                            return null;
                        }
                        case "activeHoldingsOf" -> {
                            calls.add("activeHoldingsOf");
                            Set<RoleName> granting = (Set<RoleName>) args[1];
                            Set<RoleAssignmentStore.Holding> active = new HashSet<>();
                            synchronized (live) {
                                for (Row row : live) {
                                    if (granting.contains(row.role()) && !suspended.contains(row.identity())) {
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
                            Row row = new Row((IdentityId) args[1], (RoleName) args[2]);
                            if (!live.contains(row)) {
                                return false;
                            }
                            pending.get().add(() -> live.remove(row));
                            return true;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
        SessionStore<Connection> sessionStore = (SessionStore<Connection>) Proxy.newProxyInstance(
                SessionStore.class.getClassLoader(), new Class<?>[] {SessionStore.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("revokeAllFor")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    assertThat(args[0]).as("the session revocation joins the act's unit of work").isSameAs(UNIT_OF_WORK);
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
                    throw new UnsupportedOperationException("a revocation and a suspension read no factor");
                });
        com.finapp.platform.audit.AuditWriter<Connection> trail = (uow, record) -> {
            calls.add("audit " + record.operation().code());
            written.add(record);
        };
        return new IdentityAdministration(IDS, CLOCK, identities, new Authorization(assignments, IDS, CLOCK, trail),
                new SessionRevocation(sessionStore, IDS, CLOCK, trail), trail,
                (uow, envelope, payload, mediaType) -> calls.add("outbox " + envelope.eventType()), enrolments);
    }
}
