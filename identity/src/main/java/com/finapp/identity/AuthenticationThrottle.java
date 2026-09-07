package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Counts consecutive authentication failures and locks an identity that crosses the threshold
 * (`P1-TSK-011`, {@code INV-CON-03}).
 *
 * <h2>The whole protocol is one statement</h2>
 *
 * <p>{@code INSERT … ON CONFLICT DO UPDATE … RETURNING}. The post-increment count is produced
 * <strong>by the write</strong>, so there is no read-then-write and nothing to lose between two
 * instances. {@code INV-CON-03} states the requirement as an absolute — <em>"limits enforced
 * non-atomically are limits that do not exist"</em> — and the reason is arithmetic: with a
 * read-then-write, ten concurrent attempts at the threshold all read nine and all proceed.
 *
 * <p>Every timestamp is the <strong>server's</strong> {@code now()}, never a value this process
 * computed. That is {@code V004}'s lesson from the idempotency lease (ADR-0014): an instance running
 * six minutes fast would otherwise expire its neighbours' windows and hand an attacker a fresh
 * budget on every request that happened to land on it.
 *
 * <h2>A lock never makes an attempt cheaper</h2>
 *
 * <p>This component is consulted <em>after</em> the credential has been verified, never before, and
 * nothing here short-circuits the derivation. A locked account that answered faster than an unknown
 * one would be an account-existence oracle — lockout defeating {@code INV-IDN-07}, which is the
 * invariant it exists alongside. The CPU relief a "fail fast" would buy is the job of a per-source
 * rate limit, which is a different key and a different control.
 *
 * <h2>Failures are counted only for identities that exist, and it costs the same either way</h2>
 *
 * <p>A login identifier nobody registered has no identity to key on. Keying on the attempted string
 * instead would build a caller-controlled table of things people typed — {@code CONFIDENTIAL} by
 * classification, attacker-fillable by construction, and an enumeration artefact by accident. What
 * bounds that traffic is a per-source limit, recorded as debt because it needs a trusted-proxy
 * decision this phase cannot take.
 *
 * <p>So the counter is keyed off the <strong>login identifier</strong>, resolved to an identity by a
 * subselect <em>inside the same statement</em>. That is not a stylistic choice. The obvious
 * alternative — look the identity up, then record if it was found — runs one query when the account
 * is absent and two when it is present, which is a timing difference that says whether the account
 * exists. One statement that inserts nothing when the subselect returns nothing has no such branch.
 *
 * <p>It also keeps {@code VerificationOutcome} opaque. The alternative of having verification
 * <em>report</em> which identity it tried would put back exactly the field {@code P1-TSK-008}
 * removed, and its reflective guard would fail — correctly, because a caller handed an identity on
 * a failed attempt is a caller that can leak one.
 */
public final class AuthenticationThrottle {

    /** What an audit record for a lock points at. */
    public static final String AUDIT_TARGET_TYPE = "identity.Identity";

    private final LockoutPolicy policy;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;

    public AuthenticationThrottle(
            LockoutPolicy policy,
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        // Injected, and used ONLY to stamp the audit record's occurredAt. Every decision this class
        // makes about time - window expiry, whether a lock is live - is the server's, because a
        // client clock deciding those is the ADR-0014 defect V004 had to correct.
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
    }

    /**
     * Records one failure and reports whether the identity is now locked.
     *
     * <p>Writes the audit record when this failure is the one that crosses the threshold — in the
     * same transaction, so a lock that is recorded is a lock that happened.
     */
    /**
     * The source row when the account is identified by what somebody typed.
     *
     * <p>A subselect rather than a lookup first: it runs <strong>one</strong> query whether or not
     * the account exists, and two queries when it does would be a timing difference that discloses
     * existence ({@code INV-IDN-07}).
     */
    private static final String BY_LOGIN_IDENTIFIER =
            """
            SELECT id, 1, now(), NULL, now()
              FROM identity.identity
             WHERE login_identifier = ?
            """;

    /**
     * The source row when the identity is already proven.
     *
     * <p>No subselect, and the {@code INV-IDN-07} argument above does not transfer: an MFA
     * challenge arrives on a session this platform issued, so there is no existence question to
     * disclose the answer to.
     */
    private static final String BY_IDENTITY = "SELECT ?::uuid, 1, now(), NULL, now()";

    public Lock recordFailure(Connection unitOfWork, LoginIdentifier loginIdentifier) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");

        String sql =
                """
                INSERT INTO identity.authentication_failure
                    (identity_id, failures, window_started_at, locked_until, updated_at)
                %s
                ON CONFLICT (identity_id) DO UPDATE SET
                    -- WHEN A RUN OF FAILURES ENDS. Two ways, and they are deliberately not one
                    -- condition, which the completion gate established by probing rather than
                    -- reading:
                    --
                    --   * A SERVED LOCK ends it, whatever the window says. The lock is the
                    --     punishment; once it has been served the run is over. Making this depend
                    --     on the window as well looks equivalent - in the SHIPPED policy the window
                    --     and the lock are both 15 minutes, and window_started_at always precedes
                    --     locked_until, so an expired lock implies an expired window - and it is
                    --     coincidence, not equivalence: LockoutPolicy(3, 60min, 1min) is legal and
                    --     expires the lock while the window is live.
                    --   * AN ELAPSED WINDOW ends it, provided NO LOCK IS LIVE. Without the second
                    --     half an attacker waits out the window instead of the lock.
                    --
                    -- The first version had one condition - "locked_until IS NULL AND the window
                    -- elapsed" - and it never reset a row that had ever been locked. ONE failure
                    -- after a lock expired incremented to threshold + 1 and re-locked, so an
                    -- account locked once was locked FOR EVER at one failure per lock period. That
                    -- is the permanent lockout this table's own comment says must not exist, and it
                    -- is the attack the design claims to avoid.
                    failures = CASE
                        WHEN (
                             -- A served lock ends the run, whatever the window says.
                             (identity.authentication_failure.locked_until IS NOT NULL
                              AND identity.authentication_failure.locked_until <= now())
                             -- Otherwise an elapsed window ends it, provided no lock is live.
                             OR (identity.authentication_failure.locked_until IS NULL
                                 AND identity.authentication_failure.window_started_at
                                     < now() - ?::interval)
                         )
                        THEN 1
                        ELSE identity.authentication_failure.failures + 1
                    END,
                    window_started_at = CASE
                        WHEN (
                             -- A served lock ends the run, whatever the window says.
                             (identity.authentication_failure.locked_until IS NOT NULL
                              AND identity.authentication_failure.locked_until <= now())
                             -- Otherwise an elapsed window ends it, provided no lock is live.
                             OR (identity.authentication_failure.locked_until IS NULL
                                 AND identity.authentication_failure.window_started_at
                                     < now() - ?::interval)
                         )
                        THEN now()
                        ELSE identity.authentication_failure.window_started_at
                    END,
                    locked_until = CASE
                        WHEN (
                             -- A served lock ends the run, whatever the window says.
                             (identity.authentication_failure.locked_until IS NOT NULL
                              AND identity.authentication_failure.locked_until <= now())
                             -- Otherwise an elapsed window ends it, provided no lock is live.
                             OR (identity.authentication_failure.locked_until IS NULL
                                 AND identity.authentication_failure.window_started_at
                                     < now() - ?::interval)
                         )
                        THEN NULL
                        WHEN identity.authentication_failure.failures + 1 >= ?
                        THEN now() + ?::interval
                        ELSE identity.authentication_failure.locked_until
                    END,
                    updated_at = now()
                RETURNING identity_id, failures,
                          locked_until IS NOT NULL AND locked_until > now()
                """
                        .formatted(BY_LOGIN_IDENTIFIER);

        try (PreparedStatement upsert = unitOfWork.prepareStatement(sql)) {
            upsert.setString(1, loginIdentifier.value());
            upsert.setString(2, intervalOf(policy.window()));
            upsert.setString(3, intervalOf(policy.window()));
            upsert.setString(4, intervalOf(policy.window()));
            upsert.setInt(5, policy.threshold());
            upsert.setString(6, intervalOf(policy.lockFor()));

            try (ResultSet row = upsert.executeQuery()) {
                if (!row.next()) {
                    // No identity matched, so the SELECT produced no row and nothing was inserted.
                    // There is nothing to lock and nothing to record - see the class javadoc for why
                    // keying on the attempted string instead is refused.
                    return Lock.NOTHING_TO_COUNT;
                }
                IdentityId identityId =
                        IdentityId.of((java.util.UUID) row.getObject("identity_id"));
                int failures = row.getInt("failures");
                boolean locked = row.getBoolean(3);
                // Exactly the attempt that crosses the threshold writes the record. Writing one per
                // failure while locked would bury the event that matters under repetitions of it.
                boolean lockedByThisFailure = locked && failures == policy.threshold();
                if (lockedByThisFailure) {
                    audit(unitOfWork, identityId, failures);
                }
                return new Lock(locked, lockedByThisFailure, failures);
            }
        } catch (SQLException e) {
            // Never the identifier: the message reaches a log line (INV-AUD-02), and this table's
            // subject is by definition an account somebody is attacking.
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not record an authentication failure", e));
        }
    }

    /**
     * Records a failure against an identity that is already proven (`P1-TSK-018`).
     *
     * <h2>MFA failures share the account's lockout budget, deliberately</h2>
     *
     * <p>A six-digit code with a ±1 window is <strong>three valid values in a million</strong> per
     * attempt, so an unthrottled challenge is brute-forceable by automation — RFC 4226 §7.3 requires
     * throttling and TOTP is not safe without it.
     *
     * <p>It counts against the <em>same</em> row as a password failure, and that is the decision
     * rather than an accident of reuse: an attacker guessing codes is by definition somebody who
     * already has the password, so separate counters would hand them a second fresh budget for no
     * benefit. The threshold protects the account, not one credential.
     *
     * <p>The statement is the same one {@link #recordFailure} uses, differing only in how the row is
     * sourced. Copying it was the alternative and was refused: its reset condition is two clauses
     * that a completion gate had to establish by probing, and a second copy is one that drifts.
     */
    public Lock recordFailureFor(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        String sql =
                """
                INSERT INTO identity.authentication_failure
                    (identity_id, failures, window_started_at, locked_until, updated_at)
                %s
                ON CONFLICT (identity_id) DO UPDATE SET
                    -- WHEN A RUN OF FAILURES ENDS. Two ways, and they are deliberately not one
                    -- condition, which the completion gate established by probing rather than
                    -- reading:
                    --
                    --   * A SERVED LOCK ends it, whatever the window says. The lock is the
                    --     punishment; once it has been served the run is over. Making this depend
                    --     on the window as well looks equivalent - in the SHIPPED policy the window
                    --     and the lock are both 15 minutes, and window_started_at always precedes
                    --     locked_until, so an expired lock implies an expired window - and it is
                    --     coincidence, not equivalence: LockoutPolicy(3, 60min, 1min) is legal and
                    --     expires the lock while the window is live.
                    --   * AN ELAPSED WINDOW ends it, provided NO LOCK IS LIVE. Without the second
                    --     half an attacker waits out the window instead of the lock.
                    --
                    -- The first version had one condition - "locked_until IS NULL AND the window
                    -- elapsed" - and it never reset a row that had ever been locked. ONE failure
                    -- after a lock expired incremented to threshold + 1 and re-locked, so an
                    -- account locked once was locked FOR EVER at one failure per lock period. That
                    -- is the permanent lockout this table's own comment says must not exist, and it
                    -- is the attack the design claims to avoid.
                    failures = CASE
                        WHEN (
                             -- A served lock ends the run, whatever the window says.
                             (identity.authentication_failure.locked_until IS NOT NULL
                              AND identity.authentication_failure.locked_until <= now())
                             -- Otherwise an elapsed window ends it, provided no lock is live.
                             OR (identity.authentication_failure.locked_until IS NULL
                                 AND identity.authentication_failure.window_started_at
                                     < now() - ?::interval)
                         )
                        THEN 1
                        ELSE identity.authentication_failure.failures + 1
                    END,
                    window_started_at = CASE
                        WHEN (
                             -- A served lock ends the run, whatever the window says.
                             (identity.authentication_failure.locked_until IS NOT NULL
                              AND identity.authentication_failure.locked_until <= now())
                             -- Otherwise an elapsed window ends it, provided no lock is live.
                             OR (identity.authentication_failure.locked_until IS NULL
                                 AND identity.authentication_failure.window_started_at
                                     < now() - ?::interval)
                         )
                        THEN now()
                        ELSE identity.authentication_failure.window_started_at
                    END,
                    locked_until = CASE
                        WHEN (
                             -- A served lock ends the run, whatever the window says.
                             (identity.authentication_failure.locked_until IS NOT NULL
                              AND identity.authentication_failure.locked_until <= now())
                             -- Otherwise an elapsed window ends it, provided no lock is live.
                             OR (identity.authentication_failure.locked_until IS NULL
                                 AND identity.authentication_failure.window_started_at
                                     < now() - ?::interval)
                         )
                        THEN NULL
                        WHEN identity.authentication_failure.failures + 1 >= ?
                        THEN now() + ?::interval
                        ELSE identity.authentication_failure.locked_until
                    END,
                    updated_at = now()
                RETURNING identity_id, failures,
                          locked_until IS NOT NULL AND locked_until > now()
                """
                        .formatted(BY_IDENTITY);

        try (PreparedStatement upsert = unitOfWork.prepareStatement(sql)) {
            upsert.setObject(1, identityId.value());
            upsert.setString(2, intervalOf(policy.window()));
            upsert.setString(3, intervalOf(policy.window()));
            upsert.setString(4, intervalOf(policy.window()));
            upsert.setInt(5, policy.threshold());
            upsert.setString(6, intervalOf(policy.lockFor()));

            try (ResultSet row = upsert.executeQuery()) {
                if (!row.next()) {
                    // Unreachable: the source row is a literal, so it always produces one. Stated
                    // rather than assumed - an unreachable branch that silently returns the wrong
                    // thing is worse than one that says so.
                    throw new IllegalStateException(
                            "An identity-keyed failure recorded nothing, which cannot happen");
                }
                int failures = row.getInt("failures");
                boolean locked = row.getBoolean(3);
                // Exactly the attempt that crosses the threshold writes the record. Writing one per
                // failure while locked would bury the event that matters under repetitions of it.
                boolean lockedByThisFailure = locked && failures == policy.threshold();
                if (lockedByThisFailure) {
                    audit(unitOfWork, identityId, failures);
                }
                return new Lock(locked, lockedByThisFailure, failures);
            }
        } catch (SQLException e) {
            // Never the identifier: the message reaches a log line (INV-AUD-02), and this table's
            // subject is by definition an account somebody is attacking.
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not record an authentication failure", e));
        }
    }

    /**
     * Whether the identity is locked, without recording anything.
     *
     * <p>For the success path: a correct password is refused while the account is locked, because a
     * lock that a correct guess clears is not a lock — it is a hint that the guess was right.
     */
    public boolean isLocked(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        // The comparison is the server's, not this JVM's - the whole point of V004.
        String sql =
                "SELECT locked_until IS NOT NULL AND locked_until > now()"
                        + " FROM identity.authentication_failure WHERE identity_id = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, identityId.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read an authentication lock", e));
        }
    }

    /**
     * Clears the counter after a successful authentication.
     *
     * <p>Deletes rather than zeroing, because the table's own constraint says a row exists only
     * where something failed — the absence of a row already means "nothing counted", and two
     * representations of one fact is how they come to disagree.
     *
     * <p>Only ever called when the identity is <strong>not</strong> locked.
     */
    public void clear(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        try (PreparedStatement delete =
                unitOfWork.prepareStatement(
                        "DELETE FROM identity.authentication_failure WHERE identity_id = ?")) {
            delete.setObject(1, identityId.value());
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not clear authentication failures", e));
        }
    }

    // -----------------------------------------------------------------

    private void audit(Connection unitOfWork, IdentityId identityId, int failures) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A lockout must be recorded inside a correlation"
                                                    + " scope: the audit record carries the"
                                                    + " identifier, and a fabricated one would"
                                                    + " point at no flow at all (P0-TSK-014)"));

        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        IdentityAuditAction.AUTHENTICATION_LOCKED,
                        AUDIT_TARGET_TYPE,
                        identityId.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("consecutiveFailures=" + failures)));
    }

    /**
     * Whether the identity may authenticate, and how many failures it has accumulated.
     *
     * <p>{@code lockedByThisFailure} distinguishes <em>the attempt that crossed the threshold</em>
     * from every attempt afterwards. Both leave the account locked; only the first is an event.
     */
    public record Lock(boolean locked, boolean lockedByThisFailure, int consecutiveFailures) {

        /**
         * No identity matched, so nothing was counted.
         *
         * <p>Not "not locked" with a count of zero by coincidence — the two are the same value here
         * and mean different things, and a caller must not be able to tell them apart, because
         * telling them apart is telling an attacker the account exists.
         */
        static final Lock NOTHING_TO_COUNT = new Lock(false, false, 0);
    }

    private static String intervalOf(java.time.Duration duration) {
        // Seconds, so a Duration expressed in any unit reaches PostgreSQL as one it parses exactly.
        return duration.toSeconds() + " seconds";
    }
}
