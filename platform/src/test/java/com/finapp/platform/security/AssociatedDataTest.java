package com.finapp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The associated data a field cipher binds (`X-TSK-010`): no two different rows, purposes or places encode alike, because a
 * collision would let one row's ciphertext decrypt under another's.
 */
@DisplayName("a field cipher's associated data names its row unambiguously (X-TSK-010)")
class AssociatedDataTest {

    private static final UUID ROW = new UUID(0x0123456789abcdefL, 0x0fedcba987654321L);

    @Test
    @DisplayName("the place's fields are length-prefixed: (\"ab\", \"c\") and (\"a\", \"bc\") differ")
    void fieldBoundariesAreKept() {
        assertThat(AssociatedData.of("p", ROW, "ab", "c")).isNotEqualTo(AssociatedData.of("p", ROW, "a", "bc"));
        assertThat(AssociatedData.of("p", ROW, "a", "")).isNotEqualTo(AssociatedData.of("p", ROW, "a"));
    }

    @Test
    @DisplayName("another row, another purpose or another place is other associated data; the same is the same bytes")
    void everyPartBinds() {
        byte[] bound = AssociatedData.of("fx.fx_provider_evidence.content", ROW, "fx-sim-a", "ref-1");
        assertThat(AssociatedData.of("fx.fx_provider_evidence.content", ROW, "fx-sim-a", "ref-1")).isEqualTo(bound);
        assertThat(AssociatedData.of("fx.fx_provider_evidence.content", new UUID(0, 1), "fx-sim-a", "ref-1"))
                .isNotEqualTo(bound);
        assertThat(AssociatedData.of("payments.provider_evidence.content", ROW, "fx-sim-a", "ref-1")).isNotEqualTo(bound);
        assertThat(AssociatedData.of("fx.fx_provider_evidence.content", ROW, "fx-sim-a", "ref-2")).isNotEqualTo(bound);
    }

    @Test
    @DisplayName("a blank purpose or a missing place value is refused, never encoded")
    void nothingIsLeftOut() {
        assertThatThrownBy(() -> AssociatedData.of(" ", ROW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssociatedData.of("p", ROW, (String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a stored cipher format is 1 or 2, and nothing else reads as a default")
    void theFormatIsOneOfTwo() {
        assertThat(CipherFormat.of(1)).isEqualTo(CipherFormat.UNBOUND);
        assertThat(CipherFormat.of(2)).isEqualTo(CipherFormat.BOUND);
        assertThatThrownBy(() -> CipherFormat.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CipherFormat.of(3)).isInstanceOf(IllegalArgumentException.class);
    }
}
