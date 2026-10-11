package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.credit.CreditStorageException;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exposure read's per-loan table and its refusals, and the census's order-free rule, without a database
 * (`P11-TSK-013`; PHASE_11_PLAN.md section 12.10, ADR-0091 points 5, 6 and 9). The statement and the races are
 * {@code LendingCreditExposureDatabaseTest}'s.
 */
@DisplayName("platform credit exposure v2: the per-loan table, the refusals and the census rule (P11-TSK-013)")
class LendingCreditExposureTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("version 2 replaces Phase 10's version 1")
    void versionTwo() {
        assertThat(new LendingCreditExposure().version()).isEqualTo(2);
    }

    @Test
    @DisplayName("a loan counts its principal while pending, its principal lines once disbursed and nothing cancelled;"
            + " a line counts the greater of its limit and its drawn principal while ACTIVE, its drawn principal after")
    void thePerLoanTable() {
        assertThat(figure("INSTALMENT", "PENDING_DISBURSEMENT", 8_000_00)).isEqualTo(eur(8_000_00));
        assertThat(figure("INSTALMENT", "ACTIVE", 8_000_00, 8_000_00)).isEqualTo(eur(8_000_00));
        assertThat(figure("INSTALMENT", "ACTIVE", 8_000_00, 8_000_00, -3_000_00)).isEqualTo(eur(5_000_00));
        assertThat(figure("INSTALMENT", "CLOSED", 8_000_00)).isEqualTo(eur(0));
        assertThat(figure("INSTALMENT", "CANCELLED", 8_000_00, 1_00)).as("a cancelled loan counts nothing").isEqualTo(eur(0));
        assertThat(figure("REVOLVING", "ACTIVE", 2_500_00)).as("an open line's whole limit").isEqualTo(eur(2_500_00));
        assertThat(figure("REVOLVING", "ACTIVE", 2_500_00, 1_000_00)).isEqualTo(eur(2_500_00));
        assertThat(figure("REVOLVING", "ACTIVE", 2_500_00, 3_000_00)).as("drawn beyond the limit").isEqualTo(eur(3_000_00));
        assertThat(figure("REVOLVING", "CLOSING", 2_500_00, 700_00)).as("a closing line's drawn principal").isEqualTo(eur(700_00));
        assertThat(figure("REVOLVING", "CLOSED", 2_500_00)).isEqualTo(eur(0));
        assertThat(figure("INSTALMENT", "ACTIVE", 8_000_00, -1_00))
                .as("a principal below zero - a defect - offsets nothing").isEqualTo(eur(0));
    }

    @Test
    @DisplayName("an unknown kind or status, or two scales in one currency, is refused - never read as zero")
    void unknownsAreRefused() {
        assertThatIllegalStateException().isThrownBy(() -> figure("INSTALMENT", "CLOSING", 1_00));
        assertThatIllegalStateException().isThrownBy(() -> figure("REVOLVING", "PENDING_DISBURSEMENT", 1_00));
        assertThatIllegalStateException().isThrownBy(() -> figure("BULLET", "ACTIVE", 1_00));
        LendingCreditExposure.LoanPrincipal twoScales = new LendingCreditExposure.LoanPrincipal(UUID.randomUUID(),
                "INSTALMENT", "ACTIVE", eur(1_00), new ArrayList<>(List.of(eur(1_00), Money.ofPersisted(10, EUR, 3))));
        assertThatExceptionOfType(ScaleMismatchException.class).isThrownBy(twoScales::figure);
    }

    @Test
    @DisplayName("a lending read that fails fails the caller's transaction - it never answers zero")
    void aFailedReadIsNeverZero() {
        Connection broken = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    throw new SQLException("the database went away", "08006");
                });
        assertThatExceptionOfType(CreditStorageException.class)
                .isThrownBy(() -> new LendingCreditExposure().outstandingFor(broken, UUID.randomUUID(), EUR))
                .withMessageContaining("08006");
    }

    @Test
    @DisplayName("the census: for every limit among a party's items, those under a limit at most it fit it - order-free;"
            + " an item with a figure and no limit found fails")
    void theCensusRule() {
        assertThat(ExposureCensus.fits(List.of(item(15_000_00, 8_000_00), item(15_000_00, 7_000_00)))).isTrue();
        assertThat(ExposureCensus.fits(List.of(item(15_000_00, 8_000_00), item(15_000_00, 7_000_01)))).isFalse();
        assertThat(ExposureCensus.fits(List.of(item(10_000_00, 8_000_00), item(20_000_00, 7_000_00))))
                .as("the later decision under the higher limit saw the earlier").isTrue();
        assertThat(ExposureCensus.fits(List.of(item(10_000_00, 8_000_00), item(10_000_00, 2_000_01),
                item(20_000_00, 7_000_00)))).as("the lower limit's own items breach it").isFalse();
        assertThat(ExposureCensus.fits(List.of(new ExposureCensus.Item(Optional.empty(), eur(1_00))))).isFalse();
        assertThat(ExposureCensus.fits(List.of(new ExposureCensus.Item(Optional.empty(), eur(0))))).isTrue();
    }

    private static ExposureCensus.Item item(long limit, long figure) {
        return new ExposureCensus.Item(Optional.of(eur(limit)), eur(figure));
    }

    private static Money figure(String kind, String status, long agreed, long... nets) {
        List<Money> drawn = new ArrayList<>();
        for (long net : nets) {
            drawn.add(eur(net));
        }
        return new LendingCreditExposure.LoanPrincipal(UUID.randomUUID(), kind, status, eur(agreed), drawn).figure();
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
