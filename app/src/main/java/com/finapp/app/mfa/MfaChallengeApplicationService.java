package com.finapp.app.mfa;

import com.finapp.identity.MfaChallenge;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionRotation;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary for a second-factor challenge (`P1-TSK-018`).
 *
 * <p>One transaction: the verification, the step consumption, the rotation, the audit record and
 * the throttle's counter all commit together or not at all. That matters more here than usual - a
 * consumed step without the session it bought would leave the customer holding a dead session and a
 * code they can no longer reuse.
 *
 * <p>Takes the proven {@link Session}, never an `IdentityId` - the `P1-TSK-016` shape, and for the
 * same reason: an identifier parameter would be satisfied just as well by one read out of the
 * request, which is the defect ADR-0031 names.
 */
@Service
public class MfaChallengeApplicationService {

    /**
     * {@code finapp.identity.mfa.challenge} — second-factor verifications, by outcome.
     *
     * <p>The plan names this {@code finapp.identity.mfa_challenge}, which
     * {@link com.finapp.platform.metrics.MetricNames#NAME} forbids: the convention is dots, not
     * underscores. Both forms produce the <strong>identical</strong> Prometheus series
     * {@code finapp_identity_mfa_challenge}, because Micrometer translates dots to the backend's
     * idiom — so the dotted form costs nothing and keeps the meter sorting beside its siblings.
     * {@code PHASE_1_PLAN.md} §10 is corrected rather than the convention widened.
     *
     * <p><strong>No reason tag.</strong> {@code MfaChallenge} distinguishes {@code locked},
     * {@code noFactor}, {@code wrongCode}, {@code replayed} and {@code sessionGone} — for the
     * <em>audit record</em>. Tagging them here would require {@code elevate} to return the reason,
     * which is exactly the field {@code P1-TSK-008} removed from {@code VerificationOutcome} and
     * {@code P1-TSK-010} refused to reinstate: a caller handed a reason is a caller that can leak
     * one. A metric answers <em>how many</em>; <em>which one</em> is the audit trail's question.
     */
    static final String CHALLENGE_COUNTER = "finapp.identity.mfa.challenge";

    private final MfaChallenge challenges;
    private final SessionPolicy policy;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    /**
     * Registered at CONSTRUCTION, one per outcome, never on first increment.
     *
     * <p>{@code MeterRegistry.counter(name, tags)} creates the meter on the first call, so a
     * freshly started instance would publish <strong>no series at all</strong> until the flow ran
     * once. An alert written on a rate then has nothing to evaluate at precisely the moment it
     * needed a series sitting at zero - a counter that starts existing when the thing it counts
     * happens is a delayed notification, not monitoring. Found by {@code P1-TSK-029}.
     */
    private final io.micrometer.core.instrument.Counter elevated;

    private final io.micrometer.core.instrument.Counter refused;

    public MfaChallengeApplicationService(
            MfaChallenge challenges,
            SessionPolicy sessionPolicy,
            TransactionTemplate mfaTransactions,
            DataSource dataSource,
            MeterRegistry meters) {
        this.challenges = Objects.requireNonNull(challenges, "challenges must not be null");
        this.policy = Objects.requireNonNull(sessionPolicy, "sessionPolicy must not be null");
        this.transactions =
                Objects.requireNonNull(mfaTransactions, "mfaTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(meters, "meters must not be null");
        this.elevated = meters.counter(CHALLENGE_COUNTER, "outcome", "elevated");
        this.refused = meters.counter(CHALLENGE_COUNTER, "outcome", "refused");
    }

    /** Elevates the session, or refuses. Empty for every reason, so none is distinguishable. */
    public Optional<ElevatedSession> elevate(Session current, String code) {
        Objects.requireNonNull(current, "current must not be null");

        Optional<SessionRotation.Rotated> rotated =
                transactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                return challenges.elevate(
                                        unitOfWork, current, code, policy.idleTimeout());
                            } finally {
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        });

        Optional<SessionRotation.Rotated> outcome =
                Objects.requireNonNull(rotated, "the transaction returned no outcome");

        // The tag is derived from PRESENCE, never computed alongside it - `P1-TSK-027`'s rule, so
        // the meter cannot disagree with what the caller receives. Incremented AFTER the
        // transaction: a counter moved inside one that later rolls back describes something that
        // did not happen.
        (outcome.isPresent() ? elevated : refused).increment();

        return outcome.map(
                result ->
                        new ElevatedSession(
                                result.token().presentedValue().expose(),
                                result.session().assurance().name(),
                                result.session().idleExpiresAt()));
    }
}
