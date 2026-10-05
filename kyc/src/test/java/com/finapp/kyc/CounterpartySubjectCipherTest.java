package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counterparty's name is bound to its screening (`P9-TSK-016`, ADR-0081 point 1): a ciphertext
 * moved onto another screening's row refuses to decrypt instead of naming the wrong counterparty, and
 * tampering, the wrong key and the wrong row are one indistinguishable refusal.
 */
@DisplayName("the subject cipher binds a name to its screening (P9-TSK-016)")
class CounterpartySubjectCipherTest {

    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final byte[] KEY = new byte[32];
    private static final byte[] OTHER_KEY = new byte[32];

    static {
        Arrays.fill(KEY, (byte) 7);
        Arrays.fill(OTHER_KEY, (byte) 9);
    }

    private final CounterpartySubjectCipher cipher = new CounterpartySubjectCipher(KEY, 1, new SecureRandom());

    @Test
    @DisplayName("a name round-trips under its own screening, and the ciphertext does not hold it")
    void roundTrips() {
        CounterpartyScreeningId id = CounterpartyScreeningId.next(IDS);
        CounterpartySubjectCipher.Encrypted sealed = cipher.encrypt(id, "Ana Lima");
        assertThat(cipher.decrypt(id, sealed)).isEqualTo("Ana Lima");
        assertThat(new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("Ana");
        assertThat(sealed.ciphertext()).hasSize("Ana Lima".length() + 16);
        assertThat(sealed.toString()).doesNotContain("Ana");
    }

    @Test
    @DisplayName("a ciphertext moved onto another screening refuses - the associated data is the id")
    void theRowIsBound() {
        CounterpartySubjectCipher.Encrypted sealed = cipher.encrypt(CounterpartyScreeningId.next(IDS), "Ana Lima");
        CounterpartyScreeningId other = CounterpartyScreeningId.next(IDS);
        assertThatThrownBy(() -> cipher.decrypt(other, sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a flipped byte or the wrong key refuses, with the same failure")
    void tamperingAndTheWrongKeyRefuse() {
        CounterpartyScreeningId id = CounterpartyScreeningId.next(IDS);
        CounterpartySubjectCipher.Encrypted sealed = cipher.encrypt(id, "Ana Lima");
        byte[] flipped = sealed.ciphertext();
        flipped[0] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(id, new CounterpartySubjectCipher.Encrypted(flipped, sealed.nonce(), 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("could not decrypt counterparty screening content");
        CounterpartySubjectCipher wrong = new CounterpartySubjectCipher(OTHER_KEY, 1, new SecureRandom());
        assertThatThrownBy(() -> wrong.decrypt(id, sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("could not decrypt counterparty screening content");
    }

    @Test
    @DisplayName("evidence is sealed with the same binding")
    void evidenceIsBound() {
        CounterpartyScreeningId id = CounterpartyScreeningId.next(IDS);
        byte[] body = "{\"status\":\"hit\"}".getBytes(StandardCharsets.UTF_8);
        CounterpartySubjectCipher.Encrypted sealed = cipher.seal(id, body);
        assertThat(cipher.open(id, sealed)).isEqualTo(body);
        assertThatThrownBy(() -> cipher.open(CounterpartyScreeningId.next(IDS), sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a key of the wrong size is refused at construction")
    void theKeyIsThirtyTwoBytes() {
        assertThatThrownBy(() -> new CounterpartySubjectCipher(new byte[16], 1, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
