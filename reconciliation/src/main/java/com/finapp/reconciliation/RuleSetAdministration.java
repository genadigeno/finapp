package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Matching policy changes only forward, and only under four eyes (`P8-TSK-022`, ADR-0068 §8;
 * {@code INV-HIST-04}, {@code INV-AUD-04}, {@code INV-REC-08}): a holder of
 * {@code RECONCILIATION_ADMINISTER} proposes a whole new version, frozen from {@code PROPOSED};
 * a different holder activates it — retiring its predecessor in the SAME transaction, so a
 * source always has exactly one {@code ACTIVE} version — or anyone rejects it, the proposer
 * included (withdrawing a proposal changes no policy). Nothing already decided changes: every
 * run, decision, break and expectation pins the version that decided it.
 *
 * <p>Each command runs in the caller's unit of work, which owns the transaction. The decision
 * commands lock the version row first and, for an activation, the source's {@code ACTIVE} row
 * second — one order for every writer. `V012` holds the same rules for any writer beneath:
 * the machine trigger, the four-eyes {@code CHECK}, {@code rule_set_one_active} and
 * {@code rule_set_one_proposed}.
 *
 * <p>Approval and rejection carry no key: the version's one-way machine is the idempotency
 * ({@code INV-IDEM-01} through state) — the same person's retry converges on what they already
 * decided and writes nothing, anyone else gets {@link RuleSetNotPending}.
 */
@RequiredArgsConstructor
public final class RuleSetAdministration {

    /** {@code rule_set_event_reason_bounded}, and the audit record's own bound. */
    public static final int MAX_REASON_LENGTH = RuleSetProposal.MAX_REASON_LENGTH;

    /** The audit record's target type for a rule set version. */
    static final String TARGET_TYPE = "reconciliation_rule_set";

    @NonNull private final RuleSetStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    /** The kinds each source settles: a first version must date every one (the Phase 9 -> 10 transition). */
    @NonNull private final SettledExpectationKinds settledKinds;

    // ------------------------------------------------------------------ outcomes

    /** The proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(UUID ruleSetId, int version) {}

    /**
     * A decision's outcome; {@code retiredRuleSetId} names the predecessor an activation retired
     * (empty for a rejection, and for a converged retry, which wrote nothing), {@code replayed}
     * when the same person's retry converged.
     */
    public record Decided(
            UUID ruleSetId,
            int version,
            RuleSetStatus status,
            Optional<UUID> retiredRuleSetId,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No rule set version has this id. */
    public static final class RuleSetNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RuleSetNotFound() {
            super("no rule set version has this identifier");
        }
    }

    /** The version was already decided — by somebody else, or differently — or retired. */
    public static final class RuleSetNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RuleSetNotPending(RuleSetStatus status) {
            super("the rule set version is no longer awaiting a decision: it is "
                    + status.name());
        }
    }

    /** The proposer tried to activate their own version (INV-AUD-04). */
    public static final class RuleSetActivationBySameActor extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RuleSetActivationBySameActor() {
            super("a rule set version is activated by someone other than its proposer; the"
                    + " proposer may reject it to withdraw it");
        }
    }

    /**
     * A proposal already awaits a decision for the source — one per source (`V012`'s
     * {@code rule_set_one_proposed}), or a racing proposal took the version number.
     */
    public static final class RuleSetProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RuleSetProposalPending(Throwable cause) {
            super("a proposed rule set version already awaits a decision for this source",
                    cause);
        }
    }

    /** The proposal, or a decision's reason, is not well formed; the message names the defect. */
    public static final class RuleSetInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RuleSetInvalid(String defect) {
            super(defect);
        }
    }

    /**
     * A tolerance on anything but a fee's pinned terms or a date (INV-REC-08): an amount
     * tolerance would absorb value without an entry, so it is unrepresentable.
     */
    public static final class ToleranceNotPermitted extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ToleranceNotPermitted() {
            super("a tolerance may compare a fee against its pinned terms or a date, never an"
                    + " amount (INV-REC-08)");
        }
    }

    // ------------------------------------------------------------------ the reason screen

    /**
     * A proposal's or a decision's reason, judged before anything is stored: 1..1000
     * characters, and never a card-number or bank-account shape — the reason is CONFIDENTIAL
     * prose that reaches the version, its history and the audit record ({@code INV-AUD-02}).
     */
    public static void refuseReason(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new RuleSetInvalid(
                    "a rule set decision is reasoned: reason must be 1.." + MAX_REASON_LENGTH
                            + " characters");
        }
        if (NoteScreen.screenShapes(reason).isPresent()) {
            throw new RuleSetInvalid(
                    "reason must not hold a card-number or bank-account shape");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version for the proposal's source: judged
     * ({@link RuleSetProposal#validate()}), checked to date every kind its {@code ACTIVE}
     * predecessor dates (an opener reads {@code lagDaysFor(kind)} and would fail), numbered
     * {@code max + 1}, and written {@code PROPOSED} with every member, its history row and the
     * reasoned audit record. Nothing is activated and nothing already decided changes.
     *
     * <p><strong>The first version</strong> (`P9-TSK-011`, PHASE_9_PLAN.md section 12.9.2): a
     * source with no {@code ACTIVE} version - a source declared after Phase 8, whose version 1 no
     * migration seeds (D26) - is admitted a proposal with nothing to cover; it is still one pending
     * proposal per source ({@code rule_set_one_proposed}) and still activated by a different person.
     *
     * @throws ToleranceNotPermitted for an amount tolerance ({@code INV-REC-08})
     * @throws RuleSetInvalid for a malformed version, or a lag kind the predecessor dates and the
     *     proposal does not
     * @throws RuleSetProposalPending when a proposal already awaits a decision for the source
     *     (the caller's transaction is then aborted and must roll back)
     */
    public Proposed propose(
            Connection unitOfWork,
            RuleSetProposal proposal,
            Actor actor,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        proposal.validate();
        // Advisory and lock-free: a concurrent activation shows either predecessor, and the
        // approval re-judges coverage against the predecessor it locks.
        Optional<RuleSetStore.VersionRow> active = store.active(unitOfWork, proposal.sourceId());
        if (active.isPresent()) {
            refuseUncovered(proposal.lagDays().keySet(), store.lagKinds(unitOfWork, active.get().id()));
        }
        // No ACTIVE version: the source's first version, with no predecessor to cover - but every kind the source's
        // evidence settles must be dated, or each opener of a missing kind rolls back the money movement that opened it
        // (the Phase 9 -> 10 transition).
        if (active.isEmpty()) {
            Set<ExpectationKind> missing = EnumSet.noneOf(ExpectationKind.class);
            missing.addAll(settledKinds.of(unitOfWork, proposal.sourceId()));
            missing.removeAll(proposal.lagDays().keySet());
            if (!missing.isEmpty()) {
                throw new RuleSetInvalid("the source's first version holds no lag for " + missing
                        + ", which its evidence settles: every kind an opener dates against it keeps a lag");
            }
        }

        int version = store.maxVersion(unitOfWork, proposal.sourceId()) + 1;
        UUID ruleSetId = ids.next();
        store.insertProposal(
                unitOfWork,
                ruleSetId,
                version,
                proposal,
                LocalDate.ofInstant(now, ZoneOffset.UTC),
                actor,
                now,
                correlation);
        store.appendEvent(
                unitOfWork, ruleSetId, Optional.empty(), RuleSetStatus.PROPOSED, actor,
                proposal.reason(), now, correlation);
        audit(unitOfWork, actor, now, ReconciliationAuditAction.RULE_SET_PROPOSED, ruleSetId,
                proposal.reason(), "source=" + proposal.sourceId() + ", version=" + version,
                correlation);
        return new Proposed(ruleSetId, version);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Activates a pending version — a different person's act. Under the version's row lock and
     * then its source's {@code ACTIVE} row lock, the predecessor moves {@code ACTIVE → RETIRED}
     * FIRST ({@code rule_set_one_active} admits one active row at every statement), then the
     * proposal {@code PROPOSED → ACTIVE} naming its approver; both history rows and the reasoned
     * audit record follow, all in the caller's transaction. The same person's retry converges.
     * A source's FIRST version (`P9-TSK-011`) has no predecessor: it activates retiring nothing,
     * under the same row lock and the same four-eyes rule (and {@code V012}'s {@code CHECK}).
     *
     * @throws RuleSetNotFound when no version has this id
     * @throws RuleSetNotPending when the version is no longer {@code PROPOSED}
     * @throws RuleSetActivationBySameActor when the approver proposed it (INV-AUD-04)
     * @throws RuleSetInvalid for a malformed reason, or a proposal that no longer dates every
     *     kind its locked predecessor dates
     */
    public Decided approve(
            Connection unitOfWork,
            UUID ruleSetId,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        RuleSetStore.VersionRow row =
                store.lock(unitOfWork, ruleSetId).orElseThrow(RuleSetNotFound::new);
        if (row.status() == RuleSetStatus.ACTIVE
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != RuleSetStatus.PROPOSED) {
            throw new RuleSetNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new RuleSetActivationBySameActor();
        }
        refuseReason(reason);
        // The source's ACTIVE version, locked - or none, for its first version (P9-TSK-011).
        Optional<RuleSetStore.VersionRow> locked = store.lockActive(unitOfWork, row.sourceId());
        if (locked.isPresent()) {
            RuleSetStore.VersionRow predecessor = locked.get();
            // The coverage the proposal was judged by may have been read before a racing
            // activation committed: re-judged here against the predecessor this transaction holds.
            refuseUncovered(
                    store.lagKinds(unitOfWork, row.id()),
                    store.lagKinds(unitOfWork, predecessor.id()));
            if (!store.move(unitOfWork, predecessor.id(), RuleSetStatus.ACTIVE, RuleSetStatus.RETIRED,
                    Optional.empty(), now)) {
                throw new IllegalStateException(
                        "the locked ACTIVE version moved under its own row lock: the FOR UPDATE"
                                + " protocol was bypassed");
            }
        }
        if (!store.move(unitOfWork, row.id(), RuleSetStatus.PROPOSED, RuleSetStatus.ACTIVE,
                Optional.of(actor), now)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol"
                            + " was bypassed");
        }
        if (locked.isPresent()) {
            store.appendEvent(
                    unitOfWork, locked.get().id(), Optional.of(RuleSetStatus.ACTIVE),
                    RuleSetStatus.RETIRED, actor, retirementReason(row.version(), reason), now,
                    correlation);
        }
        store.appendEvent(
                unitOfWork, row.id(), Optional.of(RuleSetStatus.PROPOSED), RuleSetStatus.ACTIVE,
                actor, reason, now, correlation);
        audit(unitOfWork, actor, now, ReconciliationAuditAction.RULE_SET_ACTIVATED, row.id(),
                reason,
                "source=" + row.sourceId() + ", activated=v" + row.version()
                        + locked.map(predecessor -> ", retired=v" + predecessor.version())
                                .orElse(", the source's first version"),
                correlation);
        return new Decided(
                row.id(), row.version(), RuleSetStatus.ACTIVE, locked.map(RuleSetStore.VersionRow::id),
                false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version — a reasoned act by any holder, the proposer included (a
     * withdrawal changes no policy, so `V012`'s four-eyes rule does not bind it). The version
     * moves {@code PROPOSED → REJECTED}, terminal, freeing the source for a new proposal. The
     * same person's retry converges.
     *
     * @throws RuleSetNotFound when no version has this id
     * @throws RuleSetNotPending when the version is no longer {@code PROPOSED}
     * @throws RuleSetInvalid for a malformed reason
     */
    public Decided reject(
            Connection unitOfWork,
            UUID ruleSetId,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        RuleSetStore.VersionRow row =
                store.lock(unitOfWork, ruleSetId).orElseThrow(RuleSetNotFound::new);
        if (row.status() == RuleSetStatus.REJECTED
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != RuleSetStatus.PROPOSED) {
            throw new RuleSetNotPending(row.status());
        }
        refuseReason(reason);
        if (!store.move(unitOfWork, row.id(), RuleSetStatus.PROPOSED, RuleSetStatus.REJECTED,
                Optional.of(actor), now)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol"
                            + " was bypassed");
        }
        store.appendEvent(
                unitOfWork, row.id(), Optional.of(RuleSetStatus.PROPOSED),
                RuleSetStatus.REJECTED, actor, reason, now, correlation);
        audit(unitOfWork, actor, now, ReconciliationAuditAction.RULE_SET_REJECTED, row.id(),
                reason,
                "source=" + row.sourceId() + ", rejected=v" + row.version()
                        + ", byProposer=" + row.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(
                row.id(), row.version(), RuleSetStatus.REJECTED, Optional.empty(), false);
    }

    // ------------------------------------------------------------------ read

    /** The source's versions, newest first, at most {@code limit}, each with its content. */
    public List<RuleSetStore.VersionView> versions(
            Connection unitOfWork, UUID sourceId, int limit) {
        return store.versions(unitOfWork, sourceId, limit);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Refuses a successor that dates fewer kinds than its predecessor: an opener of a kind the
     * successor dropped would find no lag ({@code RuleSets.ActiveRuleSet#lagDaysFor}) and fail
     * every completion of that kind.
     */
    private static void refuseUncovered(
            Set<ExpectationKind> successor, Set<ExpectationKind> predecessor) {
        Set<ExpectationKind> missing = EnumSet.noneOf(ExpectationKind.class);
        missing.addAll(predecessor);
        missing.removeAll(successor);
        if (!missing.isEmpty()) {
            throw new RuleSetInvalid(
                    "the proposal holds no lag for " + missing + ", which the source's ACTIVE"
                            + " version dates: every kind the source's evidence settles keeps"
                            + " a lag");
        }
    }

    /** The predecessor's history reason, bounded as {@code rule_set_event} requires. */
    static String retirementReason(int activatedVersion, String reason) {
        String full = "retired by version " + activatedVersion + "'s activation: " + reason;
        if (full.length() <= MAX_REASON_LENGTH) {
            return full;
        }
        int end = MAX_REASON_LENGTH;
        if (Character.isHighSurrogate(full.charAt(end - 1))) {
            end--;
        }
        return full.substring(0, end);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            ReconciliationAuditAction action,
            UUID ruleSetId,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        TARGET_TYPE,
                        ruleSetId.toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
