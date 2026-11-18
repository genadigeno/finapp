package com.finapp.app.administration;

import com.finapp.identity.IdentityId;
import com.finapp.identity.Session;
import com.finapp.identity.SessionAdministration;
import com.finapp.identity.SessionId;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary of another identity's sessions' administration (`P17-TSK-015`): each list with its record, each act
 * with its revocation and its record, committed together or not at all. Built by {@code AdministrationBeans} on the
 * administrative template, as {@link IdentityAdministrationService} is; no security scope is entered here.
 */
@RequiredArgsConstructor
public class SessionAdministrationService {

    @NonNull private final SessionAdministration administration;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    public Optional<List<Session>> liveSessionsOf(IdentityId subject) {
        return inOneTransaction(unitOfWork -> administration.liveSessionsOf(unitOfWork, subject));
    }

    public SessionAdministration.Outcome revoke(IdentityId subject, Optional<SessionId> session, IdentityId actor, String reason) {
        return inOneTransaction(unitOfWork -> administration.revoke(unitOfWork, subject, session, actor, reason));
    }

    public SessionAdministration.AllRevoked revokeAll(IdentityId subject, IdentityId actor, String reason) {
        return inOneTransaction(unitOfWork -> administration.revokeAll(unitOfWork, subject, actor, reason));
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }
}
