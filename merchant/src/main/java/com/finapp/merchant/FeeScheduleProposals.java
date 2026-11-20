package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.io.Serial;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A fee schedule version and a merchant's fee assignment by four eyes (`P17-TSK-123`; ADR-0154's shape and
 * {@link BnplFeeAssignments}'; finapp-admin-panel ADR-C135 decision 3, ADR-0125 decision 4: a price and a merchant's
 * pricing are financial configuration). Each door's write becomes a proposal here - judged first by the effect's own rules
 * on an unlocked read ({@link FeeSchedules#judgeVersion}, {@link FeeSchedules#wouldAssign}), so a request that would be
 * refused is refused at once, and an assignment that changes nothing converges with nothing proposed. Its approval performs
 * the effect <strong>unchanged</strong> under the approver - {@link FeeSchedules#addVersion} (the number minted by V004's
 * index, effective forward judged again: {@code INV-MER-03}) or {@link FeeSchedules#assign} (the merchant
 * {@code FOR UPDATE}) - with the decision in the same transaction; a rejection changes nothing.
 *
 * <p><strong>Serialization</strong> (ADR-0154 decision 1): the decider locks the proposal, then reaches the schedule or the
 * merchant through the existing effect; a proposer takes no lock (V022's partial unique indexes admit one open proposal per
 * schedule and per merchant), so proposal → merchant closes no cycle. Two approvers, or an approval racing a rejection:
 * one winner. <strong>No reservation, no expiry</strong> (decision 6): refused at approval - a named start now past, a
 * currency now mismatched - nothing is written and the proposal stays open, for a rejection.
 */
@RequiredArgsConstructor
public final class FeeScheduleProposals {

    static final String VERSION_TARGET_TYPE = FeeSchedules.SCHEDULE_TARGET_TYPE;
    static final String ASSIGNMENT_TARGET_TYPE = FeeSchedules.ASSIGNMENT_TARGET_TYPE;

    @NonNull private final FeeProposalStore<Connection> proposals;
    @NonNull private final FeeSchedules schedules;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The assignment door's answer: a proposal, or nothing to propose because the pointer is there already. */
    public record ProposedAssignment(Optional<FeeAssignmentProposal> proposal) {
        public ProposedAssignment {
            Objects.requireNonNull(proposal, "proposal");
        }
    }

    /**
     * A reason the four-eyes acts do not admit - missing, blank, past {@link AuditRecord#MAX_REASON_LENGTH} or holding a
     * card-number or bank-account shape (the reason columns' {@code RESTRICTED-PII} ceiling is a person's prose, never an
     * instrument). An {@link IllegalArgumentException}; the door answers it {@code 422}. Its message never repeats the reason
     * ({@code INV-AUD-02}).
     */
    public static final class ReasonRefused extends IllegalArgumentException {
        @Serial private static final long serialVersionUID = 1L;

        ReasonRefused(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ the version

    /**
     * Proposes {@code terms} as the next version of {@code scheduleId}, by {@code actor}.
     *
     * @throws UnknownFeeScheduleException no schedule answers to the identifier
     * @throws BackdatedFeeScheduleVersionException a named start is already past
     * @throws FeeCurrencyMismatchException the fixed part is not in the schedule's currency
     * @throws FeeProposalRefused {@code ALREADY_PROPOSED}: one is open for the schedule
     */
    public FeeScheduleVersionProposal proposeVersion(Connection unitOfWork, FeeScheduleId scheduleId,
            FeeSchedules.NewVersion terms, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(terms, "terms must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        reasoned(terms.reason());
        schedules.judgeVersion(unitOfWork, scheduleId, terms);
        FeeScheduleVersionProposal proposed = FeeScheduleVersionProposal.propose(ids, clock, scheduleId, terms, actor,
                correlation.value());
        proposals.insertVersion(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                MerchantAuditAction.FEE_SCHEDULE_VERSION_PROPOSED, VERSION_TARGET_TYPE, scheduleId.value().toString(),
                Optional.of(terms.reason()), AuditOutcome.SUCCEEDED, correlation, Optional.of("proposal="
                        // The fixed part as its minor units and currency, never Money's string form (MoneyReachesNoTextSinkTest's
                        // residue does not grow).
                        + proposed.id().value() + ", rate=" + proposed.rate() + ", fixedMinor="
                        + proposed.fixed().minorUnits() + ", currency=" + proposed.fixed().currency().code()
                        + ", rounding=" + proposed.roundingPolicy().policyName() + ", refundFee="
                        + proposed.refundFeePolicy() + ", effectiveFrom="
                        + proposed.effectiveFrom().map(Object::toString).orElse("at-approval"))));
        return proposed;
    }

    /**
     * Approves the version proposal by {@code actor}: locked, judged decidable, {@link FeeSchedules#addVersion} performed
     * under the approver, the decision - naming the version minted - written beside it. Empty for an unknown proposal.
     *
     * @throws FeeProposalRefused decided already, or by its proposer
     * @throws BackdatedFeeScheduleVersionException the named start is past now: nothing written, the proposal open
     */
    public Optional<FeeScheduleVersionProposal> approveVersion(Connection unitOfWork, FeeScheduleVersionProposal.Id id,
            String reason, Actor actor, CorrelationId correlation) {
        requireArguments(unitOfWork, id, actor, correlation);
        String reasoned = reasoned(reason);
        Optional<FeeScheduleVersionProposal> locked = proposals.lockVersion(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        FeeScheduleVersionProposal proposal = locked.get();
        proposal.requireDecidableBy(FeeProposalStatus.APPROVED, actor);
        FeeScheduleVersion minted = schedules.addVersion(unitOfWork, proposal.feeScheduleId(), proposal.terms());
        FeeScheduleVersionProposal decided = proposal.approve(actor, reasoned, clock, correlation.value(), minted.id());
        if (!proposals.decideVersion(unitOfWork, decided)) {
            throw movedUnderItsLock();
        }
        decidedVersion(unitOfWork, decided, MerchantAuditAction.FEE_SCHEDULE_VERSION_APPROVED, reasoned, actor,
                correlation);
        return Optional.of(decided);
    }

    /**
     * Rejects the version proposal by {@code actor}: nothing minted. Empty for an unknown proposal.
     *
     * @throws FeeProposalRefused decided already, or by its proposer
     */
    public Optional<FeeScheduleVersionProposal> rejectVersion(Connection unitOfWork, FeeScheduleVersionProposal.Id id,
            String reason, Actor actor, CorrelationId correlation) {
        requireArguments(unitOfWork, id, actor, correlation);
        String reasoned = reasoned(reason);
        Optional<FeeScheduleVersionProposal> locked = proposals.lockVersion(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        FeeScheduleVersionProposal decided = locked.get().reject(actor, reasoned, clock, correlation.value());
        if (!proposals.decideVersion(unitOfWork, decided)) {
            throw movedUnderItsLock();
        }
        decidedVersion(unitOfWork, decided, MerchantAuditAction.FEE_SCHEDULE_VERSION_REJECTED, reasoned, actor,
                correlation);
        return Optional.of(decided);
    }

    // ------------------------------------------------------------------ the assignment

    /**
     * Proposes pointing the merchant at {@code scheduleId}, by {@code actor} - or converges, proposing nothing, when the
     * pointer is there already.
     *
     * @throws UnknownMerchantException no merchant answers to the identifier
     * @throws UnknownFeeScheduleException no schedule does
     * @throws FeeCurrencyMismatchException the schedule does not price the merchant's settlement currency
     * @throws FeeProposalRefused {@code ALREADY_PROPOSED}: one is open for the merchant
     */
    public ProposedAssignment proposeAssignment(Connection unitOfWork, MerchantId merchantId, FeeScheduleId scheduleId,
            String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = reasoned(reason);
        if (!schedules.wouldAssign(unitOfWork, merchantId, scheduleId)) {
            return new ProposedAssignment(Optional.empty());
        }
        FeeAssignmentProposal proposed = FeeAssignmentProposal.propose(ids, clock, merchantId, scheduleId, reasoned, actor,
                correlation.value());
        proposals.insertAssignment(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                MerchantAuditAction.FEE_ASSIGNMENT_PROPOSED, ASSIGNMENT_TARGET_TYPE, merchantId.value().toString(),
                Optional.of(reasoned), AuditOutcome.SUCCEEDED, correlation, Optional.of("proposal="
                        + proposed.id().value() + ", to=" + scheduleId)));
        return new ProposedAssignment(Optional.of(proposed));
    }

    /**
     * Approves the assignment proposal by {@code actor}: locked, judged decidable, {@link FeeSchedules#assign} performed
     * under the approver, the decision written beside it. Empty for an unknown proposal.
     *
     * @throws FeeProposalRefused decided already, or by its proposer
     */
    public Optional<FeeAssignmentProposal> approveAssignment(Connection unitOfWork, FeeAssignmentProposal.Id id,
            String reason, Actor actor, CorrelationId correlation) {
        requireArguments(unitOfWork, id, actor, correlation);
        String reasoned = reasoned(reason);
        Optional<FeeAssignmentProposal> locked = proposals.lockAssignment(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        FeeAssignmentProposal proposal = locked.get();
        proposal.requireDecidableBy(FeeProposalStatus.APPROVED, actor);
        schedules.assign(unitOfWork, proposal.merchantId(), proposal.feeScheduleId(), proposal.proposalReason());
        FeeAssignmentProposal decided = proposal.decide(FeeProposalStatus.APPROVED, actor, reasoned, clock,
                correlation.value());
        decidedAssignment(unitOfWork, decided, MerchantAuditAction.FEE_ASSIGNMENT_APPROVED, reasoned, actor, correlation);
        return Optional.of(decided);
    }

    /**
     * Rejects the assignment proposal by {@code actor}: nothing changes. Empty for an unknown proposal.
     *
     * @throws FeeProposalRefused decided already, or by its proposer
     */
    public Optional<FeeAssignmentProposal> rejectAssignment(Connection unitOfWork, FeeAssignmentProposal.Id id,
            String reason, Actor actor, CorrelationId correlation) {
        requireArguments(unitOfWork, id, actor, correlation);
        String reasoned = reasoned(reason);
        Optional<FeeAssignmentProposal> locked = proposals.lockAssignment(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        FeeAssignmentProposal decided = locked.get().decide(FeeProposalStatus.REJECTED, actor, reasoned, clock,
                correlation.value());
        decidedAssignment(unitOfWork, decided, MerchantAuditAction.FEE_ASSIGNMENT_REJECTED, reasoned, actor, correlation);
        return Optional.of(decided);
    }

    // ------------------------------------------------------------------ the approver's reads

    /** Up to {@code limit} proposals of {@code kind} awaiting a second person, oldest first, after {@code after}. */
    public List<FeeProposalStore.Pending> pending(Connection unitOfWork, FeeProposalStore.Kind kind,
            Optional<FeeProposalStore.PendingCursor> after, int limit) {
        return proposals.pending(unitOfWork, kind, after, limit);
    }

    /** The proposal of any kind with this identifier; empty when none is. */
    public Optional<FeeProposalStore.Found> find(Connection unitOfWork, UUID id) {
        return proposals.find(unitOfWork, id);
    }

    // ------------------------------------------------------------------ plumbing

    /** The reason, admitted: 1 to {@link AuditRecord#MAX_REASON_LENGTH} characters holding no instrument shape. */
    static String reasoned(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ReasonRefused("a fee proposal and its decision are reasoned");
        }
        if (reason.length() > AuditRecord.MAX_REASON_LENGTH) {
            throw new ReasonRefused("a reason is at most " + AuditRecord.MAX_REASON_LENGTH + " characters");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw new ReasonRefused("a reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }

    private static void requireArguments(Connection unitOfWork, Object id, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
    }

    private static MerchantStorageException movedUnderItsLock() {
        return new MerchantStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
    }

    private void decidedVersion(Connection unitOfWork, FeeScheduleVersionProposal decided, MerchantAuditAction action,
            String reasoned, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                action, VERSION_TARGET_TYPE, decided.feeScheduleId().value().toString(), Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("proposal=" + decided.id().value()
                        + decided.mintedVersion().map(version -> ", version=" + version.value()).orElse("")
                        + ", proposedBy=" + decided.proposedBy())));
    }

    private void decidedAssignment(Connection unitOfWork, FeeAssignmentProposal decided, MerchantAuditAction action,
            String reasoned, Actor actor, CorrelationId correlation) {
        if (!proposals.decideAssignment(unitOfWork, decided)) {
            throw movedUnderItsLock();
        }
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                action, ASSIGNMENT_TARGET_TYPE, decided.merchantId().value().toString(), Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("proposal=" + decided.id().value() + ", to="
                        + decided.feeScheduleId() + ", proposedBy=" + decided.proposedBy())));
    }
}
