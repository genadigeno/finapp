package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The comparison that makes assurance a level rather than a boolean (`P1-TSK-013`, INV-IDN-05).
 *
 * <p>Swept over every pair rather than sampled, because the failure mode is one pair being wrong -
 * and it would be the pair nobody thought to write down. The completion gate found this type had no
 * test at all, which for the mechanism INV-IDN-05 names as its enforcement is the wrong number.
 */
@DisplayName("AssuranceLevel (P1-TSK-013)")
class AssuranceLevelTest {

    @Test
    @DisplayName("every level satisfies every level at or below it, and none above")
    void theOrderingHoldsForEveryPair() {
        AssuranceLevel[] levels = AssuranceLevel.values();
        for (int held = 0; held < levels.length; held++) {
            for (int required = 0; required < levels.length; required++) {
                assertThat(levels[held].atLeast(levels[required]))
                        .as("%s satisfies a requirement for %s", levels[held], levels[required])
                        .isEqualTo(held >= required);
            }
        }
    }

    @Test
    @DisplayName("the declared order is the escalation order, and reordering would be silent")
    void theOrderIsTheDecision() {
        // ordinal() carries the meaning, so the DECLARATION ORDER is load-bearing: swapping two
        // constants changes the answer to every comparison in the platform and compiles cleanly.
        // Pinned here so that change is a failing test rather than a code review nobody had.
        assertThat(AssuranceLevel.values())
                .containsExactly(
                        AssuranceLevel.PASSWORD,
                        AssuranceLevel.MULTI_FACTOR,
                        AssuranceLevel.STRONG);
    }

    @Test
    @DisplayName("a password session does not satisfy a multi-factor requirement")
    void theCaseTheInvariantIsAbout() {
        // INV-IDN-05, stated as the single assertion it comes down to.
        assertThat(AssuranceLevel.PASSWORD.atLeast(AssuranceLevel.MULTI_FACTOR)).isFalse();
    }
}
