package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The device label: bounded, control-character free, and never able to refuse a login.
 *
 * <p><strong>Every control character here is written as an explicit escape.</strong> The first
 * version of this file contained literal NUL, BEL, RLO and BOM characters, so the tests read as
 * though they exercised ordinary strings while in fact exercising control characters — which made
 * them unreviewable and their outcomes coincidental. Found by probing why a test passed when its
 * visible content said it should not.
 */
@DisplayName("DeviceDescription (P1-TSK-016)")
class DeviceDescriptionTest {

    @Test
    @DisplayName("an ordinary user agent survives intact")
    void anOrdinaryUserAgentIsKept() {
        String agent =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/141.0.0.0 Safari/537.36";

        assertThat(DeviceDescription.fromUserAgent(agent))
                .as("a label nobody can recognise is a label that fails at its only job")
                .contains(new DeviceDescription(agent));
    }

    @Test
    @DisplayName("control characters are stripped rather than refused")
    void controlCharactersAreStripped() {
        // A CR here is a forged log line and a display that lies about which session is which
        // (the P1-TSK-006 PartyName finding). It is REMOVED rather than rejected, because the
        // person did not choose this header and must not be locked out by it.
        assertThat(DeviceDescription.fromUserAgent("Chrome\r\nX-Forged: yes"))
                .contains(new DeviceDescription("ChromeX-Forged: yes"));
    }

    @Test
    @DisplayName("an over-long agent is truncated, never rejected")
    void anOverLongAgentIsTruncated() {
        DeviceDescription described =
                DeviceDescription.fromUserAgent("u".repeat(DeviceDescription.MAX_LENGTH * 3))
                        .orElseThrow();

        assertThat(described.value()).hasSize(DeviceDescription.MAX_LENGTH);
    }

    @Test
    @DisplayName("nothing displayable yields no device at all, and that is ordinary")
    void nothingDisplayableYieldsNoDevice() {
        // A session with no device label is entirely normal - the column is nullable for it. What
        // must never happen is a login failing because of this field.
        assertThat(DeviceDescription.fromUserAgent(null)).isEmpty();
        assertThat(DeviceDescription.fromUserAgent("")).isEmpty();
        assertThat(DeviceDescription.fromUserAgent("   ")).isEmpty();
        assertThat(DeviceDescription.fromUserAgent("\u0000\u0007\r\n")).isEmpty();
    }

    @Test
    @DisplayName("fromUserAgent is total: no input throws")
    void fromUserAgentNeverThrows() {
        // The property that matters, asserted over the shapes an attacker would try. If any of
        // these threw, an unscored convenience label could refuse an authentication.
        for (String hostile :
                new String[] {
                    "\u0000", "\uD800", "\u202e", "\ufeff", "a\u0000b", "\u0000".repeat(500),
                    "x".repeat(100_000), "\t\t\t", "é".repeat(300)
                }) {
            Optional<DeviceDescription> described = DeviceDescription.fromUserAgent(hostile);
            described.ifPresent(
                    device ->
                            assertThat(device.value())
                                    .hasSizeLessThanOrEqualTo(DeviceDescription.MAX_LENGTH));
        }
    }

    @Test
    @DisplayName("the constructor is strict where the factory is forgiving")
    void theConstructorIsStrict() {
        // Two different jobs. The factory sanitises a header nobody chose; the constructor is the
        // invariant, and code holding a DeviceDescription may rely on it.
        assertThatThrownBy(() -> new DeviceDescription("Chrome\nInjected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Injected");
        assertThatThrownBy(() -> new DeviceDescription(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new DeviceDescription("u".repeat(DeviceDescription.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the refusal message never echoes the value")
    void theMessageNeverEchoesTheValue() {
        // RESTRICTED-PII, and an exception message reaches a log line (INV-AUD-02). The same
        // reasoning as PartyName, which also declines to say WHICH character offended - that would
        // echo the input one code point at a time.
        assertThatThrownBy(() -> new DeviceDescription("SecretPhone\u0007Model"))
                .hasMessageNotContaining("SecretPhone")
                .hasMessageNotContaining("Model");
    }
}
