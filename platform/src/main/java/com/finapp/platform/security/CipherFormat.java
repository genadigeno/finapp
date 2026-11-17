package com.finapp.platform.security;

/**
 * Whether a stored ciphertext was bound to its row's associated data (`X-TSK-010`), recorded beside it as its
 * {@code cipher_format} column so a read never guesses.
 *
 * <p>{@link #UNBOUND} is every ciphertext written before `X-TSK-010`, and every one written while
 * {@code finapp.ciphers.bind-associated-data} is off: a field cipher bound nothing, so a ciphertext the key genuinely wrote,
 * copied into another row, decrypted cleanly there. {@link #BOUND} is a ciphertext whose AES-GCM tag authenticates the
 * row's {@link AssociatedData} with it: moved anywhere else, it fails to decrypt.
 *
 * <p>The format is not a downgrade path. A bound ciphertext read as unbound fails its tag exactly as one read under the
 * wrong row does, so rewriting the column from 2 to 1 opens nothing.
 */
public enum CipherFormat {

    /** Written without associated data: the format of every row before `X-TSK-010`. */
    UNBOUND(1),

    /** Written with the row's associated data, which every read must present again. */
    BOUND(2);

    private final int code;

    CipherFormat(int code) {
        this.code = code;
    }

    /** The value its {@code cipher_format} column holds. */
    public int code() {
        return code;
    }

    /** The format a stored {@code cipher_format} names; any other value is refused, never read as a default. */
    public static CipherFormat of(int code) {
        for (CipherFormat format : values()) {
            if (format.code == code) {
                return format;
            }
        }
        throw new IllegalArgumentException("unknown cipher format " + code);
    }
}
