package com.finapp.app.mfa;

import com.finapp.identity.MfaChallenge;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionRotation;
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

    private final MfaChallenge challenges;
    private final SessionPolicy policy;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public MfaChallengeApplicationService(
            MfaChallenge challenges,
            SessionPolicy sessionPolicy,
            TransactionTemplate mfaTransactions,
            DataSource dataSource) {
        this.challenges = Objects.requireNonNull(challenges, "challenges must not be null");
        this.policy = Objects.requireNonNull(sessionPolicy, "sessionPolicy must not be null");
        this.transactions =
                Objects.requireNonNull(mfaTransactions, "mfaTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
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

        return Objects.requireNonNull(rotated, "the transaction returned no outcome")
                .map(
                        result ->
                                new ElevatedSession(
                                        result.token().presentedValue().expose(),
                                        result.session().assurance().name(),
                                        result.session().idleExpiresAt()));
    }
}
