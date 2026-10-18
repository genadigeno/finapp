package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.bnpl.BnplAccounts;
import com.finapp.credit.CreditStorageException;
import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Platform credit exposure version 3, hermetic (`P12-TSK-012`; ADR-0104, {@code INV-BNPL-10}, {@code INV-CRD-09},
 * {@code INV-BAL-05}): version 2's loans and lines plus every BNPL obligation's principal, in ONE statement; a closed
 * obligation's principal still counted, a negative one zero, an uninterpretable row and a failed read refused - never
 * zero; bnpl's own principal purposes bound, never named here. The live read against the real purchase, both orders of an
 * acceptance and a decision, and the census are {@code BnplCreditExposureDatabaseTest}'s.
 */
@DisplayName("platform credit exposure v3: loans, lines and BNPL principal in one statement (P12-TSK-012)")
class BnplCreditExposureTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("version 3, recorded as the snapshot's provenance; versions 1 and 2 replay from their own snapshots")
    void versionThree() {
        assertThat(new BnplCreditExposure().version()).isEqualTo(3);
        assertThat(new LendingCreditExposure().version()).as("version 2 is kept, unwired, for what it decided").isEqualTo(2);
    }

    @Test
    @DisplayName("the read is ONE statement over both views and the journal lines - never two, never account_balance")
    void theReadIsOneStatement() {
        Recorded recorded = new Recorded(List.of());
        new BnplCreditExposure().outstandingFor(recorded.connection(), UUID.randomUUID(), EUR);
        assertThat(recorded.statements).as("one statement - one snapshot - so a purchase committing during the read is"
                + " counted once").hasSize(1);
        String sql = recorded.statements.get(0);
        assertThat(sql).contains("FROM lending.party_principal_exposure e").contains("FROM bnpl.party_principal_exposure b")
                .contains("ledger.journal_line").contains("UNION ALL").doesNotContain("account_balance");
    }

    @Test
    @DisplayName("each arm binds its owner kind and its module's own two principal purposes - bnpl's declaration, so"
            + " app never names the BNPL book")
    void eachArmBindsItsOwnPrincipalPurposes() {
        Recorded recorded = new Recorded(List.of());
        UUID party = UUID.randomUUID();
        new BnplCreditExposure().outstandingFor(recorded.connection(), party, EUR);
        assertThat(BnplAccounts.principalPurposes()).containsExactly(AccountPurpose.BNPL_PRINCIPAL,
                AccountPurpose.BNPL_PRINCIPAL_DUE);
        assertThat(recorded.parameters.values()).containsSubsequence("LOAN", "LOAN_PRINCIPAL", "LOAN_PRINCIPAL_DUE",
                party, "EUR", "BNPL_OBLIGATION", "BNPL_PRINCIPAL", "BNPL_PRINCIPAL_DUE", party, "EUR");
        assertThat(recorded.parameters).hasSize(10);
        assertThat(BnplCreditExposure.PER_SUBJECT).doesNotContain("'BNPL_").doesNotContain("'LOAN_");
    }

    @Test
    @DisplayName("a party holding a loan, a line and a BNPL obligation is counted once each: the loan's principal, the"
            + " line's limit, the obligation's principal not yet due plus billed")
    void aLoanALineAndAPurchaseAreEachCountedOnce() {
        UUID loan = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID obligation = UUID.randomUUID();
        Recorded recorded = new Recorded(List.of(
                row("LOAN", loan, "INSTALMENT", "ACTIVE", 2, 8_000_00L, 2, 5_000_00L),
                row("LOAN", line, "REVOLVING", "ACTIVE", 2, 2_500_00L, null, null),
                row("BNPL", obligation, "BNPL_PAY_IN_4", "ACTIVE", null, null, 2, 450_00L)));
        assertThat(new BnplCreditExposure().outstandingFor(recorded.connection(), UUID.randomUUID(), EUR))
                .isEqualTo(eur(5_000_00 + 2_500_00 + 450_00));
    }

    @Test
    @DisplayName("an obligation counts its principal lines whatever its status: repaid principal stops counting, a"
            + " closed obligation adds zero - and a planted principal on a closed obligation is still exposure")
    void aClosedObligationCountsItsPrincipal() {
        assertThat(obligation("ACTIVE", 600_00, -150_00)).as("instalment 1 repaid").isEqualTo(eur(450_00));
        assertThat(obligation("CLOSED", 600_00, -600_00)).as("closed: zero by the closure rule").isEqualTo(eur(0));
        assertThat(obligation("CLOSED", 25_00)).as("a defect leaving principal on a closed obligation is exposure")
                .isEqualTo(eur(25_00));
        assertThat(obligation("ACTIVE", 600_00, -600_00)).as("principal cancelled by a refund").isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a principal below zero counts zero for its obligation - never offsetting another's")
    void aNegativePrincipalCountsZero() {
        assertThat(obligation("ACTIVE", -1_00)).isEqualTo(eur(0));
        Recorded recorded = new Recorded(List.of(
                row("BNPL", UUID.randomUUID(), "BNPL_PAY_IN_N", "ACTIVE", null, null, 2, -100_00L),
                row("BNPL", UUID.randomUUID(), "BNPL_PAY_IN_N", "ACTIVE", null, null, 2, 300_00L)));
        assertThat(new BnplCreditExposure().outstandingFor(recorded.connection(), UUID.randomUUID(), EUR))
                .isEqualTo(eur(300_00));
    }

    @Test
    @DisplayName("an uninterpretable row fails the read: an unknown status or product, an obligation with no principal"
            + " line, an unknown source, two scales - never read as zero")
    void anUninterpretableRowFailsTheRead() {
        assertThatIllegalStateException().isThrownBy(() -> obligation("CLOSING", 1_00));
        assertThatIllegalStateException().isThrownBy(() -> new BnplCreditExposure.Obligation(UUID.randomUUID(),
                "BNPL_PAY_IN_7", "ACTIVE", EUR, new ArrayList<>(List.of(eur(1_00)))).figure());
        assertThatIllegalStateException().isThrownBy(() -> obligation("ACTIVE"));
        List<Object[]> card = new ArrayList<>();
        card.add(row("CARD", UUID.randomUUID(), "X", "ACTIVE", null, null, 2, 1L));
        Recorded unknown = new Recorded(card);
        assertThatIllegalStateException().isThrownBy(
                () -> new BnplCreditExposure().outstandingFor(unknown.connection(), UUID.randomUUID(), EUR));
        BnplCreditExposure.Obligation twoScales = new BnplCreditExposure.Obligation(UUID.randomUUID(), "BNPL_PAY_IN_4",
                "ACTIVE", EUR, new ArrayList<>(List.of(eur(1_00), Money.ofPersisted(10, EUR, 3))));
        assertThatExceptionOfType(ScaleMismatchException.class).isThrownBy(twoScales::figure);
        assertThat(new BnplCreditExposure.Obligation(UUID.randomUUID(), "BNPL_PAY_IN_4", "ACTIVE", EUR,
                new ArrayList<>(List.of(eur(1_00)))).toString()).doesNotContain("1.00");
    }

    @Test
    @DisplayName("a read that fails fails the caller's transaction - it never answers zero")
    void aFailedReadIsNeverZero() {
        Connection broken = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    throw new SQLException("the database went away", "08006");
                });
        assertThatExceptionOfType(CreditStorageException.class)
                .isThrownBy(() -> new BnplCreditExposure().outstandingFor(broken, UUID.randomUUID(), EUR))
                .withMessageContaining("08006");
    }

    @Test
    @DisplayName("the census holds a BNPL obligation to its decision's policy limit beside the party's other items")
    void theCensusFindsAnOverLimitPartyHoldingBnpl() {
        ExposureCensus.Item loan = new ExposureCensus.Item(Optional.of(eur(1_000_00)), eur(600_00));
        ExposureCensus.Item bnpl = new ExposureCensus.Item(Optional.of(eur(1_000_00)),
                new BnplCreditExposure.Obligation(UUID.randomUUID(), "BNPL_PAY_IN_4", "ACTIVE", EUR,
                        new ArrayList<>(List.of(eur(450_00)))).figure());
        assertThat(ExposureCensus.fits(List.of(loan, bnpl))).as("1,050.00 over a 1,000.00 limit").isFalse();
        assertThat(ExposureCensus.fits(List.of(loan, new ExposureCensus.Item(Optional.of(eur(1_000_00)), eur(400_00)))))
                .isTrue();
        assertThat(ExposureCensus.BNPL_OUTSTANDING).contains("FROM bnpl.party_principal_exposure b")
                .contains("credit.credit_policy_version").doesNotContain("'BNPL_");
    }

    // -----------------------------------------------------------------

    private static Money obligation(String status, long... nets) {
        List<Money> lines = new ArrayList<>();
        for (long net : nets) {
            lines.add(eur(net));
        }
        return new BnplCreditExposure.Obligation(UUID.randomUUID(), "BNPL_PAY_IN_4", status, EUR, lines).figure();
    }

    private static Object[] row(String source, UUID subject, String kind, String status, Integer agreementScale,
            Long agreementAmount, Integer scale, Long net) {
        return new Object[] {source, subject, kind, status, agreementScale, agreementAmount, scale,
                net == null ? null : BigDecimal.valueOf(net)};
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /** A connection answering one statement with canned rows, recording the SQL and the bound parameters. */
    private static final class Recorded {

        final List<String> statements = new ArrayList<>();
        final Map<Integer, Object> parameters = new TreeMap<>();
        private final List<Object[]> rows;

        Recorded(List<Object[]> rows) {
            this.rows = rows;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            statements.add((String) args[0]);
                            yield statement();
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setString", "setObject" -> {
                            parameters.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "executeQuery" -> results();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ResultSet results() {
            int[] at = {-1};
            Object[] last = {null};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "next":
                                return ++at[0] < rows.size();
                            case "close":
                                return null;
                            case "wasNull":
                                return last[0] == null;
                            default:
                                break;
                        }
                        Object value = rows.get(at[0])[(Integer) args[0] - 1];
                        last[0] = value;
                        return switch (method.getName()) {
                            case "getString", "getBigDecimal" -> value;
                            case "getObject" -> value;
                            case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                            case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                            default -> throw new UnsupportedOperationException(method.getName() + Arrays.toString(args));
                        };
                    });
        }
    }
}
