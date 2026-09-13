package com.finapp.consent;

import java.util.Objects;

/**
 * No current basis exists for a purpose a capability requires (`P2-TSK-019`, {@code INV-CNS-01}).
 *
 * <p>Carries the purpose and <strong>deliberately not the cause</strong>: absence, withdrawal
 * and a stale grant under a re-consent-demanding version are one refusal, indistinguishable to
 * every caller — a message that said which would hand consumers the distinction the derivation
 * exists to withhold. The remedy is the same either way: the person grants against the current
 * text.
 *
 * <p>No error code yet, deliberately: codes are declared by the surface that shapes them
 * (`P1-TSK-003`'s deliberately-few licence), and this task has no HTTP surface —
 * `P2-TSK-006`'s endpoint maps this to its client refusal.
 */
public class ConsentNotGrantedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // An enum is serializable, so the diagnostic survives serialization - the P1-TSK-009
    // lesson (diagnostic state marked transient came back null after a round trip).
    private final ConsentPurpose purpose;

    public ConsentNotGrantedException(ConsentPurpose purpose) {
        super("No current consent basis for purpose " + purpose);
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
    }

    /** The purpose the capability required. */
    public ConsentPurpose purpose() {
        return purpose;
    }
}
