package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.BornOnceArbiters;
import com.finapp.platform.testing.database.BornOnceArbiters.Probe;
import com.finapp.platform.testing.database.BornOnceArbiters.Verdict;
import java.util.List;
import java.util.Map;
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
 * writes (the capital contribution's, written only through {@code app}'s treasury door and probed there -
 * {@code LendingCapitalDatabaseTest#everyCapitalArbiterRefusesACopyWithEveryTriggerOff}). Every {@code CHECK} of the
 * table is dropped inside each probe's savepoint, so the copy meets the unique and nothing else.
 */
@Tag("database")
@DisplayName("every born-once arbiter of the lending schema refuses a copy alone, triggers off (P11-DOC-001)")
class LendingBornOnceArbitersDatabaseTest {

    /**
     * Every unique of the schema, pinned: a catalogue-driven probe cannot see a unique that is not there, so a dropped one
     * would simply leave the list (the review's own probe found exactly that - `MUTATION_TESTING.md` section 2). A unique
     * added later fails this suite until it is named here - and probed by the same run.
     */
    private static final Set<String> THE_SCHEMAS_UNIQUES = Set.of(
            "accrual_start_loan_unique", "capital_contribution_born_once", "capital_contribution_decided_once",
            "capital_contribution_event_expectation_once", "capital_contribution_proposer_pinned",
            "capital_contribution_reference_once", "collection_attempt_item_date_unique",
            "collection_attempt_repayment_unique", "credit_balance_allocation_component_unique",
            "credit_balance_allocation_ordinal_unique", "credit_balance_application_billing_unique",
            "credit_balance_application_entry_unique", "credit_line_draw_entry_unique",
            "credit_line_draw_key_unique", "credit_line_statement_cycle_unique",
            "credit_line_statement_due_date_unique", "credit_line_statement_entry_unique",
            "fee_assessment_entry_unique", "fee_assessment_of_its_loan", "fee_assessment_once_per_item",
            "instalment_billing_due_date_unique", "instalment_billing_entry_unique",
            "instalment_billing_instalment_unique", "interest_accrual_date_unique", "interest_accrual_entry_unique",
            "line_closure_request_loan_unique", "loan_acceptance_agreement_unique",
            "loan_agreement_amendment_unique", "loan_agreement_prepayment_unique", "loan_agreement_version_unique",
            "loan_amendment_decision_once", "loan_amendment_of_its_loan", "loan_amendment_proposer_pinned",
            "loan_amendment_rescheduling_once_per_item", "loan_application_decision_request_unique",
            "loan_application_one_open", "loan_closure_loan_unique", "loan_closure_refund_entry_unique",
            "loan_condition_event_once_a_day", "loan_decision_unique", "loan_disbursement_entry_unique",
            "loan_disbursement_loan_unique", "loan_instalment_sequence_unique", "loan_offer_application_unique",
            "loan_offer_decision_unique", "loan_offer_product_pinned", "loan_offer_unique",
            "loan_payout_hold_unique", "loan_payout_loan_unique", "loan_payout_withdrawal_unique",
            "loan_terms_one_active", "loan_terms_one_proposed", "loan_terms_version_number_unique",
            "loan_terms_version_product_pinned", "payoff_execution_loan_unique", "payoff_execution_quote_unique",
            "payoff_quote_key_unique", "repayment_allocation_component_unique",
            "repayment_allocation_ordinal_unique", "repayment_allocation_overflow_unique",
            "repayment_allocation_reversal_allocation_unique", "repayment_entry_unique", "repayment_key_unique",
            "repayment_of_its_loan", "repayment_reversal_entry_unique", "repayment_reversal_one_live",
            "repayment_schedule_agreement_unique", "repayment_schedule_version_unique",
            "waiver_decision_entry_unique", "waiver_decision_once", "waiver_proposer_pinned");

    /**
     * The uniques that bind the whole primary key - the composite targets of "of its loan" and "proposer pinned" foreign
     * keys, which the key already implies - pinned too: a born-once unique widened to include the key would otherwise
     * pass as one of them (the review's second probe found that - `MUTATION_TESTING.md` section 2).
     */
    private static final Set<String> FOREIGN_KEY_TARGETS = Set.of("capital_contribution_proposer_pinned",
            "fee_assessment_of_its_loan", "loan_amendment_of_its_loan", "loan_amendment_proposer_pinned",
            "loan_offer_product_pinned", "loan_terms_version_product_pinned", "repayment_of_its_loan",
            "waiver_proposer_pinned");

    /** Tables this module's paths never write: lending capital is contributed only through app's treasury door. */
    private static final Set<String> WRITTEN_ONLY_THROUGH_APP = Set.of("capital_contribution", "capital_contribution_event");

    @Test
    @DisplayName("every unique of every lending table, read from the catalogue, refuses a copy of a real row under a"
            + " fresh key by itself with every trigger suspended - none admits its copy, none needs a sibling to refuse it,"
            + " and every table this module writes holds a row to copy")
    void everyBornOnceArbiterRefusesACopyWithEveryTriggerOff() throws Exception {
        theWorld();
        List<Probe> probes = BornOnceArbiters.probe("lending", Map.of(
                // Two partial indexes whose predicate no settled world leaves a row inside: one row put there, in the
                // probe's own rolled-back savepoint, triggers off - an application still open, a terms version proposed.
                "loan_application_one_open", "UPDATE lending.loan_application SET status = 'SUBMITTED'"
                        + " WHERE id = (SELECT id FROM lending.loan_application ORDER BY id LIMIT 1)",
                "loan_terms_one_proposed", "UPDATE lending.loan_terms_version SET status = 'PROPOSED'"
                        + " WHERE id = (SELECT id FROM lending.loan_terms_version WHERE status = 'REJECTED' ORDER BY id"
                        + " LIMIT 1)"));
        System.out.println("P11-DOC-001 ARBITERS: " + probes.stream().collect(Collectors.groupingBy(Probe::verdict,
                Collectors.counting())));
        probes.forEach(probe -> System.out.println("  " + probe));

        assertThat(probes).extracting(Probe::constraint).as("the schema's uniques, every one still standing")
                .containsExactlyInAnyOrderElementsOf(THE_SCHEMAS_UNIQUES);
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.ADMITTED).as("a unique admitting its own copy")
                .isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.REFUSED_BY_ANOTHER)
                .as("a copy the probe could not isolate to its unique").isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.EXPRESSION_INDEX).isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.PRIMARY_KEY_BOUND).extracting(Probe::constraint)
                .as("only the foreign-key targets bind the whole key").containsExactlyInAnyOrderElementsOf(
                        FOREIGN_KEY_TARGETS);
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
        new AmendmentDatabaseTest().arrearsRescheduledConservePrincipal();
        new PrepaymentDatabaseTest().prepaymentShortensTheTermAndConserves();
        new DelinquencyDatabaseTest().defaultAtNinetyClearedAtCure();
    }
}
