package com.finapp.app.transfers;

import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.transfers.TransferId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The transfer surface (`P4-TSK-008`): the first customer-visible money movement over HTTP.
 *
 * <h2>The asynchronous-outcome contract shape</h2>
 *
 * <p>{@code POST} answers {@code 201} with the transfer view <strong>including status</strong>
 * — a {@code FAILED} judgement is a {@code 201} whose body says so, never an HTTP error
 * (`PHASE_4_PLAN.md` §9): the command was accepted and its domain outcome recorded, which is
 * the contract shape Phase 5's genuinely asynchronous outcomes will inherit unchanged. A
 * retried key replays the original body byte for byte ({@code INV-IDEM-01} — see
 * {@link TransferService}); a reused key with a different request is the distinct 409; a
 * keyless request is the interceptor's 422.
 *
 * <h2>Ownership</h2>
 *
 * <p>The POST takes no identifier of anything the caller owns that a stranger could point at a
 * victim — the source resolves through the caller's own live customer inside the command. The
 * GETs' one path identifier is resolved through {@code customer_id = ?} in the statement:
 * unknown, not-yours and malformed are one {@code 404} (the {@code P1-TSK-016} reasoning).
 */
@RestController
@RequestMapping(path = "/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class TransferController {

    @NonNull private final TransferService transfers;

    /**
     * Executes the caller's transfer, or replays the judgement — {@code 201} for the replay as
     * well as the execution (the convergence idiom: one intent, answered with the creation's
     * own status; what distinguishes the executing call is the records, never the answer).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public TransferService.TransferView createTransfer(
            @Valid @RequestBody TransferCreateRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        // Method names here are published operationIds (the P2-TSK-006 `view_1` lesson): a
        // second handler named like BeneficiaryController's would rename a PUBLISHED operation.
        return transfers.create(current(request), body, idempotencyKey);
    }

    /**
     * Reverses the transfer as the acting operator (`P4-TSK-009`) — the one privileged handler
     * on this surface: {@code @RequiresPermission(TRANSFER_REVERSE)} beside the class's
     * {@code @RequiresSession}, both protective, so the interceptor enforces the session AND
     * the permission (only the {@code @Unauthenticated} contradiction is refused). Answers
     * {@code 201} with the reversed view; a transfer the machine refuses is the one
     * {@code 409 transfers.NotReversible}; unknown and malformed are one {@code 404}.
     *
     * <p><strong>Deliberately no idempotency key</strong> (the `P3-TSK-021` approval
     * precedent): {@code COMPLETED -> REVERSED} happens at most once ever, so the machine is
     * the idempotency ({@code INV-IDEM-01} through state) — a retry after a lost response gets
     * the 409 naming the state, and the view carries the reversal.
     */
    @PostMapping(path = "/{id}/reversal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.TRANSFER_REVERSE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransferService.TransferView reverseTransfer(
            @PathVariable("id") String id, @Valid @RequestBody TransferReversalRequest body) {
        return transfers
                .reverse(parsedOrAbsent(id), body.reason())
                .orElseThrow(TransferController::transferNotFound);
    }

    /** The caller's transfer — its current state, the reversal included when it exists. */
    @GetMapping("/{id}")
    public TransferService.TransferView findTransfer(
            @PathVariable("id") String id, HttpServletRequest request) {
        return transfers
                .find(current(request), parsedOrAbsent(id))
                .orElseThrow(TransferController::transferNotFound);
    }

    /** The caller's transfers, newest first. */
    @GetMapping
    public List<TransferService.TransferView> listTransfers(HttpServletRequest request) {
        return transfers.list(current(request));
    }

    // -----------------------------------------------------------------

    /**
     * A malformed identifier is treated as an identifier that names nothing — one {@code 404}
     * with unknown and not-yours ({@code P1-TSK-016}; malformed-equals-absent).
     */
    private static TransferId parsedOrAbsent(String raw) {
        try {
            return TransferId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw transferNotFound();
        }
    }

    private static ApiException transferNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No transfer of the caller's matches the requested identifier",
                "no such transfer");
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/transfers is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
