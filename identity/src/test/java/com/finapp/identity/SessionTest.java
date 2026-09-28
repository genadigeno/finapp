package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session aggregate's own rules (`P1-TSK-013`, `X-TSK-007`).
 *
 * <h2>Where liveness is tested, and why not here</h2>
 *
 * <p>This suite used to pin {@code isLiveAt}, kept by {@code P1-TSK-013}'s gate as the
 * <em>definition</em> of liveness with the SQL as its implementation. {@code X-TSK-007} removed it.
 * The definition now includes <strong>whose clock judges</strong> - the database's - and a Java
 * method taking an arbitrary instant cannot say that. It can only invite a caller to pass its own,
 * which was the defect, as {@code V004} found when it removed {@code IdempotencyRecord.isStaleAt}.
 *
 * <p>What it pinned is pinned where the rule now lives, against the database's own {@code now()}:
 * each bound alone ({@code SessionLifecycleDatabaseTest#theIdleBoundIsEnforcedOnItsOwn},
 * {@code #theAbsoluteBoundIsEnforcedOnItsOwn}), the exclusive boundary
 * ({@code #theBoundIsExclusiveAtTheDatabasesInstant}), and revocation over the bounds
 * ({@code #everyUnusableSessionLooksTheSame}).
 *
 * <p>What remains here is what the aggregate itself decides: a draft carries lifetimes and no
 * bound, a replacement carries its predecessor's absolute bound verbatim, and nothing compares
 * business time with coordination time.
 */
@DisplayName("Session (P1-TSK-013, X-TSK-007)")
class SessionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @Test
    @DisplayName("issuing decides lifetimes, never a bound")
    void issuingDecidesNoBound() {
        // The bounds are the database's to stamp (X-TSK-007). A draft that carried an instant for
        // either would be the issuing instance's clock deciding how long the session lives.
        SessionPolicy policy = new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12));

        Session.Draft draft = draft(policy);

        assertThat(draft.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(draft.absolute())
                .as("a new sitting carries the policy's LIFETIME, for the database to add to now()")
                .isEqualTo(new SessionAbsoluteBound.Lifetime(Duration.ofHours(12)));
        assertThat(draft.issuedAt())
                .as("the clock supplies business time, and only that")
                .isEqualTo(Instant.now(CLOCK));
    }

    @Test
    @DisplayName("a replacement inherits its predecessor's absolute bound, by naming the predecessor")
    void aReplacementInheritsTheAbsoluteBound() {
        // The bound was stamped by the database when the predecessor was issued. Re-deriving it
        // here, from any clock, would let a rotation move it - by resetting it, or by carrying the
        // rotating instance's skew into it. Nor is it copied from `current`, which is the caller's
        // copy: the draft names the predecessor, and the store copies the bound from its row.
        Session current = rehydrate(Instant.now(CLOCK), Duration.ofMinutes(30), Duration.ofHours(12));
        Instant rotatedAt = Instant.now(CLOCK).plus(Duration.ofHours(3));
        SessionToken token = SessionToken.issue(RANDOMNESS);

        Session.Draft replacement =
                Session.replacement(
                        SessionId.next(IDS),
                        current,
                        token,
                        AssuranceLevel.MULTI_FACTOR,
                        rotatedAt,
                        Duration.ofMinutes(30));

        assertThat(replacement.absolute())
                .isEqualTo(new SessionAbsoluteBound.Inherited(current.id()));
        assertThat(replacement.identityId()).isEqualTo(current.identityId());
        assertThat(replacement.device()).isEqualTo(current.device());
        assertThat(replacement.tokenHash().expose()).isEqualTo(token.hash().expose());
        assertThat(replacement.assurance()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(replacement.issuedAt()).isEqualTo(rotatedAt);
    }

    @Test
    @DisplayName("a fast issuer's record may follow its own bounds, and that is not incoherent")
    void businessTimeIsNotComparedWithTheBounds() {
        // issuedAt is the issuing instance's clock; the bounds are the database's. An instance an
        // hour fast writes an issuedAt an hour after the database's now(), which is after a thirty
        // minute idle bound. Refusing that would refuse every login on a fast instance - V005's
        // session_bounds_follow_issue did exactly that, one clock removed, and V016 replaced it.
        Instant databaseNow = Instant.now(CLOCK);
        Instant fastIssuersReading = databaseNow.plus(Duration.ofHours(1));

        Session session =
                Session.rehydrate(
                        SessionId.next(IDS),
                        IdentityId.next(IDS),
                        Sensitive.of("hash"),
                        AssuranceLevel.PASSWORD,
                        SessionStatus.ACTIVE,
                        fastIssuersReading,
                        databaseNow.plus(Duration.ofMinutes(30)),
                        databaseNow.plus(Duration.ofHours(12)),
                        null,
                        null);

        assertThat(session.issuedAt()).isAfter(session.idleExpiresAt());
    }

    @Test
    @DisplayName("an idle bound beyond the absolute bound is refused")
    void theIdleBoundMayNotOutlastTheAbsoluteOne() {
        // Otherwise the absolute lifetime is advisory, and an attacker using a stolen token steadily
        // keeps the session alive for ever. Both bounds are the database's, so this compares one
        // clock with itself - the one check on the bounds that survives X-TSK-007 here.
        Instant now = Instant.now(CLOCK);

        assertThatThrownBy(() -> rehydrate(now, Duration.ofHours(13), Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a draft refuses a lifetime that would write a session already over")
    void aDraftRefusesZeroLifetimes() {
        Session current = rehydrate(Instant.now(CLOCK), Duration.ofMinutes(30), Duration.ofHours(12));

        assertThatThrownBy(() -> new SessionAbsoluteBound.Lifetime(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                Session.replacement(
                                        SessionId.next(IDS),
                                        current,
                                        SessionToken.issue(RANDOMNESS),
                                        AssuranceLevel.MULTI_FACTOR,
                                        Instant.now(CLOCK),
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("neither a session nor a draft prints its token hash")
    void nothingRendersTheHash() {
        Session session = rehydrate(Instant.now(CLOCK), Duration.ofMinutes(30), Duration.ofHours(12));
        Session.Draft draft = draft(SessionPolicy.current());

        assertThat(session.toString())
                .as("the hash identifies which live session to attack; the aggregate's own"
                        + " identifier says everything an operator needs")
                .doesNotContain(session.tokenHash().expose());
        assertThat(draft.toString()).doesNotContain(draft.tokenHash().expose());
    }

    private static Session.Draft draft(SessionPolicy policy) {
        return Session.issue(
                IDS,
                CLOCK,
                IdentityId.next(IDS),
                SessionToken.issue(RANDOMNESS),
                AssuranceLevel.PASSWORD,
                policy);
    }

    private static Session rehydrate(Instant liveFrom, Duration idle, Duration absolute) {
        return Session.rehydrate(
                SessionId.next(IDS),
                IdentityId.next(IDS),
                SessionToken.issue(RANDOMNESS).hash(),
                AssuranceLevel.PASSWORD,
                SessionStatus.ACTIVE,
                liveFrom,
                liveFrom.plus(idle),
                liveFrom.plus(absolute),
                "Firefox on Linux",
                null);
    }
}
