package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The risk seam's record (`P10-TSK-008`, seam version 2 `P13-TSK-007`; ADR-0084 point 5; {@code INV-CRD-04},
 * {@code INV-RSK-10}): a closed code, never a number; a seam version from 1; a profile version only from seam version 2,
 * counted from 1 - the shape the freezer pins into every snapshot.
 */
@DisplayName("the risk seam's signal (P10-TSK-008, P13-TSK-007)")
class CreditRiskSignalTest {

    @Test
    @DisplayName("seam version 1 answers from no profile; seam version 2 names the profile version it read, or none for a"
            + " party not assessed")
    void theVersionsAreTheSeamsAndTheProfiles() {
        CreditRiskSignal.RiskSignal v1 = new CreditRiskSignal.RiskSignal("NOT_ASSESSED", 1);
        assertThat(v1.profileVersion()).isEmpty();
        assertThatIllegalArgumentException().as("version 1 never had a profile to read")
                .isThrownBy(() -> new CreditRiskSignal.RiskSignal("LOW", 1, OptionalLong.of(3)));
        assertThat(new CreditRiskSignal.RiskSignal("HIGH", 2, OptionalLong.of(3)).profileVersion()).hasValue(3);
        assertThat(new CreditRiskSignal.RiskSignal("NOT_ASSESSED", 2).profileVersion()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditRiskSignal.RiskSignal("HIGH", 2, OptionalLong.of(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditRiskSignal.RiskSignal("HIGH", 0));
    }

    @Test
    @DisplayName("the answer is a closed code - never a number, never free text (INV-CRD-04)")
    void theAnswerIsACode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditRiskSignal.RiskSignal("420", 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditRiskSignal.RiskSignal("high risk", 2));
        assertThat(new CreditRiskSignal.RiskSignal("PROHIBITED", 2, OptionalLong.of(9)).code()).isEqualTo("PROHIBITED");
    }
}
