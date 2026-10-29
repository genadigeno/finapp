package com.finapp.app.risk;

import com.finapp.identity.Identity;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.SessionRevocation;
import com.finapp.identity.SuspensionCause;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.IdentityActions;
import com.finapp.risk.RiskPartyIdentities;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Risk's {@link IdentityActions} port over identity's existing commands (`P13-TSK-017`; {@code PHASE_13_PLAN.md}
 * sections 7.3, 12.8; ADR-0112 decision 2; owner decision C10; {@code INV-RSK-02}, {@code INV-IDEM-01},
 * {@code INV-IDN-03}): {@link IdentityAdministration#suspend} and {@link IdentityAdministration#reinstate} - identity's
 * own state machine, conditional move, audit ({@code identity.IdentitySuspended} / {@code IdentityReinstated}) and event
 * - and {@link SessionRevocation#revokeAll} ({@code identity.SessionRevoked}), each over every identity of the party the
 * action targets (bounded by {@link RiskPartyIdentities#BOUND}, ascending), in <strong>one transaction of identity's
 * own</strong> - never a risk one (T-act holds no risk row while the owner works). Identity audits each move as its own
 * act with the acting principal - the platform, under the action driver's system scope - and the action's reason code.
 * The platform never resets a credential (C10): only identity's existing suspend, reinstate and revoke.
 *
 * <p><strong>Risk lifts only its own suspension</strong> (the Phase 13 gate, owner item 25; identity {@code V024}):
 * the suspension is recorded with the cause {@link SuspensionCause#RISK}, and the reinstatement names it - identity's
 * conditional write lifts a {@code RISK} suspension only, so an administrator's suspension (or one recorded before
 * V024) stands and that identity answers {@code REFUSED}. Identity keeps the decision; risk never becomes a second owner
 * of it.
 *
 * <p><strong>Keyed {@code risk.action:<id>}.</strong> Identity's administration converges on state but takes no key, so
 * this adapter is the keyed door: the platform's {@link IdempotentExecutor} records the answer under
 * ({@value #SCOPE}, the action's key) in the same identity transaction, so a re-drive replays the first answer word for
 * word (a {@code DONE} stays {@code DONE}) and a duplicate in flight is refused, never run twice.
 *
 * <p><strong>The outcomes</strong> ({@link IdentityActions}): any identity moved (or session ended) by this key →
 * {@code DONE}; else any already in the state asked (or no session live) → {@code ALREADY}; else - no identity, every one
 * closed, or a decider who is no identity the platform holds (identity names two parties and refuses a self-loop) →
 * {@code REFUSED}. Identity's not-self rule is given the person who approved the action, never the subject.
 */
public final class IdentityRiskActions implements IdentityActions {

    /** The idempotency scope of identity's risk-action doors. */
    static final String SCOPE = "identity.risk-action";

    /** Identity's own transaction - one per keyed call. */
    @FunctionalInterface
    interface Transactions {
        <T> T inOne(Function<Connection, T> work);

        /** A new transaction of {@code template} (identity's {@code administrationTransactions}) on {@code dataSource}. */
        static Transactions over(TransactionTemplate template, DataSource dataSource) {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(dataSource, "dataSource");
            return new Transactions() {
                @Override
                public <T> T inOne(Function<Connection, T> work) {
                    return template.execute(status -> {
                        Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                        try {
                            return work.apply(unitOfWork);
                        } finally {
                            DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                        }
                    });
                }
            };
        }
    }

    private final IdentityAdministration administration;
    private final SessionRevocation sessions;
    private final IdentityStore<Connection> identities;
    private final IdempotentExecutor executor;
    private final Transactions transactions;

    IdentityRiskActions(IdentityAdministration administration, SessionRevocation sessions,
            IdentityStore<Connection> identities, IdempotentExecutor executor, Transactions transactions) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public IdentityRiskActions(IdentityAdministration administration, SessionRevocation sessions,
            IdentityStore<Connection> identities, IdempotentExecutor executor, TransactionTemplate template,
            DataSource dataSource) {
        this(administration, sessions, identities, executor, Transactions.over(template, dataSource));
    }

    @Override
    public ActionOutcome suspend(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
        Objects.requireNonNull(decidedBy, "decidedBy");
        return keyed("SUSPEND_IDENTITY", partyId, idempotencyKey, reason, uow -> decider(decidedBy)
                .map(decider -> eachIdentity(uow, partyId, identity -> switch (administration.suspend(uow,
                        identity.id(), decider, reason, SuspensionCause.RISK)) {
                    case SUSPENDED -> ActionOutcome.DONE;
                    case NOT_ACTIVE -> now(uow, identity.id()) == IdentityStatus.SUSPENDED ? ActionOutcome.ALREADY
                            : ActionOutcome.REFUSED;
                    case NOT_FOUND, SELF -> ActionOutcome.REFUSED;
                }))
                .orElse(ActionOutcome.REFUSED));
    }

    @Override
    public ActionOutcome reinstate(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
        Objects.requireNonNull(decidedBy, "decidedBy");
        return keyed("REINSTATE_IDENTITY", partyId, idempotencyKey, reason, uow -> decider(decidedBy)
                .map(decider -> eachIdentity(uow, partyId, identity -> switch (administration.reinstate(uow,
                        identity.id(), decider, reason, SuspensionCause.RISK)) {
                    case REINSTATED -> ActionOutcome.DONE;
                    case NOT_SUSPENDED -> now(uow, identity.id()) == IdentityStatus.ACTIVE ? ActionOutcome.ALREADY
                            : ActionOutcome.REFUSED;
                    // An administrator's suspension (or one recorded before identity V024) is identity's decision,
                    // never risk's to lift (the Phase 13 gate, item 25): it stands, and this identity is refused.
                    case OTHER_CAUSE, NOT_FOUND, SELF -> ActionOutcome.REFUSED;
                }))
                .orElse(ActionOutcome.REFUSED));
    }

    @Override
    public ActionOutcome revokeSessions(UUID partyId, String idempotencyKey, String reason) {
        return keyed("REVOKE_SESSIONS", partyId, idempotencyKey, reason, uow -> eachIdentity(uow, partyId,
                identity -> sessions.revokeAll(uow, identity.id()) > 0 ? ActionOutcome.DONE : ActionOutcome.ALREADY));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * {@code command} under ({@value #SCOPE}, {@code idempotencyKey}) in one identity transaction: the first answer
     * recorded beside the effect, replayed to every later call of the key.
     */
    private ActionOutcome keyed(String kind, UUID partyId, String idempotencyKey, String reason,
            Function<Connection, ActionOutcome> command) {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(reason, "reason");
        IdempotencyKey key = new IdempotencyKey(SCOPE, idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256((kind + "|" + partyId + "|" + reason)
                .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = transactions.inOne(uow -> executor.execute(uow, key, fingerprint,
                work -> CommandResult.succeeded(StoredResponse.of(command.apply(work).name()
                        .getBytes(StandardCharsets.UTF_8), "text/plain"))));
        return ActionOutcome.valueOf(new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "a risk action's identity door stores its outcome")), StandardCharsets.UTF_8));
    }

    /** Every identity of {@code partyId}, ascending, each moved by {@code move}; the answers folded. */
    private ActionOutcome eachIdentity(Connection unitOfWork, UUID partyId, Function<Identity, ActionOutcome> move) {
        List<IdentityId> held = identities.findIdsByParty(unitOfWork, partyId, RiskPartyIdentities.BOUND).stream()
                .sorted((left, right) -> left.value().compareTo(right.value())).toList();
        boolean done = false;
        boolean already = false;
        for (IdentityId id : held) {
            Optional<Identity> identity = identities.findById(unitOfWork, id);
            if (identity.isEmpty()) {
                continue;
            }
            ActionOutcome answered = move.apply(identity.get());
            done |= answered == ActionOutcome.DONE;
            already |= answered == ActionOutcome.ALREADY;
        }
        return done ? ActionOutcome.DONE : already ? ActionOutcome.ALREADY : ActionOutcome.REFUSED;
    }

    private IdentityStatus now(Connection unitOfWork, IdentityId id) {
        return identities.findById(unitOfWork, id).map(Identity::status).orElse(IdentityStatus.CLOSED);
    }

    /** The approver as identity names an administrator - an identity id; anyone else is no identity it holds. */
    private static Optional<IdentityId> decider(String decidedBy) {
        try {
            return Optional.of(IdentityId.of(UUID.fromString(decidedBy)));
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
    }
}
