package com.finapp.app.authentication;

import com.finapp.identity.AuthenticationThrottle;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.IdentityAuthentication;
import com.finapp.identity.IdentityId;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.identity.VerificationOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authenticates a person (`P1-TSK-010`).
 *
 * <h2>The refusal is returned, never thrown, and that is structural</h2>
 *
 * <p>A failed authentication writes an audit record - the most security-relevant record this phase
 * produces, and the only way a credential-stuffing campaign is visible at all. That record is
 * written on the caller's connection inside this transaction, so <strong>throwing to produce the
 * 401 would roll it back and destroy it</strong>. The service therefore returns an {@link Outcome}
 * and the controller converts it, which is the shape {@code RegistrationService} already uses and
 * for the same reason.
 *
 * <h2>One transaction</h2>
 *
 * <p>Verification, the credential upgrade it may perform, the audit record and the outbox row all
 * commit together or not at all. {@code CredentialVerifier} <em>requires</em> auto-commit to be off
 * - it refuses such a connection outright - so the transaction is not optional here, it is a
 * precondition of the component.
 *
 * <p>{@code READ COMMITTED}, deliberately, and unlike registration this flow <em>does</em> contain a
 * read-then-write: the credential is read and may then be superseded. What makes that safe is that
 * the write is <strong>conditional</strong> - {@code supersede} moves the row only while it is
 * still {@code ACTIVE} and its row count is the outcome - so a higher isolation level would buy
 * nothing (`P1-TSK-008`).
 *
 * <h2>Not idempotent, and this is stronger than "the money-moving clause is vacuous"</h2>
 *
 * <p>Registration opted into idempotency although it moves no money, because a retry that creates a
 * second Party is a duplicate person. Authentication must <strong>not</strong>: an idempotency key
 * is explicitly <em>not a secret and is not redacted</em> ({@code API_CONVENTIONS.md} §6), so a
 * stored success keyed on one would let anybody who saw the key in a proxy log replay a
 * <em>successful authentication</em>. The mechanism that makes registration safe would make this
 * endpoint an authentication bypass. A retried login is meant to re-authenticate - the credential
 * may have changed since.
 *
 * <h2>Not delivered here: the session</h2>
 *
 * <p>{@code P1-TSK-010} declares {@code Deps: P1-TSK-013}, which is {@code TODO}. So a successful
 * authentication returns nothing a client can hold, and {@code PHASE_1_PLAN.md} §11's M1.2
 * acceptance - <em>"an identity authenticates and receives a session"</em> - is not met by this task
 * and cannot be until the session aggregate exists. Recorded in {@code CURRENT_STATE.md} rather
 * than worked around, because the workaround would be to invent a session shape that ADR-0030 has
 * already decided and {@code P1-TSK-013} must own.
 */
public final class AuthenticationService {

    /** {@code finapp.<module>.<noun>}, enforced against the live registry by the build. */
    static final String AUTHENTICATION_COUNTER = "finapp.identity.authentication";

    /**
     * Locks, counted separately from failures (`PHASE_1_PLAN.md` §10).
     *
     * <p>A separate meter rather than a third tag value on the counter above, because the two answer
     * different questions: a failure rate is noisy and mostly benign, while <em>a spike in locks is
     * a credential-stuffing campaign</em>. Folding it into an `outcome` tag would bury the second
     * signal inside the first's noise.
     *
     * <p>No tag identifies which account (ADR-0018). A metric answers <em>how many</em>; <em>which
     * one</em> is the audit trail's question, and it is answered there.
     */
    static final String LOCKOUT_COUNTER = "finapp.identity.lockout";

    private final CredentialVerifier verifier;
    private final AuthenticationThrottle throttle;
    private final IdentityAuthentication authentications;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final MeterRegistry meters;

    public AuthenticationService(
            CredentialVerifier verifier,
            AuthenticationThrottle throttle,
            IdentityAuthentication authentications,
            TransactionTemplate transactions,
            DataSource dataSource,
            MeterRegistry meters) {
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.throttle = Objects.requireNonNull(throttle, "throttle must not be null");
        this.authentications =
                Objects.requireNonNull(authentications, "authentications must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    /** Authenticates, or refuses without saying why. */
    public Outcome authenticate(AuthenticationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // Normalisation happens here, once, so a capital letter finds the same row the unique
        // index claimed. A malformed identifier cannot reach this method: the boundary rejects it.
        LoginIdentifier login = new LoginIdentifier(request.loginIdentifier());

        Outcome outcome =
                transactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                return attempt(unitOfWork, login, request.password());
                            } finally {
                                // A no-op for a transaction-bound connection, and the correct call
                                // regardless: closing it here would end the transaction this
                                // method is the boundary of.
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        });
        meters.counter(AUTHENTICATION_COUNTER, "outcome", Objects.requireNonNull(outcome).tag())
                .increment();
        return outcome;
    }

    // -----------------------------------------------------------------

    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private Outcome attempt(
            Connection unitOfWork, LoginIdentifier login, Sensitive<String> secret) {
        // VERIFICATION FIRST, UNCONDITIONALLY - the throttle is never consulted before it.
        //
        // The instinct is the opposite: check the lock, and refuse a locked account without paying
        // for a derivation, which is the CPU relief lockout appears to be for. It is an
        // account-existence oracle. Attempt often enough against any identifier: if it exists it
        // locks, if it does not nothing happens - and afterwards the locked one answers in a
        // millisecond while the unknown one still takes ~46 ms. `INV-IDN-07` lost to the control
        // added beside it. So a lock costs exactly what every other failure costs, and the relief a
        // fail-fast would buy belongs to a per-source rate limit, which is a different key.
        VerificationOutcome verified = verify(unitOfWork, login, secret);

        // The security scope is entered AFTER verification, because until then nobody knows who is
        // acting - and on the failure path there may be no identity at all. Establishing a scope
        // before the answer exists would mean guessing, which is the thing ADR-0021 refuses.
        if (verified.isSuccess()) {
            IdentityId identityId = verified.identityId().orElseThrow();
            // The platform's first real actor. The identity is proven at this exact point, so
            // recording the platform instead would say the platform logged somebody in.
            try (SecurityContext.Scope ignored =
                    SecurityContext.enter(
                            new Actor(identityId.value().toString(), ActorType.CUSTOMER))) {

                // A CORRECT PASSWORD IS STILL REFUSED WHILE LOCKED, and the counter is NOT cleared.
                // A lock a correct guess clears is not a lock - it is a signal that the guess was
                // right, which is the one thing an attacker is trying to learn.
                if (throttle.isLocked(unitOfWork, identityId)) {
                    authentications.failed(unitOfWork, login);
                    return Outcome.REFUSED;
                }
                throttle.clear(unitOfWork, identityId);
                authentications.succeeded(unitOfWork, identityId, login);
            }
            return Outcome.AUTHENTICATED;
        }

        // No established actor, and possibly no identity: the platform is the only honest answer.
        // What carries the information is the audit record's target - the attempted identifier.
        //
        // The throttle writes an audit record of its own when a failure crosses the threshold, so it
        // MUST run inside this scope. The first version had it outside and every lockout test failed
        // with "no actor has been established" - the guard from P0-TSK-032 doing exactly its job,
        // and a reminder that a default actor would have accepted the mistake silently and recorded
        // the wrong party permanently (INV-HIST-03).
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            // Counted only where there is an identity to count against, and the throttle decides
            // that in ONE statement rather than telling this service anything about existence.
            // Keyed on the login identifier for the same reason VerificationOutcome carries no
            // identity on failure: a caller handed one is a caller that can leak one.
            AuthenticationThrottle.Lock lock = throttle.recordFailure(unitOfWork, login);
            if (lock.lockedByThisFailure()) {
                meters.counter(LOCKOUT_COUNTER).increment();
            }
            authentications.failed(unitOfWork, login);
        }
        return Outcome.REFUSED;
    }

    /**
     * Verifies, treating a password this platform could never have stored as an ordinary failure.
     *
     * <p>{@code RawPassword} refuses a value below its minimum length, and that refusal must not
     * become a distinguishable outcome - see {@code AuthenticationRequest}. It still costs the full
     * derivation, because {@link CredentialVerifier#verifyNothing()} does the work an absent
     * identity would have cost.
     */
    private VerificationOutcome verify(
            Connection unitOfWork, LoginIdentifier login, Sensitive<String> secret) {
        RawPassword password;
        try {
            // The one unwrap on this path, and it is immediately re-wrapped by
            // RawPassword. SecretsAreUnwrappedInOnePlaceTest pins the PRODUCTION
            // unwrap sites to `identity`; this is app, so the value goes straight
            // into the domain type rather than into a local that outlives the line.
            password = new RawPassword(secret);
        } catch (IllegalArgumentException notAUsablePassword) {
            // Deliberately not logged and deliberately not distinguished. The exception's message
            // never quotes the value (asserted by P1-TSK-009), and there is nothing here worth
            // saying that would not also be an oracle.
            return verifier.verifyNothing();
        }
        return verifier.verify(unitOfWork, login, password);
    }

    /**
     * What happened, as far as anybody outside is told.
     *
     * <p>Two values. There is no {@code UNKNOWN_IDENTITY}, no {@code LOCKED}, no {@code SUSPENDED} -
     * the same reasoning that gives {@code VerificationOutcome} no reason code, one level up. An
     * enumeration added here is an enumeration oracle with a delay fuse: harmless the day it is
     * added, a second response shape the day somebody maps it to a message.
     */
    public enum Outcome {
        AUTHENTICATED("authenticated"),
        REFUSED("refused");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        /**
         * The metric tag.
         *
         * <p>{@code outcome} is the only tag this counter carries. No identity, no login
         * identifier, no source address - ADR-0018 forbids a tag value a request could influence,
         * because an identifier in a tag is both a cardinality explosion and a disclosure with
         * months of retention. A metric answers <em>how many</em>; <em>which one</em> is the audit
         * trail's question.
         */
        String tag() {
            return tag;
        }
    }
}
