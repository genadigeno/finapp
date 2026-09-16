package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sign convention, hermetically — the one place {@code Direction} meets
 * {@code NormalBalance} (`P3-TSK-008`).
 *
 * <p>This is where "every account type" is satisfiable: {@code EQUITY} has no
 * {@link AccountPurpose} and therefore no account anything can post to, so the database tier
 * covers the four reachable types over real rows and this sweep covers the definition for all
 * five via {@link AccountType#values()}.
 */
@DisplayName("the settled-balance sign convention (P3-TSK-008)")
class BalanceDerivationTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    @DisplayName("every account type settles as normal side minus opposite side")
    void everyAccountTypeSettlesSignedByItsNormalBalance() {
        Money debits = Money.ofMinorUnits(30_00, USD);
        Money credits = Money.ofMinorUnits(10_00, USD);
        for (AccountType type : AccountType.values()) {
            Money settled = BalanceDerivation.settle(type.normalBalance(), debits, credits);
            Money expected =
                    type.normalBalance() == NormalBalance.DEBIT
                            ? Money.ofMinorUnits(20_00, USD)
                            : Money.ofMinorUnits(-20_00, USD);
            assertThat(settled)
                    .as("%s (normal %s): the account's own terms", type, type.normalBalance())
                    .isEqualTo(expected);
            // The inverse posting pattern reads negative in the account's own terms - a legal
            // state, not an error: refusing it would be overdraft policy, which is
            // P3-TSK-015's hold logic, not accounting.
            assertThat(BalanceDerivation.settle(type.normalBalance(), credits, debits))
                    .isEqualTo(expected.negated());
        }
    }

    @Test
    @DisplayName("a zero side adopts the other's scale - one-sided history is not mixed-scale")
    void aZeroSideAdoptsTheOthersScale() {
        // An account whose whole history sits on one side at a persisted scale (INV-MON-05):
        // the zero identity's default scale is an artefact, not a fact about the history -
        // the scale-aware zero (P3-TSK-004's recorded property), applied at the subtraction.
        Money persistedScaleDebits = Money.ofPersisted(1500, USD, 3);
        Money zero = Money.zero(USD);
        assertThat(BalanceDerivation.settle(NormalBalance.DEBIT, persistedScaleDebits, zero))
                .isEqualTo(Money.ofPersisted(1500, USD, 3));
        assertThat(BalanceDerivation.settle(NormalBalance.CREDIT, persistedScaleDebits, zero))
                .isEqualTo(Money.ofPersisted(-1500, USD, 3));
        // No lines at all: zero in the account's own currency at its current default scale.
        assertThat(BalanceDerivation.settle(NormalBalance.DEBIT, zero, zero))
                .isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("a genuinely mixed-scale pair refuses rather than rescaling")
    void aGenuinelyMixedScalePairRefuses() {
        // Both sides carry value at different scales: summing them would be the implicit
        // rescale INV-MON-03 forbids, so the kernel refuses and the derivation translates
        // (JdbcBalanceDerivation) rather than computing a number that means nothing.
        Money scaleTwo = Money.ofPersisted(1500, USD, 2);
        Money scaleThree = Money.ofPersisted(1500, USD, 3);
        assertThatThrownBy(
                        () -> BalanceDerivation.settle(NormalBalance.DEBIT, scaleTwo, scaleThree))
                .isInstanceOf(ScaleMismatchException.class);
    }
}
