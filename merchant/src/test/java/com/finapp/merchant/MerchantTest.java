package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Merchant machine and coherence (`P6-TSK-003`, {@code INV-LIFE-01/-02/-04}): the
 * exhaustive cross-product sweep derived from {@code permittedTransitions()} — expectation
 * read from the machine, not from a remembered list — plus the constructor's refusals. The
 * schema half is {@code MerchantMigrationTest}; the every-writer half is the app database
 * suite against `V002`'s trigger.
 */
@DisplayName("the Merchant aggregate (P6-TSK-003)")
class MerchantTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("every illegal edge is refused, swept from the machine's own cross-product")
    void everyIllegalEdgeIsRefused() {
        for (MerchantStatus from : MerchantStatus.values()) {
            for (MerchantStatus to : MerchantStatus.values()) {
                Merchant subject = at(from);
                boolean legal = from.permittedTransitions().contains(to);
                switch (to) {
                    case SUSPENDED -> assertEdge(legal, () -> subject.suspend(CLOCK), from, to);
                    case ACTIVE -> {
                        if (from == MerchantStatus.ACTIVE) {
                            continue; // no self-edge exists to drive
                        }
                        assertEdge(legal, () -> subject.reinstate(CLOCK), from, to);
                    }
                    case CLOSED -> assertEdge(legal, () -> subject.close(CLOCK), from, to);
                }
            }
        }
    }

    @Test
    @DisplayName("onboarding creates ACTIVE directly - the KYB gate is what pended (ADR-0044)")
    void onboardingCreatesActive() {
        Merchant merchant =
                Merchant.onboard(IDS, CLOCK, UUID.randomUUID(), "Acme GmbH", "Acme", EUR);
        assertThat(merchant.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.statusChangedAt()).isEqualTo(merchant.createdAt());
    }

    @Test
    @DisplayName("CLOSED is terminal, held as a machine property")
    void closedIsTerminal() {
        assertThat(MerchantStatus.CLOSED.isTerminal()).isTrue();
        assertThat(MerchantStatus.CLOSED.permittedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("the constructor holds the coherence: names bounded, timestamps ordered")
    void theConstructorHoldsTheCoherence() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> Merchant.onboard(IDS, CLOCK, UUID.randomUUID(), " ", "Acme", EUR));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Merchant.onboard(
                                        IDS,
                                        CLOCK,
                                        UUID.randomUUID(),
                                        "x".repeat(Merchant.MAX_NAME_LENGTH + 1),
                                        "Acme",
                                        EUR));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Merchant.rehydrate(
                                        MerchantId.next(IDS),
                                        UUID.randomUUID(),
                                        "Acme GmbH",
                                        "Acme",
                                        EUR,
                                        MerchantStatus.ACTIVE,
                                        Instant.parse("2026-09-21T10:00:00Z"),
                                        Instant.parse("2026-09-21T09:59:59Z")));
    }

    @Test
    @DisplayName("refusal messages carry states only - no identifier, no name (INV-AUD-02)")
    void refusalMessagesCarryStatesOnly() {
        Merchant closed = at(MerchantStatus.CLOSED);
        assertThatExceptionOfType(IllegalMerchantTransitionException.class)
                .isThrownBy(() -> closed.suspend(CLOCK))
                .withMessageNotContaining(closed.id().value().toString())
                .withMessageNotContaining("Acme");
    }

    private static void assertEdge(
            boolean legal, Runnable move, MerchantStatus from, MerchantStatus to) {
        if (legal) {
            move.run();
        } else {
            assertThatExceptionOfType(IllegalMerchantTransitionException.class)
                    .as("%s -> %s must be refused", from, to)
                    .isThrownBy(move::run);
        }
    }

    private static Merchant at(MerchantStatus status) {
        Instant born = Instant.parse("2026-09-21T09:00:00Z");
        return Merchant.rehydrate(
                MerchantId.next(IDS),
                UUID.randomUUID(),
                "Acme GmbH",
                "Acme",
                EUR,
                status,
                born,
                born);
    }
}
