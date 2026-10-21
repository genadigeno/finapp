package com.finapp.bnpl;

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
 * Every born-once arbiter of the {@code bnpl} schema survives a lock-bypass probe (`P12-DOC-001`; {@code PHASE_GATES.md}
 * section 5 Phase 12, "Multi-instance execution": "each arbiter surviving a lock-bypass probe"; "Testing": "each born-once
 * arbiter probed alone with the lock or conditional above it bypassed").
 *
 * <p>The Phase 11 exit review's {@code LendingBornOnceArbitersDatabaseTest} applied to {@code bnpl}. The Phase 12 tasks
 * probed a handful of the schema's uniques live (the acceptance's, the collection's, the accrual's, the condition's, the late
 * fee's, the reversal's); their other live probes were recorded "written and not run" and never performed. This suite closes
 * the class rather than the instances: it reads every unique index of the schema from the catalogue
 * ({@link BornOnceArbiters}) and demands that each one, with every user and foreign-key trigger suspended and every
 * {@code CHECK} of its table dropped inside the probe's own rolled-back savepoint, refuses a copy of a real row under a fresh
 * key BY ITSELF - so neither a lock above it (B3, B4, B5, B6, the claim) nor a trigger beside it is what refuses the copy.
 *
 * <p>The rows it copies are made by the real paths: the world is the module's own scenario cases, run first. Both sets are
 * pinned - a catalogue-driven probe cannot see a unique that is gone, and a born-once unique widened to include the key would
 * otherwise pass as a foreign-key target (the two blind spots the Phase 11 review's own probe found).
 */
@Tag("database")
@DisplayName("every born-once arbiter of the bnpl schema refuses a copy alone, triggers off (P12-DOC-001)")
class BnplBornOnceArbitersDatabaseTest {

    /** Every unique of the schema, pinned (a unique added later fails this suite until it is named here and probed). */
    private static final Set<String> THE_SCHEMAS_UNIQUES = Set.of(
            "bnpl_acceptance_agreement_unique", "bnpl_agreement_refund_unique", "bnpl_agreement_version_unique",
            "bnpl_application_decision_request_unique", "bnpl_application_one_open_per_party_product",
            "bnpl_application_one_open_per_session", "bnpl_billing_due_date_unique",
            "bnpl_billing_instalment_unique", "bnpl_billing_journal_entry_unique",
            "bnpl_collection_attempt_instalment_date_unique", "bnpl_collection_attempt_repayment_unique",
            "bnpl_condition_event_once_a_day", "bnpl_credit_balance_allocation_component_unique",
            "bnpl_credit_balance_allocation_ordinal_unique", "bnpl_credit_balance_application_billing_unique",
            "bnpl_credit_balance_application_catch_up_unique", "bnpl_credit_balance_application_entry_unique",
            "bnpl_dispute_event_once_per_state", "bnpl_dispute_hold_sequence_unique",
            "bnpl_dispute_one_open_per_purchase", "bnpl_dispute_proposal_one_live", "bnpl_dispute_proposal_unique",
            "bnpl_dispute_refund_unique", "bnpl_dispute_request_unique", "bnpl_fee_assessment_entry_unique",
            "bnpl_fee_assessment_of_its_instalment", "bnpl_fee_assessment_once_per_instalment",
            "bnpl_instalment_identity", "bnpl_instalment_sequence_unique", "bnpl_interest_accrual_date_unique",
            "bnpl_interest_accrual_entry_unique", "bnpl_obligation_agreement_unique",
            "bnpl_obligation_closure_obligation_unique", "bnpl_obligation_closure_refund_entry_unique",
            "bnpl_obligation_decision_unique", "bnpl_obligation_purchase_unique", "bnpl_offer_application_unique",
            "bnpl_offer_decision_unique", "bnpl_offer_product_pinned", "bnpl_payoff_execution_obligation_unique",
            "bnpl_payoff_execution_quote_unique", "bnpl_payoff_quote_key_unique",
            "bnpl_payoff_quote_of_its_obligation", "bnpl_refund_adjustment_sequence_unique",
            "bnpl_refund_dispute_unique", "bnpl_refund_journal_entry_unique", "bnpl_refund_request_unique",
            "bnpl_repayment_allocation_component_unique", "bnpl_repayment_allocation_held_unique",
            "bnpl_repayment_allocation_reversal_allocation_unique", "bnpl_repayment_allocation_sequence_unique",
            "bnpl_repayment_journal_entry_unique", "bnpl_repayment_request_unique",
            "bnpl_repayment_reversal_entry_unique", "bnpl_repayment_reversal_one_live", "bnpl_schedule_identity",
            "bnpl_schedule_refund_unique", "bnpl_schedule_version_unique", "bnpl_terms_one_active",
            "bnpl_terms_one_proposed", "bnpl_terms_version_number_unique", "bnpl_terms_version_product_pinned",
            "bnpl_waiver_decision_entry_unique", "bnpl_waiver_decision_once", "bnpl_waiver_proposer_pinned",
            "merchant_financing_journal_entry_unique", "purchase_checkout_session_unique", "purchase_offer_unique");

    /** The uniques that bind the whole primary key - composite foreign-key targets the key already implies. */
    private static final Set<String> FOREIGN_KEY_TARGETS = Set.of(
            "bnpl_fee_assessment_of_its_instalment", "bnpl_instalment_identity", "bnpl_offer_product_pinned",
            "bnpl_payoff_quote_of_its_obligation", "bnpl_schedule_identity", "bnpl_terms_version_product_pinned",
            "bnpl_waiver_proposer_pinned");

    @Test
    @DisplayName("every unique of every bnpl table, read from the catalogue, refuses a copy of a real row under a fresh key"
            + " by itself with every trigger suspended - none admits its copy, none needs a sibling to refuse it, and every"
            + " table the module writes holds a row to copy")
    void everyBornOnceArbiterRefusesACopyWithEveryTriggerOff() throws Exception {
        theWorld();
        List<Probe> probes = BornOnceArbiters.probe("bnpl", Map.of(
                // Two partial indexes whose predicate no settled world leaves a row inside: one row put there, in the
                // probe's own rolled-back savepoint, triggers off - an application still awaiting its decision, a terms
                // version proposed.
                "bnpl_application_one_open_per_party_product", "UPDATE bnpl.bnpl_application SET status ="
                        + " 'AWAITING_DECISION' WHERE id = (SELECT id FROM bnpl.bnpl_application ORDER BY id LIMIT 1)",
                "bnpl_terms_one_proposed", "UPDATE bnpl.bnpl_terms_version SET status = 'PROPOSED'"
                        + " WHERE id = (SELECT id FROM bnpl.bnpl_terms_version WHERE status IN ('REJECTED', 'RETIRED')"
                        + " ORDER BY id LIMIT 1)"));
        System.out.println("P12-DOC-001 ARBITERS: " + probes.stream().collect(Collectors.groupingBy(Probe::verdict,
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
                .map(Probe::toString).collect(Collectors.toCollection(TreeSet::new));
        assertThat(unprobed).as("a unique left unprobed for want of a row to copy").isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.REFUSED_ALONE).as("every other unique refused alone")
                .hasSize(THE_SCHEMAS_UNIQUES.size() - FOREIGN_KEY_TARGETS.size());
    }

    /** The module's own scenario cases, run for the rows they leave: every born-once fact made by its real path. */
    private static void theWorld() throws Exception {
        BnplBillingDatabaseTest.termsAndClock();
        new BnplTerminalEdgesDatabaseTest().nothingLeavesATerminalStateForAnyRole();
        new BnplAcceptanceDatabaseTest().anAcceptanceCommitsItsRows();
        new BnplAccrualDatabaseTest().accruesOncePerDate();
        new BnplBillingDatabaseTest().billedOnTheDueDateOnce();
        new BnplBillingDatabaseTest().aCreditBalancePaysTheBill();
        new BnplBillingDatabaseTest().heldCreditSettlesWhatIsAlreadyPastDue();
        new BnplCollectionDatabaseTest().insufficientFundsRecordsAndRetriesTomorrow();
        new BnplCollectionDatabaseTest().aCollectionThatSettlesClosesInItsStep();
        new BnplRepaymentDatabaseTest().overGoesToTheCreditBalanceAndIsAppliedAtTheNextBilling();
        new BnplRepaymentDatabaseTest().theLastRepaymentClosesAndRefunds();
        new BnplLateFeeDatabaseTest().assessedOnceAtTheLateFeeDay();
        new BnplLateFeeDatabaseTest().aCancelledInstalmentsLateFeeIsReleased();
        new BnplPayoffDatabaseTest().payoffZeroesEveryBalance();
        new BnplPayoffDatabaseTest().heldCreditIsAppliedBeforeTheWalletIsDebited();
        new BnplWaiverDatabaseTest().aFeeWaiverReversesOnlyTheUnpaidRemainder();
        new BnplRepaymentReversalDatabaseTest().aReversalReopensWhatItPaid();
        new BnplRefundDatabaseTest().excessGoesToTheCustomerAndAPartialRefundReducesTheLatestInstalments();
        new BnplRefundDatabaseTest().aFullRefundClosesWithNothingStranded();
        new BnplConditionDatabaseTest().currentToEachBucketAndCure();
        new BnplDisputeDatabaseTest().merchantSilenceResolvesAsARefundAtTheDeadline();
        new BnplDisputeDatabaseTest().theHoldSuspendsCollectionDpdAndFees();
    }
}
