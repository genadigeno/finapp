package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.OwnedAdjustmentCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The person's resolution machine (`P8-TSK-015`, ADR-0071): propose, approve, reject and
 * withdraw — each ONE transaction, each in the Phase 8 lock order (the break's source's
 * namespace-4 advisory FIRST, then the break rows sorted by id, then the resolution row, then
 * the subject rows, then a transfer target {@code FOR SHARE}, then — inside
 * {@code approveOwned} — the ledger proposal and the projection rows; the posting last).
 *
 * <p><strong>The lines are a template's, never typed</strong> ({@link ResolutionTemplates}):
 * the proposal derives the amount and lines from the subject's current remainder and freezes
 * them with the break's {@code residual_version}; the approval re-derives both under the locks
 * and refuses a change underneath ({@link ResolutionStale}). The ledger half rides the owned
 * door ({@code proposeOwned} / {@code approveOwned} / {@code rejectOwned}) in the same
 * transaction, so the resolution, its ledger proposal and its entry are one-to-one.
 *
 * <p><strong>Two people wherever value is at issue</strong> ({@code INV-REC-03},
 * {@code INV-AUD-04}): refused here first ({@link SelfApprovalRefused}, nothing written),
 * by `V006`'s distinctness {@code CHECK} for any writer, and — for the three posting kinds —
 * by the ledger's `V010` beneath. A zero-value {@code ACKNOWLEDGE} is one person's act, born
 * {@code APPROVED}.
 *
 * <p>Approval, rejection and withdrawal carry no key: the resolution's one-way machine is the
 * idempotency ({@code INV-IDEM-01} through state) — the same person's retry converges on what
 * they already decided, anyone else gets {@link ResolutionNotPending}.
 */
@RequiredArgsConstructor
public final class ResolutionMachine {

    public static final int MAX_NARRATIVE_LENGTH = 1000;

    public static final int MAX_REASON_LENGTH = 1000;

    private static final String UNIQUE_VIOLATION = "23505";

    /** The two purposes a transfer may credit (ADR-0071 §2): an owned wallet or payable. */
    private static final Set<AccountPurpose> TRANSFER_TARGETS =
            EnumSet.of(AccountPurpose.CUSTOMER_WALLET, AccountPurpose.MERCHANT_PAYABLE);

    @NonNull private final ResolutionStore store;
    @NonNull private final BreakCaseStore breaks;
    @NonNull private final Suspense suspense;
    @NonNull private final MatchingStore matching;
    @NonNull private final Resolutions evidence;
    @NonNull private final RuleSets ruleSets;
    @NonNull private final AdjustmentService adjustments;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ inputs and outcomes

    /** A proposal as the person asked for it: no amount, no account but a transfer's target. */
    public record ProposalRequest(
            ResolutionKind kind,
            ResolutionReasonCode reasonCode,
            String narrative,
            Optional<UUID> targetAccountId,
            Optional<UUID> offsetItemId,
            Optional<UUID> chosenExpectationId) {

        public ProposalRequest {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(narrative, "narrative must not be null");
            Objects.requireNonNull(targetAccountId, "targetAccountId must not be null");
            Objects.requireNonNull(offsetItemId, "offsetItemId must not be null");
            Objects.requireNonNull(chosenExpectationId, "chosenExpectationId must not be null");
        }
    }

    /** The proposal's outcome: {@code status} is {@code APPROVED} for a one-person act. */
    public record Proposed(
            UUID resolutionId,
            UUID breakId,
            ResolutionKind kind,
            ResolutionStatus status,
            boolean fourEyes,
            Optional<UUID> adjustmentProposalId,
            Instant proposedAt) {}

    /** A decision's outcome; {@code replayed} when the same person's retry converged. */
    public record Decided(
            UUID resolutionId,
            UUID breakId,
            ResolutionStatus status,
            Optional<UUID> journalEntryId,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No resolution has this id. */
    public static final class ResolutionNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ResolutionNotFound() {
            super("no resolution has this identifier");
        }
    }

    /** The break already carries a live proposal — one per break (ADR-0071 §1). */
    public static final class ResolutionAlreadyProposed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ResolutionAlreadyProposed() {
            super("the break already carries a live resolution proposal");
        }
    }

    /** The resolution was already decided by somebody else, or differently. */
    public static final class ResolutionNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ResolutionNotPending(ResolutionStatus status) {
            super("the resolution is no longer pending: it is " + status.name());
        }
    }

    /** The proposer tried to approve or reject their own proposal (INV-REC-03). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused() {
            super("a resolution's proposer cannot decide it: a second person approves or"
                    + " rejects, and the proposer may withdraw it");
        }
    }

    /** Somebody other than the proposer tried to withdraw the proposal. */
    public static final class NotTheProposer extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotTheProposer() {
            super("only the resolution's proposer withdraws it; another person rejects it");
        }
    }

    /** The subject's remainder or the break's residual moved since the proposal. */
    public static final class ResolutionStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ResolutionStale(String detail) {
            super("the subject changed since the proposal (" + detail + "): the proposer"
                    + " withdraws and re-proposes");
        }
    }

    /** A manual match collided with an allocation already recorded for the pair. */
    public static final class RecordAlreadyMatched extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RecordAlreadyMatched() {
            super("the item is already allocated to the chosen expectation");
        }
    }

    /** The kind is not the break type's, not the person's, or not the subject's side. */
    public static final class ResolutionKindNotAllowed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ResolutionKindNotAllowed(String detail) {
            super(detail);
        }
    }

    /** The reason code is outside the kind's admitted subset (ADR-0071 §5). */
    public static final class ReasonCodeNotAllowed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ReasonCodeNotAllowed(String detail) {
            super(detail);
        }
    }

    /** A transfer target, an offset item or a chosen candidate the template refuses. */
    public static final class ResolutionTargetRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ResolutionTargetRefused(String detail) {
            super(detail);
        }
    }

    /** A gain before the pinned minimum age (database clock, ADR-0070 §4). */
    public static final class GainNotYetEligible extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        GainNotYetEligible() {
            super("the suspense item is younger than the pinned gain_min_age_days");
        }
    }

    /** The request is shaped wrongly — a 422. */
    public static final class ResolutionRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ResolutionRefused(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ the shape screen

    /**
     * The request's shape, judged BEFORE any claim — a refused request stores nothing, not even
     * an idempotency claim: the kind a person may propose, its reason subset, the operands it
     * takes, and the narrative's bounds and screen (CONFIDENTIAL text never holds a card or
     * account shape, `V006`'s {@code CHECK}s at the database rank).
     */
    public static void refuseShape(ProposalRequest request) {
        ResolutionKind kind = request.kind();
        if (kind == ResolutionKind.EVIDENCED) {
            throw new ResolutionKindNotAllowed(
                    "EVIDENCED is the platform's alone: evidence closes a break, never a person");
        }
        if (!ResolutionKind.admittedByV007().contains(kind)) {
            throw new ResolutionKindNotAllowed(
                    kind.name() + " is proposed through its own door (a settlement batch's"
                            + " repudiation)");
        }
        if (!kind.admittedReasonCodes().contains(request.reasonCode())) {
            throw new ReasonCodeNotAllowed(
                    kind.name() + " admits the reason codes " + kind.admittedReasonCodes()
                            + ", not " + request.reasonCode().name());
        }
        operand("targetAccountId", request.targetAccountId(),
                kind == ResolutionKind.TRANSFER_TO_ACCOUNT, kind);
        operand("offsetItemId", request.offsetItemId(),
                kind == ResolutionKind.OFFSET_SUSPENSE, kind);
        operand("chosenExpectationId", request.chosenExpectationId(),
                kind == ResolutionKind.MANUAL_MATCH, kind);
        String narrative = request.narrative();
        if (narrative.isBlank() || narrative.length() > MAX_NARRATIVE_LENGTH) {
            throw new ResolutionRefused(
                    "narrative is required: 1.." + MAX_NARRATIVE_LENGTH + " characters");
        }
        NoteScreen.screenShapes(narrative)
                .ifPresent(
                        refusal -> {
                            throw new ResolutionRefused(
                                    "narrative must not hold a card-number or bank-account"
                                            + " shape (INV-PAY-02, INV-RAIL-03); link evidence"
                                            + " by identifier instead");
                        });
    }

    /** A rejection's reason, judged before any lock. */
    public static void refuseReason(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new ResolutionRefused(
                    "a rejection is reasoned: reason must be 1.." + MAX_REASON_LENGTH
                            + " characters");
        }
        if (NoteScreen.screenShapes(reason).isPresent()) {
            throw new ResolutionRefused(
                    "reason must not hold a card-number or bank-account shape");
        }
    }

    private static void operand(
            String name, Optional<UUID> value, boolean required, ResolutionKind kind) {
        if (required && value.isEmpty()) {
            throw new ResolutionRefused(name + " is required for " + kind.name());
        }
        if (!required && value.isPresent()) {
            throw new ResolutionRefused(name + " does not apply to " + kind.name());
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes — or, for a zero-value {@code ACKNOWLEDGE}, performs — a resolution. The break
     * moves {@code OPEN | INVESTIGATING → RESOLUTION_PROPOSED} (or {@code → RESOLVED} for the
     * one-person act); a posting kind's ledger proposal is recorded through
     * {@code proposeOwned} with the template's lines, both dates the proposal's business date.
     * Nothing posts.
     */
    public Proposed propose(
            Connection unitOfWork,
            UUID breakId,
            ProposalRequest request,
            Actor actor,
            CorrelationId correlation) {
        refuseShape(request);
        ResolutionKind kind = request.kind();
        Optional<UUID> offsetBreak =
                request.offsetItemId()
                        .map(
                                item ->
                                        store.breakOfSuspenseItem(unitOfWork, item)
                                                .orElseThrow(
                                                        () ->
                                                                new ResolutionTargetRefused(
                                                                        "offsetItemId names no"
                                                                                + " suspense"
                                                                                + " item")));
        Map<UUID, BreakCaseStore.BreakRow> locked = lockBreaks(unitOfWork, breakId, offsetBreak);
        BreakCaseStore.BreakRow row = locked.get(breakId);
        if (row.status() == BreakStatus.RESOLVED) {
            throw new BreakCaseFile.BreakTerminal();
        }
        if (row.status() == BreakStatus.RESOLUTION_PROPOSED) {
            throw new ResolutionAlreadyProposed();
        }
        if (!ResolutionTemplates.admittedKinds(row.type(), row.cause()).contains(kind)) {
            throw new ResolutionKindNotAllowed(
                    "a " + row.type().name() + " break (cause " + row.cause().name()
                            + ") admits " + ResolutionTemplates.admittedKinds(row.type(),
                                    row.cause()) + ", not " + kind.name());
        }
        ResolutionTemplates.Holding holding = holding(unitOfWork, row);
        ResolutionTemplates.sideRefusal(kind, holding)
                .ifPresent(
                        refusal -> {
                            throw new ResolutionKindNotAllowed(refusal);
                        });
        if (disposesOfRemainder(kind, holding)
                && remainderSiblings(unitOfWork, holding, breakId).stream()
                        .anyMatch(sibling -> sibling.status() == BreakStatus.RESOLUTION_PROPOSED)) {
            // One live proposal per remainder: a sibling break answering for the same value
            // already carries one, and both cannot dispose of it.
            throw new ResolutionAlreadyProposed();
        }
        UUID ruleSetId = ruleSets.activeFor(unitOfWork, row.sourceId()).id();
        Money amount = ResolutionTemplates.amount(kind, holding, row.valueAtIssue());
        judgeOperands(unitOfWork, kind, request, row, holding, offsetBreak.map(locked::get),
                ruleSetId);

        Instant now = Instant.now(clock);
        LocalDate proposedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        UUID resolutionId = ids.next();
        boolean fourEyes = ResolutionTemplates.fourEyes(kind, amount);
        ResolutionStatus status = fourEyes ? ResolutionStatus.PROPOSED : ResolutionStatus.APPROVED;
        Optional<UUID> proposalId = Optional.empty();
        if (kind.postsAdjustment()) {
            AdjustmentProposalId proposed =
                    adjustments.proposeOwned(
                            unitOfWork,
                            new OwnedAdjustmentCommand(
                                    proposedOn,
                                    proposedOn,
                                    resolutionId.toString(),
                                    ledgerReason(resolutionId, kind, request.reasonCode()),
                                    kind.ledgerReasonCode().orElseThrow(),
                                    lines(unitOfWork, kind, holding,
                                            request.targetAccountId())));
            proposalId = Optional.of(proposed.value());
        }
        try {
            store.insert(
                    unitOfWork,
                    new ResolutionStore.NewResolution(
                            resolutionId,
                            breakId,
                            kind,
                            status,
                            request.reasonCode(),
                            request.narrative(),
                            fourEyes,
                            amount,
                            row.residualVersion(),
                            request.targetAccountId(),
                            request.offsetItemId(),
                            request.chosenExpectationId(),
                            ruleSetId,
                            proposalId,
                            actor,
                            now,
                            correlation));
        } catch (ResolutionStore.OneLiveProposal taken) {
            throw new ResolutionAlreadyProposed();
        }
        store.appendEvent(
                unitOfWork, resolutionId, Optional.empty(), status, actor, Optional.empty(), now,
                correlation);
        String summary =
                "break=" + breakId + ", kind=" + kind.name()
                        + ", reasonCode=" + request.reasonCode().name()
                        + ", fourEyes=" + fourEyes
                        + proposalId.map(id -> ", adjustmentProposal=" + id).orElse("");
        if (status == ResolutionStatus.APPROVED) {
            // The one-person act: a zero-value acknowledgement is one act and one reasoned
            // record (ADR-0071 section 11), the break's OPEN | INVESTIGATING -> RESOLVED edge.
            resolveBreakOrLoud(unitOfWork, breakId, resolutionId, kind, actor, now, correlation);
            audit(unitOfWork, actor, now, ReconciliationAuditAction.RESOLUTION_APPROVED,
                    resolutionId, Optional.of(auditReason(kind, request.reasonCode())),
                    summary + ", status=APPROVED", correlation);
            ReconciliationEvents.breakResolved(
                    outbox, unitOfWork, ids, breakId, resolutionId, kind, request.reasonCode(),
                    Optional.empty(), now, correlation);
        } else {
            if (!store.moveBreak(
                    unitOfWork, breakId, row.status(), BreakStatus.RESOLUTION_PROPOSED, now)) {
                throw new IllegalStateException(
                        "the break moved under its own row lock (ADR-0069 section 11)");
            }
            audit(unitOfWork, actor, now, ReconciliationAuditAction.RESOLUTION_PROPOSED,
                    resolutionId, Optional.of(auditReason(kind, request.reasonCode())),
                    summary, correlation);
        }
        return new Proposed(resolutionId, breakId, kind, status, fourEyes, proposalId, now);
    }

    /** The per-kind operands, judged at proposal (and the ones that can move, at approval). */
    private void judgeOperands(
            Connection unitOfWork,
            ResolutionKind kind,
            ProposalRequest request,
            BreakCaseStore.BreakRow row,
            ResolutionTemplates.Holding holding,
            Optional<BreakCaseStore.BreakRow> offsetBreak,
            UUID ruleSetId) {
        switch (kind) {
            case TRANSFER_TO_ACCOUNT -> {
                LedgerAccount target =
                        accounts.findAllById(
                                        unitOfWork,
                                        List.of(LedgerAccountId.of(
                                                request.targetAccountId().orElseThrow())))
                                .stream()
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new ResolutionTargetRefused(
                                                        "targetAccountId names no ledger"
                                                                + " account"));
                refuseTarget(target, amountOf(holding).currency());
                if (target.status() != LedgerAccountStatus.ACTIVE) {
                    throw new ResolutionTargetRefused(
                            "the transfer's target is " + target.status().name()
                                    + ": it accepts no posting");
                }
            }
            case OFFSET_SUSPENSE ->
                    offsetPartner(unitOfWork, row, holding,
                            request.offsetItemId().orElseThrow(), offsetBreak.orElseThrow());
            case RECOGNISE_GAIN -> {
                ResolutionTemplates.Holding.Parked parked =
                        (ResolutionTemplates.Holding.Parked) holding;
                if (!store.gainEligible(unitOfWork, parked.suspenseItemId(), ruleSetId)) {
                    throw new GainNotYetEligible();
                }
            }
            case MANUAL_MATCH ->
                    manualCandidate(unitOfWork, holding,
                            request.chosenExpectationId().orElseThrow(), false);
            default -> {
                // ACKNOWLEDGE and WRITE_OFF take no operand.
            }
        }
    }

    private static void refuseTarget(LedgerAccount target, CurrencyCode currency) {
        if (!TRANSFER_TARGETS.contains(target.purpose())) {
            throw new ResolutionTargetRefused(
                    "a transfer credits a CUSTOMER_WALLET or a MERCHANT_PAYABLE, not a "
                            + target.purpose().name());
        }
        if (!target.currency().equals(currency)) {
            throw new ResolutionTargetRefused(
                    "the transfer's target is in " + target.currency().code()
                            + ", the subject in " + currency.code() + " (INV-MON-04)");
        }
    }

    /**
     * The offset's partner: another break's open suspense item, on the other side, of the same
     * unreleased amount and currency, whose break carries no live proposal of its own.
     */
    private ResolutionStore.SuspenseHolding offsetPartner(
            Connection unitOfWork,
            BreakCaseStore.BreakRow row,
            ResolutionTemplates.Holding holding,
            UUID offsetItemId,
            BreakCaseStore.BreakRow offsetBreak) {
        ResolutionTemplates.Holding.Parked parked = (ResolutionTemplates.Holding.Parked) holding;
        if (offsetBreak.id().equals(row.id())) {
            throw new ResolutionTargetRefused(
                    "the offset item is owned by this same break: an offset pairs two breaks'"
                            + " items");
        }
        if (offsetBreak.status() == BreakStatus.RESOLVED) {
            throw new ResolutionTargetRefused("the offset item's break is resolved");
        }
        if (offsetBreak.status() == BreakStatus.RESOLUTION_PROPOSED) {
            throw new ResolutionTargetRefused(
                    "the offset item's break carries a live proposal of its own (ADR-0070"
                            + " section 3)");
        }
        ResolutionStore.SuspenseHolding partner =
                store.lockSuspenseItem(unitOfWork, offsetItemId)
                        .orElseThrow(
                                () -> new ResolutionTargetRefused(
                                        "offsetItemId names no suspense item"));
        if (partner.status() == SuspenseItemStatus.RELEASED || partner.unreleasedMinor() == 0) {
            throw new ResolutionTargetRefused("the offset item holds nothing unreleased");
        }
        if (partner.side() == parked.side()) {
            throw new ResolutionTargetRefused(
                    "an offset pairs a CREDIT and a DEBIT item; both are " + parked.side());
        }
        if (!partner.currency().equals(parked.amount().currency().code())
                || partner.scale() != parked.amount().scale()
                || partner.unreleasedMinor() != parked.amount().minorUnits()) {
            throw new ResolutionTargetRefused(
                    "an offset pairs items of EQUAL unreleased amount and currency; a partial"
                            + " offset never happens");
        }
        return partner;
    }

    /**
     * The manual match's candidate: one of the item's stored snapshot candidates, of the
     * item's direction and currency, whose remainder absorbs the WHOLE parked value (a
     * partial unpark would leave a split record — the rematch rule). At approval a failed
     * absorption is a moved subject: {@link ResolutionStale}.
     */
    private MatchEngine.HitFacts manualCandidate(
            Connection unitOfWork,
            ResolutionTemplates.Holding holding,
            UUID chosen,
            boolean atApproval) {
        ResolutionTemplates.Holding.Parked parked = (ResolutionTemplates.Holding.Parked) holding;
        UUID itemId =
                parked.externalItemId()
                        .orElseThrow(
                                () ->
                                        new ResolutionKindNotAllowed(
                                                "MANUAL_MATCH allocates a reported item; this"
                                                        + " suspense item has none"));
        Map<UUID, KeyKind> candidates = store.storedCandidatesOf(unitOfWork, itemId);
        if (!candidates.containsKey(chosen)) {
            throw new ResolutionTargetRefused(
                    "chosenExpectationId is not a candidate in the item's stored decision"
                            + " snapshot (ADR-0068 section 9)");
        }
        MatchEngine.HitFacts hit =
                matching.lockExpectations(unitOfWork, List.of(chosen), candidates).get(0);
        ExpectationDirection direction =
                store.itemDirection(unitOfWork, itemId)
                        .orElseThrow(() -> new IllegalStateException(
                                "a parked item exists (V004's foreign key): " + itemId));
        if (hit.direction() != direction
                || !hit.amount().currency().equals(parked.amount().currency())) {
            throw new ResolutionTargetRefused(
                    "the chosen candidate's direction or currency differs from the item's");
        }
        if (hit.remainderMinor() < parked.amount().minorUnits()) {
            if (atApproval) {
                throw new ResolutionStale("the chosen candidate's remainder moved");
            }
            throw new ResolutionTargetRefused(
                    "the chosen candidate's remainder cannot absorb the whole parked value; a"
                            + " partial unpark never happens");
        }
        return hit;
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves a pending proposal: the second person's act. Re-derives the template under the
     * locks and refuses a moved subject ({@link ResolutionStale}); for a posting kind,
     * {@code approveOwned} posts the entry; then the closing effects (the suspense release and
     * the item's {@code RESOLVED}, or the expectation's {@code RESOLVED_BY_ADJUSTMENT}, or the
     * offset's two releases, or the manual match's allocation and unpark), the resolution's
     * {@code APPROVED}, the break's {@code RESOLVED}, the audit record and
     * {@code reconciliation.BreakResolved} — all one transaction.
     */
    public Decided approve(
            Connection unitOfWork, UUID resolutionId, Actor actor, CorrelationId correlation) {
        ResolutionStore.ResolutionRow row = lockedResolution(unitOfWork, resolutionId);
        if (row.status() == ResolutionStatus.APPROVED
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.breakId(), row.status(), row.journalEntryId(), true);
        }
        if (row.status() != ResolutionStatus.PROPOSED) {
            throw new ResolutionNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        BreakCaseStore.BreakRow breakRow =
                breaks.lockForUpdate(unitOfWork, row.breakId()).orElseThrow();
        if (breakRow.status() != BreakStatus.RESOLUTION_PROPOSED) {
            throw new IllegalStateException(
                    "a PROPOSED resolution's break stands RESOLUTION_PROPOSED (ADR-0071"
                            + " section 1)");
        }
        ResolutionKind kind = row.kind();
        ResolutionTemplates.Holding holding = holding(unitOfWork, breakRow);
        Optional<String> sideRefusal = ResolutionTemplates.sideRefusal(kind, holding);
        long derived =
                sideRefusal.isPresent()
                        ? -1
                        : ResolutionTemplates.amount(kind, holding, breakRow.valueAtIssue())
                                .minorUnits();
        if (breakRow.residualVersion() != row.residualVersion()) {
            throw new ResolutionStale(
                    "residual_version " + row.residualVersion() + " -> "
                            + breakRow.residualVersion());
        }
        if (derived != row.proposedAmountMinor()) {
            throw new ResolutionStale("the remainder the lines were derived from moved");
        }

        List<ResolutionStore.RemainderSibling> siblings =
                disposesOfRemainder(kind, holding)
                        ? remainderSiblings(unitOfWork, holding, row.breakId())
                        : List.of();
        if (siblings.stream()
                .anyMatch(sibling -> sibling.status() == BreakStatus.RESOLUTION_PROPOSED)) {
            throw new IllegalStateException(
                    "a sibling of a proposed remainder carries its own proposal: the proposal's"
                            + " sibling check was bypassed");
        }

        Instant now = Instant.now(clock);
        LocalDate proposedOn = LocalDate.ofInstant(row.proposedAt(), ZoneOffset.UTC);
        Optional<ResolutionStore.SuspenseHolding> partner = Optional.empty();
        Optional<MatchEngine.HitFacts> candidate = Optional.empty();
        switch (kind) {
            case TRANSFER_TO_ACCOUNT -> {
                // The target FOR SHARE before any projection row (ADR-0071 section 9, step 5):
                // a close waits for this approval or commits first and the posting refuses.
                LedgerAccount target =
                        accounts.lockForShare(
                                        unitOfWork,
                                        LedgerAccountId.of(row.targetAccountId().orElseThrow()))
                                .orElseThrow(() -> new IllegalStateException(
                                        "a ledger account is never deleted"));
                refuseTarget(target, CurrencyCode.of(row.currency()));
            }
            case OFFSET_SUSPENSE -> {
                UUID offsetItemId = row.offsetItemId().orElseThrow();
                UUID offsetBreakId = store.breakOfSuspenseItem(unitOfWork, offsetItemId)
                        .orElseThrow();
                BreakCaseStore.BreakRow offsetBreak =
                        breaks.lockForUpdate(unitOfWork, offsetBreakId).orElseThrow();
                try {
                    partner = Optional.of(
                            offsetPartner(unitOfWork, breakRow, holding, offsetItemId,
                                    offsetBreak));
                } catch (ResolutionTargetRefused moved) {
                    throw new ResolutionStale("the offset item or its break moved");
                }
            }
            case RECOGNISE_GAIN -> {
                ResolutionTemplates.Holding.Parked parked =
                        (ResolutionTemplates.Holding.Parked) holding;
                if (!store.gainEligible(unitOfWork, parked.suspenseItemId(), row.ruleSetId())) {
                    throw new GainNotYetEligible();
                }
            }
            case MANUAL_MATCH ->
                    candidate = Optional.of(
                            manualCandidate(unitOfWork, holding,
                                    row.chosenExpectationId().orElseThrow(), true));
            default -> {
                // ACKNOWLEDGE and WRITE_OFF: nothing more to lock.
            }
        }

        Optional<UUID> entryId = Optional.empty();
        Optional<UUID> decisionId = Optional.empty();
        Optional<UUID> parkId = Optional.empty();
        if (kind.postsAdjustment()) {
            AdjustmentProposalId proposal =
                    AdjustmentProposalId.of(row.adjustmentProposalId().orElseThrow());
            // The approver approves what was proposed (ADR-0071 section 8): the template
            // re-derived from the locked remainder must be exactly the stored lines.
            List<JournalLine> stored =
                    adjustments.find(unitOfWork, proposal)
                            .orElseThrow(() -> new IllegalStateException(
                                    "a resolution's ledger proposal exists (V007)"))
                            .lines();
            if (!stored.equals(lines(unitOfWork, kind, holding, row.targetAccountId()))) {
                throw new ResolutionStale("the template's lines no longer match the stored"
                        + " ledger proposal");
            }
            PostingResult posted = adjustments.approveOwned(unitOfWork, proposal);
            entryId = Optional.of(posted.entryId().value());
        }
        String causeRef = "resolution=" + row.id();
        switch (holding) {
            case ResolutionTemplates.Holding.Parked parked when kind.postsAdjustment() ->
                    releaseWhole(unitOfWork, parked.suspenseItemId(), parked.externalItemId(),
                            parked.amount().minorUnits(), ReleaseCause.RESOLUTION, causeRef,
                            actor, now, correlation);
            case ResolutionTemplates.Holding.Remainder remainder when kind.postsAdjustment() -> {
                if (!store.resolveExpectation(
                        unitOfWork, remainder.expectationId(), remainder.amount().minorUnits(),
                        row.id(), actor, now, correlation)) {
                    throw new IllegalStateException(
                            "the locked expectation's remainder moved under its lock");
                }
            }
            default -> {
                // ACKNOWLEDGE disposes of nothing; OFFSET and MANUAL_MATCH close below.
            }
        }
        if (kind == ResolutionKind.OFFSET_SUSPENSE) {
            ResolutionTemplates.Holding.Parked parked =
                    (ResolutionTemplates.Holding.Parked) holding;
            ResolutionStore.SuspenseHolding other = partner.orElseThrow();
            releaseWhole(unitOfWork, parked.suspenseItemId(), parked.externalItemId(),
                    parked.amount().minorUnits(), ReleaseCause.OFFSET_SUSPENSE, causeRef,
                    actor, now, correlation);
            releaseWhole(unitOfWork, other.suspenseItemId(), other.externalItemId(),
                    other.unreleasedMinor(), ReleaseCause.OFFSET_SUSPENSE, causeRef, actor, now,
                    correlation);
        }
        if (kind == ResolutionKind.MANUAL_MATCH) {
            ManualMatch applied =
                    applyManualMatch(unitOfWork, row, (ResolutionTemplates.Holding.Parked) holding,
                            candidate.orElseThrow(), proposedOn, actor, now, correlation);
            decisionId = Optional.of(applied.decisionId());
            parkId = Optional.of(applied.parkId());
            entryId = Optional.of(applied.entryId());
        }

        if (!store.decide(unitOfWork, row.id(), ResolutionStatus.APPROVED, actor, now, entryId,
                decisionId, parkId)) {
            throw new IllegalStateException(
                    "the locked resolution was decided by another writer: the FOR UPDATE"
                            + " protocol was bypassed");
        }
        store.appendEvent(
                unitOfWork, row.id(), Optional.of(ResolutionStatus.PROPOSED),
                ResolutionStatus.APPROVED, actor, Optional.empty(), now, correlation);
        resolveBreakOrLoud(unitOfWork, row.breakId(), row.id(), kind, actor, now, correlation);
        ReconciliationEvents.breakResolved(
                outbox, unitOfWork, ids, row.breakId(), row.id(), kind, row.reasonCode(),
                entryId, now, correlation);
        // The remainder is gone, so every other break answering for it is explained by this
        // same resolution (the offset's two-break precedent): closed now, never left standing
        // over a value no person kind could still dispose of.
        for (ResolutionStore.RemainderSibling sibling : siblings) {
            resolveBreakOrLoud(unitOfWork, sibling.breakId(), row.id(), kind, actor, now,
                    correlation);
            ReconciliationEvents.breakResolved(
                    outbox, unitOfWork, ids, sibling.breakId(), row.id(), kind, row.reasonCode(),
                    Optional.empty(), now, correlation);
        }
        if (partner.isPresent()) {
            UUID offsetBreakId = partner.get().breakId();
            resolveBreakOrLoud(unitOfWork, offsetBreakId, row.id(), kind, actor, now,
                    correlation);
            ReconciliationEvents.breakResolved(
                    outbox, unitOfWork, ids, offsetBreakId, row.id(), kind, row.reasonCode(),
                    Optional.empty(), now, correlation);
        }
        audit(unitOfWork, actor, now, ReconciliationAuditAction.RESOLUTION_APPROVED, row.id(),
                Optional.empty(),
                "break=" + row.breakId() + ", kind=" + kind.name()
                        + ", reasonCode=" + row.reasonCode().name()
                        + ", proposedBy=" + row.proposedBy()
                        + entryId.map(id -> ", journalEntry=" + id).orElse("")
                        + decisionId.map(id -> ", decision=" + id).orElse("")
                        + partner.map(p -> ", offsetBreak=" + p.breakId()).orElse("")
                        + (siblings.isEmpty()
                                ? ""
                                : ", siblingBreaks=" + siblings.stream()
                                        .map(sibling -> sibling.breakId().toString())
                                        .toList()),
                correlation);
        return new Decided(row.id(), row.breakId(), ResolutionStatus.APPROVED, entryId, false);
    }

    private void releaseWhole(
            Connection unitOfWork,
            UUID suspenseItemId,
            Optional<UUID> externalItemId,
            long amountMinor,
            ReleaseCause cause,
            String causeRef,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        suspense.release(
                unitOfWork, suspenseItemId, amountMinor, cause, causeRef, Optional.empty(), actor,
                at, correlation);
        externalItemId.ifPresent(
                item -> {
                    if (!matching.markParkedItemResolved(unitOfWork, item, actor, at,
                            correlation)) {
                        throw new IllegalStateException(
                                "a parked item with open suspense stands PARKED (V004)");
                    }
                });
    }

    private record ManualMatch(UUID decisionId, UUID parkId, UUID entryId) {}

    /**
     * The manual match, applied as the engine's own allocation would be: a {@code MANUAL}
     * decision over the stored candidate, its allocation (the pair unique arbitrating against
     * the engine for any writer), the expectation's machine, the item's
     * {@code PARKED → MATCHED}, and the unpark — the park's exact inverse. A settling match
     * also closes the candidate's open {@code MISSING_EXTERNAL} break by evidence, the late
     * settlement's rule (`P8-TSK-013`, L1).
     */
    private ManualMatch applyManualMatch(
            Connection unitOfWork,
            ResolutionStore.ResolutionRow row,
            ResolutionTemplates.Holding.Parked parked,
            MatchEngine.HitFacts hit,
            LocalDate proposedOn,
            Actor actor,
            Instant now,
            CorrelationId correlation) {
        UUID itemId = parked.externalItemId().orElseThrow();
        UUID runId =
                store.runOfItem(unitOfWork, itemId)
                        .orElseThrow(() -> new IllegalStateException(
                                "an external item names its run (V003)"));
        Money amount = parked.amount();
        boolean settles = hit.remainderMinor() == amount.minorUnits();
        Optional<UUID> overdueBreak =
                settles
                        ? matching.lockOpenBreakOn(
                                unitOfWork, hit.expectationId(), BreakType.MISSING_EXTERNAL)
                        : Optional.empty();
        UUID decisionId = ids.next();
        matching.insertDecision(
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        itemId,
                        runId,
                        DecisionOrigin.MANUAL,
                        row.ruleSetId(),
                        Optional.empty(),
                        Optional.of(Cardinality.ONE_TO_ONE),
                        Optional.of(hit.reachedBy()),
                        DecisionOutcome.MATCHED,
                        Optional.of(1),
                        Optional.of(1),
                        Optional.empty(),
                        Optional.empty(),
                        actor,
                        now,
                        proposedOn,
                        correlation));
        matching.insertCandidates(unitOfWork, decisionId, List.of(hit));
        try {
            matching.insertAllocation(
                    unitOfWork,
                    new MatchingStore.NewAllocation(
                            ids.next(), decisionId, itemId, hit.expectationId(), amount, now,
                            correlation));
        } catch (ReconciliationStorageException failure) {
            if (uniqueViolation(failure)) {
                throw new RecordAlreadyMatched();
            }
            throw failure;
        }
        ExpectationStatus status =
                matching.allocateToExpectation(
                        unitOfWork, hit.expectationId(), amount, "decision=" + decisionId,
                        actor, now, correlation);
        matching.bumpResidualOnSubjects(unitOfWork, hit.expectationId(), itemId);
        if (status == ExpectationStatus.SETTLED) {
            ReconciliationEvents.expectationSettled(
                    outbox, unitOfWork, ids, hit.expectationId(), hit.kind(), hit.operationRef(),
                    breaks.sourceOf(unitOfWork, row.breakId()).orElseThrow(), now, correlation);
        }
        if (!matching.markItemMatchedFrom(
                unitOfWork, itemId, "PARKED", amount.minorUnits(), actor, now, correlation)) {
            throw new IllegalStateException(
                    "a parked item with open suspense stands PARKED (V004)");
        }
        Suspense.Unparked unparked =
                suspense.unpark(
                        unitOfWork, parked.suspenseItemId(), amount, ReleaseCause.UNPARK,
                        "resolution=" + row.id(), proposedOn, actor, now, correlation);
        overdueBreak.ifPresent(
                breakId ->
                        evidence.evidence(
                                unitOfWork,
                                new Resolutions.Evidence(
                                        ids.next(),
                                        breakId,
                                        amount,
                                        matching.breakResidualVersion(unitOfWork, breakId),
                                        decisionId,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        row.ruleSetId(),
                                        actor,
                                        now,
                                        correlation)));
        return new ManualMatch(decisionId, unparked.parkId(), unparked.entryId());
    }

    private static boolean uniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ reject and withdraw

    /**
     * Rejects a pending proposal — another RESOLVE holder's reasoned act. The ledger proposal is
     * rejected through {@code rejectOwned} in the same transaction, and the break returns to
     * {@code INVESTIGATING}. The same person's retry converges.
     */
    public Decided reject(
            Connection unitOfWork,
            UUID resolutionId,
            String reason,
            Actor actor,
            CorrelationId correlation) {
        refuseReason(reason);
        ResolutionStore.ResolutionRow row = lockedResolution(unitOfWork, resolutionId);
        if (row.status() == ResolutionStatus.REJECTED
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.breakId(), row.status(), Optional.empty(), true);
        }
        if (row.status() != ResolutionStatus.PROPOSED) {
            throw new ResolutionNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        close(unitOfWork, row, ResolutionStatus.REJECTED, Optional.of(reason), actor,
                correlation);
        return new Decided(row.id(), row.breakId(), ResolutionStatus.REJECTED, Optional.empty(),
                false);
    }

    /**
     * Withdraws a pending proposal — the proposer's own act; nothing is deleted, the row moves
     * to {@code WITHDRAWN}. The ledger proposal is rejected through {@code rejectOwned}, and the
     * break returns to {@code INVESTIGATING}. The proposer's retry converges.
     */
    public Decided withdraw(
            Connection unitOfWork, UUID resolutionId, Actor actor, CorrelationId correlation) {
        ResolutionStore.ResolutionRow row = lockedResolution(unitOfWork, resolutionId);
        if (row.status() == ResolutionStatus.WITHDRAWN
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.breakId(), row.status(), Optional.empty(), true);
        }
        if (row.status() != ResolutionStatus.PROPOSED) {
            throw new ResolutionNotPending(row.status());
        }
        if (!row.proposedBy().equals(actor.id())) {
            throw new NotTheProposer();
        }
        close(unitOfWork, row, ResolutionStatus.WITHDRAWN, Optional.empty(), actor, correlation);
        return new Decided(row.id(), row.breakId(), ResolutionStatus.WITHDRAWN, Optional.empty(),
                false);
    }

    private void close(
            Connection unitOfWork,
            ResolutionStore.ResolutionRow row,
            ResolutionStatus to,
            Optional<String> reason,
            Actor actor,
            CorrelationId correlation) {
        row.adjustmentProposalId()
                .ifPresent(
                        proposal ->
                                adjustments.rejectOwned(
                                        unitOfWork, AdjustmentProposalId.of(proposal)));
        Instant now = Instant.now(clock);
        if (!store.decide(unitOfWork, row.id(), to, actor, now, Optional.empty(),
                Optional.empty(), Optional.empty())) {
            throw new IllegalStateException(
                    "the locked resolution was decided by another writer: the FOR UPDATE"
                            + " protocol was bypassed");
        }
        store.appendEvent(
                unitOfWork, row.id(), Optional.of(ResolutionStatus.PROPOSED), to, actor, reason,
                now, correlation);
        if (!store.moveBreak(unitOfWork, row.breakId(), BreakStatus.RESOLUTION_PROPOSED,
                BreakStatus.INVESTIGATING, now)) {
            throw new IllegalStateException(
                    "a PROPOSED resolution's break stands RESOLUTION_PROPOSED (ADR-0071"
                            + " section 1)");
        }
        audit(unitOfWork, actor, now,
                to == ResolutionStatus.REJECTED
                        ? ReconciliationAuditAction.RESOLUTION_REJECTED
                        : ReconciliationAuditAction.RESOLUTION_WITHDRAWN,
                row.id(), reason,
                "break=" + row.breakId() + ", kind=" + row.kind().name()
                        + ", reasonCode=" + row.reasonCode().name()
                        + ", proposedBy=" + row.proposedBy()
                        + row.adjustmentProposalId()
                                .map(id -> ", adjustmentProposal=" + id).orElse(""),
                correlation);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The decision commands' lock order: the frozen break id read lock-free, the sources'
     * advisories (sorted — an offset may span two sources), the break rows sorted by id, then
     * the resolution row.
     */
    private ResolutionStore.ResolutionRow lockedResolution(
            Connection unitOfWork, UUID resolutionId) {
        ResolutionStore.ResolutionRow unlocked =
                store.byId(unitOfWork, resolutionId).orElseThrow(ResolutionNotFound::new);
        Optional<UUID> offsetBreak =
                unlocked.offsetItemId().flatMap(item -> store.breakOfSuspenseItem(unitOfWork,
                        item));
        lockBreaks(unitOfWork, unlocked.breakId(), offsetBreak);
        return store.lockById(unitOfWork, resolutionId).orElseThrow(ResolutionNotFound::new);
    }

    /** Advisories sorted, then break rows sorted — the Phase 8 order for one or two breaks. */
    private Map<UUID, BreakCaseStore.BreakRow> lockBreaks(
            Connection unitOfWork, UUID breakId, Optional<UUID> otherBreakId) {
        TreeSet<UUID> breakIds = new TreeSet<>();
        breakIds.add(breakId);
        otherBreakId.ifPresent(breakIds::add);
        TreeSet<UUID> sources = new TreeSet<>();
        for (UUID id : breakIds) {
            sources.add(
                    breaks.sourceOf(unitOfWork, id)
                            .orElseThrow(
                                    () ->
                                            id.equals(breakId)
                                                    ? new BreakCaseFile.BreakNotFound()
                                                    : new ResolutionTargetRefused(
                                                            "the offset item's break is"
                                                                    + " missing")));
        }
        for (UUID source : sources) {
            breaks.lockSource(unitOfWork, source);
        }
        Map<UUID, BreakCaseStore.BreakRow> locked = new java.util.HashMap<>();
        for (UUID id : breakIds) {
            locked.put(
                    id,
                    breaks.lockForUpdate(unitOfWork, id)
                            .orElseThrow(BreakCaseFile.BreakNotFound::new));
        }
        return locked;
    }

    /**
     * What the break's subject holds, locked: its expectation's open remainder, then its OWN
     * open suspense item — parked value wins, because that is what the break owns.
     */
    private ResolutionTemplates.Holding holding(
            Connection unitOfWork, BreakCaseStore.BreakRow row) {
        Optional<ResolutionStore.ExpectationHolding> expectation =
                row.expectationId().flatMap(id -> store.lockExpectation(unitOfWork, id));
        List<ResolutionStore.SuspenseHolding> parked =
                store.lockOpenSuspenseOf(unitOfWork, row.id());
        if (parked.size() > 1) {
            throw new ResolutionKindNotAllowed(
                    "the break owns several suspense items; a template disposes of one");
        }
        if (parked.size() == 1) {
            ResolutionStore.SuspenseHolding item = parked.get(0);
            return new ResolutionTemplates.Holding.Parked(
                    item.suspenseItemId(),
                    item.externalItemId(),
                    item.side(),
                    Money.ofPersisted(
                            item.unreleasedMinor(), CurrencyCode.of(item.currency()),
                            item.scale()),
                    item.positionAccountId());
        }
        if (expectation.isPresent()
                && (expectation.get().status() == ExpectationStatus.OPEN
                        || expectation.get().status() == ExpectationStatus.PARTIALLY_SETTLED)
                && expectation.get().remainderMinor() > 0) {
            ResolutionStore.ExpectationHolding open = expectation.get();
            return new ResolutionTemplates.Holding.Remainder(
                    open.expectationId(),
                    open.direction(),
                    Money.ofPersisted(
                            open.remainderMinor(), CurrencyCode.of(open.currency()),
                            open.scale()),
                    open.positionAccountId());
        }
        return new ResolutionTemplates.Holding.Nothing();
    }

    /** A posting kind over an expectation's remainder takes the whole remainder away. */
    private static boolean disposesOfRemainder(
            ResolutionKind kind, ResolutionTemplates.Holding holding) {
        return kind.postsAdjustment() && holding instanceof ResolutionTemplates.Holding.Remainder;
    }

    /**
     * The other breaks answering for the remainder, locked after the expectation row: all
     * their writers hold the source's advisory this transaction took first, and ageing's raise
     * needs the expectation row this transaction already holds, so none can appear unseen.
     */
    private List<ResolutionStore.RemainderSibling> remainderSiblings(
            Connection unitOfWork, ResolutionTemplates.Holding holding, UUID breakId) {
        return store.lockRemainderSiblings(
                unitOfWork,
                ((ResolutionTemplates.Holding.Remainder) holding).expectationId(),
                breakId);
    }

    private List<JournalLine> lines(
            Connection unitOfWork,
            ResolutionKind kind,
            ResolutionTemplates.Holding holding,
            Optional<UUID> target) {
        CurrencyCode currency = amountOf(holding).currency();
        return ResolutionTemplates.lines(
                kind,
                holding,
                operational(unitOfWork, AccountPurpose.RECONCILIATION_LOSSES, currency),
                operational(unitOfWork, AccountPurpose.RECONCILIATION_GAINS, currency),
                operational(unitOfWork, AccountPurpose.SUSPENSE_UNMATCHED, currency),
                target.map(LedgerAccountId::of));
    }

    private LedgerAccountId operational(
            Connection unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
        return accounts.findOperational(unitOfWork, purpose, currency)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the chart seeds " + purpose.name() + " in "
                                                + currency.code() + " (ledger V017)"))
                .id();
    }

    private static Money amountOf(ResolutionTemplates.Holding holding) {
        return switch (holding) {
            case ResolutionTemplates.Holding.Remainder remainder -> remainder.amount();
            case ResolutionTemplates.Holding.Parked parked -> parked.amount();
            case ResolutionTemplates.Holding.Nothing nothing ->
                    throw new IllegalArgumentException("the subject holds no value");
        };
    }

    private void resolveBreakOrLoud(
            Connection unitOfWork,
            UUID breakId,
            UUID resolutionId,
            ResolutionKind kind,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        if (!store.resolveBreak(unitOfWork, breakId, resolutionId, kind, actor, at,
                correlation)) {
            throw new IllegalStateException(
                    "the locked break was resolved by another writer: the lock order was"
                            + " bypassed");
        }
    }

    /** The ledger proposal's required reason: identifiers and codes, never the narrative. */
    static String ledgerReason(
            UUID resolutionId, ResolutionKind kind, ResolutionReasonCode reasonCode) {
        return "resolution=" + resolutionId + ", kind=" + kind.name()
                + ", reasonCode=" + reasonCode.name();
    }

    private static String auditReason(ResolutionKind kind, ResolutionReasonCode reasonCode) {
        return "kind=" + kind.name() + ", reasonCode=" + reasonCode.name();
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            ReconciliationAuditAction action,
            UUID resolutionId,
            Optional<String> reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        JdbcResolutions.TARGET_TYPE,
                        resolutionId.toString(),
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
