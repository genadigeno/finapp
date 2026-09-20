package com.finapp.payments;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The <strong>provider's own</strong> identifier for an operation it performed (`P5-TSK-003`).
 *
 * <p>Distinct from {@link ProviderIdempotencyReference} on purpose, because the two answer
 * different questions: ours identifies the operation <em>we asked for</em> and exists before the
 * provider is ever reached; this one identifies the operation <em>the provider performed</em>
 * and exists only once an answer arrived. A capture presents the authorization's provider
 * reference; a refund presents the capture's; Phase 8 reconciles settlement files against both.
 * `P5-TSK-008` stores it unique per provider when present.
 *
 * <p><strong>The charset is bounded even though the value is foreign.</strong> This identifier
 * is stored, printed in diagnostics and re-presented on a wire this platform builds, so
 * admitting arbitrary bytes would mean escaping machinery in every one of those places and a
 * forged-log-line channel in each we forget. {@code [A-Za-z0-9_.:-]} covers every real PSP id
 * shape ({@code pi_3Nx...}, {@code ch_1AbC...}); a provider whose identifiers exceed it is an
 * adapter-level mapping concern, exactly where `INV-PAY-03` puts provider vocabulary. An answer
 * whose reference does not fit is treated as unparseable — indeterminate, evidence retained —
 * never truncated into a different identifier.
 */
public record ProviderReference(String value) {

    /** Bounded because the value becomes a wire field and a unique column. */
    public static final int MAX_LENGTH = 128;

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_LENGTH + "}");

    public ProviderReference {
        Objects.requireNonNull(value, "the provider reference must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a provider reference must be 1-"
                            + MAX_LENGTH
                            + " characters of [A-Za-z0-9_.:-]");
        }
    }
}
