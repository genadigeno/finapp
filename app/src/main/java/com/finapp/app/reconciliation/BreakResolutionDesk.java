package com.finapp.app.reconciliation;

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
import com.finapp.reconciliation.BreakCaseFile;
import com.finapp.reconciliation.ReconciliationErrorCode;
import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.ResolutionMachine;
import com.finapp.reconciliation.ResolutionReasonCode;
import com.finapp.sharedkernel.correlation.Correlation;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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
 * transaction in reconciliation's {@link ResolutionMachine}, all under
 * {@code RECONCILIATION_RESOLVE} at the door.
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

    /** A proposal's receipt — identifiers, the kind and state, never the narrative or amount. */
    public record ResolutionReceipt(
            String resolutionId,
            String breakId,
            String kind,
            String status,
            boolean fourEyes,
            String adjustmentProposalId,
            String proposedAt) {}

    /** A decision's outcome; {@code journalEntryId} for an approval that posted. */
    public record ResolutionDecision(
            String resolutionId, String breakId, String status, String journalEntryId) {}

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
                        PROPOSE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
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
                guarded(
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
                                                        })));
        String[] fields = recorded(outcome);
        return new ResolutionReceipt(
                fields[0], fields[1], fields[2], fields[3], Boolean.parseBoolean(fields[4]),
                fields[5].isEmpty() ? null : fields[5], fields[6]);
    }

    public ResolutionDecision approve(String rawResolutionId) {
        UUID resolutionId = parsed(rawResolutionId, ReconciliationErrorCode.RESOLUTION_NOT_FOUND);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return view(guarded(() -> command(uow -> machine.approve(
                uow, resolutionId, actor, correlation.correlationId()))));
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
        return view(guarded(() -> command(uow -> machine.reject(
                uow, resolutionId, text, actor, correlation.correlationId()))));
    }

    public ResolutionDecision withdraw(String rawResolutionId) {
        UUID resolutionId = parsed(rawResolutionId, ReconciliationErrorCode.RESOLUTION_NOT_FOUND);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return view(guarded(() -> command(uow -> machine.withdraw(
                uow, resolutionId, actor, correlation.correlationId()))));
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
                decided.status().name(),
                decided.journalEntryId().map(UUID::toString).orElse(null));
    }

    // ================================================================== plumbing

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
        } catch (ResolutionMachine.ResolutionRefused shape) {
            throw invalid(shape.getMessage());
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
