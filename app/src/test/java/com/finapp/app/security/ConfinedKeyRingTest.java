package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.kyc.DocumentKey;
import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.platform.security.KeyRing;
import com.finapp.platform.security.PreviousKeyWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Reading a ring and a previous-key window from the environment (`P15-TSK-019`, ADR-0123 points 4 and 5): every way a
 * configuration could lose a row, or leave a previous key accepted for ever, refused at startup.
 */
@DisplayName("a key ring is read from configuration under the same confinement as the key it extends (P15-TSK-019)")
class ConfinedKeyRingTest {

    private static final KeySpec SPEC = new KeySpec(
            "test encryption key", "test encryption key", "FINAPP_TEST_KEY", "/test", KeyLength.EXACTLY_32, ".");

    private static final ConfinedKeyRing.RingProperties PROPERTIES = new ConfinedKeyRing.RingProperties(
            "finapp.test.ring.versions", "finapp.test.ring.active-version", "finapp.test.ring.retired-versions",
            "finapp.test.ring.check-values",
            Map.of(2, "finapp.test.v2.key", 3, "finapp.test.v3.key"));

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    /** Versions 1 and 2 of {@link #bytes}, each with its check value: what every listing of two versions now publishes (`P15-TSK-020`). */
    private static final String CHECKED_1_2 = "1:" + KeyRing.checkValueOf(bytes(1)) + ",2:" + KeyRing.checkValueOf(bytes(2));

    /** Obviously not a key: a fill byte, base64-encoded. */
    private static String encoded(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return Base64.getEncoder().encodeToString(key);
    }

    private static byte[] bytes(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static MockEnvironment environment() {
        return new MockEnvironment();
    }

    private static KeyRing ring(MockEnvironment environment) {
        return ConfinedKeyRing.read(SPEC, PROPERTIES, encoded(1), 1, environment, false);
    }

    @Test
    @DisplayName("absent ring configuration, the ring is the base key alone, active: exactly the one-key behaviour")
    void absentConfigurationIsToday() {
        KeyRing ring = ring(environment());
        assertThat(ring.activeVersion()).isEqualTo(1);
        assertThat(ring.heldVersions()).containsExactly(1);
        assertThat(ring.activeKey().getEncoded()).isEqualTo(bytes(1));
    }

    @Test
    @DisplayName("a base version configured away from 1 stays the one version only with the lower versions declared retired")
    void anExistingKeyVersionIsPreservedOnlyIfTheLowerOnesAreRetired() {
        MockEnvironment retired = environment().withProperty("finapp.test.ring.retired-versions", "1,2");
        KeyRing ring = ConfinedKeyRing.read(SPEC, PROPERTIES, encoded(1), 3, retired, false);
        assertThat(ring.heldVersions()).containsExactly(3);
        assertThat(ring.activeVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("a base version above 1 with the lower versions unaccounted for refuses to start: rows under them would be refused")
    void aBaseVersionAboveOneRefusesWithoutTheLowerVersionsAccounted() {
        assertThatThrownBy(() -> ConfinedKeyRing.read(SPEC, PROPERTIES, encoded(1), 4, environment(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 1")
                .hasMessageContaining("neither held nor retired");
    }

    @Test
    @DisplayName("a further version is listed as known and held for decryption while the active pointer stays put: step one of two")
    void aListedVersionIsKnownBeforeItIsActive() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.v2.key", encoded(2));

        KeyRing ring = ring(environment);

        assertThat(ring.heldVersions()).containsExactly(1, 2);
        assertThat(ring.activeVersion()).as("listing a version activates nothing").isEqualTo(1);
        assertThat(ring.keyFor(2).getEncoded()).isEqualTo(bytes(2));
    }

    @Test
    @DisplayName("activation is a second input, and is reversible by moving it back")
    void activationIsTheSecondStep() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.ring.active-version", "2")
                .withProperty("finapp.test.v2.key", encoded(2));
        assertThat(ring(environment).activeVersion()).isEqualTo(2);

        MockEnvironment movedBack = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.ring.active-version", "1")
                .withProperty("finapp.test.v2.key", encoded(2));
        assertThat(ring(movedBack).activeVersion()).isEqualTo(1);
        assertThat(ring(movedBack).holds(2)).as("rows written while version 2 was active stay readable").isTrue();
    }

    @Test
    @DisplayName("an active version with no key in this replica's environment refuses to start, naming the variable to set")
    void anActiveVersionWithNoKeyRefuses() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.ring.active-version", "2");

        assertThatThrownBy(() -> ring(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINAPP_TEST_V2_KEY");
    }

    @Test
    @DisplayName("a listed version with no key refuses to start, off loopback, under the marked-default confinement")
    void aListedVersionWithNoKeyRefuses() {
        MockEnvironment environment = environment().withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2);

        assertThatThrownBy(() -> ring(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINAPP_TEST_V2_KEY");
    }

    @Test
    @DisplayName("a version's marked local default stays confined to loopback, and off loopback refuses by name")
    void aVersionsMarkedDefaultIsConfined() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.v2.key", ConfinedCredential.MARKED_LOCAL_DEFAULT);

        assertThatThrownBy(() -> ring(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("published local default")
                .hasMessageContaining("FINAPP_TEST_V2_KEY");

        byte[] derived = SPEC.forVersion(2, "FINAPP_TEST_V2_KEY").decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        MockEnvironment loopback = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", "1:" + KeyRing.checkValueOf(bytes(1)) + ",2:" + KeyRing.checkValueOf(derived))
                .withProperty("finapp.test.v2.key", ConfinedCredential.MARKED_LOCAL_DEFAULT);
        KeyRing onLoopback = ConfinedKeyRing.read(SPEC, PROPERTIES, encoded(1), 1, loopback, true);
        assertThat(onLoopback.keyFor(2).getEncoded()).as("derived, and not the base key's bytes").isNotEqualTo(bytes(1));
    }

    @Test
    @DisplayName("a version's value that is not valid base64 refuses, and the refusal does not echo the value")
    void aMalformedVersionKeyRefuses() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.v2.key", "not base64 at all");

        assertThatThrownBy(() -> ring(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not base64 at all");
    }

    @Test
    @DisplayName("an active version retired refuses to start; a retired version still listed as held refuses to start")
    void aRetiredVersionCannotBeActiveOrHeld() {
        MockEnvironment activeRetired = environment()
                .withProperty("finapp.test.ring.versions", "2")
                .withProperty("finapp.test.ring.active-version", "1")
                .withProperty("finapp.test.ring.retired-versions", "1")
                .withProperty("finapp.test.v2.key", encoded(2));
        assertThatThrownBy(() -> ring(activeRetired)).isInstanceOf(IllegalStateException.class).hasMessageContaining("refused for encryption");

        MockEnvironment heldAndRetired = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.ring.retired-versions", "1")
                .withProperty("finapp.test.v2.key", encoded(2));
        assertThatThrownBy(() -> ring(heldAndRetired)).isInstanceOf(IllegalStateException.class).hasMessageContaining("both held and retired");
    }

    @Test
    @DisplayName("a version dropped from the list without being declared retired refuses to start: the base and any below the top, never the top itself, which leaves no hole and is the activation check's to refuse in CI")
    void aDroppedVersionIsAHole() {
        MockEnvironment baseDropped = environment()
                .withProperty("finapp.test.ring.versions", "2")
                .withProperty("finapp.test.ring.active-version", "2")
                .withProperty("finapp.test.v2.key", encoded(2));
        assertThatThrownBy(() -> ring(baseDropped)).isInstanceOf(IllegalStateException.class).hasMessageContaining("neither held nor retired");

        MockEnvironment middleDropped = environment()
                .withProperty("finapp.test.ring.versions", "1,3")
                .withProperty("finapp.test.ring.check-values", "1:" + KeyRing.checkValueOf(bytes(1)) + ",3:" + KeyRing.checkValueOf(bytes(3)))
                .withProperty("finapp.test.v3.key", encoded(3));
        assertThatThrownBy(() -> ring(middleDropped)).isInstanceOf(IllegalStateException.class).hasMessageContaining("hole");
    }

    @Test
    @DisplayName("a retired version's key is not read at all, so a value left behind in its slot is ignored and never loaded")
    void aRetiredVersionsKeyIsNeverRead() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.ring.versions", "2")
                .withProperty("finapp.test.ring.active-version", "2")
                .withProperty("finapp.test.ring.retired-versions", "1")
                .withProperty("finapp.test.v2.key", encoded(2));

        KeyRing ring = ConfinedKeyRing.read(SPEC, PROPERTIES, ConfinedCredential.MARKED_LOCAL_DEFAULT, 1, environment, false);

        assertThat(ring.heldVersions()).containsExactly(2);
        assertThat(ring.retiredVersions()).containsExactly(1);
    }

    @Test
    @DisplayName("a version with no configured slot refuses to start, and so does the base version named twice")
    void noSlotAndNamedTwiceRefuse() {
        MockEnvironment noSlot = environment().withProperty("finapp.test.ring.versions", "1,2,3,4")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2 + ",3:" + KeyRing.checkValueOf(bytes(3)) + ",4:" + KeyRing.checkValueOf(bytes(4)))
                .withProperty("finapp.test.v2.key", encoded(2))
                .withProperty("finapp.test.v3.key", encoded(3));
        assertThatThrownBy(() -> ring(noSlot)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no configured place for version 4");

        // Version 1 declared retired, so that base version 2 passes its anchor and the two-names rule is what answers.
        MockEnvironment namedTwice = environment()
                .withProperty("finapp.test.v2.key", encoded(2))
                .withProperty("finapp.test.ring.retired-versions", "1");
        assertThatThrownBy(() -> ConfinedKeyRing.read(SPEC, PROPERTIES, encoded(1), 2, namedTwice, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("named twice");
    }

    @Test
    @DisplayName("a version list that does not parse refuses, and never echoes what it held: it could be a key pasted in the wrong place")
    void aMalformedListRefuses() {
        for (String malformed : new String[] {"1,", "one", "1,1", "0", "-1", "1;2", "c2VjcmV0"}) {
            MockEnvironment environment = environment().withProperty("finapp.test.ring.versions", malformed);
            assertThatThrownBy(() -> ring(environment))
                    .as("the list [%s]", malformed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finapp.test.ring.versions")
                    .hasMessageNotContaining(malformed);
        }
    }

    @Test
    @DisplayName("each field-encryption key's ring configuration is read from its own properties: the real DocumentKey, end to end")
    void aRealKeyClassBuildsItsRing() {
        MockEnvironment environment = environment()
                .withProperty("finapp.doc.ring.versions", "1,2")
                .withProperty("finapp.doc.ring.check-values", "1:" + KeyRing.checkValueOf(bytes(8)) + ",2:" + KeyRing.checkValueOf(bytes(9)))
                .withProperty("finapp.doc.ring.active-version", "2")
                .withProperty("finapp.doc.v2.key", encoded(9));

        KeyRing ring = DocumentKey.ring(encoded(8), 1, environment, false);

        assertThat(ring.activeVersion()).isEqualTo(2);
        assertThat(ring.keyFor(1).getEncoded()).isEqualTo(bytes(8));
        assertThat(ring.keyFor(2).getEncoded()).isEqualTo(bytes(9));
    }

    // ----------------------------------------------------------------- the check values (P15-TSK-020, THREAT_MODEL.md section 6 item 32 (a))

    @Test
    @DisplayName("once two versions are listed, every one needs a published check value, the base included, or the replica refuses")
    void checkValuesAreRequiredOnceTwoVersionsAreListed() {
        MockEnvironment none = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.v2.key", encoded(2));
        assertThatThrownBy(() -> ring(none)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finapp.test.ring.check-values publishes no check value for version 1");

        MockEnvironment onlyTheNewOne = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", "2:" + KeyRing.checkValueOf(bytes(2)))
                .withProperty("finapp.test.v2.key", encoded(2));
        assertThatThrownBy(() -> ring(onlyTheNewOne)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no check value for version 1");

        assertThat(ring(environment()).heldVersions()).as("one version and no check value: today's start, unchanged").containsExactly(1);
    }

    @Test
    @DisplayName("a listed version's value replaced in its slot refuses the replica, echoing neither the key nor a check value")
    void aReplacedValueRefusesTheReplica() {
        MockEnvironment replaced = environment()
                .withProperty("finapp.test.ring.versions", "1,2")
                .withProperty("finapp.test.ring.check-values", CHECKED_1_2)
                .withProperty("finapp.test.v2.key", encoded(7));
        assertThatThrownBy(() -> ring(replaced)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 2 does not give its published check value")
                .hasMessageNotContaining(encoded(7))
                .hasMessageNotContaining(KeyRing.checkValueOf(bytes(2)))
                .hasMessageNotContaining(KeyRing.checkValueOf(bytes(7)));

        MockEnvironment baseReplaced = environment().withProperty("finapp.test.ring.check-values", "1:" + KeyRing.checkValueOf(bytes(3)));
        assertThatThrownBy(() -> ring(baseReplaced)).as("one version with a published value is checked too")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version 1 does not give its published check value");
    }

    @Test
    @DisplayName("a check-value list that does not parse, or names a version twice or one not held, refuses without echoing it")
    void aMalformedCheckValueListRefuses() {
        String good = KeyRing.checkValueOf(bytes(1));
        for (String malformed : new String[] {"7", "7:", "1:" + good + ":x", "x:" + good, "1:" + good.substring(2), "1:" + good + ",",
                "1:" + good.replace(good.charAt(0), 'z'), "c2VjcmV0"}) {
            MockEnvironment environment = environment().withProperty("finapp.test.ring.check-values", malformed);
            assertThatThrownBy(() -> ring(environment)).as("the list [%s]", malformed).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finapp.test.ring.check-values").hasMessageNotContaining(malformed);
        }
        MockEnvironment twice = environment().withProperty("finapp.test.ring.check-values", "1:" + good + ",1:" + good);
        assertThatThrownBy(() -> ring(twice)).isInstanceOf(IllegalStateException.class).hasMessageContaining("names one version twice");
        MockEnvironment notHeld = environment().withProperty("finapp.test.ring.check-values", "1:" + good + ",2:" + good);
        assertThatThrownBy(() -> ring(notHeld)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 2, which this instance does not hold");
    }

    @Test
    @DisplayName("every one of the eight keys reads its check values from a property of its own: the real MfaKey refuses a replaced version")
    void aRealKeyClassReadsItsCheckValues() {
        MockEnvironment replaced = environment()
                .withProperty("finapp.mfa.ring.versions", "1,2")
                .withProperty("finapp.mfa.ring.check-values", "1:" + KeyRing.checkValueOf(bytes(8)) + ",2:" + KeyRing.checkValueOf(bytes(9)))
                .withProperty("finapp.mfa.v2.key", encoded(6));
        assertThatThrownBy(() -> com.finapp.app.mfa.MfaKey.ring(encoded(8), 1, replaced, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version 2 does not give its published check value");
    }

    // ----------------------------------------------------------------- the previous-key window

    private static final KeySpec DOOR = new KeySpec(
            "test signing key", "test signing key", "FINAPP_TEST_WEBHOOK_KEY", "/test-door", KeyLength.AT_LEAST_32, ".");

    private static Optional<PreviousKeyWindow> window(MockEnvironment environment, boolean localDefaultPermitted) {
        return ConfinedKeyRing.previousWindow(
                DOOR, "finapp.test.webhook.previous.key", "finapp.test.webhook.previous.valid-until", environment, localDefaultPermitted, NOW);
    }

    @Test
    @DisplayName("a previous key with no window configured opens nothing: a value waiting in the store is not a window")
    void aPreviousKeyWithoutAnInstantOpensNothing() {
        MockEnvironment environment = environment().withProperty("finapp.test.webhook.previous.key", encoded(5));
        assertThat(window(environment, false)).isEmpty();
    }

    @Test
    @DisplayName("an instant with no previous key refuses to start")
    void anInstantWithoutAKeyRefuses() {
        MockEnvironment environment = environment().withProperty("finapp.test.webhook.previous.valid-until", NOW.plusSeconds(60).toString());
        assertThatThrownBy(() -> window(environment, false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no previous key");
    }

    @Test
    @DisplayName("both together open a window that the key's own confinement guards, and its end names the instant")
    void bothTogetherOpenAWindow() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.webhook.previous.key", encoded(5))
                .withProperty("finapp.test.webhook.previous.valid-until", NOW.plus(Duration.ofHours(6)).toString());

        assertThat(window(environment, false)).get()
                .satisfies(open -> assertThat(open.isOpenAt(NOW)).isTrue())
                .satisfies(open -> assertThat(open.key()).isEqualTo(bytes(5)));
    }

    @Test
    @DisplayName("the previous key's marked default is confined to loopback too")
    void thePreviousKeysMarkedDefaultIsConfined() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.webhook.previous.key", ConfinedCredential.MARKED_LOCAL_DEFAULT)
                .withProperty("finapp.test.webhook.previous.valid-until", NOW.plusSeconds(60).toString());

        assertThatThrownBy(() -> window(environment, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("published local default")
                .hasMessageContaining("FINAPP_TEST_WEBHOOK_PREVIOUS_KEY");
        assertThat(window(environment, true)).as("on loopback, derived and domain-separated").isPresent();
    }

    @Test
    @DisplayName("an instant that does not parse, or lies further ahead than the maximum, refuses to start")
    void anUnusableInstantRefuses() {
        MockEnvironment notAnInstant = environment()
                .withProperty("finapp.test.webhook.previous.key", encoded(5))
                .withProperty("finapp.test.webhook.previous.valid-until", "next tuesday");
        assertThatThrownBy(() -> window(notAnInstant, false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ISO-8601");

        MockEnvironment tooFarAhead = environment()
                .withProperty("finapp.test.webhook.previous.key", encoded(5))
                .withProperty("finapp.test.webhook.previous.valid-until", NOW.plus(PreviousKeyWindow.MAX_AHEAD).plusSeconds(1).toString());
        assertThatThrownBy(() -> window(tooFarAhead, false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("days ahead");
    }

    @Test
    @DisplayName("an instant already past closes the window and the replica still starts")
    void aPastInstantClosesTheWindow() {
        MockEnvironment environment = environment()
                .withProperty("finapp.test.webhook.previous.key", encoded(5))
                .withProperty("finapp.test.webhook.previous.valid-until", NOW.minusSeconds(60).toString());
        assertThat(window(environment, false)).isEmpty();
    }
}
