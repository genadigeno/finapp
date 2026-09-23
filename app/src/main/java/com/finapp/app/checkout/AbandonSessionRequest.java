package com.finapp.app.checkout;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body withdrawing a checkout session (`P6-TSK-008`).
 *
 * <p><strong>One field, and it is mandatory.</strong> This is the only checkout action that
 * requires a reason ({@code INV-AUD-03}), because it is the only one where a merchant makes a
 * judgement about somebody else's purchase: a customer who had a live offer finds it gone, and
 * the trail must be able to say why. The merchant's operator suspension and key revocation
 * bodies have exactly this shape, for exactly this reason.
 *
 * <p>Recorded verbatim in the audit record, which is why {@code @NotBlank} rather than
 * {@code @NotNull}: a reason nobody wrote is worse than none at all, because it makes the field
 * look answered.
 *
 * @param reason why the offer is being withdrawn, in the merchant's own words
 */
public record AbandonSessionRequest(
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
