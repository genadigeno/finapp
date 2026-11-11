package com.finapp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ring's rules (`P15-TSK-019`, ADR-0123 point 4), each a way a row could be lost or a replica could write what it could
 * not read back. The per-cipher reads are in each cipher's own test.
 */
@DisplayName("the key ring: one active version, the stamped version always decides, nothing unaccounted for (P15-TSK-019)")
class KeyRingTest {

    private static byte[] key(int fill) {
        byte[] key = new byte[KeyRing.KEY_BYTES];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static KeyRing ring(int active) {
        return KeyRing.of("test key", Map.of(1, key(1), 2, key(2)), active, Set.of());
    }

    @Test
    @DisplayName("the active version writes, and the stamped version is the one that decrypts, even after the pointer has moved")
    void theStampDecidesTheKeyNotTheActiveVersion() {
        KeyRing afterActivation = ring(2);

        assertThat(afterActivation.activeVersion()).isEqualTo(2);
        assertThat(afterActivation.activeKey().getEncoded()).isEqualTo(key(2));
        assertThat(afterActivation.keyFor(1).getEncoded()).as("a row under version 1").isEqualTo(key(1));
        assertThat(afterActivation.keyFor(2).getEncoded()).isEqualTo(key(2));
    }

    @Test
    @DisplayName("moving the pointer back is a rollback: both versions stay held, every row stays readable")
    void activationIsReversible() {
        KeyRing movedBack = ring(1);
        assertThat(movedBack.activeVersion()).isEqualTo(1);
        assertThat(movedBack.keyFor(2).getEncoded()).as("a row written while version 2 was active").isEqualTo(key(2));
    }

    @Test
    @DisplayName("a version the ring does not hold is refused: unknown and retired alike, never another version's key")
    void aVersionNotHeldIsRefused() {
        KeyRing retiredOne = KeyRing.of("test key", Map.of(2, key(2)), 2, Set.of(1));

        assertThatThrownBy(() -> retiredOne.keyFor(1)).isInstanceOf(UnknownKeyVersionException.class).hasMessageContaining("1");
        assertThatThrownBy(() -> retiredOne.keyFor(9)).isInstanceOf(UnknownKeyVersionException.class).hasMessageContaining("9");
    }

    @Test
    @DisplayName("a cipher gets the miss in its own words: its one generic refusal, the version not said")
    void aCipherChoosesItsRefusal() {
        KeyRing ring = ring(1);
        assertThatThrownBy(() -> ring.keyFor(9, "Could not decrypt"))
                .isInstanceOf(UnknownKeyVersionException.class)
                .hasMessage("Could not decrypt")
                .hasNoCause();
    }

    @Test
    @DisplayName("an active version the ring does not hold refuses construction: a replica never starts unable to read what it writes")
    void anActiveVersionNotHeldIsRefused() {
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1)), 2, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not held by this instance");
    }

    @Test
    @DisplayName("a retired version is refused for encryption, and cannot be held as well")
    void aRetiredVersionIsRefusedForEncryption() {
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1), 2, key(2)), 1, Set.of(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(2, key(2)), 1, Set.of(1)))
                .as("the pointer on a version whose key is gone")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused for encryption");
    }

    @Test
    @DisplayName("every version between the lowest and the highest is held or retired: a hole refuses")
    void aHoleInTheVersionsIsRefused() {
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1), 3, key(3)), 3, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hole at key version 2");
        assertThat(KeyRing.of("test key", Map.of(1, key(1), 3, key(3)), 3, Set.of(2)).heldVersions())
                .as("a retired version is accounted for").containsExactly(1, 3);
    }

    @Test
    @DisplayName("a key that is not exactly 32 bytes is refused, and the refusal states no length")
    void aWrongLengthKeyIsRefused() {
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, new byte[16]), 1, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32")
                .hasMessageNotContaining("16");
    }

    @Test
    @DisplayName("a ring's text names versions and states only, never key material")
    void aRingsTextHoldsNoKeyMaterial() {
        KeyRing ring = ring(2);
        assertThat(ring.toString()).contains("active=2", "held=[1, 2]").doesNotContain("0x").doesNotContain("[B");
    }

    @Test
    @DisplayName("a ring is built from a copy: changing the bytes passed in afterwards changes nothing it writes")
    void aRingCopiesItsKeys() {
        byte[] material = key(5);
        KeyRing ring = KeyRing.single("test key", material, 1);
        material[0] = 0;
        assertThat(ring.activeKey().getEncoded()).as("the key the ring was given, not the array's later state").isEqualTo(key(5));
    }

    // ------------------------------------------------------------------------------- the check value (P15-TSK-020, item 32 (a))

    /** Computed apart from this code, by openssl: HMAC-SHA256 of the label under each key, its first eight bytes. */
    private static final String CHECK_OF_ONES = "552d40876e6d9748";
    private static final String CHECK_OF_TWOS = "0ec936eb254d5bcf";

    @Test
    @DisplayName("a check value is HMAC-SHA256 of the fixed label under the key, its first eight bytes: the answer openssl gives")
    void theCheckValueIsTheKnownAnswer() {
        byte[] counting = new byte[KeyRing.KEY_BYTES];
        for (int i = 0; i < counting.length; i++) {
            counting[i] = (byte) i;
        }
        assertThat(KeyRing.checkValueOf(counting)).isEqualTo("5daa68abe4ec79b0");
        assertThat(KeyRing.checkValueOf(key(1))).isEqualTo(CHECK_OF_ONES);
        assertThat(KeyRing.checkValueOf(key(2))).isEqualTo(CHECK_OF_TWOS);
    }

    @Test
    @DisplayName("every held version whose stored value gives its published check value starts, in either case of hexadecimal")
    void matchingCheckValuesStart() {
        KeyRing ring = KeyRing.of("test key", Map.of(1, key(1), 2, key(2)), 2, Set.of(),
                Map.of(1, CHECK_OF_ONES, 2, CHECK_OF_TWOS.toUpperCase()));
        assertThat(ring.activeVersion()).isEqualTo(2);
        assertThat(ring.keyFor(1).getEncoded()).isEqualTo(key(1));
    }

    @Test
    @DisplayName("a version's value replaced after it was listed refuses the replica, naming the version and neither value")
    void aReplacedVersionIsRefused() {
        // Version 2's slot now holds the bytes of another key, as an overwrite of a listed entry would leave it.
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1), 2, key(7)), 1, Set.of(),
                Map.of(1, CHECK_OF_ONES, 2, CHECK_OF_TWOS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 2 does not give its published check value")
                .hasMessageNotContaining(CHECK_OF_TWOS)
                .hasMessageNotContaining(KeyRing.checkValueOf(key(7)));
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(9)), 1, Set.of(), Map.of(1, CHECK_OF_ONES)))
                .as("a ring of one version given a check value is checked too")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 1 does not give its published check value");
    }

    @Test
    @DisplayName("only what is given is checked here: a version given none is the configuration's rule to refuse (ConfinedKeyRing)")
    void onlyTheGivenCheckValuesAreChecked() {
        assertThat(KeyRing.of("test key", Map.of(1, key(1), 2, key(2)), 1, Set.of(), Map.of(2, CHECK_OF_TWOS)).holds(1)).isTrue();
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1), 2, key(2)), 1, Set.of(), Map.of(1, CHECK_OF_TWOS)))
                .as("the one given is the one checked").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 1 does not give its published check value");
    }

    @Test
    @DisplayName("a check value for a version not held, retired or unknown, and a malformed one, are refused")
    void aCheckValueMustNameAHeldVersionInItsForm() {
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(2, key(2)), 2, Set.of(1), Map.of(1, CHECK_OF_ONES)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version 1, which this instance does not hold");
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1)), 1, Set.of(), Map.of(3, CHECK_OF_ONES)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version 3, which this instance does not hold");
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1)), 1, Set.of(), Map.of(1, "552d40876e6d97")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be 16 hexadecimal characters");
        assertThatThrownBy(() -> KeyRing.of("test key", Map.of(1, key(1)), 1, Set.of(), Map.of(1, "552d40876e6d974z")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be 16 hexadecimal characters");
    }
}
