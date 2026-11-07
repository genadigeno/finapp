package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.PeriodCloseId;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodReopenId;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.ReturnsAReopenOvertakes;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the close's and the reopen's doors (`P14-TSK-012`; {@code AccountingPeriodDesk}'s shape): key every act per principal ({@code accounting.period:<type>:<id>}, the period doors' own scope),
 * run each in one transaction under its span, translate the domain's refusals onto the error contract; a refused self-approval is recorded {@code FAILED} in a transaction of its own; a controller's read of what it
 * approves recorded {@code accounting.CloseProposalRead} in a transaction of its own before the answer. The machine, what the person read and the four eyes are the domain's and {@code accounting V008}'s; nothing here
 * decides them, and nothing here posts.
 *
 * <p><strong>A key's replay is the first answer</strong>: the same key with the same body returns the stored receipt, a timeout then a retry included; the same key with another body is the executor's conflict, its
 * fingerprint covering every reason. Beneath the key, the domain converges the same person's repeat.
 *
 * <p>What leaves the read: the proposal, each frozen attempt with its evidence, and the proposer's reason <em>per {@code DISCLOSED} attempt</em>, which exists to be disclosed to the approver. Never a person; never the
 * proposal's or a decision's own reason.
 *
 * <p><strong>A reopen's two receipts list the returns it overtakes</strong> (`P14-TSK-023`, the owner's ruling on `P14-TSK-018`'s item (a)): {@link ReturnsAReopenOvertakes}, a plain read on the act's own transaction, holding the period's row. A list and no more: unlike the adjustments' it is named back by nobody and compared with nothing, so it decides nothing and refuses nothing. It is stored with the receipt and a replay returns what was stored; the words it is served with, {@link #OVERTAKEN_RETURNS_NOTE}, are not stored. The fingerprint does not move: a receipt is no part of a request. What a form's code and a return's status are, who reads them here and why that stands: {@code DATA_CLASSIFICATION.md}, the return's section.
 *
 * <p><strong>The approver is told before deciding</strong> (`P14-TSK-024`, the owner's acceptance of `P14-TSK-023`'s item (a) and `P14-TSK-012`'s (j)): the read serves each frozen attempt's {@code concludedAt}, and on a pending reopen the same list by the same statement, read
 * on the read's own transaction, holding nothing, with {@link #PENDING_REOPEN_NOTE}. It is information: the approval's receipt reads its own list holding the month, and the two differ when a return was sent or concluded in between; the note says so. No reopen pending, no list read, and its
 * three fields are null.
 *
 * <p><strong>A reopen's end</strong> (`P14-TSK-024`): a second person's rejection and the proposer's withdrawal, keyed and reasoned as the acts are, each under the period's row. Its receipt reads no list: an end overtakes nothing.
 *
 * <p><strong>Every act on a pending reopen names it</strong> (`P14-TSK-024`'s fix round): an end makes a pending reopen replaceable within seconds, so the approval, the rejection and the withdrawal each name
 * the reopen read by {@code reopenId}, which the approver's read, {@link #requests} and the accountant's period list serve; the fingerprint covers it, so the same key naming another reopen is the executor's
 * conflict. Another pending is {@code 409 accounting.RequestStale}, recorded {@code FAILED} as a self-approval and another person's withdrawal are. The close's approval and rejection named their proposal
 * from the first. {@link #requests} serves the controller what each act names, an unlock by {@code unlockProposedAt}: a controller holds no {@code ACCOUNTING_READ}, so the period list is not theirs.
 */
@RequiredArgsConstructor
public final class PeriodCloseDesk {

    static final String CLOSE_PROPOSE_SPAN = "accounting.period.close.propose";
    static final String CLOSE_APPROVE_SPAN = "accounting.period.close.approve";
    static final String CLOSE_REJECT_SPAN = "accounting.period.close.reject";
    static final String CLOSE_READ_SPAN = "accounting.period.close.read";
    static final String REOPEN_PROPOSE_SPAN = "accounting.period.reopen.propose";
    static final String REOPEN_APPROVE_SPAN = "accounting.period.reopen.approve";
    static final String REOPEN_REJECT_SPAN = "accounting.period.reopen.reject";
    static final String REOPEN_WITHDRAW_SPAN = "accounting.period.reopen.withdraw";
    static final String REQUESTS_READ_SPAN = "accounting.period.requests.read";

    /** What the list holds and leaves out, the same words wherever it is served: what each of the two notes below says after its first sentence. */
    private static final String WHAT_IS_LISTED = " Each return SUBMITTED or ACKNOWLEDGED for this month, newest first, at most "
            + ReturnsAReopenOvertakes.NEWEST + " of overtakenReturnsTotal. Not listed: a return not yet sent. One APPROVED can still be sent until a reopen's approval moves the month's revision, and is stale and refused after it. Nor one UNDELIVERED, which may still reach the provider late. So an empty list does not mean nothing is in flight:"
            + " GET /v1/operator/regulatory/submissions under REGULATORY_REPORT_PREPARE, which a regulatory reporter holds and neither of a reopen's two people does, lists what stands.";

    /**
     * What a reopen's receipt says of its list, served and never stored: no field of this API carries a description (ADR-0015), and the person reading the receipt reads the receipt. It names the one door that lists what stands, and says who holds it, since neither of a reopen's two people does.
     */
    static final String OVERTAKEN_RETURNS_NOTE = "Information only, read while this act held the month: it stopped nothing and decides nothing, and a status may have moved since." + WHAT_IS_LISTED;

    /**
     * What the approver's read says of the same list on a pending reopen (`P14-TSK-024`), served and never stored: read before deciding and holding nothing, so the approval's receipt, which reads its own holding the month, can differ from it. Said here rather than hidden.
     */
    static final String PENDING_REOPEN_NOTE = "Information only, read for the person deciding and holding nothing: it decides nothing, and a status may have moved since. The approval's receipt lists what stands when the approval holds the month, so it differs from this list when a return was sent or concluded in between."
            + WHAT_IS_LISTED;

    @NonNull private final PeriodCloses closes;
    /** What has posted against the month: a reopen's two people and a re-close's approver are told (`P14-TSK-013`). Read only. */
    @NonNull private final PriorPeriodAdjustments priorPeriodAdjustments;
    /** What a reopen's two people are told it overtakes (`P14-TSK-023`). Read only. */
    @NonNull private final ReturnsAReopenOvertakes overtaken;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    /**
     * An act's receipt: the period as the act left it, and the proposal it answers.
     *
     * @param priorPeriodAdjustments on a reopen's two acts, the adjustments already posted against the month, read holding its row (`P14-TSK-013`): its figures at the next revision will still show what those corrected, each entry being dated in a later month, so
     *     whoever corrects the month directly must know they stand. What an act that judged its naming named, since `P14-TSK-022` refuses anything else; a repeat that converged compared nothing, and this is what stands now. Empty on a close's acts and on a reopen's end (`P14-TSK-024`), which name nothing.
     * @param overtakenReturns on a reopen's two acts, the returns {@code SUBMITTED} or {@code ACKNOWLEDGED} for the month, newest first, at most {@link ReturnsAReopenOvertakes#NEWEST} (`P14-TSK-023`). As read at that act: a list, never a gate, and {@link #overtakenReturnsNote} says what it does not hold. A month with none reads an empty list. <strong>Null where no list was read</strong>: on a close's acts, on a reopen's end (`P14-TSK-024`), which overtakes nothing, and on a receipt a reopen's key stored before `P14-TSK-023`, so "read, and none" and "never read" never look alike (the fix round's M-6).
     * @param overtakenReturnsTotal how many there are, of which {@code overtakenReturns} is the newest; null where no list was read, as that list
     * @param overtakenReturnsNote what the list says and does not, on a reopen's act; null where no list was read, as that list
     */
    public record PeriodCloseReceipt(String id, String period, String status, int revision, String proposalId, String closeRunId, List<String> priorPeriodAdjustments, List<OvertakenReturn> overtakenReturns, Long overtakenReturnsTotal, String overtakenReturnsNote) {}

    /** A return a reopen overtakes: its id, its form and its status as read. Never a figure, a person, a reason or a provider's reference. */
    public record OvertakenReturn(String id, String form, String status) {}

    /** A person's reason for the attempt they read. */
    public record StatedDisclosure(String check, int attempt, String reason) {

        /** A reason never leaves in a text form: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "StatedDisclosure[" + check + "#" + attempt + "]";
        }
    }

    /**
     * One check of a proposal as the approver reads it - no person.
     *
     * @param concludedAt when the attempt read concluded (`P14-TSK-024`): its {@code close_check.concluded_at}, the database's clock as stored. What it judged was read no later than this, after the attempt's last claim, and nothing re-judges it at the approval: an approver attests to it
     *     as of then. Always set, since only a concluded attempt is proposed.
     */
    public record CloseProposalCheckView(String check, int attempt, String status, String judgedBy, String concludedAt, String evidence, String disclosureReason) {

        /** Served to the approver and never logged: a record's generated {@code toString} would carry the reason into a log line. */
        @Override
        public String toString() {
            return "CloseProposalCheckView[" + check + "#" + attempt + ", " + status + "]";
        }
    }

    /**
     * A period's latest close proposal. {@code stillStands} when the approval would find what it read still standing; when false the way on is to reject and propose again.
     *
     * @param reopenPending a reopen of the {@code CLOSED} period is pending (`P14-TSK-024`): what its approver decides, this proposal being the close it would reopen
     * @param reopenId that reopen's id, which its approval and rejection name; null when none is pending
     * @param reopenProposedAt when that reopen was proposed, the database's clock; null when none is pending
     * @param overtakenReturns on a pending reopen, the returns it would overtake, newest first, at most {@link ReturnsAReopenOvertakes#NEWEST}: the receipts' statement, read here holding nothing. <strong>Null when no reopen is pending</strong>, since no list was read
     * @param overtakenReturnsTotal how many there are; null as that list
     * @param overtakenReturnsNote what the list says and does not, and that the approval's receipt can differ from it; null as that list
     * @param valuationRateSetReferenceGaps how many of the named set's rates a reference-gap exception admitted beyond the staleness bound (`P14-DOC-001`, `P14-TSK-026`'s owner item (c)): the
     *     close's approver told on the close's own read, as the set's read and every translated report tell; 0 for an ordinary capture. Additive under {@code v1} (ADR-0015)
     */
    public record CloseProposalView(
            String proposalId,
            String period,
            String periodStatus,
            int revision,
            String status,
            String closeRunId,
            String lockedAt,
            String proposedAt,
            String decidedAt,
            String valuationRateSetId,
            long breaksAcknowledged,
            long breaksResolved,
            boolean stillStands,
            List<CloseProposalCheckView> checks,
            List<String> priorPeriodAdjustments,
            boolean reopenPending,
            String reopenId,
            String reopenProposedAt,
            List<OvertakenReturn> overtakenReturns,
            Long overtakenReturnsTotal,
            String overtakenReturnsNote,
            int valuationRateSetReferenceGaps) {}

    /**
     * A period's pending requests as the controller reads them before deciding one (`P14-TSK-024`'s fix round): what each act names - an unlock by when it was proposed, a reopen by its id - and
     * when the reopen was proposed. No person, no reason, no figure. Null where none is pending.
     */
    public record PeriodRequestsView(String id, String period, String status, int revision, boolean unlockPending, String unlockProposedAt, boolean reopenPending, String reopenId,
            String reopenProposedAt) {}

    public PeriodCloseReceipt propose(String idempotencyKey, String rawId, String rawRun, String reason, List<StatedDisclosure> disclosures) {
        AccountingPeriodId id = periodId(rawId);
        CloseRunId run = CloseRunId.of(named(rawRun, "the close run"));
        List<StatedDisclosure> stated = disclosures == null ? List.of() : disclosures;
        List<PeriodCloses.Disclosure> parsed = stated.stream().map(PeriodCloseDesk::disclosure).toList();
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(CLOSE_PROPOSE_SPAN, () -> keyed(actor, idempotencyKey,
                "close-proposal|" + id.value() + "|" + run.value() + "|" + reason + "|" + fingerprinted(stated),
                uow -> receipt(closes.propose(uow, id, run, parsed, actor, reason, correlation))));
    }

    public PeriodCloseReceipt approve(String idempotencyKey, String rawId, String rawProposal, String reason) {
        AccountingPeriodId id = periodId(rawId);
        PeriodCloseId proposal = PeriodCloseId.of(named(rawProposal, "the proposal"));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(CLOSE_APPROVE_SPAN, () -> keyed(actor, idempotencyKey,
                "close-approval|" + id.value() + "|" + proposal.value() + "|" + reason,
                uow -> receipt(closes.approve(uow, id, proposal, actor, reason, correlation))));
    }

    public PeriodCloseReceipt reject(String idempotencyKey, String rawId, String rawProposal, String reason) {
        AccountingPeriodId id = periodId(rawId);
        PeriodCloseId proposal = PeriodCloseId.of(named(rawProposal, "the proposal"));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(CLOSE_REJECT_SPAN, () -> keyed(actor, idempotencyKey,
                "close-rejection|" + id.value() + "|" + proposal.value() + "|" + reason,
                uow -> receipt(closes.reject(uow, id, proposal, actor, reason, correlation))));
    }

    public PeriodCloseReceipt proposeReopen(String idempotencyKey, String rawId, List<String> rawPosted, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Set<UUID> named = namedAdjustments(rawPosted);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REOPEN_PROPOSE_SPAN, () -> keyed(actor, idempotencyKey, reopenFingerprint("reopen-proposal", id, Optional.empty(), named, reason),
                uow -> toldOfTheMonth(uow, receipt(closes.proposeReopen(uow, id, actor, named, reason, correlation)), id)));
    }

    public PeriodCloseReceipt approveReopen(String idempotencyKey, String rawId, String rawReopen, List<String> rawPosted, String reason) {
        AccountingPeriodId id = periodId(rawId);
        PeriodReopenId requested = requestedReopen(rawReopen);
        Set<UUID> named = namedAdjustments(rawPosted);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REOPEN_APPROVE_SPAN, () -> keyed(actor, idempotencyKey, reopenFingerprint("reopen-approval", id, Optional.of(requested), named, reason),
                uow -> toldOfTheMonth(uow, receipt(closes.approveReopen(uow, id, requested, actor, named, reason, correlation)), id)));
    }

    /** Rejects the pending reopen named - a second person's decision (`P14-TSK-024`): the period stays {@code CLOSED} at its revision, nothing moves. */
    public PeriodCloseReceipt rejectReopen(String idempotencyKey, String rawId, String rawReopen, String reason) {
        AccountingPeriodId id = periodId(rawId);
        PeriodReopenId requested = requestedReopen(rawReopen);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REOPEN_REJECT_SPAN, () -> keyed(actor, idempotencyKey, "reopen-rejection|" + id.value() + "@" + requested.value() + "|" + reason,
                uow -> receipt(closes.rejectReopen(uow, id, requested, actor, reason, correlation))));
    }

    /** Withdraws the pending reopen named - its proposer's own act (`P14-TSK-024`): the period stays {@code CLOSED} at its revision, nothing moves. */
    public PeriodCloseReceipt withdrawReopen(String idempotencyKey, String rawId, String rawReopen, String reason) {
        AccountingPeriodId id = periodId(rawId);
        PeriodReopenId requested = requestedReopen(rawReopen);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REOPEN_WITHDRAW_SPAN, () -> keyed(actor, idempotencyKey, "reopen-withdrawal|" + id.value() + "@" + requested.value() + "|" + reason,
                uow -> receipt(closes.withdrawReopen(uow, id, requested, actor, reason, correlation))));
    }

    /**
     * A reopen's fingerprint: the same key naming another set, or another reopen, is the executor's conflict. A proposal naming none reads exactly as one did before `P14-TSK-022`, so a retry across the deploy replays its receipt; one naming some has a prefix of its own, so the two forms can never be the same text whatever a reason holds. An approval names the reopen it decides
     * (`P14-TSK-024`) beside the period, a body the old form never had, so its text differs from every earlier one.
     */
    static String reopenFingerprint(String act, AccountingPeriodId id, Optional<PeriodReopenId> requested, Set<UUID> named, String reason) {
        String period = id.value() + requested.map(reopen -> "@" + reopen.value()).orElse("");
        return named.isEmpty() ? act + "|" + period + "|" + reason : act + "-named|" + period + "|" + new TreeSet<>(named) + "|" + reason;
    }

    /** A period's pending requests, as the controller reads them before deciding one - a plain read, unrecorded as the period list is, for it serves no person, reason or figure. */
    public PeriodRequestsView requests(String rawId) {
        AccountingPeriodId id = periodId(rawId);
        return flow.door(REQUESTS_READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> {
            PeriodCloses.PendingRequests pending = closes.pendingOf(uow, id);
            return new PeriodRequestsView(pending.period().id().value().toString(), pending.period().month().toString(), pending.period().status().name(), pending.period().revision(),
                    pending.unlockProposedAt().isPresent(), pending.unlockProposedAt().map(Instant::toString).orElse(null), pending.reopen().isPresent(),
                    pending.reopen().map(reopen -> reopen.id().value().toString()).orElse(null), pending.reopen().map(reopen -> reopen.proposedAt().toString()).orElse(null));
        })));
    }

    /**
     * The period's latest proposal, as its approver reads it; a period with none is {@code accounting.NotFound}. On a pending reopen, the returns it would overtake, by {@link ReturnsAReopenOvertakes} on this read's own transaction: a plain statement holding nothing, so it adds no edge to the order. Recorded before it
     * is served, how many returns were listed with it.
     */
    public CloseProposalView proposal(String rawId) {
        AccountingPeriodId id = periodId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        record Read(PeriodCloses.ProposalView proposal, List<UUID> posted, Optional<ReturnsAReopenOvertakes.Overtaken> overtaken) {}
        Read read = flow.door(CLOSE_READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> {
            PeriodCloses.ProposalView proposal = closes.proposalOf(uow, id).orElseThrow(PeriodCloseDesk::notFound);
            return new Read(proposal, priorPeriodAdjustments.postedAgainst(uow, id), proposal.pendingReopen().isPresent() ? Optional.of(overtaken.of(uow, id)) : Optional.empty());
        })));
        transactions.inTransaction(uow -> {
            closes.recordRead(uow, read.proposal(), read.overtaken().map(ReturnsAReopenOvertakes.Overtaken::total), actor, correlation);
            return null;
        });
        return view(read.proposal(), read.posted(), read.overtaken());
    }

    // ------------------------------------------------------------------ views

    private static CloseProposalView view(PeriodCloses.ProposalView view, List<UUID> posted, Optional<ReturnsAReopenOvertakes.Overtaken> overtaken) {
        return new CloseProposalView(
                view.proposal().id().value().toString(),
                view.proposal().month().toString(),
                view.periodStatus().name(),
                view.proposal().revision(),
                view.proposal().status().name(),
                view.proposal().run().value().toString(),
                view.proposal().lockedAt().toString(),
                view.proposal().proposedAt().toString(),
                view.proposal().decidedAt().map(Instant::toString).orElse(null),
                view.proposal().rateSet().value().toString(),
                view.proposal().acknowledged(),
                view.proposal().resolved(),
                view.stillStands(),
                view.checks().stream().map(check -> new CloseProposalCheckView(
                        check.check().check().name(),
                        check.check().attempt(),
                        check.check().status().name(),
                        check.check().judgedBy(),
                        check.attempt().concludedAt().toString(),
                        check.attempt().evidence(),
                        check.check().disclosureReason().orElse(null))).toList(),
                posted.stream().map(UUID::toString).toList(),
                view.pendingReopen().isPresent(),
                view.pendingReopen().map(reopen -> reopen.id().value().toString()).orElse(null),
                view.pendingReopen().map(reopen -> reopen.proposedAt().toString()).orElse(null),
                overtaken.map(PeriodCloseDesk::listed).orElse(null),
                overtaken.map(ReturnsAReopenOvertakes.Overtaken::total).orElse(null),
                overtaken.isPresent() ? PENDING_REOPEN_NOTE : null,
                view.referenceGaps());
    }

    /** A list as the statement read it: an id, a form and a status per return, never more. */
    private static List<OvertakenReturn> listed(ReturnsAReopenOvertakes.Overtaken read) {
        return read.newest().stream().map(item -> new OvertakenReturn(item.id().value().toString(), item.form(), item.status().name())).toList();
    }

    private static PeriodCloseReceipt receipt(PeriodCloses.Acted acted) {
        return new PeriodCloseReceipt(acted.id().value().toString(), acted.month().toString(), acted.status().name(), acted.revision(),
                acted.proposal().map(id -> id.value().toString()).orElse(null), acted.run().map(id -> id.value().toString()).orElse(null), List.of(), null, null, null);
    }

    /**
     * The receipt with what a reopen's two people are told of the month, each read on the act's own transaction, still holding the period's row. What has posted against it: an approval racing this act has committed, or will be refused. What it overtakes: a plain read, no lock, so no edge; a send committed before this act held the row is here, one waiting on the row is not, and a conclusion takes no period, so a status can be stale. Information, and it decides nothing.
     */
    private PeriodCloseReceipt toldOfTheMonth(Connection unitOfWork, PeriodCloseReceipt receipt, AccountingPeriodId id) {
        ReturnsAReopenOvertakes.Overtaken read = overtaken.of(unitOfWork, id);
        return new PeriodCloseReceipt(receipt.id(), receipt.period(), receipt.status(), receipt.revision(), receipt.proposalId(), receipt.closeRunId(),
                priorPeriodAdjustments.postedAgainst(unitOfWork, id).stream().map(UUID::toString).toList(), listed(read), read.total(), OVERTAKEN_RETURNS_NOTE);
    }

    // ------------------------------------------------------------------ plumbing

    /** One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing; a self-approval, another person's withdrawal and an act on another reopen are recorded {@code FAILED} in a transaction of their own. */
    private PeriodCloseReceipt keyed(Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, PeriodCloseReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(AccountingPeriodDesk.SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    PeriodCloseReceipt receipt = act.apply(uow);
                    return CommandResult.succeeded(StoredResponse.of(stored(receipt).getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        return parsed(new String(outcome.body().orElseThrow(() -> new IllegalStateException("a close act stores its receipt")), StandardCharsets.UTF_8));
    }

    /**
     * A receipt as it is stored, for a key's replay: a form's grammar and a status hold no field's separator. Its two list fields stand only on an act that read the list, so a close's act stores what it always did. The note is words, never stored.
     */
    private static String stored(PeriodCloseReceipt receipt) {
        String read = receipt.overtakenReturnsNote() == null ? "" : "|" + receipt.overtakenReturnsTotal() + "|"
                + receipt.overtakenReturns().stream().map(item -> item.id() + "~" + item.form() + "~" + item.status()).collect(Collectors.joining(","));
        return receipt.id() + "|" + receipt.period() + "|" + receipt.status() + "|" + receipt.revision() + "|"
                + (receipt.proposalId() == null ? "" : receipt.proposalId()) + "|" + (receipt.closeRunId() == null ? "" : receipt.closeRunId()) + "|"
                + String.join(",", receipt.priorPeriodAdjustments()) + read;
    }

    /**
     * A stored receipt read back. One stored before P14-TSK-013 has six fields, one before P14-TSK-023 or of a close's act seven: each reads as it did, and its list, total and note are null, since no list was read. One that read a list has nine, the last empty for a month with none.
     */
    private static PeriodCloseReceipt parsed(String text) {
        String[] stored = text.split("\\|", -1);
        boolean read = stored.length > 8;
        List<OvertakenReturn> overtaken = !read ? null : stored[8].isEmpty() ? List.of() : Arrays.stream(stored[8].split(",")).map(item -> item.split("~", 3)).map(item -> new OvertakenReturn(item[0], item[1], item[2])).toList();
        return new PeriodCloseReceipt(stored[0], stored[1], stored[2], Integer.parseInt(stored[3]), stored[4].isEmpty() ? null : stored[4],
                stored[5].isEmpty() ? null : stored[5], stored.length < 7 || stored[6].isEmpty() ? List.of() : List.of(stored[6].split(",")), overtaken, read ? Long.valueOf(stored[7]) : null, read ? OVERTAKEN_RETURNS_NOTE : null);
    }

    /** {@code work}, a refusal the doors keep ({@link AccountingPeriods.RecordedRefusal}) recorded {@code FAILED} in a transaction of its own after the act rolled back, then refused as before. */
    private <R> R refusalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingPeriods.RecordedRefusal refused) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                closes.recordRefusal(uow, refused, actor, correlation);
                return null;
            });
            throw refused;
        }
    }

    /** The domain's refusals, in the API's words. A detail names codes and a count, never a reason. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingPeriods.PeriodNotFound unknown) {
            throw notFound();
        } catch (AccountingPeriods.SelfApprovalRefused self) {
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (AccountingPeriods.NotTheProposer other) {
            throw refused(AccountingErrorCode.NOT_THE_PROPOSER, other);
        } catch (AccountingPeriods.RequestStale another) {
            throw refused(AccountingErrorCode.REQUEST_STALE, another);
        } catch (AccountingPeriods.PeriodStale stale) {
            throw refused(AccountingErrorCode.PERIOD_STALE, stale);
        } catch (PeriodCloses.CloseNotReady notReady) {
            throw refused(AccountingErrorCode.CLOSE_NOT_READY, notReady);
        } catch (PeriodCloses.CloseProposalStale stale) {
            throw refused(AccountingErrorCode.CLOSE_PROPOSAL_STALE, stale);
        } catch (PriorPeriodAdjustments.AdjustmentStale stale) {
            throw refused(AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, stale);
        } catch (PostingCutoff.CutoffBusy busy) {
            throw new ApiException(AccountingErrorCode.CUTOFF_BUSY, "An accounting period command was refused",
                    "postings held the posting cutoff; nothing was changed, retry.");
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "An accounting period command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No accounting period answers the request", "no such record.");
    }

    private static AccountingPeriodId periodId(String raw) {
        try {
            return AccountingPeriodId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    /** An id the body names: one that cannot be anything's is not what stands. */
    private static UUID named(String raw, String what) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new ApiException(AccountingErrorCode.CLOSE_PROPOSAL_STALE, "An accounting period command was refused", what + " named is no such record.");
        }
    }

    /** The reopen a body names, by its id: one that cannot be an id names no request, the refusal naming another gets. */
    private static PeriodReopenId requestedReopen(String raw) {
        try {
            return PeriodReopenId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new ApiException(AccountingErrorCode.REQUEST_STALE, "An accounting period command was refused", "the reopen named is no such request.");
        }
    }

    /** The adjustments a reopen's body names: an id that cannot be anything's is not what stands, the same refusal as naming the wrong one. */
    private static Set<UUID> namedAdjustments(List<String> raw) {
        Set<UUID> named = new TreeSet<>();
        for (String adjustment : raw == null ? List.<String>of() : raw) {
            try {
                named.add(UUID.fromString(adjustment));
            } catch (IllegalArgumentException | NullPointerException malformed) {
                throw new ApiException(AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, "An accounting period command was refused", "a posted adjustment named is no such record.");
            }
        }
        return named;
    }

    /** A check's name in the body: one that names no check is not what stands. */
    private static PeriodCloses.Disclosure disclosure(StatedDisclosure stated) {
        try {
            return new PeriodCloses.Disclosure(CloseCheckCode.valueOf(stated.check()), stated.attempt(), stated.reason());
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new ApiException(AccountingErrorCode.CLOSE_PROPOSAL_STALE, "An accounting period command was refused", "a disclosure names no close check.");
        }
    }

    /** Every reason in one fingerprint, in one order: the same key with another body is a conflict, never a replay. */
    private static String fingerprinted(List<StatedDisclosure> stated) {
        List<StatedDisclosure> ordered = new ArrayList<>(stated);
        ordered.sort(Comparator.comparing(StatedDisclosure::check, Comparator.nullsFirst(Comparator.naturalOrder())));
        StringBuilder text = new StringBuilder();
        ordered.forEach(disclosure -> text.append(disclosure.check()).append('#').append(disclosure.attempt()).append('=').append(disclosure.reason()).append('\n'));
        return text.toString();
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an accounting period command runs inside a correlation scope"))
                .correlationId();
    }
}
