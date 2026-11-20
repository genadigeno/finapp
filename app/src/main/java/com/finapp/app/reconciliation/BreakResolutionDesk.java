package com.finapp.app.reconciliation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisions;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.BatchRepudiations;
import com.finapp.reconciliation.BreakCaseFile;
import com.finapp.reconciliation.ReconciliationErrorCode;
import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.ResolutionMachine;
import com.finapp.reconciliation.ResolutionReasonCode;
import com.finapp.reconciliation.ResolutionStore;
import com.finapp.sharedkernel.correlation.Correlation;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The resolver's desk (`P8-TSK-015`, ADR-0071): propose, approve, reject and withdraw — each ONE
 * transaction, all under {@code RECONCILIATION_RESOLVE} at the door. A break-subject resolution
 * runs in reconciliation's {@link ResolutionMachine}; a settlement batch's repudiation
 * ({@code REPUDIATE_BATCH}, `P8-TSK-023`) is proposed through its own door and decided through
 * the same three, routed by the resolution's subject to {@link BatchRepudiations}. *(Corrected
 * 2026-10-01, `P8-DOC-001`: this named the resolution machine alone.)*
 *
 * <p>A proposal is keyed per principal from birth ({@code reconciliation.resolve:<actorType>:
 * <actorId>}, the `X-TSK-003` disposition): the shape screen runs BEFORE the claim (a refused
 * request stores nothing, not even a claim), the state and the templates inside it (a refusal
 * rolls the claim back; a replay of a success replays byte for byte). <strong>The stored
 * receipt never carries the narrative</strong> — the idempotency record would otherwise be a
 * second, unscreened home for CONFIDENTIAL text. Approval, rejection and withdrawal carry no
 * key: the machine is the idempotency, and the same person's retry converges.
 */
@RequiredArgsConstructor
public class BreakResolutionDesk {

    static final String PROPOSE_SCOPE = "reconciliation.resolve:";

    @NonNull private final ResolutionMachine machine;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate reconciliationTransactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final BatchRepudiations repudiations;
    @NonNull private final com.finapp.platform.telemetry.Spans spans;

    /** The approver's read, one {@code REPEATABLE READ} snapshot (the Phase 8 -> 9 transition). */
    @NonNull private final TransactionTemplate reconciliationSnapshotReads;

    /** A ledger account as the approver reads it: what it is and whose. */
    public record ProposalAccountView(
            String accountId, String purpose, String ownerRef, String currency, String status) {}

    /** One frozen proposal line, as it will post. */
    public record ProposalLineView(
            ProposalAccountView account, String direction, String amount, String currency) {}

    /** An offset's partner suspense item, as stored. */
    public record OffsetItemView(
            String suspenseItemId,
            String breakId,
            String externalItemId,
            String side,
            String status,
            String unreleased,
            String currency) {}

    /** A manual match's chosen candidate, as stored. */
    public record ChosenExpectationView(
            String expectationId,
            String kind,
            String operationRef,
            String direction,
            String status,
            String remainder,
            String currency) {}

    /**
     * What an approver reads before approving (the Phase 8 -> 9 transition, SEC-01): every
     * operand rendered and the frozen ledger lines, account by account - the same rows the
     * approval posts from. The narrative is CONFIDENTIAL, served only to a RESOLVE holder.
     */
    public record ResolutionDetail(
            String resolutionId,
            String breakId,
            String kind,
            String status,
            String reasonCode,
            String narrative,
            boolean fourEyes,
            String amount,
            String currency,
            long residualVersion,
            ProposalAccountView targetAccount,
            OffsetItemView offsetItem,
            ChosenExpectationView chosenExpectation,
            List<ProposalLineView> lines,
            String adjustmentProposalId,
            String journalEntryId,
            String proposedBy,
            String proposedAt,
            String decidedBy) {}

    /** A proposal's receipt — identifiers, the kind and state, never the narrative or amount. */
    public record ResolutionReceipt(
            String resolutionId,
            String breakId,
            String kind,
            String status,
            boolean fourEyes,
            String adjustmentProposalId,
            String proposedAt) {}

    /**
     * A decision's outcome; {@code journalEntryId} for an approval that posted. A break's
     * resolution names its break; a batch's repudiation names its settlement batch
     * (`P8-TSK-023`) - exactly one of the two.
     */
    public record ResolutionDecision(
            String resolutionId,
            String breakId,
            String settlementBatchId,
            String status,
            String journalEntryId) {}

    /**
     * A repudiation proposal's receipt: identifiers, the state and what the approval will
     * do, in counts - never the narrative or an amount.
     */
    public record RepudiationReceipt(
            String resolutionId,
            String settlementBatchId,
            String status,
            int items,
            int counterAllocations,
            int reopenedItems,
            int unparks,
            int answered,
            int breaksClosed,
            boolean remittanceClosed,
            boolean reversesRecognition,
            String proposedAt) {}

    /**
     * Proposes a settlement batch's repudiation (`P8-TSK-023`, ADR-0065 §10): keyed per
     * principal in the resolve scope, the narrative screened before any claim; one live
     * proposal per batch.
     */
    public RepudiationReceipt proposeRepudiation(
            String rawBatchId, String idempotencyKey, String reasonCode, String narrative) {
        UUID batchId = parsed(rawBatchId, ReconciliationErrorCode.BATCH_NOT_FOUND);
        ResolutionReasonCode code =
                enumParam("reasonCode", reasonCode, ResolutionReasonCode.values());
        String text = narrative == null ? "" : narrative;
        guarded(() -> {
            ResolutionMachine.refuseNarrative(text);
            return null;
        });
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key =
                new IdempotencyKey(
                        PROPOSE_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        String.join("|", "repudiation", batchId.toString(), code.name(), text)
                                .getBytes(StandardCharsets.UTF_8));
        Function<Connection, CommandResult> proposal =
                uow -> CommandResult.succeeded(stored(repudiationReceipt(
                        repudiations.propose(
                                uow, batchId, code, text, actor,
                                correlation.correlationId()))));
        IdempotentExecutor.ExecutionOutcome outcome =
                resolving(Map.of("batch.id", batchId.toString()), () -> guarded(
                        () -> command(unitOfWork -> executor.execute(
                                unitOfWork, key, fingerprint, proposal::apply))));
        byte[] body =
                outcome.body()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a recorded proposal always carries its"
                                                        + " receipt"));
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", 12);
        if (f.length != 12) {
            throw new IllegalStateException("a stored repudiation receipt is malformed");
        }
        return new RepudiationReceipt(
                f[0], f[1], f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                Integer.parseInt(f[5]), Integer.parseInt(f[6]), Integer.parseInt(f[7]),
                Integer.parseInt(f[8]), Boolean.parseBoolean(f[9]),
                Boolean.parseBoolean(f[10]), f[11]);
    }

    public ResolutionReceipt propose(
            String rawBreakId,
            String idempotencyKey,
            String kind,
            String reasonCode,
            String narrative,
            String targetAccountId,
            String offsetItemId,
            String chosenExpectationId) {
        UUID breakId = parsed(rawBreakId, ReconciliationErrorCode.BREAK_NOT_FOUND);
        ResolutionMachine.ProposalRequest request =
                new ResolutionMachine.ProposalRequest(
                        enumParam("kind", kind, ResolutionKind.values()),
                        enumParam("reasonCode", reasonCode, ResolutionReasonCode.values()),
                        narrative == null ? "" : narrative,
                        uuidParam("targetAccountId", targetAccountId),
                        uuidParam("offsetItemId", offsetItemId),
                        uuidParam("chosenExpectationId", chosenExpectationId));
        // The shape screen BEFORE any claim: a refused request stores nothing at all.
        guarded(() -> {
            ResolutionMachine.refuseShape(request);
            return null;
        });
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key =
                new IdempotencyKey(
                        PROPOSE_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        String.join(
                                        "|",
                                        breakId.toString(),
                                        request.kind().name(),
                                        request.reasonCode().name(),
                                        request.narrative(),
                                        String.valueOf(request.targetAccountId().orElse(null)),
                                        String.valueOf(request.offsetItemId().orElse(null)),
                                        String.valueOf(
                                                request.chosenExpectationId().orElse(null)))
                                .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                resolving(Map.of("break.id", breakId.toString()), () -> guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                executor.execute(
                                                        unitOfWork,
                                                        key,
                                                        fingerprint,
                                                        uow -> {
                                                            ResolutionMachine.Proposed proposed =
                                                                    machine.propose(
                                                                            uow, breakId,
                                                                            request, actor,
                                                                            correlation
                                                                                    .correlationId());
                                                            return CommandResult.succeeded(
                                                                    stored(receipt(proposed)));
                                                        }))));
        String[] fields = recorded(outcome);
        return new ResolutionReceipt(
                fields[0], fields[1], fields[2], fields[3], Boolean.parseBoolean(fields[4]),
                fields[5].isEmpty() ? null : fields[5], fields[6]);
    }

    public ResolutionDecision approve(String rawResolutionId) {
        return approve(rawResolutionId, null, null, null);
    }

    /**
     * Approves, the body optionally echoing the operand the approver read (the Phase 8 -> 9
     * transition, SEC-01): an echo that is not the stored operand is a {@code 409
     * ResolutionStale} with nothing written. A batch's repudiation takes no operand, so any
     * echo refuses it the same way.
     */
    public ResolutionDecision approve(
            String rawResolutionId,
            String targetAccountId,
            String offsetItemId,
            String chosenExpectationId) {
        UUID resolutionId = parsed(rawResolutionId, ReconciliationErrorCode.RESOLUTION_NOT_FOUND);
        ResolutionMachine.ApprovalEcho echo =
                new ResolutionMachine.ApprovalEcho(
                        uuidParam("targetAccountId", targetAccountId),
                        uuidParam("offsetItemId", offsetItemId),
                        uuidParam("chosenExpectationId", chosenExpectationId));
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return resolving(Map.of("resolution.id", resolutionId.toString()),
                () -> guarded(() -> command(uow -> {
                    if (repudiations.isRepudiation(uow, resolutionId)) {
                        if (!echo.equals(ResolutionMachine.ApprovalEcho.NONE)) {
                            throw refused(ReconciliationErrorCode.RESOLUTION_STALE,
                                    new IllegalArgumentException(
                                            "a batch's repudiation takes no operand: the"
                                                    + " approval names one it does not hold"));
                        }
                        return view(repudiations.approve(
                                uow, resolutionId, actor, correlation.correlationId()));
                    }
                    return view(machine.approve(
                            uow, resolutionId, echo, actor, correlation.correlationId()));
                })));
    }

    /** The approver's queue's collection name, inside its cursors. */
    static final String PENDING_QUEUE = "reconciliation.resolutions";
    static final int PENDING_DEFAULT_LIMIT = 50;
    static final int PENDING_MAXIMUM_LIMIT = 200;

    /**
     * {@code GET /resolutions} (`P17-TSK-107`, gap G-122; finapp-admin-panel ADR-C133 decision 3): a §7 page of the resolutions
     * awaiting a second person, in the pending-decision shape - oldest proposal first, the id breaking the tie. {@code status}
     * is {@code PROPOSED} or absent: the queue is what awaits a decision, and a decided resolution is read from its break. Each
     * item says whether the caller proposed it, so an approver's own proposals are told apart before the door refuses them. The
     * reason summary is the reason code; the narrative stays on the resolution's own page. One {@code REPEATABLE READ}
     * snapshot, recording nothing, as the resolution's own read.
     */
    public PendingReconciliationResolutionPage pendingResolutions(String status, String cursor, String limit) {
        if (status != null && !"PROPOSED".equals(status)) {
            throw OperatorCollection.invalid("status is PROPOSED: the queue holds what awaits a decision.");
        }
        int size = OperatorCollection.limit(limit, PENDING_DEFAULT_LIMIT, PENDING_MAXIMUM_LIMIT);
        Optional<ResolutionStore.PendingCursor> after = OperatorCollection.key(PENDING_QUEUE, cursor, 2)
                .map(key -> {
                    try {
                        return new ResolutionStore.PendingCursor(
                                Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS), UUID.fromString(key.get(1)));
                    } catch (RuntimeException unreadable) {
                        throw OperatorCollection.invalidCursor();
                    }
                });
        String caller = SecurityContext.require().id();
        List<ResolutionStore.PendingRow> rows = reconciliationSnapshotReads.execute(
                snapshot -> onConnection(uow -> machine.pending(uow, after, size + 1)));
        List<ResolutionStore.PendingRow> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            ResolutionStore.PendingRow tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(PENDING_QUEUE,
                    Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, tail.proposedAt())), tail.id().toString());
        }
        return new PendingReconciliationResolutionPage(
                served.stream().map(row -> pendingView(row, caller)).toList(), next);
    }

    static PendingReconciliationResolution pendingView(ResolutionStore.PendingRow row, String caller) {
        boolean ofABatch = row.settlementBatchId().isPresent();
        return new PendingReconciliationResolution(
                row.id().toString(),
                "reconciliation." + row.kind().name(),
                row.proposedBy(),
                row.proposedAt().toString(),
                row.reasonCode().name(),
                (ofABatch ? row.settlementBatchId() : row.breakId()).map(UUID::toString).orElseThrow(),
                ofABatch ? "SETTLEMENT_BATCH" : "BREAK",
                row.proposedBy().equals(caller));
    }

    /** The pending-decision queue's cursor collection (`P17-TSK-040`). */
    static final String PENDING_DECISIONS = "pending.reconciliation.resolutions";

    /**
     * {@code GET /v1/operator/pending-decisions/reconciliation/resolutions} (`P17-TSK-040`, gap G-140; finapp-admin-panel ADR-C133
     * decision 3): the resolutions awaiting a second person in the one pending-decision shape - oldest proposal first, the id the
     * tiebreak; {@code status} {@code PROPOSED} or absent. A break's resolution is {@code reconciliation.BreakResolution}, its
     * subject the break; a batch's repudiation {@code reconciliation.BatchRepudiation}, its subject the settlement batch - both
     * decided at {@code /v1/operator/reconciliation/resolutions/{id}}. The reason summary is the reason code; the narrative,
     * the proposer's confidential account, stays on the resolution's own page and is never selected here. One {@code REPEATABLE READ} snapshot, recording nothing, as the resolution's own
     * read and {@link #pendingResolutions}.
     */
    public PendingDecisions.PendingDecisionPage resolutionDecisions(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<ResolutionStore.PendingCursor> after = PendingDecisions.position(PENDING_DECISIONS, cursor)
                .map(position -> new ResolutionStore.PendingCursor(position.proposedAt(), position.id()));
        List<ResolutionStore.PendingRow> rows = reconciliationSnapshotReads.execute(
                snapshot -> onConnection(uow -> machine.pending(uow, after, size + 1)));
        return PendingDecisions.page(PENDING_DECISIONS,
                rows.stream().map(BreakResolutionDesk::decisionView).toList(), size);
    }

    static PendingDecisions.PendingDecision decisionView(ResolutionStore.PendingRow row) {
        boolean ofABatch = row.settlementBatchId().isPresent();
        return PendingDecisions.of(row.id(),
                ofABatch ? "reconciliation.BatchRepudiation" : "reconciliation.BreakResolution",
                row.proposedBy(), row.proposedAt(), row.reasonCode().name(),
                (ofABatch ? row.settlementBatchId() : row.breakId()).orElse(null));
    }

    /** One proposal in the approver's queue: ADR-C133's pending-decision shape, with its subject's kind and the caller's mark. */
    public record PendingReconciliationResolution(
            String id,
            String kind,
            String proposedBy,
            String proposedAt,
            String reasonSummary,
            String subjectId,
            String subjectKind,
            boolean proposedByCaller) {}

    /** A §7 page: {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingReconciliationResolutionPage(List<PendingReconciliationResolution> items, String nextCursor) {}

    /**
     * The approver's read (the Phase 8 -> 9 transition, SEC-01): the resolution with every
     * operand rendered and its frozen lines - one {@code REPEATABLE READ} snapshot, recording
     * nothing. Unknown and malformed ids are one named {@code 404}.
     */
    public ResolutionDetail view(String rawResolutionId) {
        UUID resolutionId = parsed(rawResolutionId, ReconciliationErrorCode.RESOLUTION_NOT_FOUND);
        ResolutionMachine.ProposalView read =
                reconciliationSnapshotReads.execute(
                                status -> onConnection(uow -> machine.read(uow, resolutionId)))
                        .orElseThrow(() -> notFound(ReconciliationErrorCode.RESOLUTION_NOT_FOUND));
        return new ResolutionDetail(
                read.resolutionId().toString(),
                read.breakId().map(UUID::toString).orElse(null),
                read.kind().name(),
                read.status().name(),
                read.reasonCode().name(),
                read.narrative(),
                read.fourEyes(),
                plain(read.amount()),
                read.amount().currency().code(),
                read.residualVersion(),
                read.target().map(BreakResolutionDesk::accountView).orElse(null),
                read.offsetItem()
                        .map(item -> new OffsetItemView(
                                item.suspenseItemId().toString(),
                                item.breakId().toString(),
                                item.externalItemId().map(UUID::toString).orElse(null),
                                item.side().name(),
                                item.status().name(),
                                plain(item.unreleased()),
                                item.unreleased().currency().code()))
                        .orElse(null),
                read.chosenExpectation()
                        .map(candidate -> new ChosenExpectationView(
                                candidate.expectationId().toString(),
                                candidate.kind().name(),
                                candidate.operationRef(),
                                candidate.direction().name(),
                                candidate.status().name(),
                                java.math.BigDecimal.valueOf(
                                                candidate.remainderMinor(), candidate.scale())
                                        .toPlainString(),
                                candidate.currency()))
                        .orElse(null),
                read.lines().stream()
                        .map(line -> new ProposalLineView(
                                accountView(line.account()),
                                line.direction().name(),
                                plain(line.amount()),
                                line.amount().currency().code()))
                        .toList(),
                read.adjustmentProposalId().map(UUID::toString).orElse(null),
                read.journalEntryId().map(UUID::toString).orElse(null),
                read.proposedBy(),
                read.proposedAt().toString(),
                read.decidedBy().orElse(null));
    }

    private static ProposalAccountView accountView(ResolutionMachine.AccountOperand account) {
        return new ProposalAccountView(
                account.accountId().toString(),
                account.purpose().name(),
                account.ownerRef().map(UUID::toString).orElse(null),
                account.currency().code(),
                account.status().name());
    }

    /** Minor units at the amount's own scale - exact decimal text, never a float. */
    private static String plain(com.finapp.sharedkernel.money.Money amount) {
        return java.math.BigDecimal.valueOf(amount.minorUnits(), amount.scale()).toPlainString();
    }

    public ResolutionDecision reject(String rawResolutionId, String reason) {
        UUID resolutionId = parsed(rawResolutionId, ReconciliationErrorCode.RESOLUTION_NOT_FOUND);
        String text = reason == null ? "" : reason;
        guarded(() -> {
            ResolutionMachine.refuseReason(text);
            return null;
        });
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return resolving(Map.of("resolution.id", resolutionId.toString()),
                () -> guarded(() -> command(uow -> repudiations.isRepudiation(uow, resolutionId)
                ? view(repudiations.reject(
                        uow, resolutionId, text, actor, correlation.correlationId()))
                : view(machine.reject(
                        uow, resolutionId, text, actor, correlation.correlationId())))));
    }

    public ResolutionDecision withdraw(String rawResolutionId) {
        UUID resolutionId = parsed(rawResolutionId, ReconciliationErrorCode.RESOLUTION_NOT_FOUND);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return resolving(Map.of("resolution.id", resolutionId.toString()),
                () -> guarded(() -> command(uow -> repudiations.isRepudiation(uow, resolutionId)
                ? view(repudiations.withdraw(
                        uow, resolutionId, actor, correlation.correlationId()))
                : view(machine.withdraw(
                        uow, resolutionId, actor, correlation.correlationId())))));
    }

    // ================================================================== rendering

    private static String receipt(ResolutionMachine.Proposed proposed) {
        return String.join(
                "|",
                proposed.resolutionId().toString(),
                proposed.breakId().toString(),
                proposed.kind().name(),
                proposed.status().name(),
                Boolean.toString(proposed.fourEyes()),
                proposed.adjustmentProposalId().map(UUID::toString).orElse(""),
                proposed.proposedAt().toString());
    }

    private static ResolutionDecision view(ResolutionMachine.Decided decided) {
        return new ResolutionDecision(
                decided.resolutionId().toString(),
                decided.breakId().toString(),
                null,
                decided.status().name(),
                decided.journalEntryId().map(UUID::toString).orElse(null));
    }

    private static ResolutionDecision view(BatchRepudiations.Decided decided) {
        return new ResolutionDecision(
                decided.resolutionId().toString(),
                null,
                decided.settlementBatchId().toString(),
                decided.status().name(),
                decided.journalEntryId().map(UUID::toString).orElse(null));
    }

    private static String repudiationReceipt(BatchRepudiations.Proposed proposed) {
        BatchRepudiations.Counts counts = proposed.counts();
        return String.join(
                "|",
                proposed.resolutionId().toString(),
                proposed.settlementBatchId().toString(),
                proposed.status().name(),
                Integer.toString(counts.items()),
                Integer.toString(counts.counterAllocations()),
                Integer.toString(counts.reopenedItems()),
                Integer.toString(counts.unparks()),
                Integer.toString(counts.answered()),
                Integer.toString(counts.breaksClosed()),
                Boolean.toString(counts.remittanceClosed()),
                Boolean.toString(counts.reversesRecognition()),
                proposed.proposedAt().toString());
    }

    // ================================================================== plumbing

    /** One {@code reconciliation.resolve} span per decision door (`P8-TSK-024`). */
    private <R> R resolving(Map<String, String> identifiers, Supplier<R> work) {
        return spans.within("reconciliation.resolve", identifiers, work);
    }

    /** The machine's refusals, in the API's words — never a narrative in either. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (BreakCaseFile.BreakNotFound unknown) {
            throw notFound(ReconciliationErrorCode.BREAK_NOT_FOUND);
        } catch (ResolutionMachine.ResolutionNotFound unknown) {
            throw notFound(ReconciliationErrorCode.RESOLUTION_NOT_FOUND);
        } catch (BreakCaseFile.BreakTerminal terminal) {
            throw refused(ReconciliationErrorCode.BREAK_TERMINAL, terminal);
        } catch (ResolutionMachine.ResolutionAlreadyProposed taken) {
            throw refused(ReconciliationErrorCode.RESOLUTION_ALREADY_PROPOSED, taken);
        } catch (ResolutionMachine.ResolutionNotPending decided) {
            throw refused(ReconciliationErrorCode.RESOLUTION_NOT_PENDING, decided);
        } catch (ResolutionMachine.SelfApprovalRefused self) {
            throw refused(ReconciliationErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (ResolutionMachine.NotTheProposer other) {
            throw refused(ReconciliationErrorCode.NOT_THE_PROPOSER, other);
        } catch (ResolutionMachine.ResolutionStale stale) {
            throw refused(ReconciliationErrorCode.RESOLUTION_STALE, stale);
        } catch (ResolutionMachine.RecordAlreadyMatched matched) {
            throw refused(ReconciliationErrorCode.RECORD_ALREADY_MATCHED, matched);
        } catch (ResolutionMachine.ResolutionKindNotAllowed kind) {
            throw refused(ReconciliationErrorCode.RESOLUTION_KIND_NOT_ALLOWED, kind);
        } catch (ResolutionMachine.ReasonCodeNotAllowed code) {
            throw refused(ReconciliationErrorCode.REASON_CODE_NOT_ALLOWED, code);
        } catch (ResolutionMachine.ResolutionTargetRefused target) {
            throw refused(ReconciliationErrorCode.RESOLUTION_TARGET_REFUSED, target);
        } catch (ResolutionMachine.GainNotYetEligible young) {
            throw refused(ReconciliationErrorCode.GAIN_NOT_YET_ELIGIBLE, young);
        } catch (ResolutionMachine.OperationNotTerminal waiting) {
            throw refused(ReconciliationErrorCode.OPERATION_NOT_TERMINAL, waiting);
        } catch (ResolutionMachine.ReturnAlreadyAttributed attributed) {
            throw refused(ReconciliationErrorCode.RETURN_ALREADY_ATTRIBUTED, attributed);
        } catch (ResolutionMachine.ResolutionRefused shape) {
            throw invalid(shape.getMessage());
        } catch (BatchRepudiations.BatchNotFound unknown) {
            throw notFound(ReconciliationErrorCode.BATCH_NOT_FOUND);
        } catch (BatchRepudiations.BatchNotRepudiable accepted) {
            throw refused(ReconciliationErrorCode.BATCH_NOT_REPUDIABLE, accepted);
        } catch (BatchRepudiations.BatchNotDisposed pending) {
            throw refused(ReconciliationErrorCode.BATCH_NOT_DISPOSED, pending);
        } catch (BatchRepudiations.RepudiationNotSupported shape) {
            throw refused(ReconciliationErrorCode.REPUDIATION_NOT_SUPPORTED, shape);
        }
    }

    private static ApiException refused(ReconciliationErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A break resolution command was refused", cause.getMessage());
    }

    private static StoredResponse stored(String joined) {
        return StoredResponse.of(joined.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static String[] recorded(IdempotentExecutor.ExecutionOutcome outcome) {
        byte[] body =
                outcome.body()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a recorded proposal always carries its"
                                                        + " receipt"));
        String[] split = new String(body, StandardCharsets.UTF_8).split("\\|", 7);
        if (split.length != 7) {
            throw new IllegalStateException("a stored proposal receipt is malformed");
        }
        return split;
    }

    /** A closed-vocabulary parameter: the enum's own constants, named when refused. */
    private static <E> E enumParam(String name, String value, E[] allowed) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
        for (E candidate : allowed) {
            if (candidate.toString().equals(value)) {
                return candidate;
            }
        }
        throw invalid(name + " must be one of " + java.util.Arrays.toString(allowed));
    }

    private static Optional<UUID> uuidParam(String name, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException malformed) {
            throw invalid(name + " must be an identifier (a UUID)");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(
                PlatformErrorCode.VALIDATION_FAILED,
                "A break resolution request was refused",
                detail);
    }

    /** Unknown and malformed ids are ONE answer, recording nothing. */
    private static UUID parsed(String id, ReconciliationErrorCode absent) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException malformed) {
            throw notFound(absent);
        }
    }

    private static ApiException notFound(ReconciliationErrorCode code) {
        return new ApiException(
                code,
                "No reconciliation record matches the requested identifier",
                "no such record.");
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a reconciliation command must run inside a correlation"
                                                + " scope"));
    }

    private <R> R command(Function<Connection, R> work) {
        return reconciliationTransactions.execute(status -> onConnection(work));
    }

    private <R> R onConnection(Function<Connection, R> work) {
        Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
        try {
            return work.apply(unitOfWork);
        } finally {
            DataSourceUtils.releaseConnection(unitOfWork, dataSource);
        }
    }
}
