package com.finapp.credit;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Scorecard models change only forward, and only under four eyes (`P10-TSK-011`; ADR-0086 section 3;
 * {@code INV-CRD-05}, {@code INV-HIST-04}, {@code INV-AUD-04}): a holder of {@code CREDIT_POLICY_ADMINISTER} proposes a
 * whole new version - its bands born with it and frozen; a different holder activates it, retiring its predecessor in
 * the SAME transaction, so a family has at most one {@code ACTIVE} version at every statement and its effective periods
 * meet; or anyone rejects it, the proposer included (a withdrawal scores nothing). The corridor policy's shape
 * ({@code crossborder.CorridorPolicyAdministration}), plus a lock of credit's own.
 *
 * <p><strong>Ten proposers, one proposal.</strong> A proposal takes the family's advisory lock (namespace 10) first and
 * then looks for a pending version - so the losers wait and answer {@link ProposalPending} without touching the
 * table; the partial unique over {@code PROPOSED} is the backstop. <strong>Ten approvers, one activation.</strong> A
 * decision locks the version row {@code FOR UPDATE} and acts only from {@code PROPOSED}; the others, once the lock is
 * theirs, find it decided ({@link PolicyStale}). An activation locks the {@code ACTIVE} row second - one order for every
 * writer. The same person's retry of their own decision converges.
 *
 * <p>No seed exemption: {@code credit V006} seeds {@code RETAIL_SCORECARD} v1 as a proposal whose proposer is the
 * reviewed migration ({@code migration:V006}); no model is migration-activated - a person activates it, held to the
 * same four-eyes {@code CHECK} as every version, and every later version is one person's proposal and another's
 * activation.
 */
@RequiredArgsConstructor
public final class ScorecardAdministration {

    /** The event every activation publishes. */
    static final String ACTIVATED_EVENT = "credit.ScorecardModelVersionActivated";

    static final String PRODUCER = "credit";
    static final int EVENT_VERSION = 1;

    /** The audit record's target type for a scorecard model version. */
    static final String TARGET_TYPE = "credit_scorecard_model_version";

    /** A reason's bound. */
    static final int REASON_BOUND = 1000;

    @NonNull private final ScorecardStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(ScorecardModelVersionId id, int version) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for the first, a
     * rejection, and a converged retry), {@code replayed} when the same person's retry converged.
     */
    public record Decided(
            ScorecardModelVersionId id,
            int version,
            ScorecardStatus status,
            Optional<ScorecardModelVersionId> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No scorecard version has this id. */
    public static final class ScorecardNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ScorecardNotFound() {
            super("no scorecard model version has this identifier");
        }
    }

    /** The version is no longer {@code PROPOSED} - decided by somebody else, or differently. */
    public static final class PolicyStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PolicyStale(ScorecardStatus status) {
            super("the scorecard model version is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused() {
            super("a scorecard model version is activated by someone other than its proposer; the proposer may reject it");
        }
    }

    /** A version of the family already awaits a decision. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("a proposed scorecard model version already awaits a decision");
        }
    }

    /** A decision or a proposal carries no reason, or one past its bound. */
    public static final class ReasonRequired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRequired() {
            super("a reason of 1 to " + REASON_BOUND + " characters is required");
        }

        ReasonRequired(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: the table already judged ({@link Scorecard}'s construction), the family locked,
     * numbered {@code max + 1}, written {@code PROPOSED} with every band, its history row and the reasoned audit record.
     *
     * @throws ProposalPending when a version of the family already awaits a decision
     * @throws ReasonRequired for a missing or unbounded reason
     */
    public Proposed propose(
            Connection unitOfWork, ScorecardFamily family, Scorecard scorecard, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(scorecard, "scorecard");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        store.lockFamily(unitOfWork, family);
        if (store.proposalPending(unitOfWork, family)) {
            throw new ProposalPending();
        }
        int version = store.maxVersion(unitOfWork, family) + 1;
        ScorecardModelVersionId id = ScorecardModelVersionId.next(ids);
        store.insertProposal(unitOfWork, id, family, version, scorecard, actor.id(), reasoned);
        store.appendEvent(unitOfWork, ids.next(), id, Optional.empty(), ScorecardStatus.PROPOSED, actor.id(), reasoned);
        audit(unitOfWork, actor, CreditAuditAction.SCORECARD_VERSION_PROPOSED, id, reasoned,
                "family=" + family.name() + ", version=" + version + ", attributes=" + scorecard.attributes().size(),
                correlation);
        return new Proposed(id, version);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Activates a pending version - a different person's act. Under the version's row lock, then the {@code ACTIVE}
     * row's, the predecessor (if any) is retired FIRST and the proposal moves {@code PROPOSED -> ACTIVE} naming its
     * approver; the effective period is the database's - the predecessor's end and the successor's start one instant,
     * {@code GREATEST(transaction_timestamp(), the predecessor's start + 1 us)}, so a database clock that steps back
     * neither refuses the activation nor overlaps two periods ({@code credit V014}, `X-TSK-017`). Both history rows, the reasoned audit record and
     * {@code credit.ScorecardModelVersionActivated} commit with it.
     *
     * @throws ScorecardNotFound when no version has this id
     * @throws PolicyStale when the version is no longer {@code PROPOSED}
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws ReasonRequired for a missing or unbounded reason
     */
    public Decided approve(
            Connection unitOfWork, ScorecardModelVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        ScorecardStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(ScorecardNotFound::new);
        if (row.status() == ScorecardStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != ScorecardStatus.PROPOSED) {
            throw new PolicyStale(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        Optional<ScorecardStore.VersionRow> predecessor = store.lockActive(unitOfWork, row.family());
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id())) {
            throw new IllegalStateException(
                    "the locked ACTIVE version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        Instant effectiveFrom = store.decide(unitOfWork, row.id(), ScorecardStatus.ACTIVE, actor.id(), reasoned)
                .orElseThrow(() -> new IllegalStateException(
                        "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed"))
                .orElseThrow(() -> new IllegalStateException("an activation is stamped with its effective start"));
        predecessor.ifPresent(retired -> store.appendEvent(unitOfWork, ids.next(), retired.id(),
                Optional.of(ScorecardStatus.ACTIVE), ScorecardStatus.RETIRED, actor.id(),
                bounded("retired by version " + row.version() + "'s activation: " + reasoned)));
        store.appendEvent(unitOfWork, ids.next(), row.id(), Optional.of(ScorecardStatus.PROPOSED), ScorecardStatus.ACTIVE,
                actor.id(), reasoned);
        audit(unitOfWork, actor, CreditAuditAction.SCORECARD_VERSION_ACTIVATED, row.id(), reasoned,
                "family=" + row.family().name() + ", activated=v" + row.version()
                        + predecessor.map(p -> ", retired=v" + p.version()).orElse(", retired=none"),
                correlation);
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ACTIVATED_EVENT,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        row.id(),
                        TARGET_TYPE,
                        clock.instant(),
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("family", row.family().name())
                        .with("version", Integer.toString(row.version()))
                        .with("effectiveFromEpochMilli", Long.toString(effectiveFrom.toEpochMilli()))
                        .with("predecessorId", predecessor.map(p -> p.id().value().toString()).orElse("none"))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.version(), ScorecardStatus.ACTIVE,
                predecessor.map(ScorecardStore.VersionRow::id), false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act by any holder, the proposer included. Terminal; frees the family for a
     * new proposal - a correction is a rejection and a new proposal. The same person's retry converges.
     */
    public Decided reject(
            Connection unitOfWork, ScorecardModelVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        ScorecardStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(ScorecardNotFound::new);
        if (row.status() == ScorecardStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != ScorecardStatus.PROPOSED) {
            throw new PolicyStale(row.status());
        }
        if (store.decide(unitOfWork, row.id(), ScorecardStatus.REJECTED, actor.id(), reasoned).isEmpty()) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, ids.next(), row.id(), Optional.of(ScorecardStatus.PROPOSED),
                ScorecardStatus.REJECTED, actor.id(), reasoned);
        audit(unitOfWork, actor, CreditAuditAction.SCORECARD_VERSION_REJECTED, row.id(), reasoned,
                "family=" + row.family().name() + ", rejected=v" + row.version() + ", byProposer="
                        + row.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(row.id(), row.version(), ScorecardStatus.REJECTED, Optional.empty(), false);
    }

    // ------------------------------------------------------------------ plumbing

    private static String reasoned(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > REASON_BOUND) {
            throw new ReasonRequired();
        }
        // Never a card-number or bank-account shape (the Phase 10 to 11 transition; credit V016 beneath).
        if (CreditReasons.holdsInstrument(reason)) {
            throw new ReasonRequired("a reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }

    private static String bounded(String reason) {
        return reason.length() <= REASON_BOUND ? reason : reason.substring(0, REASON_BOUND);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            CreditAuditAction action,
            ScorecardModelVersionId id,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
