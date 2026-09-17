package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link Beneficiary} machine and coherence (`P4-TSK-006`, {@code INV-LIFE-01/-02/-04}).
 *
 * <p>The sweep is derived from {@link BeneficiaryStatus#values()} and judged against
 * {@link BeneficiaryStatus#permittedTransitions()} — a state added later is swept without
 * anyone remembering — and the machine itself is <strong>pinned exactly</strong> beside it,
 * because a sweep that trusts the machine cannot notice the machine changing (the
 * {@code TransferTest} pair of assertions).
 */
@DisplayName("the Beneficiary aggregate (P4-TSK-006)")
class BeneficiaryTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("the machine is pinned: ACTIVE -> REMOVED, REMOVED terminal, birth the only door")
    void theMachineIsPinned() {
        assertThat(BeneficiaryStatus.ACTIVE.permittedTransitions())
                .containsExactly(BeneficiaryStatus.REMOVED);
        assertThat(BeneficiaryStatus.REMOVED.isTerminal()).isTrue();
        // Nothing transitions TO ACTIVE: no state permits it and no method on the aggregate
        // targets it - birth is the only door, which is what "resurrection" would need.
        for (BeneficiaryStatus from : BeneficiaryStatus.values()) {
            assertThat(from.permittedTransitions()).doesNotContain(BeneficiaryStatus.ACTIVE);
        }
        // The one-live index predicate's definition: exactly the terminal set (INV-LIFE-04's
        // freed-slot asymmetry - a REMOVED row leaves the slot, an ACTIVE one holds it).
        assertThat(BeneficiaryStatus.sqlTerminalValueList()).isEqualTo("'REMOVED'");
    }

    @Test
    @DisplayName("every transition door is swept from the cross-product")
    void everyTransitionDoorIsSwept() {
        for (BeneficiaryStatus from : BeneficiaryStatus.values()) {
            Beneficiary subject = inStatus(from);
            if (from.canTransitionTo(BeneficiaryStatus.REMOVED)) {
                Beneficiary removed = subject.remove(CLOCK);
                assertThat(removed.status()).isEqualTo(BeneficiaryStatus.REMOVED);
                assertThat(removed.removedAt()).contains(Instant.now(CLOCK));
            } else {
                assertThatThrownBy(() -> subject.remove(CLOCK))
                        .as("remove() from %s must be refused by the aggregate", from)
                        .isInstanceOf(IllegalBeneficiaryTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("coherence is refused both ways on rehydrate, ahead of V003's CHECK")
    void coherenceIsRefusedOnRehydrate() {
        Instant now = Instant.now(CLOCK);
        assertThatThrownBy(() -> rehydrated(BeneficiaryStatus.REMOVED, null, now))
                .as("a REMOVED row without its removal instant is corrupt")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(BeneficiaryStatus.ACTIVE, now, now))
                .as("an ACTIVE row carrying a removal instant is corrupt")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                rehydrated(
                                        BeneficiaryStatus.REMOVED,
                                        now.minusSeconds(60),
                                        now))
                .as("a removal before creation is corrupt")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> rehydrated(BeneficiaryStatus.REMOVED, now, now))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the display name is present, bounded and free of the five categories")
    void theDisplayNameIsBoundedAndClean() {
        assertThatThrownBy(() -> named(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> named("x".repeat(Beneficiary.MAX_DISPLAY_NAME_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        // A CR is a forged log line; a bidirectional override (Cf) lies about which name this
        // is. Neither is part of anybody's name (the PartyName rule, restated here because
        // module isolation forbids the import).
        assertThatThrownBy(() -> named("John\rDoe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> named("John‮Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        // What must NOT be refused: real names (apostrophes, accents, non-Latin scripts).
        assertThatCode(() -> named("Siobhán O'Brien")).doesNotThrowAnyException();
        assertThatCode(() -> named("გენადი")).doesNotThrowAnyException();
        assertThatCode(() -> named("x".repeat(Beneficiary.MAX_DISPLAY_NAME_LENGTH)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the name reaches no rendering or message (INV-AUD-02)")
    void theNameReachesNoRenderingOrMessage() {
        String needle = "NEEDLE-Aunt-Vera";
        Beneficiary subject = named(needle);
        assertThat(subject.toString()).doesNotContain(needle);
        // The over-length refusal must state the bound, never echo the RESTRICTED-PII value.
        String longNeedle = needle + "x".repeat(Beneficiary.MAX_DISPLAY_NAME_LENGTH);
        assertThatThrownBy(() -> named(longNeedle))
                .hasMessageNotContaining(needle);
        // The transition refusal names the identifier and states, never the name.
        Beneficiary removed = subject.remove(CLOCK);
        assertThatThrownBy(() -> removed.remove(CLOCK)).hasMessageNotContaining(needle);
    }

    // -----------------------------------------------------------------

    private static Beneficiary named(String displayName) {
        return Beneficiary.create(
                BeneficiaryId.next(IDS),
                UUID.randomUUID(),
                displayName,
                UUID.randomUUID(),
                CLOCK);
    }

    private static Beneficiary inStatus(BeneficiaryStatus status) {
        Instant now = Instant.now(CLOCK);
        return new Beneficiary(
                BeneficiaryId.next(IDS),
                UUID.randomUUID(),
                "Aunt Vera",
                UUID.randomUUID(),
                status,
                now,
                status == BeneficiaryStatus.REMOVED ? Optional.of(now) : Optional.empty());
    }

    private static Beneficiary rehydrated(
            BeneficiaryStatus status, Instant removedAt, Instant createdAt) {
        return Beneficiary.rehydrate(
                BeneficiaryId.next(IDS),
                UUID.randomUUID(),
                "Aunt Vera",
                UUID.randomUUID(),
                status,
                createdAt,
                removedAt);
    }
}
