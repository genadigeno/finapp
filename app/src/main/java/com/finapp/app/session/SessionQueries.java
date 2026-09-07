package com.finapp.app.session;

import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionRevocation;
import com.finapp.identity.SessionStore;
import com.finapp.platform.correlation.CorrelationContext;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary for the session endpoints (`P1-TSK-016`).
 *
 * <h2>Ownership is passed as a proven session, never as an identifier</h2>
 *
 * <p>Every method here takes the {@link Session} the interceptor authenticated, and derives the
 * owner from it. That is a deliberately awkward signature: an {@code IdentityId} parameter would be
 * satisfied just as well by one read out of the request, which is the exact defect ADR-0031 names.
 * Making the caller hold a proven session means the unsafe version has nothing to pass.
 *
 * <h2>Listing is not audited</h2>
 *
 * <p>Reading your own sessions is not a privileged action, and an audit record per read would bury
 * real decisions under traffic — the argument {@code P1-TSK-014} already made for one record per
 * operation rather than per session. Revocation is audited, by {@link SessionRevocation}.
 */
@Service
public class SessionQueries {

    private final SessionStore<Connection> sessions;
    private final SessionRevocation revocation;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final Clock clock;

    public SessionQueries(
            SessionStore<Connection> sessions,
            SessionRevocation revocation,
            TransactionTemplate sessionTransactions,
            DataSource dataSource,
            Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.revocation = Objects.requireNonNull(revocation, "revocation must not be null");
        this.transactions =
                Objects.requireNonNull(sessionTransactions, "sessionTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Every live session belonging to the authenticated identity. */
    public List<SessionSummary> listOwnedBy(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        Instant at = Instant.now(clock);

        List<Session> live =
                inATransaction(
                        unitOfWork -> sessions.findLiveFor(unitOfWork, current.identityId(), at));
        return live.stream().map(session -> SessionSummary.of(session, current)).toList();
    }

    /**
     * Ends one session, if it belongs to the authenticated identity.
     *
     * <p>The audit record is written in the same transaction as the revocation, so a trail entry
     * cannot exist for a revocation that rolled back, nor a revocation without its entry
     * ({@code INV-AUD-01}).
     *
     * @return whether a live session of that identity was ended. A caller must report false the
     *     same way whether the session was somebody else's or never existed
     */
    public boolean revokeOwnedBy(Session current, SessionId target) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(target, "target must not be null");

        // The audit record carries the correlation identifier of this request; SessionRevocation
        // refuses to write one outside a scope rather than fabricating a value that points at no
        // flow (P0-TSK-014).
        CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A revocation must run inside a correlation scope"));

        return Boolean.TRUE.equals(
                inATransaction(
                        unitOfWork ->
                                revocation.revoke(unitOfWork, target, current.identityId())));
    }

    private <T> T inATransaction(java.util.function.Function<Connection, T> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
