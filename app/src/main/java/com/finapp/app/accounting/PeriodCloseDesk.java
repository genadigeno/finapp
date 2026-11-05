package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.PeriodCloseId;
import com.finapp.accounting.PeriodCloses;
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
 */
@RequiredArgsConstructor
public final class PeriodCloseDesk {

    static final String CLOSE_PROPOSE_SPAN = "accounting.period.close.propose";
    static final String CLOSE_APPROVE_SPAN = "accounting.period.close.approve";
    static final String CLOSE_REJECT_SPAN = "accounting.period.close.reject";
    static final String CLOSE_READ_SPAN = "accounting.period.close.read";
    static final String REOPEN_PROPOSE_SPAN = "accounting.period.reopen.propose";
    static final String REOPEN_APPROVE_SPAN = "accounting.period.reopen.approve";

    /**
     * What a reopen's receipt says of its list, served and never stored: no field of this API carries a description (ADR-0015), and the person reading the receipt reads the receipt. It names the one door that lists what stands, and says who holds it, since neither of a reopen's two people does.
     */
    static final String OVERTAKEN_RETURNS_NOTE = "Information only, read while this act held the month: it stopped nothing and decides nothing, and a status may have moved since. Each return SUBMITTED or ACKNOWLEDGED for this month, newest first, at most "
            + ReturnsAReopenOvertakes.NEWEST + " of overtakenReturnsTotal. Not listed: a return not yet sent. One APPROVED can still be sent until a reopen's approval moves the month's revision, and is stale and refused after it. Nor one UNDELIVERED, which may still reach the provider late. So an empty list does not mean nothing is in flight:"
            + " GET /v1/operator/regulatory/submissions under REGULATORY_REPORT_PREPARE, which a regulatory reporter holds and neither of a reopen's two people does, lists what stands.";

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
     *     whoever corrects the month directly must know they stand. What an act that judged its naming named, since `P14-TSK-022` refuses anything else; a repeat that converged compared nothing, and this is what stands now. Empty on a close's acts.
     * @param overtakenReturns on a reopen's two acts, the returns {@code SUBMITTED} or {@code ACKNOWLEDGED} for the month, newest first, at most {@link ReturnsAReopenOvertakes#NEWEST} (`P14-TSK-023`). As read at that act: a list, never a gate, and {@link #overtakenReturnsNote} says what it does not hold. A month with none reads an empty list. <strong>Null where no list was read</strong>: on a close's acts, and on a receipt a reopen's key stored before `P14-TSK-023`, so "read, and none" and "never read" never look alike (the fix round's M-6).
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

    /** One check of a proposal as the approver reads it - no person. */
    public record CloseProposalCheckView(String check, int attempt, String status, String judgedBy, String evidence, String disclosureReason) {

        /** Served to the approver and never logged: a record's generated {@code toString} would carry the reason into a log line. */
        @Override
        public String toString() {
            return "CloseProposalCheckView[" + check + "#" + attempt + ", " + status + "]";
        }
    }

    /**
     * A period's latest close proposal. {@code stillStands} when the approval would find what it read still standing; when false the way on is to reject and propose again.
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
            List<String> priorPeriodAdjustments) {}

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
        return flow.door(REOPEN_PROPOSE_SPAN, () -> keyed(actor, idempotencyKey, reopenFingerprint("reopen-proposal", id, named, reason),
                uow -> toldOfTheMonth(uow, receipt(closes.proposeReopen(uow, id, actor, named, reason, correlation)), id)));
    }

    public PeriodCloseReceipt approveReopen(String idempotencyKey, String rawId, List<String> rawPosted, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Set<UUID> named = namedAdjustments(rawPosted);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REOPEN_APPROVE_SPAN, () -> keyed(actor, idempotencyKey, reopenFingerprint("reopen-approval", id, named, reason),
                uow -> toldOfTheMonth(uow, receipt(closes.approveReopen(uow, id, actor, named, reason, correlation)), id)));
    }

    /**
     * A reopen's fingerprint: the same key naming another set is the executor's conflict. A body naming none reads exactly as one did before `P14-TSK-022`, so a retry across the deploy replays its receipt; one naming some has a prefix of its own, so the two forms can never be the same text whatever a reason holds.
     */
    static String reopenFingerprint(String act, AccountingPeriodId id, Set<UUID> named, String reason) {
        return named.isEmpty() ? act + "|" + id.value() + "|" + reason : act + "-named|" + id.value() + "|" + new TreeSet<>(named) + "|" + reason;
    }

    /** The period's latest proposal, as its approver reads it; a period with none is {@code accounting.NotFound}. Recorded before it is served. */
    public CloseProposalView proposal(String rawId) {
        AccountingPeriodId id = periodId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        record Read(PeriodCloses.ProposalView proposal, List<UUID> posted) {}
        Read read = flow.door(CLOSE_READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow ->
                new Read(closes.proposalOf(uow, id).orElseThrow(PeriodCloseDesk::notFound), priorPeriodAdjustments.postedAgainst(uow, id)))));
        transactions.inTransaction(uow -> {
            closes.recordRead(uow, read.proposal(), actor, correlation);
            return null;
        });
        return view(read.proposal(), read.posted());
    }

    // ------------------------------------------------------------------ views

    private static CloseProposalView view(PeriodCloses.ProposalView view, List<UUID> posted) {
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
                        check.attemptEvidence().orElse(null),
                        check.check().disclosureReason().orElse(null))).toList(),
                posted.stream().map(UUID::toString).toList());
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
                priorPeriodAdjustments.postedAgainst(unitOfWork, id).stream().map(UUID::toString).toList(),
                read.newest().stream().map(item -> new OvertakenReturn(item.id().value().toString(), item.form(), item.status().name())).toList(), read.total(), OVERTAKEN_RETURNS_NOTE);
    }

    // ------------------------------------------------------------------ plumbing

    /** One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing; a self-approval is recorded {@code FAILED} in a transaction of its own. */
    private PeriodCloseReceipt keyed(Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, PeriodCloseReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(AccountingPeriodDesk.SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
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

    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingPeriods.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                closes.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
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
