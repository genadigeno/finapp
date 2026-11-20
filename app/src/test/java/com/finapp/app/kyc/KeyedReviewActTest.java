package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.RecordedRead;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.kyc.DecisionOutcome;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.ReviewTaskId;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * A reviewer's act under its {@code Idempotency-Key} (`P17-TSK-029`, gap G-61; {@code INV-IDEM-01}, {@code -03}): the act made
 * once and replayed; a refusal leaving the key unspent, its claim rolled back with its transaction; another request under the
 * key refused; the key scoped to its reviewer; and the doors carrying the key, the factor and the permission.
 *
 * <p>The claims here live in a map a unit of work copies and restores on an exception - the rollback the act's transaction does
 * on the database, which {@code DecisionDatabaseTest} and {@code ReviewDatabaseTest} prove there.
 */
@DisplayName("a reviewer's decision and resolution under their Idempotency-Key (P17-TSK-029)")
class KeyedReviewActTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor REVIEWER = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final KycCaseId CASE = KycCaseId.of(IDS.next());
    /** The unit of work the executor is handed; the claims are this suite's map, so nothing is asked of it. */
    private static final Connection UNIT = (Connection) Proxy.newProxyInstance(KeyedReviewActTest.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    private Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final IdempotentExecutor executor =
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30));
    private final AtomicInteger acts = new AtomicInteger();
    private CorrelationContext.Scope flow;

    @BeforeEach
    void enter() {
        flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    @AfterEach
    void leave() {
        flow.close();
    }

    @Test
    @DisplayName("the act is made once and its outcome stored; a retry under the key replays it without acting again")
    void aRetryReplaysTheAct() {
        KeyedReviewAct keyed = decision("key-1", DecisionOutcome.REJECTED, "confirmed hit, OPS-1");

        assertThat(inATransaction(() -> keyed.run(UNIT, DecisionRecording.Recording.class,
                DecisionRecording.Recording.RECORDED, work -> act(DecisionRecording.Recording.RECORDED))))
                .isEqualTo(DecisionRecording.Recording.RECORDED);
        assertThat(inATransaction(() -> keyed.run(UNIT, DecisionRecording.Recording.class,
                DecisionRecording.Recording.RECORDED, work -> act(DecisionRecording.Recording.ALREADY_DECIDED))))
                .as("the first outcome, not the conditional the act already won")
                .isEqualTo(DecisionRecording.Recording.RECORDED);
        assertThat(acts).as("acted once").hasValue(1);
        assertThat(claims.get(keyed.key()).state()).isEqualTo(IdempotencyState.COMPLETED);
    }

    @Test
    @DisplayName("a refusal is thrown with its outcome and spends no key: its claim rolls back, and a later retry is judged again")
    void aRefusalSpendsNoKey() {
        KeyedReviewAct keyed = decision("key-2", DecisionOutcome.APPROVED, "false positive, list entry 7");

        assertThatThrownBy(() -> inATransaction(() -> keyed.run(UNIT, DecisionRecording.Recording.class,
                DecisionRecording.Recording.RECORDED, work -> act(DecisionRecording.Recording.NOT_READY))))
                .isInstanceOfSatisfying(KeyedReviewAct.Refused.class, refused -> assertThat(
                        refused.outcome(DecisionRecording.Recording.class)).isEqualTo(DecisionRecording.Recording.NOT_READY));
        assertThat(claims).as("the claim rolled back with the refusal").isEmpty();

        assertThat(inATransaction(() -> keyed.run(UNIT, DecisionRecording.Recording.class,
                DecisionRecording.Recording.RECORDED, work -> act(DecisionRecording.Recording.RECORDED))))
                .as("the same key, once the case is ready, decides it")
                .isEqualTo(DecisionRecording.Recording.RECORDED);
        assertThat(acts).hasValue(2);
    }

    @Test
    @DisplayName("the same key under another outcome, another reason, another case or another task is refused as a conflict")
    void anotherRequestUnderTheKeyIsAConflict() {
        inATransaction(() -> decision("key-3", DecisionOutcome.REJECTED, "confirmed hit, OPS-3").run(UNIT,
                DecisionRecording.Recording.class, DecisionRecording.Recording.RECORDED,
                work -> act(DecisionRecording.Recording.RECORDED)));
        for (KeyedReviewAct other : new KeyedReviewAct[] {
                decision("key-3", DecisionOutcome.APPROVED, "confirmed hit, OPS-3"),
                decision("key-3", DecisionOutcome.REJECTED, "confirmed hit, OPS-4"),
                KeyedReviewAct.decision(executor, "key-3", REVIEWER, KycCaseId.of(IDS.next()), DecisionOutcome.REJECTED,
                        "confirmed hit, OPS-3")}) {
            assertThatThrownBy(() -> inATransaction(() -> other.run(UNIT, DecisionRecording.Recording.class,
                    DecisionRecording.Recording.RECORDED, work -> act(DecisionRecording.Recording.RECORDED))))
                    .isInstanceOf(IdempotencyConflictException.class);
        }
        ReviewTaskId task = ReviewTaskId.of(IDS.next());
        inATransaction(() -> resolution("key-4", task, "cleared against the list").run(UNIT,
                ReviewService.Resolution.class, ReviewService.Resolution.RESOLVED, work -> act(ReviewService.Resolution.RESOLVED)));
        assertThatThrownBy(() -> inATransaction(() -> resolution("key-4", ReviewTaskId.of(IDS.next()), "cleared against the list")
                .run(UNIT, ReviewService.Resolution.class, ReviewService.Resolution.RESOLVED,
                        work -> act(ReviewService.Resolution.RESOLVED))))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(acts).as("only the two first requests acted").hasValue(2);
    }

    @Test
    @DisplayName("the key is the reviewer's and the act's: two reviewers, or a decision and a resolution, never share one")
    void theKeyNamesItsPrincipalAndItsAct() {
        Actor another = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        KeyedReviewAct mine = decision("shared", DecisionOutcome.REJECTED, "r");
        KeyedReviewAct theirs = KeyedReviewAct.decision(executor, "shared", another, CASE, DecisionOutcome.REJECTED, "r");
        KeyedReviewAct resolving = resolution("shared", ReviewTaskId.of(IDS.next()), "r");

        assertThat(mine.key().scope()).isEqualTo("kyc.case-decision:" + REVIEWER.keyedType().name() + ":" + REVIEWER.id());
        assertThat(theirs.key().scope()).isEqualTo("kyc.case-decision:" + another.keyedType().name() + ":" + another.id());
        assertThat(resolving.key().scope()).isEqualTo("kyc.review-resolution:" + REVIEWER.keyedType().name() + ":" + REVIEWER.id());
        assertThat(mine.fingerprint().matches(decision("other-key", DecisionOutcome.REJECTED, "r").fingerprint()))
                .as("the same request, the same fingerprint").isTrue();
        assertThat(mine.fingerprint().matches(decision("other-key", DecisionOutcome.REJECTED, "r ").fingerprint()))
                .as("another reason, another fingerprint").isFalse();
    }

    @Test
    @DisplayName("the decision and the resolution require the key, a MULTI_FACTOR session and KYC_REVIEW; the reviewer's reads"
            + " are marked recorded and stepped up")
    void theDoorsCarryTheKeyTheFactorAndThePermission() throws Exception {
        for (Method door : ReviewController.class.getDeclaredMethods()) {
            if (door.isAnnotationPresent(PostMapping.class)) {
                assertThat(door.isAnnotationPresent(RequiresIdempotencyKey.class)).as(door.getName()).isTrue();
                assertThat(door.getAnnotation(RequiresAssurance.class).value()).as(door.getName())
                        .isEqualTo(AssuranceLevel.MULTI_FACTOR);
                assertThat(door.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.KYC_REVIEW);
            }
        }
        int reads = 0;
        for (Method read : KycQueueController.class.getDeclaredMethods()) {
            if (read.isAnnotationPresent(GetMapping.class)) {
                reads++;
                assertThat(read.isAnnotationPresent(RecordedRead.class)).as(read.getName()).isTrue();
                assertThat(read.getAnnotation(RequiresAssurance.class).value()).as(read.getName())
                        .isEqualTo(AssuranceLevel.MULTI_FACTOR);
                assertThat(read.getAnnotation(RequiresPermission.class).value()).as(read.getName())
                        .isIn(PermissionName.KYC_REVIEW, PermissionName.COUNTERPARTY_SCREENING_REVIEW);
            }
        }
        assertThat(reads).isEqualTo(3);
    }

    // ------------------------------------------------------------------ plumbing

    private KeyedReviewAct decision(String key, DecisionOutcome outcome, String reason) {
        return KeyedReviewAct.decision(executor, key, REVIEWER, CASE, outcome, reason);
    }

    private KeyedReviewAct resolution(String key, ReviewTaskId task, String reason) {
        return KeyedReviewAct.resolution(executor, key, REVIEWER, CASE, task, reason);
    }

    private <R> R act(R outcome) {
        acts.incrementAndGet();
        return outcome;
    }

    /** A unit of work: the claims as they were are restored when it throws, as the database's rollback restores them. */
    private <R> R inATransaction(Supplier<R> work) {
        Map<IdempotencyKey, IdempotencyRecord> before = new HashMap<>(claims);
        try {
            return work.get();
        } catch (RuntimeException rolledBack) {
            claims = before;
            throw rolledBack;
        }
    }

    /** The idempotency records, in memory. */
    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(),
                    correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
                    held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
