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
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.util.Optional;
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

    /**
     * {@code finapp.identity.recovery.initiation} — the account-takeover signal.
     *
     * <p>{@code PHASE_1_PLAN.md} §10 names one meter, <em>"counter by stage"</em>. It is two, and
     * the reason is the tag allow-list rather than taste: {@code MetricNames.ALLOWED_TAG_KEYS} is
     * {@code {outcome, module, type}}, and {@code stage} is not in it.
     *
     * <p>Widening the allow-list was <strong>available and refused</strong>. {@code stage} would
     * satisfy ADR-0018's actual rule — a bounded set fixed at compile time, nothing a request can
     * influence — but the allow-list exists precisely to make that an explicit decision rather than
     * an autocomplete, and there is a naming that needs no widening. Using {@code type} for a stage
     * would be the dishonest rename this project declined for {@code sharedSecret}
     * ({@code P1-TSK-017}) and {@code ACTIVE_CREDENTIAL_OF} ({@code P1-TSK-023}).
     *
     * <p>Two meters also serve the signal better. The plan's §Security signals names
     * <em>"recovery initiation rate"</em> specifically, and that is now
     * {@code rate(finapp_identity_recovery_initiation_total[5m])} rather than a filtered sum.
     */
    static final String INITIATION_COUNTER = "finapp.identity.recovery.initiation";

    /** {@code finapp.identity.recovery.completion} — a credential actually changed. */
    static final String COMPLETION_COUNTER = "finapp.identity.recovery.completion";

    private final RecoveryService recoveries;
    private final ContactChannelService channels;
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
    private final java.util.Map<String, io.micrometer.core.instrument.Counter> counters =
            new java.util.LinkedHashMap<>();

    public RecoveryApplicationService(
            RecoveryService recoveries,
            ContactChannelService channels,
            TransactionTemplate recoveryTransactions,
            DataSource dataSource,
            MeterRegistry meters) {
        this.recoveries = Objects.requireNonNull(recoveries, "recoveries must not be null");
        this.channels = Objects.requireNonNull(channels, "channels must not be null");
        this.transactions =
                Objects.requireNonNull(recoveryTransactions, "recoveryTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(meters, "meters must not be null");
        for (String meter : java.util.List.of(INITIATION_COUNTER, COMPLETION_COUNTER)) {
            for (String outcome : java.util.List.of("accepted", "refused")) {
                counters.put(meter + "/" + outcome, meters.counter(meter, "outcome", outcome));
            }
        }
    }

    /**
     * Begins recovery.
     *
     * <p>The outcome is deliberately <strong>discarded from the response</strong>. The caller must
     * answer identically whether anything happened, so returning it would create the branch that
     * discloses whether the account exists ({@code INV-IDN-07}). The token goes to the channel, and
     * in Phase 1 nothing delivers it - the adapter is Phase 15's.
     *
     * <p><strong>The counter sees what the response hides, and that is correct rather than a
     * leak.</strong> A metric is never visible to the caller, and a flood of <em>refused</em>
     * initiations is a probe against identifiers that do not exist - which is the account-takeover
     * signal in its sharpest form, and the one {@code PHASE_1_PLAN.md} §10 calls out by name.
     */
    public void initiate(LoginIdentifier login) {
        Objects.requireNonNull(login, "login must not be null");
        Optional<RecoveryService.Initiated> initiated =
                inAFlowAsThePlatform(unitOfWork -> recoveries.initiate(unitOfWork, login));
        count(INITIATION_COUNTER, initiated != null && initiated.isPresent());
    }

    /** Completes recovery. False is one answer for every reason it could be. */
    public boolean complete(RecoveryRequestId id, Sensitive<String> token, RawPassword replacement) {
        Objects.requireNonNull(id, "id must not be null");
        boolean completed =
                Boolean.TRUE.equals(
                        inAFlowAsThePlatform(
                                unitOfWork ->
                                        recoveries.complete(unitOfWork, id, token, replacement)));
        count(COMPLETION_COUNTER, completed);
        return completed;
    }

    /**
     * One increment, after the transaction.
     *
     * <p>A counter moved inside a transaction that later rolls back is a metric describing
     * something that did not happen - and unlike the audit record, which must commit with the
     * operation, a measurement has nothing to lose by waiting.
     */
    private void count(String meter, boolean accepted) {
        counters.get(meter + "/" + (accepted ? "accepted" : "refused")).increment();
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
