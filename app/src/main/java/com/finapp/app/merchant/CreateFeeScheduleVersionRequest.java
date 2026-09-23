package com.finapp.app.merchant;

import com.finapp.platform.audit.AuditRecord;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The body creating an immutable fee schedule version (`P6-TSK-004`, ADR-0050 §4/§5).
 *
 * <p><strong>The rate arrives as a {@link BigDecimal}, never a {@code double}</strong>
 * ({@code INV-MON-01}): Jackson binds JSON numbers to {@code BigDecimal} exactly for this
 * field's declared type, so {@code 0.029} stays {@code 0.029} rather than becoming
 * {@code 0.028999999999999998}. A rate bound through a {@code double} would misprice every
 * capture very slightly, in a direction nobody chose.
 *
 * <p>The bounds here are {@code FeeRate}'s boundary copies — {@code [0, 1)} and at most
 * {@code FeeRate.MAX_SCALE} decimal places — so a mistyped decimal point is a {@code 422} at
 * the door rather than an exception from inside the domain.
 *
 * <p>The fixed part is minor units of the schedule's own currency, which the request therefore
 * does not name: a version prices in its schedule's currency, held by `V004`'s composite
 * foreign key, so letting a caller state a currency here would create a value that can only
 * ever be wrong or redundant.
 *
 * <p><strong>{@code effectiveFrom} is optional, and omitting it means immediately.</strong>
 * That is not a convenience: it is the <em>only</em> way a caller can say "now". The server
 * stamps {@code createdAt} when the request arrives, and `V004` refuses any version whose
 * {@code effectiveFrom} precedes it, so a "now" the caller computed before the round trip is
 * already past and is correctly refused as a backdating. Letting the server resolve it is
 * exact; a tolerance window would put a fudge factor inside {@code INV-MER-03}.
 *
 * <p>The reason is required and bounded by {@link AuditRecord#MAX_REASON_LENGTH}
 * ({@code INV-AUD-03}): a price change is a commercial judgement.
 */
public record CreateFeeScheduleVersionRequest(
        @NotNull
                @DecimalMin(value = "0", message = "a fee rate must not be negative")
                @DecimalMax(
                        value = "1",
                        inclusive = false,
                        message = "a fee rate of 1 or more leaves the merchant nothing")
                @Digits(integer = 1, fraction = 6)
                BigDecimal rate,
        @NotNull @PositiveOrZero Long fixedAmountMinor,
        @NotBlank @Size(max = 50) String roundingPolicy,
        @NotBlank @Size(max = 50) String refundFeePolicy,
        Instant effectiveFrom,
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
