package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Capture and its bounds (`P2-TSK-008`). */
@DisplayName("KycDocument capture (P2-TSK-008)")
class KycDocumentTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

    private final IdGenerator ids = new IdGenerator(FIXED, new SecureRandom());

    @Test
    @DisplayName("capture records the SHA-256 of the bytes received")
    void captureChecksumsTheBytesReceived() {
        // The empty-string vector would be cute and would not exercise content; "abc" is FIPS
        // 180-2's own published vector, so the checksum is held against the specification rather
        // than against this codebase's opinion of itself.
        KycDocument document =
                KycDocument.capture(
                        ids,
                        FIXED,
                        KycCaseId.next(ids),
                        DocumentType.PASSPORT,
                        DocumentContentType.JPEG,
                        DocumentBytes.of("abc".getBytes(StandardCharsets.UTF_8)));

        assertThat(document.checksumHex())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(document.contentLength()).isEqualTo(3);
        assertThat(document.uploadedAt()).isEqualTo(Instant.parse("2026-09-09T12:00:00Z"));
    }

    @Test
    @DisplayName("the bounds refuse an empty and an oversized document")
    void boundsAreEnforced() {
        assertThatIllegalArgumentException().isThrownBy(() -> DocumentBytes.of(new byte[0]));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DocumentBytes.of(new byte[DocumentBytes.MAX_BYTES + 1]));
        // The bound itself is legal: an off-by-one here is a customer whose scanner produces
        // exactly 512 KiB being refused for no rule.
        assertThat(DocumentBytes.of(new byte[DocumentBytes.MAX_BYTES]).length())
                .isEqualTo(DocumentBytes.MAX_BYTES);
    }

    @Test
    @DisplayName("neither the bytes nor the metadata render content")
    void nothingRendersContent() {
        byte[] content = "not for a log line".getBytes(StandardCharsets.UTF_8);
        DocumentBytes bytes = DocumentBytes.of(content);

        assertThat(bytes.toString()).doesNotContain("not for a log line");

        // And the value is cloned in: mutating the caller's array afterwards changes nothing.
        content[0] = 'X';
        assertThat(bytes.value()).startsWith((byte) 'n');
    }
}
