package com.finapp.app.reconciliation;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reconciliation controller's own door (`P8-TSK-007`, ADR-0067 §8): the opening-position
 * backfill, under {@link PermissionName#RECONCILIATION_ADMINISTER} — a reasoned, keyed,
 * audited act. Handler names are deliberately distinctive (the springdoc {@code operationId}
 * rule, {@code OpenApiContractTest}).
 */
@RestController
@RequestMapping(
        path = "/operator/reconciliation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationOperationsController {

    @NonNull private final OpeningPosition openingPosition;

    /** The backfill's ask: the controller's reason, audited with the record. */
    public record OpeningPositionRequest(
            @NotBlank @Size(min = 1, max = 1000) String reason) {}

    /**
     * Adopts the opening position: every completed clearing operation history left behind
     * opens its expectation through the live recorder's own path, converging on the
     * register's uniques — a re-run, another key or another instance adds nothing. Returns
     * the recorded counts; a replay of the same principal's key answers them byte for byte.
     */
    @PostMapping(path = "/opening-position", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    @RequiresIdempotencyKey
    public OpeningPosition.Adopted recordOpeningPosition(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody OpeningPositionRequest body) {
        return openingPosition.record(idempotencyKey, body.reason());
    }
}
