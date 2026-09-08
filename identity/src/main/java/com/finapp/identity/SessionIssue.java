package com.finapp.identity;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;

/**
 * The first session of a login (`P1-TSK-027`, ADR-0030).
 *
 * <h2>This is the path the Phase 1 review found missing</h2>
 *
 * <p>Before it, {@code Session.issue} had <strong>no production caller</strong>: the only writer of
 * session rows was {@link SessionRotation}, reached only from {@code MfaChallenge.elevate}, which
 * requires a session already. So the eight endpoints {@code PHASE_1_PLAN.md} §7 marks
 * <em>"Auth: session"</em> were unreachable by any real client, and the phase objective —
 * <em>"prove it, hold a session"</em> — was not met end to end. That was criterion 1's failure.
 *
 * <h2>Always {@code PASSWORD}, and never withheld because a second factor exists</h2>
 *
 * <p>Issuing at any higher level would make this the MFA bypass {@code INV-IDN-05} forbids, so the
 * level is not a parameter — a caller cannot ask for more.
 *
 * <p>The less obvious half: a session <strong>is</strong> issued to an identity that has an active
 * second factor, at {@code PASSWORD}. Withholding one until MFA is done looks stricter and would
 * make step-up unreachable, because {@code MfaChallenge.elevate} takes a <em>current</em> session —
 * the same shape as the gap this class exists to close. Assurance being a <strong>level</strong>
 * rather than a boolean (ADR-0030) is what makes that composition safe: a {@code PASSWORD} session
 * satisfies nothing that requires {@code MULTI_FACTOR}.
 *
 * <h2>A separate component rather than a method on the service</h2>
 *
 * <p>{@code MfaBypassPathsAreEnumeratedTest} enumerates session origins by call site, and a named
 * class makes the entry legible: <em>this</em> is where a first session comes from. It also keeps
 * the decision — the level, and that it is not a parameter — in {@code identity}, where the rule
 * lives, rather than in an application service that could pass whatever it liked.
 */
public final class SessionIssue {

    private final SessionStore<Connection> sessions;
    private final SessionPolicy policy;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecureRandom randomness;

    /**
     * @param policy injected rather than read from {@link SessionPolicy#current()} here. {@code
     *     SessionBeans} makes it one shared value on purpose - calling {@code current()} at each
     *     site is how two components come to disagree about how long a session lasts, and the
     *     interceptor that extends these sessions reads the injected one.
     */
    public SessionIssue(
            SessionStore<Connection> sessions,
            SessionPolicy policy,
            IdGenerator ids,
            Clock clock,
            SecureRandom randomness) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
    }

    /**
     * Issues a session for an identity whose credential has just been proven.
     *
     * <p>Written on the <strong>caller's</strong> unit of work, so the session and the audit record
     * of the login that produced it commit together or not at all. A session that exists always has
     * a record saying who logged in and when; a rolled-back login leaves neither.
     *
     * <p><strong>No idempotency key, deliberately.</strong> Ten logins are ten sessions, because a
     * person may hold several — and {@code P1-TSK-010} settled that keying this endpoint would be
     * worse than useless: a key is explicitly not a secret ({@code API_CONVENTIONS.md} §6), so a
     * stored success keyed on one would let anybody who saw it in a proxy log <em>replay a
     * successful authentication</em>.
     *
     * @param device what the session was established from. Optional and never scored — a label its
     *     owner recognises their own sessions by ({@code V005})
     * @return the session and the one copy of its token
     */
    public Issued issue(Connection unitOfWork, IdentityId identityId, DeviceDescription device) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        SessionToken token = SessionToken.issue(randomness);
        Session session =
                Session.issue(
                        ids,
                        clock,
                        identityId,
                        token,
                        // NOT a parameter. A caller able to ask for MULTI_FACTOR here would have
                        // found the MFA bypass INV-IDN-05 exists to prevent, and no amount of
                        // reviewing call sites is as good as the level not being askable.
                        AssuranceLevel.PASSWORD,
                        policy,
                        device);
        sessions.insert(unitOfWork, session);
        return new Issued(session, token);
    }

    /**
     * A session and the one copy of its token.
     *
     * <p>The {@code SessionRotation.Rotated} shape: a per-call return value, never retained. The
     * plaintext exists here and nowhere else, because {@code Session} holds only the hash.
     */
    public record Issued(Session session, SessionToken token) {
        public Issued {
            Objects.requireNonNull(session, "session must not be null");
            Objects.requireNonNull(token, "token must not be null");
        }
    }
}
