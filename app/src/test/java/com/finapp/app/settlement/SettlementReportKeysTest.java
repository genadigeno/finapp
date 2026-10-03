package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.merchant.PayoutProviderKey;
import com.finapp.app.payments.ProviderApiKey;
import com.finapp.app.security.ConfinedCredential;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four settlement pull credentials' own contract (`P8-TSK-021`, ADR-0066 §1) — one key per
 * concern: report access is not the money-moving API. Each is separated locally from the others
 * and from the provider API keys it must never stand in for, confined to loopback when
 * defaulted, and at least 32 bytes; every credential class gets a test (the `P2-TSK-011`
 * lesson, standing). The mechanism itself is `ConfinedCredentialTest`'s subject.
 */
@DisplayName("the settlement pull credentials (P8-TSK-021)")
class SettlementReportKeysTest {

    private record Key(
            String name, String variable, BiFunction<String, Boolean, byte[]> decode) {}

    private static final List<Key> KEYS =
            List.of(
                    new Key(
                            "PSP settlement report key",
                            "FINAPP_SETTLEMENT_PSP_REPORT_KEY",
                            PspReportKey::decode),
                    new Key(
                            "scheme cycle report key",
                            "FINAPP_SETTLEMENT_SCHEME_REPORT_KEY",
                            SchemeReportKey::decode),
                    new Key(
                            "payout provider report key",
                            "FINAPP_SETTLEMENT_PAYOUT_REPORT_KEY",
                            PayoutReportKey::decode),
                    new Key(
                            "bank statement key",
                            "FINAPP_SETTLEMENT_BANK_STATEMENT_KEY",
                            BankStatementKey::decode));

    @Test
    @DisplayName("each local default is separated from the other three and from the provider"
            + " API keys - report access never shares the money-moving API's key")
    void locallyDomainSeparated() {
        String marked = ConfinedCredential.MARKED_LOCAL_DEFAULT;
        List<byte[]> derived = KEYS.stream().map(key -> key.decode().apply(marked, true)).toList();
        byte[] paymentApi = ProviderApiKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        byte[] payoutApi = PayoutProviderKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        for (int i = 0; i < derived.size(); i++) {
            assertThat(derived.get(i))
                    .as(KEYS.get(i).name())
                    .hasSizeGreaterThanOrEqualTo(32)
                    .isNotEqualTo(paymentApi)
                    .isNotEqualTo(payoutApi);
            for (int j = i + 1; j < derived.size(); j++) {
                assertThat(derived.get(i))
                        .as(KEYS.get(i).name() + " vs " + KEYS.get(j).name())
                        .isNotEqualTo(derived.get(j));
            }
        }
    }

    @Test
    @DisplayName("the confinement is inherited, and each refusal names its own variable")
    void confinementIsInherited() {
        for (Key key : KEYS) {
            assertThatThrownBy(
                            () ->
                                    key.decode()
                                            .apply(ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                    .as(key.name())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key.variable());
        }
    }

    @Test
    @DisplayName("at least 32 bytes: 16 refused, 48 accepted - a bearer secret may be longer")
    void atLeastThirtyTwoBytes() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        String fortyEight = Base64.getEncoder().encodeToString(new byte[48]);
        for (Key key : KEYS) {
            assertThatThrownBy(() -> key.decode().apply(sixteen, true))
                    .as(key.name())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(key.decode().apply(fortyEight, true)).as(key.name()).hasSize(48);
        }
    }
}
