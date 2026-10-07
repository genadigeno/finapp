package com.finapp.app.fx;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's FX trade reversal (`P9-TSK-025`, PHASE_9_PLAN.md section 9): propose (keyed), then a different person
 * approves - executing it - or rejects; each act reasoned, each checking {@code FX_TRADE_REVERSE}. A wallet conversion
 * only; the unwind cover it causes is sent asynchronously by the cover sweep.
 */
@RestController
@RequestMapping(path = "/operator/fx/trades", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FxTradeReversalController {

    @NonNull private final FxTradeReversalDesk desk;

    /** Proposes reversing a booked conversion - keyed per principal, reasoned. */
    @PostMapping(path = "/{id}/reversal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_TRADE_REVERSE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public FxTradeReversalDesk.ReversalReceipt proposeFxTradeReversal(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody TradeReversalRequest body) {
        return desk.propose(id, idempotencyKey, body.reason());
    }

    /** Approves a pending reversal - a different person's act, executing it in one transaction. */
    @PostMapping(path = "/{id}/reversal/{rid}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_TRADE_REVERSE)
    public FxTradeReversalDesk.ReversalReceipt approveFxTradeReversal(
            @PathVariable("id") String id, @PathVariable("rid") String rid, @Valid @RequestBody TradeReversalRequest body) {
        return desk.approve(id, rid, body.reason());
    }

    /** Rejects a pending reversal - a different person's act; nothing moves. */
    @PostMapping(path = "/{id}/reversal/{rid}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_TRADE_REVERSE)
    public FxTradeReversalDesk.ReversalReceipt rejectFxTradeReversal(
            @PathVariable("id") String id, @PathVariable("rid") String rid, @Valid @RequestBody TradeReversalRequest body) {
        return desk.reject(id, rid, body.reason());
    }

    /** Every act's reason. Closed: never a rate, never an amount. */
    @ClosedBody
    public record TradeReversalRequest(@NotBlank @Size(min = 1, max = 500) String reason) {}
}
