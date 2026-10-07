package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reserved-exposure contract (`P10-TSK-010`; ADR-0088 section 4, PHASE_10_PLAN.md section 12.4; {@code INV-CRD-09}):
 * exactly which decisions reserve. A party's reserved exposure is the sum of the approved amounts of its decisions that
 * are {@code APPROVED}, whose {@code valid_until} is still ahead of the implementation's clock - the database's,
 * {@code statement_timestamp()}, for every implementation that is not a test double - and that have no consumption row.
 * A declined decision, a lapsed one (exactly at {@code valid_until} included), a consumed one and another party's
 * reserve nothing.
 *
 * <p>Run here over {@link InMemoryReservedExposureContractTest}'s fake; the JDBC implementation and its lock arrive with
 * {@code credit_decision} in `P10-TSK-016`, which extends this class unchanged.
 *
 * @param <T> the implementation's unit of work
 */
abstract class ReservedExposureContract<T> {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** The implementation under test. */
    protected abstract ReservedExposure<T> implementation();

    /** A unit of work to read on. */
    protected abstract T unitOfWork();

    /**
     * Records a decision for {@code party}: its outcome, its approved amount (a declined decision's is ignored), its
     * validity relative to the implementation's own clock ({@code Duration.ZERO} lapses exactly now, a negative one has
     * lapsed), and whether it has been consumed.
     */
    protected abstract void decided(
            UUID party, DecisionOutcome outcome, Money approved, Duration validityFromNow, boolean consumed);

    private static UUID party() {
        return UUID.randomUUID();
    }

    private static Money euros(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    private Money reserved(UUID party) {
        return implementation().reservedFor(unitOfWork(), party, EUR);
    }

    @Test
    @DisplayName("nothing decided reserves zero, in the asked currency")
    void nothingDecidedIsZero() {
        assertThat(reserved(party())).isEqualTo(Money.zero(EUR));
        assertThat(implementation().version()).as("a version to record").isPositive();
    }

    @Test
    @DisplayName("the party's current approvals reserve their approved amounts, summed exactly")
    void currentApprovalsAreSummed() {
        UUID party = party();
        decided(party, DecisionOutcome.APPROVED, euros(250_000), Duration.ofDays(30), false);
        decided(party, DecisionOutcome.APPROVED, euros(120_050), Duration.ofSeconds(5), false);
        assertThat(reserved(party)).isEqualTo(euros(370_050));
    }

    @Test
    @DisplayName("a decision lapsing exactly at valid_until reserves nothing - and nor does one already lapsed")
    void aLapsedDecisionReservesNothing() {
        UUID party = party();
        decided(party, DecisionOutcome.APPROVED, euros(100_000), Duration.ZERO, false);
        decided(party, DecisionOutcome.APPROVED, euros(200_000), Duration.ofSeconds(-1), false);
        decided(party, DecisionOutcome.APPROVED, euros(300_00), Duration.ofDays(1), false);
        assertThat(reserved(party)).isEqualTo(euros(300_00));
    }

    @Test
    @DisplayName("a consumed decision reserves nothing - the loan it became is the exposure")
    void aConsumedDecisionReservesNothing() {
        UUID party = party();
        decided(party, DecisionOutcome.APPROVED, euros(100_000), Duration.ofDays(30), true);
        assertThat(reserved(party)).isEqualTo(Money.zero(EUR));
    }

    @Test
    @DisplayName("a declined decision reserves nothing")
    void aDeclinedDecisionReservesNothing() {
        UUID party = party();
        decided(party, DecisionOutcome.DECLINED, euros(100_000), Duration.ofDays(30), false);
        assertThat(reserved(party)).isEqualTo(Money.zero(EUR));
    }

    @Test
    @DisplayName("another party's approvals reserve nothing for this one")
    void anotherPartysApprovalsAreTheirs() {
        UUID party = party();
        UUID other = party();
        decided(other, DecisionOutcome.APPROVED, euros(100_000), Duration.ofDays(30), false);
        assertThat(reserved(party)).isEqualTo(Money.zero(EUR));
        assertThat(reserved(other)).isEqualTo(euros(100_000));
    }
}
