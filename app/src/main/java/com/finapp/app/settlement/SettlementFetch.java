package com.finapp.app.settlement;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementErrorCode;
import com.finapp.settlement.SettlementPull;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An operator's explicit pull (`P8-TSK-021`, ADR-0066 §1:
 * {@code POST /v1/operator/settlement/sources/{code}/fetch} under {@code SETTLEMENT_INGEST}):
 * the same {@link SettlementPull} the schedule runs, its permit renewed rather than windowed —
 * an operator's ask is never paced away, and the schedule's herd then waits its window.
 *
 * <p><strong>Natural idempotency</strong>: no key, because the content address is the arbiter —
 * a repeated fetch of the same report lands one file and answers {@code DUPLICATE}. The request
 * and what it came to are audited ({@code settlement.SettlementFetchRequested}) in their own
 * transaction after the pull; the reception, when bytes arrived, audited itself inside the door
 * ({@code settlement.SettlementFileReceivedByPull}, acting-only). An unknown or retired source
 * writes nothing; a pull that FAILED with an exception is audited {@code FAILED} naming the
 * exception's class, then rethrown — its permit may have been renewed and its door may have
 * run, so the operator's ask is never left unrecorded (the tests agent's find).
 *
 * <p><strong>The start is recorded with the fetch's first effect</strong> (the Phase 8 -> 9
 * transition, SEC-08): the fetch is three steps — the permit, the external read holding no
 * connection, the door — so no one transaction holds it whole, and a process dying past the
 * permit never reaches the closing record. {@code settlement.SettlementFetchStarted} commits in
 * the permit's own transaction, naming the operator, the source and the key; the closing record
 * then says what it came to.
 */
@RequiredArgsConstructor
public class SettlementFetch {

    @NonNull private final SettlementPull pull;
    @NonNull private final AuditWriter<java.sql.Connection> audit;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * What the fetch came to: {@code RECEIVED}, {@code DUPLICATE}, {@code REFUSED},
     * {@code NOT_YET}, {@code FAILED} or {@code NOT_PULLABLE}; the file for the first two, the
     * door's reason for a refusal, the collector's for a failure.
     */
    public record FetchAnswer(String outcome, String fileId, String reason) {}

    public FetchAnswer fetch(String sourceCode, SettlementFetchRequest request) {
        Actor actor = SecurityContext.require();
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a settlement fetch runs inside a correlation"
                                                        + " scope"));
        SettlementPull.Outcome outcome;
        try {
            outcome =
                    pull.pull(
                            sourceCode, request.businessKey(), Optional.empty(), actor,
                            correlation,
                            unitOfWork ->
                                    started(unitOfWork, actor, correlation, sourceCode,
                                            request.businessKey()));
        } catch (FileReception.SettlementSourceUnknown unknown) {
            throw new ApiException(
                    SettlementErrorCode.SOURCE_UNKNOWN,
                    "A settlement fetch named a source this build does not declare",
                    "no declared settlement source has this code.");
        } catch (FileReception.SettlementSourceRetired retired) {
            throw new ApiException(
                    SettlementErrorCode.SOURCE_RETIRED,
                    "A settlement fetch addressed a retired source",
                    "this settlement source is retired and accepts no deliveries.");
        } catch (RuntimeException failure) {
            try {
                record(actor, correlation, sourceCode, request.businessKey(),
                        AuditOutcome.FAILED,
                        // The class only: a message can name a path or a reference.
                        "ERROR:" + failure.getClass().getSimpleName());
            } catch (RuntimeException unrecorded) {
                failure.addSuppressed(unrecorded);
            }
            throw failure;
        }
        FetchAnswer answer = answerOf(outcome);
        record(actor, correlation, sourceCode, request.businessKey(), AuditOutcome.SUCCEEDED,
                answer.outcome());
        return answer;
    }

    /** The fetch's start, in the permit's transaction (SEC-08) - never a byte of the report. */
    private void started(
            java.sql.Connection unitOfWork,
            Actor actor,
            Correlation correlation,
            String sourceCode,
            String businessKey) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        SettlementAuditAction.SETTLEMENT_FETCH_STARTED,
                        "settlement_source",
                        sourceCode,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("businessKey=" + businessKey)));
    }

    /** The request and what it came to, in its own transaction - never a byte of the report. */
    private void record(
            Actor actor,
            Correlation correlation,
            String sourceCode,
            String businessKey,
            AuditOutcome result,
            String outcome) {
        transactions.inTransaction(
                unitOfWork -> {
                    audit.append(
                            unitOfWork,
                            new AuditRecord(
                                    AuditId.next(ids),
                                    actor,
                                    clock.instant(),
                                    SettlementAuditAction.SETTLEMENT_FETCH_REQUESTED,
                                    "settlement_source",
                                    sourceCode,
                                    Optional.empty(),
                                    result,
                                    correlation.correlationId(),
                                    Optional.of(
                                            "businessKey=" + businessKey
                                                    + ", outcome=" + outcome)));
                    return null;
                });
    }

    private static FetchAnswer answerOf(SettlementPull.Outcome outcome) {
        return switch (outcome) {
            case SettlementPull.Outcome.Received received ->
                    switch (received.result()) {
                        case FileReception.Result.New landed ->
                                new FetchAnswer("RECEIVED", landed.fileId().toString(), null);
                        case FileReception.Result.Duplicate standing ->
                                new FetchAnswer(
                                        "DUPLICATE", standing.existingFileId().toString(), null);
                        case FileReception.Result.Refused refused ->
                                new FetchAnswer("REFUSED", null, refused.reason().name());
                    };
            case SettlementPull.Outcome.NotYet notYet -> new FetchAnswer("NOT_YET", null, null);
            case SettlementPull.Outcome.Failed failed ->
                    new FetchAnswer("FAILED", null, failed.outcome().name());
            case SettlementPull.Outcome.NotPullable notPullable ->
                    new FetchAnswer("NOT_PULLABLE", null, null);
            // An operator's fetch renews its permit and is never paced.
            case SettlementPull.Outcome.Paced paced ->
                    throw new IllegalStateException("an operator's fetch is never paced");
        };
    }
}
