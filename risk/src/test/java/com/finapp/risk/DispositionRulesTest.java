package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who a disposition needs (`P13-TSK-016`; {@code INV-RSK-07}; ADR-0110 decision 6; {@code DESIGN-016} D2-D4): the two
 * persons' set is {@code CONFIRMED_*}, {@code REFERRED} and any prohibiting disposition - each also needing the case
 * taken - and the one-person closes are the two {@code CLOSED_*}; PROHIBITED is asked only of a confirming disposition
 * of a party's case; the rule is the {@code CHECK}'s list too ({@code RiskCaseMigrationTest}).
 */
@DisplayName("which dispositions need the case taken and two persons (P13-TSK-016)")
class DispositionRulesTest {

    @Test
    @DisplayName("CONFIRMED_*, REFERRED and every prohibiting disposition are two persons' act on a taken case; the"
            + " CLOSED_* that prohibit nothing are one person's")
    void whoADispositionNeeds() {
        Set<Disposition> twoPersons = EnumSet.noneOf(Disposition.class);
        for (Disposition disposition : Disposition.values()) {
            if (DispositionRules.needsSecondPerson(disposition, false)) {
                twoPersons.add(disposition);
            }
            assertThat(DispositionRules.needsTaken(disposition, false))
                    .isEqualTo(DispositionRules.needsSecondPerson(disposition, false));
            assertThat(DispositionRules.needsSecondPerson(disposition, true)).as(disposition + " prohibiting").isTrue();
        }
        assertThat(twoPersons).containsExactlyInAnyOrder(Disposition.CONFIRMED_FRAUD, Disposition.CONFIRMED_SUSPICIOUS,
                Disposition.REFERRED);
        assertThat(EnumSet.complementOf(EnumSet.copyOf(twoPersons))).containsExactlyInAnyOrder(
                Disposition.CLOSED_FALSE_POSITIVE, Disposition.CLOSED_NO_ACTION);
    }

    @Test
    @DisplayName("PROHIBITED only of a confirming disposition of a PARTY's case - never of a referral, a close, a"
            + " merchant's or an account's")
    void whoMayBeProhibited() {
        for (Disposition disposition : Disposition.values()) {
            for (SubjectKind subject : SubjectKind.values()) {
                assertThat(DispositionRules.mayProhibit(disposition, subject)).as(disposition + " of a " + subject)
                        .isEqualTo(disposition.confirms() && subject == SubjectKind.PARTY);
            }
        }
    }

    @Test
    @DisplayName("item 78 (the Phase 13 gate): a CLOSED_* of a case holding an alert is two persons' act on a taken"
            + " case - every kind but COLLECTIONS (C3); a case holding no alert keeps the one-person close")
    void aCaseHoldingAnAlertIsClosedByTwo() {
        for (CaseKind kind : CaseKind.values()) {
            for (Disposition disposition : Disposition.values()) {
                boolean byTwo = disposition.fourEyes() || kind != CaseKind.COLLECTIONS;
                assertThat(DispositionRules.needsSecondPerson(disposition, false, kind, true))
                        .as(disposition + " of a " + kind + " case holding an alert").isEqualTo(byTwo);
                assertThat(DispositionRules.needsTaken(disposition, false, kind, true))
                        .as(disposition + " of a " + kind + " case holding an alert, taken").isEqualTo(byTwo);
                assertThat(DispositionRules.needsSecondPerson(disposition, false, kind, false))
                        .as(disposition + " of a " + kind + " case holding none").isEqualTo(disposition.fourEyes());
            }
        }
    }
}
