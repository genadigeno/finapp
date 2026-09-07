package com.finapp.app.recovery;

import com.finapp.identity.ContactChannelService;
import com.finapp.identity.EmailAddress;
import com.finapp.sharedkernel.security.Sensitive;
import com.finapp.identity.IdentityId;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RecoveryRequestId;
import com.finapp.identity.RecoveryService;
import com.finapp.platform.security.SecurityContext;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary for recovery and channel verification (`P1-TSK-023`).
 *
 * <h2>Two of these operations are unauthenticated, and that decides the actor</h2>
 *
 * <p>Recovery is begun and completed by somebody who <strong>cannot log in</strong> - that is what
 * recovery is for - so there is no proven identity to attribute the action to, and naming a guessed
 * one would put an unproven claim in a permanent record ({@code INV-HIST-03}). The platform is the
 * only honest actor, exactly as it is for registration.
 *
 * <p>These are the third and fourth {@code enterSystem()} sites, and
 * {@code SystemActorCallSitesAreEnumeratedTest} refuses them until the reason is written down -
 * which is that guard, written one task ago, doing its job.
 *
 * <h2>Adding a channel is authenticated, and that is the asymmetry that matters</h2>
 *
 * <p>If registering a channel were unauthenticated, an attacker could point recovery at their own
 * mailbox without holding anything at all. Requiring a session means the first move in a takeover
 * still costs a stolen password.
 */
@Service
@SuppressWarnings("try") // The Scope is used for its close side effect.
public class RecoveryApplicationService {

    private final RecoveryService recoveries;
    private final ContactChannelService channels;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public RecoveryApplicationService(
            RecoveryService recoveries,
            ContactChannelService channels,
            TransactionTemplate recoveryTransactions,
            DataSource dataSource) {
        this.recoveries = Objects.requireNonNull(recoveries, "recoveries must not be null");
        this.channels = Objects.requireNonNull(channels, "channels must not be null");
        this.transactions =
                Objects.requireNonNull(recoveryTransactions, "recoveryTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Begins recovery.
     *
     * <p>The outcome is deliberately <strong>discarded</strong>. The caller must answer identically
     * whether anything happened, so returning it would create the branch that discloses whether the
     * account exists ({@code INV-IDN-07}). The token goes to the channel, and in Phase 1 nothing
     * delivers it - the adapter is Phase 15's.
     */
    public void initiate(LoginIdentifier login) {
        Objects.requireNonNull(login, "login must not be null");
        inAFlowAsThePlatform(
                unitOfWork -> {
                    recoveries.initiate(unitOfWork, login);
                    return null;
                });
    }

    /** Completes recovery. False is one answer for every reason it could be. */
    public boolean complete(RecoveryRequestId id, Sensitive<String> token, RawPassword replacement) {
        Objects.requireNonNull(id, "id must not be null");
        return Boolean.TRUE.equals(
                inAFlowAsThePlatform(
                        unitOfWork -> recoveries.complete(unitOfWork, id, token, replacement)));
    }

    /** Registers a channel against the authenticated identity. */
    public void addChannel(IdentityId owner, EmailAddress address) {
        Objects.requireNonNull(owner, "owner must not be null");
        inATransaction(
                unitOfWork -> {
                    channels.add(unitOfWork, owner, address);
                    return null;
                });
    }

    /**
     * Proves control of a channel.
     *
     * <p>Runs as the platform for the same reason recovery does: the token arrives from a mailbox,
     * and the person reading it may hold no session at all.
     */
    public boolean verifyChannel(Sensitive<String> token) {
        Objects.requireNonNull(token, "token must not be null");
        return Boolean.TRUE.equals(
                inAFlowAsThePlatform(
                        unitOfWork -> channels.verify(unitOfWork, token).isPresent()));
    }

    // -----------------------------------------------------------------

    private <T> T inAFlowAsThePlatform(Function<Connection, T> work) {
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return inATransaction(work);
        }
    }

    private <T> T inATransaction(Function<Connection, T> work) {
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
