package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The operator's reasoned state moves: suspend, reinstate, close (`P6-TSK-003`).
 *
 * <p><strong>Lock, then judge, then move conditionally</strong> — the {@code P3-TSK-014}
 * closers idiom: the row {@code FOR UPDATE}, the edge judged by the aggregate
 * ({@code INV-LIFE-02}), the write conditional on the from-state, the history row in the same
 * transaction. A racer's lost row count converges by re-reading — and if the re-read shows
 * the racer already produced this very state, the retry converges silently on it
 * ({@code INV-IDEM-01} through state, the adjustment-approval reasoning): one suspension,
 * however many operators asked.
 *
 * <p><strong>The reason is required, verbatim, on every move</strong> ({@code INV-AUD-03}):
 * each of these is a judgement about a counterparty, and {@link AuditRecord} refuses the
 * record without it — the action enum is where the domain decided.
 *
 * <p><strong>What suspension does not do</strong>: publish. Suspension is reversible
 * administrative state, not a terminal fact (ADR-0044's doctrine); its record is the audit
 * trail and the history row, and its <em>effect</em> — new dispatches refusing — lives at the
 * dispatching commands (`P6-TSK-007`, `-012`), which read the status in their own
 * transactions.
 */
public final class MerchantAdministration {

    static final String TARGET_TYPE = "merchant";

    private final MerchantStore<Connection> merchants;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public MerchantAdministration(
            MerchantStore<Connection> merchants,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock) {
        this.merchants = Objects.requireNonNull(merchants, "merchants must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** {@code ACTIVE → SUSPENDED}, reasoned. Converges on an already-suspended merchant. */
    public Merchant suspend(Connection unitOfWork, MerchantId id, String reason) {
        return move(
                unitOfWork,
                id,
                MerchantStatus.SUSPENDED,
                Merchant::suspend,
                MerchantAuditAction.MERCHANT_SUSPENDED,
                reason);
    }

    /** {@code SUSPENDED → ACTIVE}, reasoned. Converges on an already-active merchant. */
    public Merchant reinstate(Connection unitOfWork, MerchantId id, String reason) {
        return move(
                unitOfWork,
                id,
                MerchantStatus.ACTIVE,
                Merchant::reinstate,
                MerchantAuditAction.MERCHANT_REINSTATED,
                reason);
    }

    /** {@code ACTIVE → CLOSED}, terminal, reasoned. Converges on an already-closed merchant. */
    public Merchant close(Connection unitOfWork, MerchantId id, String reason) {
        return move(
                unitOfWork,
                id,
                MerchantStatus.CLOSED,
                Merchant::close,
                MerchantAuditAction.MERCHANT_CLOSED,
                reason);
    }

    /** The merchant as it stands — the operator's read. */
    public Optional<Merchant> find(Connection unitOfWork, MerchantId id) {
        return merchants.findById(unitOfWork, id);
    }

    private Merchant move(
            Connection unitOfWork,
            MerchantId id,
            MerchantStatus target,
            Transition transition,
            MerchantAuditAction action,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        Actor actor = SecurityContext.require();
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a merchant administration must run inside a"
                                                        + " correlation scope"));

        Merchant before =
                merchants
                        .findByIdForUpdate(unitOfWork, id)
                        .orElseThrow(UnknownMerchantException::new);

        // The retry's convergence: the state this move produces, already produced, is this
        // move done - one act, however many operators asked (the adjustment-approval idiom).
        if (before.status() == target) {
            return before;
        }

        Merchant moved = transition.apply(before, clock);
        if (!merchants.transition(unitOfWork, before, moved)) {
            // The lock makes a lost count unreachable in this flow; refusing loudly beats
            // guessing if an unknown writer proves otherwise (INV-CON-01).
            throw new MerchantStorageException(
                    "a locked merchant row's conditional move found another writer's state");
        }

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        // The operator's own words, verbatim (INV-AUD-03).
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "merchant=" + id + ", from=" + before.status() + ", to="
                                        + moved.status())));
        return moved;
    }

    private interface Transition {
        Merchant apply(Merchant merchant, Clock clock);
    }
}
