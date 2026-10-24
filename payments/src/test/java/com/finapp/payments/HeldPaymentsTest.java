package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A held card payment's doors and its expiry, over in-memory rows with {@code V034}'s conditionals (`P13-TSK-010`,
 * slice 2; {@code PHASE_13_PLAN.md} §5, §7.4, §12.8, §14; ADR-0112; {@code INV-RSK-02}): the capture door within
 * {@code held_until} and refused past it, the void door, the deadline once, a capture racing the deadline leaving exactly
 * one resolution (the loser {@code ALREADY}), a re-driven key replaying its answer and re-driving the converging
 * command, an authorisation still in flight thrown with nothing recorded, the doors' callers and reason codes, a failing
 * row passed over. The {@code SKIP LOCKED} claim, the database clock and the triggers are the owed
 * {@code PaymentRiskSeamDatabaseTest}'s and {@code PaymentRiskMigrationDatabaseTest}'s (C5).
 */
@DisplayName("a held card payment's doors and expiry: exactly once (P13-TSK-010)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class HeldPaymentsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Money AMOUNT = Money.ofMinorUnits(300_00, CurrencyCode.of("EUR"));
    private static final Actor PLATFORM = Actor.SYSTEM;
    private static final Actor OPERATOR = new Actor("employee-1", ActorType.EMPLOYEE);

    private final World world = new World();
    private final HeldPayments held = new HeldPayments(world.runner, world.holds, world.attempts(),
            new IdempotentExecutor(world.claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
            (uow, record) -> world.audit.add(record), world.commands, IDS, CLOCK, 10);

    // ------------------------------------------------------------------ the capture door

    @Test
    @DisplayName("a capture within held_until is DONE once: the hold resolved CAPTURE with the key, audited as the"
            + " caller's act with its reason code, and the capture driven; another key reads ALREADY")
    void aCaptureWithinTheWindowIsDoneOnce() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTHORIZED);

        HeldPayments.Answer done = as(PLATFORM, () -> held.captureHeld("risk.action:1", intent, "OVERRIDE_APPROVED"));
        assertThat(done.outcome()).isEqualTo(HeldPayments.HeldOutcome.DONE);
        assertThat(done.resolution()).contains(CaptureHold.Resolution.CAPTURE);
        assertThat(world.hold(intent).resolutionKey()).contains("risk.action:1");
        assertThat(world.commands.captured).containsExactly(world.attemptOf(intent));
        assertThat(world.audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(PaymentsAuditAction.HELD_PAYMENT_CAPTURED);
            assertThat(record.actor()).isEqualTo(PLATFORM);
            assertThat(record.reason()).contains("OVERRIDE_APPROVED");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        });

        HeldPayments.Answer other = as(PLATFORM, () -> held.captureHeld("risk.action:2", intent, "OVERRIDE_APPROVED"));
        assertThat(other.outcome()).isEqualTo(HeldPayments.HeldOutcome.ALREADY);
        assertThat(other.resolution()).contains(CaptureHold.Resolution.CAPTURE);
        assertThat(world.audit).as("an ALREADY records nothing").hasSize(1);
        assertThat(world.commands.captured).as("and drives nothing").hasSize(1);
    }

    @Test
    @DisplayName("a re-driven key replays its DONE and re-drives the capture, which converges - the finisher of a"
            + " crash between the resolution's commit and the capture")
    void aReDrivenKeyReplaysAndReDrives() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTHORIZED);
        as(PLATFORM, () -> held.captureHeld("risk.action:7", intent, "OVERRIDE_APPROVED"));
        HeldPayments.Answer replay = as(PLATFORM, () -> held.captureHeld("risk.action:7", intent, "OVERRIDE_APPROVED"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.outcome()).isEqualTo(HeldPayments.HeldOutcome.DONE);
        assertThat(world.commands.captured).as("the converging capture driven again").hasSize(2);
        assertThat(world.audit).as("the act recorded once").hasSize(1);
    }

    @Test
    @DisplayName("a capture past held_until on the database clock is REFUSED with nothing written: the expiry is the"
            + " policy's outcome, and the leg then expires and voids it")
    void aLateCaptureIsRefused() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTHORIZED);
        world.now = world.hold(intent).heldUntil();

        HeldPayments.Answer late = as(PLATFORM, () -> held.captureHeld("risk.action:3", intent, "OVERRIDE_APPROVED"));
        assertThat(late.outcome()).isEqualTo(HeldPayments.HeldOutcome.REFUSED);
        assertThat(world.hold(intent).open()).isTrue();
        assertThat(world.commands.captured).isEmpty();
        assertThat(world.audit).isEmpty();

        HeldPayments.ExpiryTally tick = held.expireHolds();
        assertThat(tick.expired()).isEqualTo(1);
        assertThat(tick.voided()).isEqualTo(1);
        assertThat(world.hold(intent).resolution()).contains(CaptureHold.Resolution.EXPIRED);
        assertThat(world.commands.released).containsExactly(intent + "|" + HeldPayments.EXPIRY_REASON);
    }

    @Test
    @DisplayName("#captureAndVoidLeaveExactlyOne: the deadline's expiry first - the capture reads ALREADY(EXPIRED);"
            + " the capture first - the leg finds nothing to expire or void; exactly one resolution either way")
    void captureAndTheDeadlineLeaveExactlyOne() {
        PaymentIntentId expiredFirst = world.held(PaymentAttemptStatus.AUTHORIZED);
        PaymentIntentId capturedFirst = world.held(PaymentAttemptStatus.AUTHORIZED);
        assertThat(as(PLATFORM, () -> held.captureHeld("risk.action:c", capturedFirst, "OVERRIDE_APPROVED"))
                .outcome()).isEqualTo(HeldPayments.HeldOutcome.DONE);
        world.attemptStatus.put(world.attemptOf(capturedFirst), PaymentAttemptStatus.CAPTURE_DISPATCHED);

        world.now = world.now.plus(Duration.ofHours(73));
        HeldPayments.ExpiryTally tick = held.expireHolds();
        assertThat(tick.expired()).as("only the open one").isEqualTo(1);
        assertThat(world.hold(capturedFirst).resolution()).contains(CaptureHold.Resolution.CAPTURE);
        assertThat(world.hold(expiredFirst).resolution()).contains(CaptureHold.Resolution.EXPIRED);

        HeldPayments.Answer loser = as(PLATFORM, () -> held.captureHeld("risk.action:e", expiredFirst,
                "OVERRIDE_APPROVED"));
        assertThat(loser.outcome()).isEqualTo(HeldPayments.HeldOutcome.ALREADY);
        assertThat(loser.resolution()).contains(CaptureHold.Resolution.EXPIRED);
        assertThat(world.commands.captured).containsExactly(world.attemptOf(capturedFirst));
        assertThat(world.commands.released).containsExactly(expiredFirst + "|" + HeldPayments.EXPIRY_REASON);
    }

    // ------------------------------------------------------------------ the void door

    @Test
    @DisplayName("a void is DONE once - the hold resolved VOID, audited, the authorisation released with the reason"
            + " code; a capture afterwards reads ALREADY(VOID)")
    void aVoidIsDoneOnce() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTHORIZED);
        HeldPayments.Answer voided = as(OPERATOR, () -> held.voidHeld("risk.action:v", intent, "FRAUD_CONFIRMED"));
        assertThat(voided.outcome()).isEqualTo(HeldPayments.HeldOutcome.DONE);
        assertThat(voided.resolution()).contains(CaptureHold.Resolution.VOID);
        assertThat(world.commands.released).containsExactly(intent + "|FRAUD_CONFIRMED");
        assertThat(world.audit).singleElement()
                .satisfies(record -> assertThat(record.operation()).isEqualTo(PaymentsAuditAction.HELD_PAYMENT_VOIDED));

        assertThat(as(PLATFORM, () -> held.captureHeld("risk.action:x", intent, "OVERRIDE_APPROVED")).resolution())
                .contains(CaptureHold.Resolution.VOID);
        assertThat(world.commands.captured).isEmpty();
    }

    @Test
    @DisplayName("the finisher voids a VOID whose release never ran (a crash between the commits) - once")
    void theFinisherVoidsAStrandedVoid() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTHORIZED);
        world.commands.failNextRelease = true;
        assertThatThrownBy(() -> as(PLATFORM, () -> held.voidHeld("risk.action:s", intent, "FRAUD_CONFIRMED")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(world.hold(intent).resolution()).as("the resolution committed").contains(CaptureHold.Resolution.VOID);

        HeldPayments.ExpiryTally tick = held.expireHolds();
        assertThat(tick.voided()).isEqualTo(1);
        assertThat(world.commands.released).containsExactly(intent + "|" + HeldPayments.VOIDED_REASON);
        assertThat(held.expireHolds().voided()).as("a released authorisation is never voided again").isZero();
    }

    // ------------------------------------------------------------------ the authorisation and the refusals

    @Test
    @DisplayName("an authorisation still in flight is thrown with nothing recorded under the key - the same key"
            + " succeeds once the issuer has answered")
    void anAuthorisationInFlightIsThrown() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTH_UNKNOWN);
        assertThatThrownBy(() -> as(PLATFORM, () -> held.captureHeld("risk.action:f", intent, "OVERRIDE_APPROVED")))
                .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
        assertThat(world.hold(intent).open()).isTrue();
        assertThat(world.claims.rows).as("the claim rolled back with the transaction").isEmpty();

        world.attemptStatus.put(world.attemptOf(intent), PaymentAttemptStatus.AUTHORIZED);
        assertThat(as(PLATFORM, () -> held.captureHeld("risk.action:f", intent, "OVERRIDE_APPROVED")).outcome())
                .isEqualTo(HeldPayments.HeldOutcome.DONE);
    }

    @Test
    @DisplayName("an authorisation that no longer stands (declined, or the customer's own void) reads ALREADY and"
            + " resolves nothing; an unknown intent is REFUSED")
    void anAuthorisationThatNoLongerStandsIsAlready() {
        PaymentIntentId declined = world.held(PaymentAttemptStatus.FAILED);
        assertThat(as(PLATFORM, () -> held.captureHeld("risk.action:d", declined, "OVERRIDE_APPROVED")))
                .satisfies(answer -> {
                    assertThat(answer.outcome()).isEqualTo(HeldPayments.HeldOutcome.ALREADY);
                    assertThat(answer.resolution()).isEmpty();
                });
        assertThat(world.hold(declined).open()).isTrue();
        assertThat(as(PLATFORM, () -> held.voidHeld("risk.action:u", PaymentIntentId.next(IDS), "FRAUD_CONFIRMED"))
                .outcome()).isEqualTo(HeldPayments.HeldOutcome.REFUSED);
        assertThat(world.audit).isEmpty();
    }

    @Test
    @DisplayName("the doors refuse a customer, a note for a reason code and a blank or unbounded key - before"
            + " anything is read")
    void theDoorsRefuseTheWrongCallerAndInput() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTHORIZED);
        Actor customer = new Actor(IDS.next().toString(), ActorType.CUSTOMER);
        assertThatThrownBy(() -> as(customer, () -> held.captureHeld("k", intent, "OVERRIDE_APPROVED")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> as(PLATFORM, () -> held.captureHeld("k", intent, "the analyst said so")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason code");
        assertThatThrownBy(() -> as(PLATFORM, () -> held.voidHeld(" ", intent, "FRAUD_CONFIRMED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(PLATFORM, () -> held.voidHeld("k".repeat(201), intent, "FRAUD_CONFIRMED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(world.hold(intent).open()).isTrue();
        assertThat(world.claims.rows).isEmpty();
    }

    // ------------------------------------------------------------------ the deadline

    @Test
    @DisplayName("the deadline is the platform's and once: two ticks past held_until expire it once, audited"
            + " HeldPaymentExpired as the platform, the authorisation released once; before held_until nothing moves")
    void theDeadlineExpiresOnce() {
        PaymentIntentId intent = world.held(PaymentAttemptStatus.AUTHORIZED);
        assertThat(held.expireHolds().expired()).as("before held_until").isZero();
        assertThat(world.hold(intent).open()).isTrue();

        world.now = world.now.plus(Duration.ofHours(72));
        assertThat(held.expireHolds().expired()).isEqualTo(1);
        assertThat(held.expireHolds().expired()).isZero();
        assertThat(world.audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(PaymentsAuditAction.HELD_PAYMENT_EXPIRED);
            assertThat(record.actor().type()).isEqualTo(ActorType.SYSTEM);
        });
        assertThat(world.commands.released).hasSize(1);
    }

    @Test
    @DisplayName("one bad row never stalls the queue: a hold whose expiry fails is passed over this tick and the next"
            + " is expired")
    void aFailingRowIsPassedOver() {
        PaymentIntentId poisoned = world.held(PaymentAttemptStatus.AUTHORIZED);
        PaymentIntentId healthy = world.held(PaymentAttemptStatus.AUTHORIZED);
        world.now = world.now.plus(Duration.ofHours(80));
        world.holds.failExpiring = poisoned;

        HeldPayments.ExpiryTally tick = held.expireHolds();
        assertThat(tick.failed()).isEqualTo(1);
        assertThat(tick.expired()).isEqualTo(1);
        assertThat(world.hold(poisoned).open()).isTrue();
        assertThat(world.hold(healthy).resolution()).contains(CaptureHold.Resolution.EXPIRED);
    }

    // ------------------------------------------------------------------ the world

    private static <T> T as(Actor actor, Supplier<T> call) {
        try (SecurityContext.Scope acting = SecurityContext.enter(actor);
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return call.get();
        }
    }

    /** The unit of work handed through: every store here is in memory, so any use of it fails the suite. */
    private static final Connection UNTOUCHED = (Connection) Proxy.newProxyInstance(
            HeldPaymentsTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) {
                    return "UNTOUCHED";
                }
                throw new AssertionError("the unit of work was used directly: " + method.getName());
            });

    /** In-memory rows with V034's conditionals and a database clock the suite moves. */
    private static final class World {
        Instant now = CLOCK.instant();
        final Map<PaymentAttemptId, PaymentAttemptStatus> attemptStatus = new HashMap<>();
        final Map<PaymentAttemptId, PaymentIntentId> attemptIntent = new HashMap<>();
        final Holds holds = new Holds();
        final Claims claims = new Claims();
        final List<AuditRecord> audit = new ArrayList<>();
        final Commands commands = new Commands();
        final TransactionRunner runner = new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                Map<PaymentIntentId, CaptureHold> holdsBefore = new LinkedHashMap<>(holds.rows);
                Map<IdempotencyKey, IdempotencyRecord> claimsBefore = new LinkedHashMap<>(claims.rows);
                int auditBefore = audit.size();
                try {
                    return work.apply(UNTOUCHED);
                } catch (RuntimeException rolledBack) {
                    holds.rows.clear();
                    holds.rows.putAll(holdsBefore);
                    claims.rows.clear();
                    claims.rows.putAll(claimsBefore);
                    audit.subList(auditBefore, audit.size()).clear();
                    throw rolledBack;
                }
            }
        };

        PaymentIntentId held(PaymentAttemptStatus attemptAt) {
            PaymentIntentId intent = PaymentIntentId.next(IDS);
            PaymentAttemptId attempt = PaymentAttemptId.next(IDS);
            attemptStatus.put(attempt, attemptAt);
            attemptIntent.put(attempt, intent);
            holds.rows.put(intent, new CaptureHold(intent, attempt, now, now.plus(Duration.ofHours(72)),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            return intent;
        }

        CaptureHold hold(PaymentIntentId intent) {
            return holds.rows.get(intent);
        }

        PaymentAttemptId attemptOf(PaymentIntentId intent) {
            return hold(intent).attemptId();
        }

        /** The attempt store's one read the doors make: the attempt in its scripted status. */
        @SuppressWarnings("unchecked")
        PaymentAttemptStore<Connection> attempts() {
            return (PaymentAttemptStore<Connection>) Proxy.newProxyInstance(HeldPaymentsTest.class.getClassLoader(),
                    new Class<?>[] {PaymentAttemptStore.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("findById")) {
                            throw new AssertionError("the doors read the attempt and nothing else: " + method);
                        }
                        PaymentAttemptId id = (PaymentAttemptId) args[1];
                        return Optional.ofNullable(attemptStatus.get(id)).map(status -> attemptIn(id, status));
                    });
        }

        private PaymentAttempt attemptIn(PaymentAttemptId id, PaymentAttemptStatus status) {
            boolean authorized = status != PaymentAttemptStatus.AUTH_DISPATCHED
                    && status != PaymentAttemptStatus.AUTH_UNKNOWN
                    && status != PaymentAttemptStatus.FAILED;
            boolean captureRef = status == PaymentAttemptStatus.CAPTURE_DISPATCHED;
            return PaymentAttempt.rehydrate(id, attemptIntent.get(id), SimulatedCardPspAdapter.RAIL.id(),
                    InteractionModel.TWO_STEP, new ProviderIdempotencyReference("auth-" + id.value()),
                    captureRef ? new ProviderIdempotencyReference("cap-" + id.value()) : null,
                    authorized ? new ProviderReference("psp-" + id.value()) : null,
                    authorized ? AMOUNT : null, null, null, null, null,
                    status == PaymentAttemptStatus.FAILED ? PaymentFailureReason.DECLINED : null,
                    status, CLOCK.instant(), null, null, null, null, null);
        }

        /** payment_capture_hold in memory, V034's conditionals on the suite's database clock. */
        final class Holds implements CaptureHoldStore<Connection> {
            final Map<PaymentIntentId, CaptureHold> rows = new LinkedHashMap<>();
            PaymentIntentId failExpiring;

            @Override
            public void insert(Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, Duration validity) {
                throw new UnsupportedOperationException("the confirmation births holds, not these doors");
            }

            @Override
            public Optional<CaptureHold> findForIntent(Connection uow, PaymentIntentId intent) {
                return Optional.ofNullable(rows.get(intent));
            }

            @Override
            public Optional<CaptureHold> lock(Connection uow, PaymentIntentId intent) {
                return findForIntent(uow, intent);
            }

            @Override
            public boolean resolveCapture(Connection uow, PaymentIntentId intent, String key) {
                CaptureHold hold = rows.get(intent);
                if (hold == null || !hold.open() || !hold.heldUntil().isAfter(now)) {
                    return false;
                }
                rows.put(intent, resolved(hold, CaptureHold.Resolution.CAPTURE, key));
                return true;
            }

            @Override
            public boolean resolveVoid(Connection uow, PaymentIntentId intent, String key) {
                CaptureHold hold = rows.get(intent);
                if (hold == null || !hold.open()) {
                    return false;
                }
                rows.put(intent, resolved(hold, CaptureHold.Resolution.VOID, key));
                return true;
            }

            @Override
            public Optional<CaptureHold> claimExpired(Connection uow, Collection<PaymentIntentId> passedOver) {
                return rows.values().stream()
                        .filter(hold -> hold.open() && !hold.heldUntil().isAfter(now)
                                && !passedOver.contains(hold.intentId()))
                        .findFirst();
            }

            @Override
            public boolean expire(Connection uow, PaymentIntentId intent) {
                if (intent.equals(failExpiring)) {
                    throw new PaymentsStorageException("the poisoned row");
                }
                CaptureHold hold = rows.get(intent);
                if (hold == null || !hold.open() || hold.heldUntil().isAfter(now)) {
                    return false;
                }
                rows.put(intent, resolved(hold, CaptureHold.Resolution.EXPIRED, null));
                return true;
            }

            @Override
            public List<CaptureHold> voidsToFinish(Connection uow, int limit) {
                return rows.values().stream()
                        .filter(hold -> hold.resolution().filter(r -> r != CaptureHold.Resolution.CAPTURE).isPresent()
                                && attemptStatus.get(hold.attemptId()) == PaymentAttemptStatus.AUTHORIZED)
                        .limit(limit)
                        .toList();
            }

            private CaptureHold resolved(CaptureHold hold, CaptureHold.Resolution resolution, String key) {
                return new CaptureHold(hold.intentId(), hold.attemptId(), hold.heldAt(), hold.heldUntil(),
                        Optional.of(resolution), Optional.ofNullable(key), Optional.of(now));
            }
        }

        /** The commands, recorded; a release voids the attempt as PaymentVoid would. */
        final class Commands implements HeldPayments.Commands {
            final List<PaymentAttemptId> captured = new ArrayList<>();
            final List<String> released = new ArrayList<>();
            boolean failNextRelease;

            @Override
            public void capture(PaymentAttemptId attempt) {
                captured.add(attempt);
            }

            @Override
            public boolean release(PaymentIntentId intent, String reason) {
                if (failNextRelease) {
                    failNextRelease = false;
                    throw new IllegalStateException("the void's process died between the two commits");
                }
                PaymentAttemptId attempt = hold(intent).attemptId();
                if (attemptStatus.get(attempt) != PaymentAttemptStatus.AUTHORIZED) {
                    return false;
                }
                attemptStatus.put(attempt, PaymentAttemptStatus.VOIDED);
                released.add(intent + "|" + reason);
                return true;
            }
        }

        /** platform.idempotency_record in memory, rolled back with the suite's transaction. */
        final class Claims implements IdempotencyRecordStore<Connection> {
            final Map<IdempotencyKey, IdempotencyRecord> rows = new LinkedHashMap<>();

            @Override
            public ClaimOutcome claim(Connection uow, IdempotencyKey key, RequestFingerprint fingerprint,
                    CorrelationId correlationId, Instant at, Instant expiresAt, Duration lease) {
                if (rows.containsKey(key)) {
                    return ClaimOutcome.ALREADY_CLAIMED;
                }
                rows.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                        StoredResponse.empty(), correlationId, at, expiresAt));
                return ClaimOutcome.CLAIMED;
            }

            @Override
            public Optional<IdempotencyRecord> find(Connection uow, IdempotencyKey key) {
                return Optional.ofNullable(rows.get(key));
            }

            @Override
            public boolean complete(Connection uow, IdempotencyKey key, IdempotencyState terminalState,
                    StoredResponse response, Instant completedAt) {
                IdempotencyRecord current = rows.get(key);
                if (current == null || current.state() != IdempotencyState.IN_PROGRESS) {
                    return false;
                }
                rows.put(key, new IdempotencyRecord(key, current.fingerprint(), terminalState, response,
                        current.correlationId(), current.createdAt(), current.expiresAt()));
                return true;
            }

            @Override
            public boolean reclaimIfLeaseExpired(Connection uow, IdempotencyKey key, CorrelationId correlationId,
                    Instant at, Instant expiresAt, Duration lease) {
                return false;
            }
        }
    }
}
