package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MerchantApiKey aggregate's one edge, {@code ACTIVE → REVOKED} (`P6-TSK-002`, ADR-0052),
 * under a clock that reads behind the instant that issued the key.
 */
@DisplayName("the MerchantApiKey aggregate (P6-TSK-002)")
class MerchantApiKeyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName(
            "a clock behind issuance cannot fail a legal revocation: the stamp clamps to issuedAt"
                    + " (the P1-TSK-031 drift, met in domain code; ADR-0014)")
    void aClockBehindIssuanceCannotFailALegalRevocation() {
        MerchantApiKey key = issued();
        Clock behind = Clock.fixed(key.issuedAt().minusMillis(250), ZoneOffset.UTC);

        MerchantApiKey revoked = key.revoke(behind);
        assertThat(revoked.status()).isEqualTo(MerchantApiKeyStatus.REVOKED);
        assertThat(revoked.revokedAt()).contains(key.issuedAt());

        // A floor, not a pin: a clock at or past issuance stamps its own read.
        Clock ahead = Clock.fixed(key.issuedAt().plusSeconds(5), ZoneOffset.UTC);
        assertThat(key.revoke(ahead).revokedAt()).contains(key.issuedAt().plusSeconds(5));
    }

    @Test
    @DisplayName(
            "an illegal revocation under a behind clock is still the machine's refusal, never"
                    + " the constructor guard's")
    void anIllegalRevocationUnderABehindClockIsStillTheMachinesRefusal() {
        MerchantApiKey revoked = issued().revoke(CLOCK);
        Clock behind = Clock.fixed(revoked.issuedAt().minusSeconds(1), ZoneOffset.UTC);
        assertThatExceptionOfType(IllegalMerchantApiKeyTransitionException.class)
                .isThrownBy(() -> revoked.revoke(behind));
    }

    private static MerchantApiKey issued() {
        return MerchantApiKey.issue(
                IDS,
                CLOCK,
                MerchantId.next(IDS),
                MerchantApiKeySecret.issue(new SecureRandom()),
                UUID.randomUUID().toString());
    }
}
