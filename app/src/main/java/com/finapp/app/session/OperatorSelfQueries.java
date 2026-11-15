package com.finapp.app.session;

import com.finapp.identity.Authorization;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Describes the signed-in operator to themselves (`X-TSK-019`): {@link OperatorSelfController}'s reads.
 *
 * <p><strong>Nothing here is held between requests.</strong> The roles come from the read that admitted this request; the
 * session's bounds and whether a factor is enrolled are read now, in one transaction, from the tables that decide them. So a
 * grant, a revocation, an enrolment or a sign-out on any replica is what the next request on any other replica reports.
 */
@RequiredArgsConstructor
public final class OperatorSelfQueries {

    @NonNull private final SessionStore<Connection> sessions;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /**
     * The operator, as the database and the request's own role read describe them.
     *
     * <p><strong>The session is read again</strong>, not taken from the interceptor's copy: the interceptor extended its idle
     * bound after reading it, so its copy holds the bound from before this request. Re-read, the bound is the one the database
     * now holds. A session that stopped being live since the interceptor admitted the request - a sign-out on another device,
     * the last role revoked - answers {@code 401 api.Unauthenticated}, as the next request would.
     */
    public OperatorSelfController.OperatorSelf describe(Session current, Authorization.Standing standing) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(standing, "standing must not be null");

        // The answer is built inside the transaction and nothing about the session leaves it but the answer: a value
        // holding a Session would be the per-process session state NoProcessLocalSessionStateTest refuses.
        Optional<OperatorSelfController.OperatorSelf> described = Objects.requireNonNull(transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                boolean factorEnrolled =
                        enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
                return sessions.findLiveFor(unitOfWork, current.identityId()).stream()
                        .filter(session -> session.id().equals(current.id()))
                        .findFirst()
                        .map(session -> new OperatorSelfController.OperatorSelf(
                                session.identityId().value().toString(),
                                standing.roles().stream().map(RoleName::name).sorted().toList(),
                                permissionsOf(standing),
                                session.assurance().name(),
                                factorEnrolled,
                                session.idleExpiresAt(),
                                session.absoluteExpiresAt()));
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        }));
        return described.orElseThrow(() -> new ApiException(
                PlatformErrorCode.UNAUTHENTICATED, "The session ended after it was presented"));
    }

    /** The union of the roles' grants, by name, sorted: {@code RoleName}'s mapping and nothing else. */
    static List<String> permissionsOf(Authorization.Standing standing) {
        return standing.roles().stream()
                .flatMap(role -> role.permissions().stream())
                .map(PermissionName::name)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
