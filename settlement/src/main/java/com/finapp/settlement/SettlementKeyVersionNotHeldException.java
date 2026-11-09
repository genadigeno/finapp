package com.finapp.settlement;

import java.util.UUID;

/**
 * A settlement file names a key version this instance's ring does not hold (`P15-TSK-019`, ADR-0123 point 4).
 *
 * <p>Its own type, because nothing is wrong with the file: its bytes were not read, so they cannot have failed anything. A
 * caller that treats {@link SettlementStorageException} as tampering records {@code CORRUPT} for ever on an intact file if it
 * catches this too, so the operator reads catch this first. The version is a number, never key material.
 */
public final class SettlementKeyVersionNotHeldException extends SettlementStorageException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final int keyVersion;

    public SettlementKeyVersionNotHeldException(UUID fileId, int keyVersion) {
        super("settlement file " + fileId + " is stamped with key version " + keyVersion
                + ", which this instance's ring does not hold: nothing is served, and the file is not in question");
        this.keyVersion = keyVersion;
    }

    public int keyVersion() {
        return keyVersion;
    }
}
