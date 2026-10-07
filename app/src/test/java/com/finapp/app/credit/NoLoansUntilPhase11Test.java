package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 10's platform credit exposure (`P10-TSK-010`, ADR-0088 section 6): zero for every party, with its version. */
@DisplayName("Phase 10's platform credit exposure answers zero with its version (P10-TSK-010)")
class NoLoansUntilPhase11Test {

    @Test
    @DisplayName("every party's outstanding platform credit is zero, in the asked currency, under version 1")
    void platformOutstandingIsZeroWithItsVersion() {
        NoLoansUntilPhase11 composition = new NoLoansUntilPhase11();
        assertThat(composition.version()).isEqualTo(1);
        for (String currency : new String[] {"EUR", "JPY", "BHD"}) {
            CurrencyCode code = CurrencyCode.of(currency);
            assertThat(composition.outstandingFor(null, UUID.randomUUID(), code)).isEqualTo(Money.zero(code));
        }
    }
}
