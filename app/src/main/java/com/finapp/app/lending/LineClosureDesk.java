package com.finapp.app.lending;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LineClosureRequest;
import com.finapp.lending.LineClosures;
import com.finapp.lending.LoanClosure;
import com.finapp.lending.LoanId;
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
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the closure door (`P11-TSK-023`; {@code PHASE_11_PLAN.md} sections 5.3, 9 and 11, A19, ADR-0100
 * point 8): {@code POST /v1/me/loans/{id}/closure}, keyed per principal ({@code lending.closure:CUSTOMER:<id>}), run in one
 * transaction under the {@link IdempotentExecutor} claim - a retried key replays the recorded receipt, and a refusal, which
 * writes nothing, rolls the claim back with it.
 *
 * <p><strong>A closure requires a {@code MULTI_FACTOR} session, always</strong> (A19, plan section 11): it ends the line's
 * promise for good - a closing line is never reopened. <strong>No operator can close a customer's line</strong>: the door
 * is {@code /v1/me}, the line the session's own party's (owner-scoped in the lock's statement). No body: the line is the
 * path's, and what the request does is the domain's to decide (closed at once when nothing is outstanding, otherwise
 * {@code CLOSING}).
 */
@RequiredArgsConstructor
public final class LineClosureDesk {

    static final String CLOSURE_SCOPE = "lending.closure:";

    @NonNull private final LineClosures closures;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;

    /**
     * What a closure request answers - and replays: the request, its outcome at the request ({@code CLOSING} or
     * {@code CLOSED}) and, when it closed the line, the closure's reason and the credit balance refunded to the wallet.
     */
    public record LineClosureView(
            String requestId,
            String loanId,
            String outcome,
            String requestedAt,
            LineClosureClosedView closure) {}

    /** The closure a request brought: its reason and the credit balance refunded, an exact decimal. */
    public record LineClosureClosedView(String reason, String refunded) {}

    /** Requests the closure of the caller's own line: the request, any closure and its events in one transaction. */
    public LineClosureView request(Session current, String rawLoanId, String idempotencyKey) {
        LoanId id = loanId(rawLoanId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(CLOSURE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.closure",
                id.value().toString()).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireAssurance(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    LineClosures.Requested requested =
                            closures.request(claimed, id, party, idempotencyKey, actor, correlation);
                    return CommandResult.succeeded(stored(view(requested.request(), requested.closure())));
                });
            });
        } catch (LineClosures.LineNotFound absent) {
            throw notFound();
        } catch (LineClosures.LineNotClosable notClosable) {
            throw new ApiException(LendingErrorCode.LINE_NOT_CLOSABLE, "The account cannot be closed by request",
                    "an instalment loan closes when it is repaid; nothing was done.");
        } catch (LineClosures.LoanClosed closed) {
            throw new ApiException(LendingErrorCode.LOAN_CLOSED, "The line is closed", "the line is already closed;"
                    + " nothing was done.");
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a closure request stores its receipt")));
    }

    // ------------------------------------------------------------------ the view and the receipt

    private static LineClosureView view(LineClosureRequest request, Optional<LoanClosure> closure) {
        return new LineClosureView(request.id().toString(), request.loan().value().toString(), request.outcome().name(),
                request.requestedAt().toString(),
                closure.map(closing -> new LineClosureClosedView(closing.reason().name(),
                        closing.refund().toBigDecimal().toPlainString())).orElse(null));
    }

    private static StoredResponse stored(LineClosureView view) {
        String body = String.join("|", "OK", view.requestId(), view.loanId(), view.outcome(), view.requestedAt(),
                view.closure() == null ? "" : view.closure().reason() + "," + view.closure().refunded());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static LineClosureView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        LineClosureClosedView closure = null;
        if (!fields[5].isEmpty()) {
            String[] parts = fields[5].split(",", -1);
            closure = new LineClosureClosedView(parts[0], parts[1]);
        }
        return new LineClosureView(fields[1], fields[2], fields[3], fields[4], closure);
    }

    // ------------------------------------------------------------------ plumbing

    /** A closure's assurance (A19): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private static void requireAssurance(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A credit line closure requires a MULTI_FACTOR session");
        }
    }

    private static LoanId loanId(String raw) {
        try {
            return LoanId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan matches the requested identifier", "no such loan.");
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a loan command runs inside a correlation scope"))
                .correlationId();
    }
}
