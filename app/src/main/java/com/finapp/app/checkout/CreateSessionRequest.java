package com.finapp.app.checkout;

import com.finapp.checkout.CheckoutSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * The body opening a checkout session (`P6-TSK-007`).
 *
 * <p>The line-summary bound is {@link CheckoutSession#MAX_LINE_SUMMARY_LENGTH}'s boundary copy
 * — annotation, aggregate and `V002`'s {@code CHECK} are one bound in three reconciled places
 * (the {@code P1-TSK-028} finding: a boundary wider than the last write fails as our
 * {@code 500} after validation already passed).
 *
 * <p><strong>The merchant is not in the body</strong>, deliberately: it comes from the
 * authenticated API key. A merchant a caller can name is not a tenant
 * ({@code INV-MER-01}, ADR-0031's defect at the multi-tenant boundary).
 *
 * <p>The line summary is display data about <em>what one person is buying</em> — classified
 * {@code RESTRICTED-PII} at its column for that reason, and never echoed into an audit record
 * or an event.
 */
public record CreateSessionRequest(
        @NotNull @Positive Long amountMinor,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Size(max = CheckoutSession.MAX_LINE_SUMMARY_LENGTH) String lineSummary) {}
