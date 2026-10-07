package com.finapp.credit;

import static com.finapp.credit.AffordabilityTest.assessed;
import static com.finapp.credit.AffordabilityTest.euros;
import static com.finapp.credit.AffordabilityTest.loan;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.AffordabilityAssessment.Assessment;
import com.finapp.credit.AffordabilityAssessment.Parameters;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The affordability assessment's properties over generated inputs (`P10-TSK-009`; {@code INV-CRD-12},
 * {@code INV-MON-01}): the disposable figure is monotone in each input the right way, verified data is only ever
 * read prudently, and every figure is exact - the disposable is the identity of its parts to the minor unit, and the
 * repayment agrees with a 50-digit computation rounded once. Seeded, so a failure reproduces.
 */
@DisplayName("the affordability assessment's properties (P10-TSK-009)")
class AffordabilityPropertiesTest {

    private static final int CASES = 400;

    @Test
    @DisplayName("the disposable never falls as the income rises")
    void monotoneInIncome() {
        Random random = new Random(9001);
        for (int i = 0; i < CASES; i++) {
            Input input = Input.generate(random);
            long raise = 1 + random.nextInt(100_000);
            assertThat(input.withIncome(input.income + raise).disposable())
                    .as(input + " + " + raise).isGreaterThanOrEqualTo(input.disposable());
        }
    }

    @Test
    @DisplayName("the disposable never rises as the amount, the rate or the shortness of the term rises")
    void monotoneInAmountRateAndTerm() {
        Random random = new Random(9002);
        for (int i = 0; i < CASES; i++) {
            Input input = Input.generate(random);
            Money base = input.disposable();
            assertThat(input.withAmount(Math.min(input.amount + 1 + random.nextInt(500_000), 25_000_00)).disposable())
                    .as("amount " + input).isLessThanOrEqualTo(base);
            assertThat(input.withRate(input.rate.add(new BigDecimal(random.nextInt(500)).movePointLeft(4))).disposable())
                    .as("rate " + input).isLessThanOrEqualTo(base);
            assertThat(input.withTerm(Math.max(6, input.term - 1 - random.nextInt(12))).disposable())
                    .as("shorter term " + input).isLessThanOrEqualTo(base);
        }
    }

    @Test
    @DisplayName("verified data can lower the income and raise the expenditure, never the reverse")
    void verifiedDataIsOnlyEverPrudent() {
        Random random = new Random(9003);
        for (int i = 0; i < CASES; i++) {
            Input input = Input.generate(random);
            long verifiedIncome = random.nextInt(1_500_000);
            long verifiedSpend = random.nextInt(800_000);
            Assessment.Assessed verified = assessed(loan(input.amount, input.term, Map.of(
                    CreditAttributeCode.DECLARED_MONTHLY_INCOME, euros(input.income),
                    CreditAttributeCode.FINDATA_MONTHLY_INCOME, euros(verifiedIncome),
                    CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE, euros(verifiedSpend))), input.parameters());
            assertThat(verified.income()).as("income " + input).isEqualTo(euros(Math.min(verifiedIncome, input.income)));
            assertThat(verified.expenditure()).as("spend " + input).isEqualTo(euros(Math.max(verifiedSpend, 140_000)));
            assertThat(verified.disposable()).as("disposable " + input).isLessThanOrEqualTo(input.disposable());
        }
    }

    @Test
    @DisplayName("every figure is exact: the disposable is its parts to the minor unit, the repayment a 50-digit annuity rounded once")
    void exactAcrossGeneratedInputs() {
        Random random = new Random(9004);
        for (int i = 0; i < CASES; i++) {
            Input input = Input.generate(random);
            Assessment.Assessed assessed = input.assess();
            assertThat(assessed.disposable().minorUnits()).as("identity " + input).isEqualTo(
                    assessed.income().minorUnits() - assessed.expenditure().minorUnits()
                            - assessed.obligations().minorUnits() - assessed.repayment().minorUnits());
            assertThat(assessed.disposable().scale()).isEqualTo(2);
            assertThat(assessed.repayment().toBigDecimal()).as("annuity " + input).isEqualByComparingTo(reference(input));
            assertThat(assessed.affordable()).isEqualTo(assessed.disposable().compareTo(input.parameters().minimumDisposable()) >= 0);
        }
    }

    /** The annuity at 50 significant digits, rounded once to cents half up - the engine's oracle, not its copy. */
    private static BigDecimal reference(Input input) {
        MathContext digits = new MathContext(50, RoundingMode.HALF_EVEN);
        BigDecimal amount = BigDecimal.valueOf(input.amount, 2);
        if (input.rate.signum() == 0) {
            return amount.divide(BigDecimal.valueOf(input.term), digits).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal monthly = input.rate.divide(BigDecimal.valueOf(12), digits);
        BigDecimal discount = BigDecimal.ONE.divide(BigDecimal.ONE.add(monthly).pow(input.term, digits), digits);
        return amount.multiply(monthly, digits).divide(BigDecimal.ONE.subtract(discount), digits)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** A generated personal-loan request within the product's bounds, on declared figures. */
    private record Input(long amount, int term, BigDecimal rate, long income) {

        static Input generate(Random random) {
            long amount = 500_00 + random.nextInt(24_500_01);
            int term = 6 + random.nextInt(55);
            BigDecimal rate = random.nextInt(10) == 0 ? BigDecimal.ZERO : new BigDecimal(1 + random.nextInt(2500)).movePointLeft(4);
            long income = random.nextInt(2_000_000);
            return new Input(amount, term, rate, income);
        }

        Input withIncome(long raised) {
            return new Input(amount, term, rate, raised);
        }

        Input withAmount(long raised) {
            return new Input(raised, term, rate, income);
        }

        Input withRate(BigDecimal raised) {
            return new Input(amount, term, raised, income);
        }

        Input withTerm(int shortened) {
            return new Input(amount, shortened, rate, income);
        }

        Parameters parameters() {
            return new Parameters(rate, new BigDecimal("0.03"), euros(500_00));
        }

        Assessment.Assessed assess() {
            return assessed(loan(amount, term, Map.of(CreditAttributeCode.DECLARED_MONTHLY_INCOME, euros(income))), parameters());
        }

        Money disposable() {
            return assess().disposable();
        }
    }
}
