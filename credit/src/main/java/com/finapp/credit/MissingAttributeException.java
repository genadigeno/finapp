package com.finapp.credit;

import java.io.Serial;

/**
 * A read of an attribute the snapshot does not hold (`P10-TSK-008`, {@code INV-CRD-07}): an evaluation error, never a
 * default. An attribute that is {@code ABSENT} is held - as a value the policy reasons about; this is a code the
 * snapshot was never given.
 */
public final class MissingAttributeException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    MissingAttributeException(CreditAttributeCode code) {
        super("the snapshot holds no attribute " + code + " - an evaluation error, never a default");
    }
}
