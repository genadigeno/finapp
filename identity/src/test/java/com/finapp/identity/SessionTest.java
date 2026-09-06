package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session aggregate's own rules (`P1-TSK-013`).
 *
 * <h2>Why {@code isLiveAt} exists at all, given the store filters in SQL</h2>
 *
 * <p>The completion gate found this method and {@code idleBoundAfterUseAt} were <strong>never
 * called</strong> — {@code JdbcSessionStore} does both checks in the query. Dead code carrying
 * confident javadoc about a safety property is worse than no code: the next reader believes the
 * aggregate enforces something it does not.
 *
 * <p>{@code idleBoundAfterUseAt} was deleted — it duplicated the SQL's {@code LEAST(…)} with no
 * caller and no second consumer. {@code isLiveAt} was kept and made load-bearing instead, because
 * it is the <strong>definition</strong> of liveness and the SQL is an implementation of it. Two
 * expressions of one rule, reconciled by test, is the shape {@code sqlValueList} already uses — and
 * without the reconciliation the domain rule would live only in a {@code WHERE} clause, where no
 * reader and no architecture rule would ever find it.
 */
@DisplayName("Session (P1-TSK-013)")
class SessionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @Test
    @DisplayName("a session is live inside both bounds and dead outside either")
    void livenessIsBothBounds() {
        SessionPolicy policy = new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12));
        Session session = issue(policy);

        assertThat(session.isLiveAt(session.issuedAt().plusSeconds(1))).isTrue();

        // Each bound alone. A test that only crossed both together would pass against an
        // implementation checking either one.
        assertThat(session.isLiveAt(session.idleExpiresAt().plusSeconds(1)))
                .as("past the idle bound, with the absolute bound still hours away")
                .isFalse();
        assertThat(session.isLiveAt(session.absoluteExpiresAt().plusSeconds(1)))
                .as("past the absolute bound")
                .isFalse();
    }

    @Test
    @DisplayName("the boundary instant itself is not live")
    void theBoundIsExclusive() {
        // Which side of the boundary counts is the kind of thing nobody writes down and everybody
        // assumes differently. Pinned, and it matches the SQL's strict `>`.
        Session session = issue(new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12)));

        assertThat(session.isLiveAt(session.idleExpiresAt()))
                .as("expiry is exclusive: at the bound, it is over")
                .isFalse();
    }

    @Test
    @DisplayName("a revoked session is not live, whatever its bounds say")
    void revocationOverridesTheBounds() {
        Session revoked =
                Session.rehydrate(
                        SessionId.next(IDS),
                        IdentityId.next(IDS),
                        com.finapp.sharedkernel.security.Sensitive.of("hash"),
                        AssuranceLevel.PASSWORD,
                        SessionStatus.REVOKED,
                        Instant.now(CLOCK),
                        Instant.now(CLOCK).plus(Duration.ofHours(1)),
                        Instant.now(CLOCK).plus(Duration.ofHours(12)),
                        null,
                        Instant.now(CLOCK));

        assertThat(revoked.isLiveAt(Instant.now(CLOCK)))
                .as("well inside both bounds, and still not usable")
                .isFalse();
    }

    @Test
    @DisplayName("a session that is already expired when issued is refused")
    void incoherentBoundsAreRefused() {
        Instant now = Instant.now(CLOCK);

        assertThatThrownBy(
                        () ->
                                Session.rehydrate(
                                        SessionId.next(IDS),
                                        IdentityId.next(IDS),
                                        com.finapp.sharedkernel.security.Sensitive.of("hash"),
                                        AssuranceLevel.PASSWORD,
                                        SessionStatus.ACTIVE,
                                        now,
                                        now.minusSeconds(1),
                                        now.plus(Duration.ofHours(12)),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an idle bound beyond the absolute bound is refused")
    void theIdleBoundMayNotOutlastTheAbsoluteOne() {
        // Otherwise the absolute lifetime is advisory, and an attacker using a stolen token steadily
        // keeps the session alive for ever.
        Instant now = Instant.now(CLOCK);

        assertThatThrownBy(
                        () ->
                                Session.rehydrate(
                                        SessionId.next(IDS),
                                        IdentityId.next(IDS),
                                        com.finapp.sharedkernel.security.Sensitive.of("hash"),
                                        AssuranceLevel.PASSWORD,
                                        SessionStatus.ACTIVE,
                                        now,
                                        now.plus(Duration.ofHours(13)),
                                        now.plus(Duration.ofHours(12)),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a session never prints its token hash")
    void nothingRendersTheHash() {
        Session session = issue(SessionPolicy.current());

        assertThat(session.toString())
                .as("the hash identifies which live session to attack; the aggregate's own"
                        + " identifier says everything an operator needs")
                .doesNotContain(session.tokenHash().expose());
    }

    private static Session issue(SessionPolicy policy) {
        return Session.issue(
                IDS,
                CLOCK,
                IdentityId.next(IDS),
                SessionToken.issue(RANDOMNESS),
                AssuranceLevel.PASSWORD,
                policy);
    }
}
