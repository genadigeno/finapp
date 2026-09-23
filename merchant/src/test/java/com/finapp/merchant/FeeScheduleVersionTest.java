package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The version's own rules (`P6-TSK-004`): effective forward, and the resolution order that
 * decides which version priced a capture ({@code INV-MER-03}).
 */
@DisplayName("fee schedule version rules (P6-TSK-004)")
class FeeScheduleVersionTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private final FeeSchedule schedule =
            FeeSchedule.create(IDS, "Standard card", USD, "operator", CLOCK);

    @Test
    @DisplayName("INV-MER-03: a version cannot take effect before it was created")
    void backdatingIsRefused() {
        assertThatThrownBy(() -> version(1, "0.029", NOW.minusSeconds(1)))
                .isInstanceOf(BackdatedFeeScheduleVersionException.class)
                .hasMessageContaining("INV-MER-03");
    }

    @Test
    @DisplayName("effective immediately is allowed - the bound is 'not before', not 'after'")
    void effectiveNowIsAllowed() {
        assertThat(version(1, "0.029", NOW).effectiveFrom()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("OMITTING effectiveFrom means immediately - and it is the only way a caller"
            + " can say 'now'")
    void anOmittedInstantMeansImmediately() {
        // Found by the database suite, which is where it had to be found: every version the
        // test created was REFUSED as backdated, because the test computed "now" before the
        // HTTP round trip and the server stamps createdAt after it. A caller literally cannot
        // name an instant that is still "now" when it arrives. Letting the server resolve an
        // absent one is exact; widening the CHECK by a tolerance would put a fudge factor
        // inside INV-MER-03, which is the invariant the CHECK exists to hold.
        FeeScheduleVersion immediate =
                FeeScheduleVersion.create(
                        IDS,
                        schedule,
                        1,
                        FeeRate.of(new BigDecimal("0.029")),
                        Money.ofMinorUnits(30L, USD),
                        RoundingPolicy.HALF_EVEN,
                        RefundFeePolicy.RETAINED,
                        Optional.empty(),
                        "operator",
                        CLOCK);
        assertThat(immediate.effectiveFrom()).isEqualTo(immediate.createdAt());
        assertThat(immediate.isEffectiveAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("a version's fixed part must be in its schedule's currency")
    void aForeignFixedPartIsRefused() {
        assertThatThrownBy(
                        () ->
                                FeeScheduleVersion.create(
                                        IDS,
                                        schedule,
                                        1,
                                        FeeRate.of(new BigDecimal("0.029")),
                                        Money.ofMinorUnits(30L, CurrencyCode.of("EUR")),
                                        RoundingPolicy.HALF_EVEN,
                                        RefundFeePolicy.RETAINED,
                                        Optional.of(NOW),
                                        "operator",
                                        CLOCK))
                .isInstanceOf(FeeCurrencyMismatchException.class);
    }

    @Test
    @DisplayName("the effective version is the greatest effective_from at or before the instant")
    void resolutionPicksTheLatestEffective() {
        FeeScheduleVersion v1 = version(1, "0.029", NOW);
        FeeScheduleVersion v2 = version(2, "0.025", NOW.plus(Duration.ofDays(30)));

        assertThat(effectiveAt(List.of(v1, v2), NOW)).isEqualTo(v1);
        assertThat(effectiveAt(List.of(v1, v2), NOW.plus(Duration.ofDays(29)))).isEqualTo(v1);
        assertThat(effectiveAt(List.of(v1, v2), NOW.plus(Duration.ofDays(30)))).isEqualTo(v2);
        assertThat(effectiveAt(List.of(v1, v2), NOW.plus(Duration.ofDays(365)))).isEqualTo(v2);
    }

    @Test
    @DisplayName("TIES ARE LEGAL and the greater version number wins")
    void aSupersedingVersionMayShareTheInstant() {
        // The operational case this rule exists for: an operator schedules 2.9% for the first
        // of next month, catches the mistyped rate, and supersedes it AT THE SAME INSTANT
        // rather than being forced to leave a second of wrong pricing. The superseded version
        // stays in the record as evidence it was scheduled and replaced before it ever priced.
        Instant firstOfNextMonth = NOW.plus(Duration.ofDays(9));
        FeeScheduleVersion mistyped = version(2, "0.290", firstOfNextMonth);
        FeeScheduleVersion corrected = version(3, "0.029", firstOfNextMonth);

        assertThat(effectiveAt(List.of(mistyped, corrected), firstOfNextMonth))
                .isEqualTo(corrected);
        assertThat(effectiveAt(List.of(corrected, mistyped), firstOfNextMonth))
                .as("the answer does not depend on the order the rows arrive in")
                .isEqualTo(corrected);
    }

    @Test
    @DisplayName("nothing is effective before the first version")
    void nothingIsEffectiveTooEarly() {
        FeeScheduleVersion v1 = version(1, "0.029", NOW.plus(Duration.ofDays(1)));
        assertThat(v1.isEffectiveAt(NOW)).isFalse();
        assertThat(effectiveAt(List.of(v1), NOW)).isNull();
    }

    @Test
    @DisplayName("version numbers start at one and are never zero or negative")
    void versionNumbersArePositive() {
        assertThatThrownBy(() -> version(0, "0.029", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(version(FeeScheduleVersion.FIRST_VERSION, "0.029", NOW).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("a negative fixed part is refused - a fee is not a payment to the merchant")
    void aNegativeFixedPartIsRefused() {
        assertThatThrownBy(
                        () ->
                                FeeScheduleVersion.create(
                                        IDS,
                                        schedule,
                                        1,
                                        FeeRate.of(new BigDecimal("0.029")),
                                        Money.ofMinorUnits(-30L, USD),
                                        RoundingPolicy.HALF_EVEN,
                                        RefundFeePolicy.RETAINED,
                                        Optional.of(NOW),
                                        "operator",
                                        CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a stored policy name that is not known is never silently defaulted")
    void unknownPolicyNamesThrow() {
        // A default here would change the meaning of the decision being replayed: the same
        // stored row would price differently after a release that removed a policy.
        assertThatThrownBy(() -> RefundFeePolicy.ofName("PRO_RATA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoundingPolicy.ofName("HALF_DOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the SQL value list is exactly the declared refund fee policies")
    void theRefundFeePolicySqlListIsExact() {
        // Pinned literally as well as generated: the generator makes the migration and the
        // enum one definition, and this makes a SILENT WIDENING of that definition visible -
        // adding a third policy changes what every existing version's column may hold.
        assertThat(RefundFeePolicy.sqlValueList()).isEqualTo("'RETAINED', 'RETURNED'");
    }

    // -----------------------------------------------------------------

    /** The resolution rule as the store expresses it in SQL, evaluated in memory. */
    private static FeeScheduleVersion effectiveAt(
            List<FeeScheduleVersion> versions, Instant instant) {
        List<FeeScheduleVersion> effective = new ArrayList<>(versions);
        effective.removeIf(version -> !version.isEffectiveAt(instant));
        effective.sort(FeeScheduleVersion.EFFECTIVE_ORDER);
        return effective.isEmpty() ? null : effective.get(0);
    }

    private FeeScheduleVersion version(int number, String rate, Instant effectiveFrom) {
        return FeeScheduleVersion.create(
                IDS,
                schedule,
                number,
                FeeRate.of(new BigDecimal(rate)),
                Money.ofMinorUnits(30L, USD),
                RoundingPolicy.HALF_EVEN,
                RefundFeePolicy.RETAINED,
                Optional.of(effectiveFrom),
                "operator",
                CLOCK);
    }

    @Test
    @DisplayName("EFFECTIVE_ORDER is newest-effective first, so the store's ORDER BY matches")
    void theComparatorIsNewestFirst() {
        FeeScheduleVersion older = version(1, "0.029", NOW);
        FeeScheduleVersion newer = version(2, "0.025", NOW.plus(Duration.ofDays(1)));
        List<FeeScheduleVersion> ordered = new ArrayList<>(List.of(older, newer));
        ordered.sort(FeeScheduleVersion.EFFECTIVE_ORDER);
        assertThat(ordered).containsExactly(newer, older);
        assertThat(FeeScheduleVersion.EFFECTIVE_ORDER)
                .isNotEqualTo(Comparator.comparing(FeeScheduleVersion::effectiveFrom));
    }
}
