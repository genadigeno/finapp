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

    private static final CounterpartyScreeningId GOLDEN_SCREENING =
            CounterpartyScreeningId.of(java.util.UUID.fromString("01890000-0000-7000-8000-00000000c001"));

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "232323232323232323232323";

    private static final String RING_GOLDEN_CIPHERTEXT = "b14a983ab2b436d00cc14af48edaf9ef4db8b8db5d046e5f2f90434ab289fe6dad4e73721c9578d1c829";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x13), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static CounterpartySubjectCipher.Encrypted writtenBeforeRings() {
        return new CounterpartySubjectCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE), 1);
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        CounterpartySubjectCipher cipher = new CounterpartySubjectCipher(ringKey(0x13), 1, new java.security.SecureRandom());
        assertThat(cipher.decrypt(GOLDEN_SCREENING, writtenBeforeRings())).isEqualTo("golden vector counterparty");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        CounterpartySubjectCipher cipher = new CounterpartySubjectCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(cipher.decrypt(GOLDEN_SCREENING, writtenBeforeRings())).isEqualTo("golden vector counterparty");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        CounterpartySubjectCipher before = new CounterpartySubjectCipher(rotatedRing(1), new java.security.SecureRandom());
        CounterpartySubjectCipher after = new CounterpartySubjectCipher(rotatedRing(2), new java.security.SecureRandom());

        CounterpartySubjectCipher.Encrypted writtenBefore = before.encrypt(GOLDEN_SCREENING, "a later counterparty");
        CounterpartySubjectCipher.Encrypted writtenAfter = after.encrypt(GOLDEN_SCREENING, "a later counterparty");

        assertThat(writtenBefore.keyVersion()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(writtenAfter.keyVersion()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(after.decrypt(GOLDEN_SCREENING, writtenAfter)).isEqualTo("a later counterparty");
        assertThat(before.decrypt(GOLDEN_SCREENING, writtenAfter)).as("a replica not yet activated reads its neighbour's new row").isEqualTo("a later counterparty");
        assertThat(after.decrypt(GOLDEN_SCREENING, writtenBefore)).as("an activated replica reads an old row").isEqualTo("a later counterparty");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        CounterpartySubjectCipher cipher = new CounterpartySubjectCipher(rotatedRing(1), new java.security.SecureRandom());
        CounterpartySubjectCipher.Encrypted written = cipher.encrypt(GOLDEN_SCREENING, "a later counterparty");
        assertThatThrownBy(() -> cipher.decrypt(GOLDEN_SCREENING, new CounterpartySubjectCipher.Encrypted(written.ciphertext(), written.nonce(), 9)))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
