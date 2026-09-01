package com.finapp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The comparison that {@code INV-IDEM-03} rests on, and the ways it must refuse to guess. */
class RequestFingerprintTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the same request produces the same fingerprint, a different one does not")
    void fingerprintsTrackRequestContent() {
        assertThat(RequestFingerprint.sha256(bytes("transfer:100")))
                .isEqualTo(RequestFingerprint.sha256(bytes("transfer:100")));
        assertThat(RequestFingerprint.sha256(bytes("transfer:100")))
                .isNotEqualTo(RequestFingerprint.sha256(bytes("transfer:101")));
    }

    @Test
    @DisplayName("matching is symmetric and content-based")
    void matchingIsContentBased() {
        RequestFingerprint original = RequestFingerprint.sha256(bytes("transfer:100"));
        RequestFingerprint retry = RequestFingerprint.sha256(bytes("transfer:100"));
        RequestFingerprint different = RequestFingerprint.sha256(bytes("transfer:999"));

        assertThat(original.matches(retry)).isTrue();
        assertThat(retry.matches(original)).isTrue();
        assertThat(original.matches(different)).isFalse();
    }

    @Test
    @DisplayName("comparing across algorithms refuses rather than answering 'different'")
    void mismatchedAlgorithmsRefuseToCompare() {
        // Answering "different" would be catastrophic and silent: after an algorithm change,
        // every retry of an existing claim would look like a new request and produce a second
        // financial effect. Refusing forces the migration to be handled deliberately.
        RequestFingerprint current = RequestFingerprint.sha256(bytes("transfer:100"));
        RequestFingerprint legacy = RequestFingerprint.ofStored(new byte[48], "SHA-384");

        assertThatThrownBy(() -> current.matches(legacy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256")
                .hasMessageContaining("SHA-384");
    }

    @Test
    @DisplayName("a digest outside the storable range is refused, because truncation weakens the check")
    void digestLengthIsBounded() {
        assertThatThrownBy(() -> RequestFingerprint.ofStored(new byte[16], "SHA-256"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestFingerprint.ofStored(new byte[65], "SHA-512"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RequestFingerprint.ofStored(new byte[64], "SHA-512").digest()).hasSize(64);
    }

    @Test
    @DisplayName("the digest cannot be mutated through the accessor")
    void digestIsDefensivelyCopied() {
        // A fingerprint that could be changed after it was stored decides nothing.
        RequestFingerprint fingerprint = RequestFingerprint.sha256(bytes("transfer:100"));
        byte[] handedOut = fingerprint.digest();

        handedOut[0] = (byte) ~handedOut[0];

        assertThat(fingerprint.digest()).isNotEqualTo(handedOut);
        assertThat(fingerprint.matches(RequestFingerprint.sha256(bytes("transfer:100")))).isTrue();
    }

    @Test
    @DisplayName("printing a fingerprint never discloses the digest")
    void toStringDoesNotLeakTheDigest() {
        // It is derived from request content, which can include an amount, a beneficiary or an
        // account number. INV-AUD-02: not in logs.
        RequestFingerprint fingerprint = RequestFingerprint.sha256(bytes("transfer:100"));

        assertThat(fingerprint.toString()).contains("SHA-256").contains("32 bytes");
        assertThat(fingerprint.toString()).doesNotContain(Integer.toHexString(fingerprint.digest()[0] & 0xFF));
    }

    @Test
    @DisplayName("a conflict keeps its key across serialization")
    void conflictDiagnosticsSurviveSerialization() throws Exception {
        // The P0-TSK-009 review found transient diagnostic state returning null after a round
        // trip. An error report that has lost the key it was about is not a report.
        IdempotencyKey key = new IdempotencyKey("scope", "key");
        IdempotencyConflictException original = new IdempotencyConflictException(key);

        IdempotencyConflictException restored = roundTrip(original);

        assertThat(restored.key()).isEqualTo(key);
        assertThat(restored.getMessage()).contains("INV-IDEM-03");
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
