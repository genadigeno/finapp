package com.finapp.app.payments;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * The body creating an immutable routing policy version (`P7-TSK-003`, ADR-0060 §1): the
 * ordered rules, each with its matchers and its ordered candidate rails.
 *
 * <p><strong>{@code effectiveFrom} is optional, and omitting it means immediately</strong> —
 * the fee schedule's recorded argument verbatim: the server stamps creation, `V013` refuses
 * a version effective before it, and a "now" the caller computed is already past by
 * arrival, so letting the server resolve it is the only exact way to say it.
 *
 * <p><strong>The ceiling is minor units of the rule's own currency</strong>, which a bounded
 * rule must therefore name (an amount without a currency is not a number, {@code INV-MON});
 * the scale is the currency's own, so the caller cannot state one that disagrees with what
 * the platform mints.
 *
 * <p>The reason is required and bounded ({@code INV-AUD-03}): changing how money travels is
 * an operational judgement. Enumerated fields arrive as strings and are parsed at the
 * boundary — an unknown direction, kind or rail is the caller's 422, never our 500.
 */
public record CreateRoutingPolicyVersionRequest(
        @NotEmpty @Size(max = 50) @Valid List<RuleBody> rules,
        Instant effectiveFrom,
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {

    /** One rule: matchers and ordered candidates, positions taken from list order. */
    public record RuleBody(
            @NotBlank @Size(max = 20) String direction,
            @NotBlank @Size(max = 30) String instrumentKind,
            @Size(min = 3, max = 3) String currency,
            @Positive Long ceilingAmountMinor,
            @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 32) String> rails,
            Boolean requiresDestinationCountry) {

        /** A rule matching regardless of a destination country - the body before `P9-TSK-019`. */
        public RuleBody(String direction, String instrumentKind, String currency, Long ceilingAmountMinor, List<String> rails) {
            this(direction, instrumentKind, currency, ceilingAmountMinor, rails, null);
        }
    }
}
