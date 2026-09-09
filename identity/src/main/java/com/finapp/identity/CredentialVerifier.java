package com.finapp.identity;

import com.finapp.platform.persistence.DatabaseFailure;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether somebody knows the secret, and upgrades the credential while it is in hand
 * (`P1-TSK-008`, ADR-0032).
 *
 * <h2>Why this resolves the identity itself</h2>
 *
 * <p>It would be simpler to take an {@link IdentityId} and let the caller find it. That is the wrong
 * boundary. {@code INV-IDN-07} requires a <em>non-existent</em> account to be indistinguishable from
 * an existing one, and absence is therefore a case this component has to handle - pushing it out
 * makes the invariant the endpoint's problem, and {@code INV-IDN-05}'s lesson is that a control is
 * defeated by whichever path forgot it rather than by the path that remembered.
 *
 * <h2>Every failing path does the work</h2>
 *
 * <p>Four ways to fail, and <strong>all four run a full Argon2id verification</strong>:
 *
 * <ul>
 *   <li>no identity with that login identifier;
 *   <li>an identity that cannot authenticate - {@code SUSPENDED} or {@code CLOSED};
 *   <li>an identity holding no active credential;
 *   <li>an identity whose credential simply does not match.
 * </ul>
 *
 * <p>The middle two are the ones an implementation skips, and they are the ones that matter most: a
 * suspended account that answers instantly tells an attacker both that it exists <em>and</em> that
 * it is suspended. Skipping the work is {@code INV-IDN-07} lost through the timing channel rather
 * than through the response body, which is the harder half to notice and the harder half to test.
 *
 * <p><strong>The residual is stated rather than glossed.</strong> The dummy derivation is produced at
 * <em>current</em> parameters, and a real credential may be at weaker ones - so verifying an old
 * credential is genuinely cheaper than failing against the dummy, and a patient attacker could
 * distinguish "an account with a stale credential" from "no account". What bounds it is exactly the
 * upgrade below: the store converges on current parameters and the gap closes itself.
 *
 * <h2>Upgrade on use, and the rule that governs it</h2>
 *
 * <p>A successful verification is the only moment the platform legitimately holds the plaintext, and
 * therefore the only moment a credential can be strengthened without involving the customer
 * (ADR-0032). So a credential below current policy is re-derived here, in the caller's transaction.
 *
 * <p><strong>An upgrade failure must never turn a correct password into a failed login.</strong> The
 * customer typed the right thing; refusing them because a background optimisation collided would be
 * a self-inflicted outage. The upgrade therefore sits behind a savepoint and every failure of it is
 * discarded, logged, and reported as the success it was.
 *
 * <h2>Ten instances</h2>
 *
 * <p>This is the platform's first genuine read-then-write, so the claim {@code P1-TSK-007} made -
 * "no read-then-write anywhere" - does not extend here and is not relied on. What makes it safe is
 * that the write is <strong>conditional</strong>: {@code supersede} moves the row only while it is
 * still {@code ACTIVE}, and its row count is the outcome. Two instances upgrading the same
 * credential produce one upgrade; the loser is told it lost and skips its insert, so the partial
 * unique index is never even reached.
 */
public final class CredentialVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(CredentialVerifier.class);

    /** Long enough to be well outside {@link RawPassword}'s bounds check by a wide margin. */
    private static final int THROWAWAY_BYTES = 32;

    private final IdentityStore<Connection> identities;
    private final CredentialStore<Connection> credentials;
    private final PasswordDeriver deriver;
    private final IdGenerator ids;
    private final Clock clock;

    /**
     * A derivation to fail against when there is nothing real to verify.
     *
     * <p>Computed <strong>once, at construction, from a random throwaway password</strong> that is
     * discarded immediately. Not a constant in source: a hard-coded derivation is a value somebody
     * would eventually try to crack, and shipping one publishes a target for no benefit. Nobody -
     * including this process - knows a password that matches it, which is the property that makes
     * it safe to compare against.
     *
     * <p>Immutable once built, so ten instances share nothing ({@code DISTRIBUTED_EXECUTION.md} §3
     * gains no row). Each instance computes its own, and it does not matter that they differ: the
     * value is never compared across instances, only worked against.
     */
    private final Sensitive<String> dummyDerivation;

    public CredentialVerifier(
            IdentityStore<Connection> identities,
            CredentialStore<Connection> credentials,
            PasswordDeriver deriver,
            IdGenerator ids,
            Clock clock) {
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.deriver = Objects.requireNonNull(deriver, "deriver must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.dummyDerivation = deriver.derive(throwawayPassword());
    }

    /**
     * Verifies {@code password} against the credential of the identity that logs in with
     * {@code loginIdentifier}, upgrading that credential if it is below current policy.
     *
     * @param unitOfWork the caller's open transaction. The upgrade commits with whatever else the
     *     caller is doing - a session issue, an audit record - or with nothing at all
     * @return success carrying the identity, or a failure carrying nothing at all
     */
    public VerificationOutcome verify(
            Connection unitOfWork, LoginIdentifier loginIdentifier, RawPassword password) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");
        Objects.requireNonNull(password, "password must not be null");
        requireTransaction(unitOfWork);

        Optional<Identity> identity = identities.findByLoginIdentifier(unitOfWork, loginIdentifier);
        if (identity.isEmpty() || !identity.get().canAuthenticate()) {
            return failAfterDoingTheWork(password);
        }

        Optional<Credential> credential =
                credentials.findActive(unitOfWork, identity.get().id(), CredentialType.PASSWORD);
        if (credential.isEmpty()) {
            // An identity with no credential is the state P1-TSK-006 leaves every registration in
            // until P1-TSK-026 lands. It must cost the same as a wrong password, or the absence of
            // a credential is readable from the outside.
            return failAfterDoingTheWork(password);
        }

        if (!deriver.matches(password, credential.get().credentialDerivation())) {
            return VerificationOutcome.failed();
        }

        upgradeIfBelowPolicy(unitOfWork, identity.get(), credential.get(), password);
        return VerificationOutcome.succeeded(identity.get().id());
    }

    /**
     * Re-proves a caller's <em>current</em> password against the credential of a known identity,
     * for a credential change (`P1-TSK-033`).
     *
     * <p>Keyed by {@link IdentityId} rather than {@link LoginIdentifier}, because the caller holds a
     * <strong>proven session</strong> and its identity, not a login string — the {@code /me} shape.
     *
     * <p><strong>No upgrade-on-use here, deliberately.</strong> {@link #verify} re-derives a
     * below-policy credential because the plaintext is legitimately in hand and the credential
     * survives. Here it does <em>not</em> survive — it is about to be superseded — so upgrading it
     * would be ~46&nbsp;ms of work on a row that is about to become history. The credential is
     * returned so the caller supersedes exactly the one this matched, in one read.
     *
     * <p>The equal-work discipline is kept for the same reason {@link #verify} keeps it: an
     * identity concurrently closed, or one whose credential was concurrently superseded, must cost
     * what a wrong password costs, or the outcome is readable from a clock. It runs inside the
     * caller's transaction like everything else it coordinates with.
     *
     * @return the active credential, if the password matched it; empty on any failure, having done
     *     equivalent work
     */
    public Optional<Credential> matchCurrent(
            Connection unitOfWork, IdentityId identityId, RawPassword password) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(password, "password must not be null");
        requireTransaction(unitOfWork);

        Optional<Identity> identity = identities.findById(unitOfWork, identityId);
        if (identity.isEmpty() || !identity.get().canAuthenticate()) {
            failAfterDoingTheWork(password);
            return Optional.empty();
        }
        Optional<Credential> credential =
                credentials.findActive(unitOfWork, identityId, CredentialType.PASSWORD);
        if (credential.isEmpty()) {
            failAfterDoingTheWork(password);
            return Optional.empty();
        }
        if (!deriver.matches(password, credential.get().credentialDerivation())) {
            return Optional.empty();
        }
        return credential;
    }

    /**
     * Fails, having done the work an attempt would have cost (`P1-TSK-010`).
     *
     * <p>For a caller that cannot even construct a {@link RawPassword} from what it was given - a
     * value below the minimum length, say. That must not be a fast path: this endpoint has exactly
     * two outcomes, and a rejection that skips the derivation is a third one readable from a clock.
     *
     * <p>It touches no database, so it needs no transaction; the caller has not reached one yet.
     */
    public VerificationOutcome verifyNothing() {
        deriver.matches(throwawayPassword(), dummyDerivation);
        return VerificationOutcome.failed();
    }

    // -----------------------------------------------------------------

    /**
     * Refuses a connection in auto-commit mode.
     *
     * <p><strong>Found by the upgrade silently not happening.</strong> The upgrade needs a savepoint,
     * and {@code setSavepoint} throws on an auto-commit connection - which the catch below then
     * discarded as though it were an ordinary upgrade collision. Every login would have verified
     * correctly and upgraded nothing, for ever, with a warning nobody reads and no test failing.
     * That is the worst shape of defect this repository keeps meeting: a control that reports
     * success for work it did not do.
     *
     * <p>So a caller that has not opened a transaction is refused <em>up front</em>, with a message
     * about the mistake rather than about the mechanism. The broad catch below stays, because a
     * genuine upgrade failure must not fail a correct authentication - but it now catches only
     * failures of the upgrade, not a caller who wired this wrongly. The same distinction, and the
     * same remedy, as {@code JdbcInboxRecordStore}.
     */
    private static void requireTransaction(Connection unitOfWork) {
        try {
            if (unitOfWork.getAutoCommit()) {
                throw new IllegalArgumentException(
                        "Credential verification must run inside the caller's transaction: this "
                            + "connection is in auto-commit mode, so an upgrade could not be rolled "
                            + "back and would not be atomic with the authentication that authorised "
                            + "it");
            }
        } catch (SQLException e) {
            throw new CredentialStorageException(
                    DatabaseFailure.describe("Could not determine the transaction state", e));
        }
    }

    /**
     * Runs a full verification against the dummy derivation, then fails.
     *
     * <p>The result is discarded because it is always false - nobody knows the throwaway password -
     * but the <em>work</em> is the point, and it is why this is a method rather than an early
     * return. A test counts derivations rather than measuring a clock, so "the work happened" is a
     * deterministic assertion instead of a flaky one.
     */
    private VerificationOutcome failAfterDoingTheWork(RawPassword password) {
        deriver.matches(password, dummyDerivation);
        return VerificationOutcome.failed();
    }

    /**
     * Re-derives a credential that is below current policy, discarding any failure.
     *
     * <p>Behind a savepoint, because the transaction must survive whatever happens here: the
     * password was correct, and the caller is entitled to its success however the upgrade goes.
     */
    private void upgradeIfBelowPolicy(
            Connection unitOfWork, Identity identity, Credential credential, RawPassword password) {

        if (!isBelowPolicy(credential)) {
            return;
        }

        Savepoint beforeUpgrade = null;
        try {
            beforeUpgrade = unitOfWork.setSavepoint("before_credential_upgrade");

            // Conditional, and its answer is the coordination point. If another instance has already
            // upgraded this credential, this affects zero rows and there is nothing left to do -
            // inserting a replacement anyway would collide with the partial unique index and abort
            // a transaction that had every right to commit.
            if (!credentials.supersede(unitOfWork, credential.id(), Instant.now(clock))) {
                unitOfWork.rollback(beforeUpgrade);
                return;
            }

            credentials.insert(
                    unitOfWork,
                    Credential.forPassword(
                            ids, clock, identity.id(), credential.type(), deriver, password));
            unitOfWork.releaseSavepoint(beforeUpgrade);
        } catch (RuntimeException | SQLException failed) {
            rollbackQuietly(unitOfWork, beforeUpgrade);
            // Warn, not error, and deliberately without the identity or the identifier: this is a
            // missed optimisation rather than an incident, and the credential is upgraded on the
            // next successful login. Naming the account would put an existence fact in a log line
            // for an event that is not about a person doing anything wrong.
            LOGGER.warn("A credential upgrade was discarded; the authentication itself succeeded",
                    failed);
        }
    }

    /**
     * Whether a credential no longer matches current policy.
     *
     * <p>Weaker parameters <strong>or a different algorithm</strong>. The second half is what makes
     * ADR-0032's "a second algorithm can coexist during migration" true rather than aspirational: a
     * credential produced by the previous algorithm is not weak by its own parameters and must still
     * be re-derived, and nothing in {@code isWeakerThan} would ever say so.
     */
    private boolean isBelowPolicy(Credential credential) {
        return credential.algorithm() != deriver.algorithm()
                || credential.isWeakerThan(deriver.currentParameters());
    }

    private static void rollbackQuietly(Connection unitOfWork, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            unitOfWork.rollback(savepoint);
        } catch (SQLException unrecoverable) {
            // The transaction cannot be salvaged, so the caller must not be told it succeeded. This
            // is the one upgrade failure that is allowed to fail the login, because the alternative
            // is reporting success on a transaction that will not commit.
            throw new CredentialStorageException(
                    DatabaseFailure.describe(
                            "Could not roll back a failed credential upgrade; the transaction must"
                                    + " not commit",
                            unrecoverable));
        }
    }

    /**
     * A password nobody will ever supply.
     *
     * <p>{@code SecureRandom} rather than a constant, and discarded as soon as it is derived. The
     * only property required is that no caller can produce it.
     */
    private static RawPassword throwawayPassword() {
        byte[] random = new byte[THROWAWAY_BYTES];
        new SecureRandom().nextBytes(random);
        return RawPassword.of(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
    }
}
