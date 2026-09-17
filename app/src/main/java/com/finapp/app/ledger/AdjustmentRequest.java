package com.finapp.app.ledger;

import com.finapp.ledger.Direction;
import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * The adjustment request body (`P3-TSK-017`).
 *
 * <p><strong>The reason is required and bounded by {@link AuditRecord#MAX_REASON_LENGTH}</strong>
 * — the boundary copy of a bound that lives in three reconciled places (this annotation,
 * `V004`'s {@code CHECK}, {@code AuditRecord} itself), because a boundary wider than the last
 * write would fail as our {@code 500} after the entry was already validated
 * (the {@code P1-TSK-028} finding). {@code AdjustmentRequestTest} holds the copies together.
 *
 * <p><strong>Amounts are decimal strings</strong> — the first monetary values to ever
 * <em>enter</em> this platform over HTTP, and {@code INV-MON-01}'s reasoning does not stop at
 * our own boundary in either direction: a JSON number is a {@code double} in every careless
 * client, so the wire carries {@code "12.50"} and the platform parses it <em>exactly</em> —
 * an amount not representable at the currency's scale is the caller's {@code 422}, never a
 * rounding ({@code INV-MON-03}: an adjustment states its amount exactly or not at all).
 *
 * <p>Carries no secret and no PII — the reason is free prose bound for the reason columns
 * ({@code RESTRICTED-FINANCIAL}), named in {@code CredentialReachesNoEmittedSinkTest}'s
 * pinned set.
 */
public record AdjustmentRequest(
        @NotNull LocalDate postingDate,
        @NotNull LocalDate valueDate,
        @NotBlank @Size(max = MAX_REFERENCE_LENGTH) String reference,
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason,
        @NotEmpty List<@Valid Line> lines) {

    /** `V004`'s own bound on {@code journal_entry.reference}; the parity is pinned by test. */
    public static final int MAX_REFERENCE_LENGTH = 200;

    /** One line: account, side, and the money as an exact decimal string. */
    public record Line(
            @NotBlank String accountId,
            @NotNull Direction direction,
            @NotBlank String amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}
}
