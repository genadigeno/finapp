package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.BornOnceArbiters;
import com.finapp.platform.testing.database.BornOnceArbiters.Probe;
import com.finapp.platform.testing.database.BornOnceArbiters.Verdict;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every born-once arbiter of the {@code lending} schema survives a lock-bypass probe (`P11-DOC-001`; {@code PHASE_GATES.md}
 * section 5 Phase 11, "Multi-instance correctness": "each arbiter surviving a lock-bypass probe").
 *
 * <p>The tasks probed nineteen of the schema's uniques with their triggers off; the exit review found some fifty never
 * probed - among them the disbursement's and the payout's {@code UNIQUE (loan_id)}, whose raw second inserts the tests make
 * are refused by the trigger first, so a dropped unique would have left every suite green. This suite closes the class
 * rather than the instances: it reads every unique index of the schema from the catalogue ({@link BornOnceArbiters}), so a
 * unique added later is probed without anyone remembering it, and demands that each one, with every user and foreign-key
 * trigger suspended, refuses a copy of a real row under a fresh key BY ITSELF.
 *
 * <p>The rows it copies are made by the real paths: the world is the module's own scenario cases, run first - a disbursed
 * loan and an external payout, accrual, billing, repayment and its reversal, an auto-collection, fees and waivers, a
 * credit line's draws, statement, credit-balance application and closure, payoffs, an amendment, a prepayment, condition
 * events and terms versions. A unique whose table holds no row to copy fails the suite unless it is one this module never
 * writes (the capital contribution's, written only through {@code app}'s treasury door and probed there).
 */
@Tag("database")
@DisplayName("every born-once arbiter of the lending schema refuses a copy alone, triggers off (P11-DOC-001)")
class LendingBornOnceArbitersDatabaseTest {

    /** Tables this module's paths never write: lending capital is contributed only through app's treasury door. */
    private static final Set<String> WRITTEN_ONLY_THROUGH_APP = Set.of("capital_contribution", "capital_contribution_event");

    @Test
    @DisplayName("every unique of every lending table, read from the catalogue, refuses a copy of a real row under a"
            + " fresh key by itself with every trigger suspended - none admits its copy, none needs a sibling to refuse it,"
            + " and every table this module writes holds a row to copy")
    void everyBornOnceArbiterRefusesACopyWithEveryTriggerOff() throws Exception {
        theWorld();
        List<Probe> probes = BornOnceArbiters.probe("lending");
        System.out.println("P11-DOC-001 ARBITERS: " + probes.stream().collect(Collectors.groupingBy(Probe::verdict,
                Collectors.counting())));
        probes.forEach(probe -> System.out.println("  " + probe));

        assertThat(probes).as("the catalogue holds the schema's uniques").hasSizeGreaterThan(50);
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.ADMITTED).as("a unique admitting its own copy")
                .isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.REFUSED_BY_ANOTHER)
                .as("a copy the probe could not isolate to its unique").isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.EXPRESSION_INDEX).isEmpty();
        Set<String> unprobed = probes.stream().filter(probe -> probe.verdict() == Verdict.NO_SOURCE_ROW)
                .filter(probe -> !WRITTEN_ONLY_THROUGH_APP.contains(probe.table()))
                .map(Probe::toString).collect(Collectors.toCollection(TreeSet::new));
        assertThat(unprobed).as("a unique left unprobed for want of a row to copy").isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.REFUSED_ALONE).hasSizeGreaterThan(50);
    }

    /** The module's own scenario cases, run for the rows they leave: every born-once fact made by its real path. */
    private static void theWorld() throws Exception {
        DisbursementWorld.ensureActive();
        new LoanOriginationDatabaseTest().noEventStillOffers();
        new LoanOriginationDatabaseTest().theCustomerDeclinesTheOffer();
        new LoanTermsDatabaseTest().fourEyesAtBothRanks();
        new LoanPayoutStepsDatabaseTest().interestStartsAtTheTerminalOutcome();
        new RepaymentReversalDatabaseTest().aReversalAfterAllocationReopensWhatItPaid();
        new AutoCollectionDatabaseTest().twoSweepersCollectOnce();
        new FeeAndWaiverDatabaseTest().aFeeWaiverReversesOnlyTheUnpaidRemainder();
        new FeeAndWaiverDatabaseTest().aWaiverOfAPaidFeeRefundsToTheCreditBalance();
        new CreditLineStatementDatabaseTest().aCreditBalancePaysTheStatement();
        new CreditLineRepaymentDatabaseTest().theLastPaymentClosesAClosingLine();
        PayoffDatabaseTest payoffs = new PayoffDatabaseTest();
        payoffs.noLineTermsProposalPending();
        payoffs.payoffZeroesEveryBalance();
        payoffs.noLineTermsProposalPending();
        payoffs.aCreditBalanceThatCoversEverythingIsRefunded();
        new AmendmentDatabaseTest().anApprovedAmendmentChangesTheScheduleOnce();
        new PrepaymentDatabaseTest().prepaymentShortensTheTermAndConserves();
        new DelinquencyDatabaseTest().defaultAtNinetyClearedAtCure();
    }
}
