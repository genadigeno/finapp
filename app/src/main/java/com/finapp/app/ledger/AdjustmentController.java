package com.finapp.app.ledger;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * `POST /v1/ledger/adjustments` (`P3-TSK-017`, {@code INV-REV-04}): a person posts a manual
 * adjusting entry — the highest-risk financial action on the platform, and the only public
 * write into the journal (plan §9: posting is an internal API; the adjustment is the one
 * exception, behind its own permission).
 *
 * <p><strong>{@code LEDGER_ADJUST} meets its first real check site</strong>, exactly as
 * `P3-TSK-007` recorded it would: the permission is the boundary's half (ADR-0031), resolved
 * per request from authoritative state, and a refusal is audited against the person who
 * attempted it. {@code @RequiresPermission} implies {@code @RequiresSession}
 * (`P1-TSK-018`'s implication, applied to permission by `P1-TSK-020`).
 *
 * <p><strong>Money-moving, so the idempotency key is required</strong>
 * ({@code @RequiresIdempotencyKey}, `P0-TSK-017`'s interceptor meeting the money it was
 * built for): a keyless request is refused before this handler is entered.
 *
 * <p><strong>Four-eyes is recorded debt, not implied</strong> ({@code INV-AUD-04}, ADR-0010):
 * no threshold triggers a second approver, and nothing here should be read as that control.
 */
@RestController
@RequestMapping(path = "/ledger/adjustments", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdjustmentController {

    private final LedgerAdjustments adjustments;

    public AdjustmentController(LedgerAdjustments adjustments) {
        this.adjustments = Objects.requireNonNull(adjustments, "adjustments must not be null");
    }

    /**
     * Posts the adjustment, or replays the recorded outcome for a retried key
     * ({@code INV-IDEM-01}); a reused key from a different operator or with a different
     * request — the reason included — is a {@code 409} conflict ({@code INV-IDEM-03}).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerAdjustments.AdjustmentView postAdjustment(
            @Valid @RequestBody AdjustmentRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return adjustments.adjust(body, idempotencyKey);
    }
}
