package com.finapp.app.administration;

import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary for the two administrative operations (`P1-TSK-028`).
 *
 * <h2>One transaction, and for a suspension it is doing real work</h2>
 *
 * <p>A suspension is a status transition, a bulk session revocation, an audit record and an outbox
 * row. Committing the transition without the revocation would leave an identity marked suspended
 * whose sessions still work — which is precisely the state this operation exists to prevent, and it
 * would look correct from the administrator's side.
 *
 * <p>{@code TransactionTemplate} rather than {@code @Transactional}, for ADR-0033's reason: the
 * annotation fails <em>silently</em> on self-invocation.
 *
 * <h2>No security scope is entered here</h2>
 *
 * <p>{@code SessionAuthenticationInterceptor} has already established one naming the proven
 * administrator, and it closes in {@code afterCompletion} — after this returns. Entering a second
 * would either shadow the real actor or record the platform, and {@code SecurityContext.require()}
 * refusing an unestablished actor is what makes that a failure rather than a silent
 * misattribution ({@code P0-TSK-032}).
 */
public class IdentityAdministrationService {

    private final IdentityAdministration administration;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public IdentityAdministrationService(
            IdentityAdministration administration,
            TransactionTemplate administrationTransactions,
            DataSource dataSource) {
        this.administration =
                Objects.requireNonNull(administration, "administration must not be null");
        this.transactions =
                Objects.requireNonNull(
                        administrationTransactions, "administrationTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    public IdentityAdministration.Suspension suspend(
            IdentityId subject, IdentityId actor, String reason) {
        return inOneTransaction(
                unitOfWork -> administration.suspend(unitOfWork, subject, actor, reason));
    }

    public IdentityAdministration.RoleGrant assignRole(
            IdentityId subject, RoleName role, IdentityId actor, String reason) {
        return inOneTransaction(
                unitOfWork -> administration.assignRole(unitOfWork, subject, role, actor, reason));
    }

    private <T> T inOneTransaction(Function<Connection, T> command) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return command.apply(unitOfWork);
                    } finally {
                        // A no-op for a transaction-bound connection, and the correct call
                        // regardless: closing it here would end the transaction this method is the
                        // boundary of.
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
