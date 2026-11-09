package com.finapp.platform.security;

/**
 * A row names a key version its ring does not hold (`P15-TSK-019`, ADR-0123 point 4): unknown, or retired.
 *
 * <p>A distinct class, so that a caller that treats an {@link IllegalStateException} as "failed authenticated decryption"
 * can tell it from one, while a caller that only needs "unreadable" still catches it: a ciphertext is never tried under
 * another version's key to find out. Whoever raises it chooses the message: the ring names itself and the number, a cipher
 * passes its own generic refusal so that nothing about the row reaches a caller. The number, here and in the ring's log line,
 * is never a key.
 */
public final class UnknownKeyVersionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final int stampedVersion;

    public UnknownKeyVersionException(String message, int stampedVersion) {
        super(message);
        this.stampedVersion = stampedVersion;
    }

    public int stampedVersion() {
        return stampedVersion;
    }
}
