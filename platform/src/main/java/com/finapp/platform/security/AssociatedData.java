package com.finapp.platform.security;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The associated data a field cipher binds to a ciphertext (`X-TSK-010`, ADR-0066's settlement chunks the precedent): what
 * the ciphertext is for, the identity of the row that holds it, and every value that names the plaintext's place.
 *
 * <p>AES-GCM authenticates associated data with the ciphertext, so a ciphertext bound here and copied into another row - a
 * row with another identity, purpose or place - fails to decrypt instead of answering another row's content. Each field is
 * length-prefixed (four bytes, big-endian) and the row's identity is its sixteen bytes, so no two different places encode
 * alike: {@code ("ab", "c")} and {@code ("a", "bc")} are different associated data.
 *
 * <p>The bytes are the binding, never stored and never logged: a reader rebuilds them from the row it read.
 */
public final class AssociatedData {

    private AssociatedData() {
    }

    /**
     * @param purpose what the ciphertext is, unique per cipher and column ({@code "fx-provider-evidence"})
     * @param row the identity of the row holding the ciphertext
     * @param place every value that names where the plaintext belongs, in a fixed order per purpose
     */
    public static byte[] of(String purpose, UUID row, String... place) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(place, "place must not be null");
        if (purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must not be blank");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        field(bytes, purpose.getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(ByteBuffer.allocate(16)
                .putLong(row.getMostSignificantBits())
                .putLong(row.getLeastSignificantBits())
                .array());
        for (String value : place) {
            Objects.requireNonNull(value, "a place value must not be null");
            field(bytes, value.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void field(ByteArrayOutputStream bytes, byte[] value) {
        bytes.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
        bytes.writeBytes(value);
    }
}
