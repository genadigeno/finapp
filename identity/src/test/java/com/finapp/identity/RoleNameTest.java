package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a role grants (`P1-TSK-020`, ADR-0031).
 *
 * <h2>This is the definition, not a convenience</h2>
 *
 * <p>ADR-0031 chose RBAC over a policy engine precisely so that <em>"why was this denied?"</em> is
 * answered by <strong>reading a method</strong> rather than by evaluating a rule set. That makes
 * {@link RoleName#permissions()} the platform's authorization policy in its entirety — and the
 * completion gate found it had <strong>no test at all</strong>, which is the {@link AssuranceLevel}
 * finding from {@code P1-TSK-013} repeated on the type that decides who may do anything privileged.
 *
 * <h2>The recorded limit, and the task that closed it</h2>
 *
 * <p>With <strong>one</strong> role holding <strong>both</strong> permissions, the mapping could not
 * be meaningfully mutated: {@code P1-TSK-020}'s gate found that {@code permissions()} returning
 * {@code EnumSet.allOf(PermissionName.class)} <strong>survived</strong>, correctly, because it was
 * the same set — and recorded that the interesting mutations become available at the second role.
 *
 * <p>{@code P2-TSK-004} is that role, and closing the limit is its acceptance criterion: a role
 * granting everything, a role gaining its sibling's permission, and the two grants swapped are all
 * caught now — by the exact-grant assertions here, and independently by the cross-population
 * refusal tests over HTTP ({@code DenyByDefaultDatabaseTest}), which is two controls blind in
 * different directions rather than a duplication.
 */
@DisplayName("what a role grants (P1-TSK-020)")
class RoleNameTest {

    @Test
    @DisplayName("ADMINISTRATOR grants exactly the two Phase 1 privileged permissions")
    void administratorGrantsExactlyTwo() {
        // containsExactlyInAnyOrder, never contains: a widening is the defect, and `contains` is
        // satisfied by a role that grants everything. Naming both is what makes a third permission
        // added to this role a failing test rather than a silent expansion of what an administrator
        // can do.
        assertThat(RoleName.ADMINISTRATOR.permissions())
                .as("granting a permission is a reviewed code change, so widening must fail a test")
                .containsExactlyInAnyOrder(
                        PermissionName.IDENTITY_SUSPEND, PermissionName.ROLE_ASSIGN);
    }

    @Test
    @DisplayName("KYC_REVIEWER grants exactly KYC_REVIEW and COUNTERPARTY_SCREENING_REVIEW - and neither administrative permission")
    void kycReviewerGrantsExactlyOne() {
        // The least-privilege split is the point of the role (P2-TSK-004), so it is asserted as
        // an exact set: this is the assertion that catches the previously-untestable mutations -
        // the role granting everything, or quietly gaining IDENTITY_SUSPEND or ROLE_ASSIGN.
        assertThat(RoleName.KYC_REVIEWER.permissions())
                .as("a reviewer reviews; managing identities is a different trust decision")
                .containsExactlyInAnyOrder(PermissionName.KYC_REVIEW, PermissionName.COUNTERPARTY_SCREENING_REVIEW);
    }

    @Test
    @DisplayName("LEDGER_OPERATOR grants exactly the eight money-operating permissions")
    void ledgerOperatorGrantsExactlySeven() {
        // One role, seven permissions (P3-TSK-007; TRANSFER_REVERSE by P4-TSK-009;
        // PAYMENT_REFUND by P5-TSK-015; MERCHANT_PAYOUT by P6-TSK-012;
        // PAYMENT_ROUTING_ADMINISTER by P7-TSK-003, how money travels being this desk's
        // judgement; DISPUTE_ADMINISTER by P7-TSK-012, answering money the rail forced back
        // being payment operations): one money-operating
        // population, and the vocabulary stays precise so the adjustment, reversal, refund,
        // payout, routing and dispute endpoints each check their own. Exact set, so the role quietly
        // gaining ROLE_ASSIGN - the permission that
        // grants permissions - is a failing test rather than a silent expansion.
        //
        // THIS PIN WAS RED FROM P5-TSK-015 UNTIL P5-DOC-001's post-flip battery, which is
        // the finding that battery exists to make: the phase verified by TARGETED tiers
        // (`:payments:test`, `:platform:test`, `:app:test` and the payment database suites)
        // never ran `:identity:test`, so widening the role passed every check the task
        // itself ran. Recorded in the phase review's area 8 as the cost of the skip
        // instruction, with the fleet-wide hermetic run as the thing that closes it.
        assertThat(RoleName.LEDGER_OPERATOR.permissions())
                .as("operating the money is not managing identities or reviewing cases")
                .containsExactlyInAnyOrder(
                        PermissionName.LEDGER_POST,
                        PermissionName.LEDGER_ADJUST,
                        PermissionName.TRANSFER_REVERSE,
                        PermissionName.PAYMENT_REFUND,
                        PermissionName.MERCHANT_PAYOUT,
                        PermissionName.PAYMENT_ROUTING_ADMINISTER,
                        PermissionName.DISPUTE_ADMINISTER,
                        // P9-TSK-025: the FX trade reversal, beside the transfer reversal.
                        PermissionName.FX_TRADE_REVERSE);
    }

    @Test
    @DisplayName("MERCHANT_ADMINISTRATOR grants exactly the four counterparty permissions")
    void merchantAdministratorGrantsExactlyFour() {
        // One role, four permissions (P6-TSK-003; FEE_ADMINISTER by P6-TSK-004;
        // PAYOUT_DESTINATION_APPROVE by P6-TSK-011, four-eyes being distinct identities): one
        // merchant-administering population - the LEDGER_OPERATOR bundling reasoning - while
        // the vocabulary stays precise so the onboarding endpoint, the reasoned state moves
        // and the pricing surfaces each check their own. Exact set, so the role quietly
        // gaining a money-operating or identity permission is a failing test, held pairwise
        // below as well.
        //
        // UPDATED IN THE TASK THAT WIDENED THE ROLE, deliberately. The same pin on
        // LEDGER_OPERATOR above was left red by P5-TSK-015 and stayed red through four
        // completion gates because `:identity:test` was in none of that phase's targeted
        // tiers. A role widened here is a pin edited here, in the same change.
        assertThat(RoleName.MERCHANT_ADMINISTRATOR.permissions())
                .as("administering counterparties is neither operating money nor managing"
                        + " identities")
                .containsExactlyInAnyOrder(
                        PermissionName.MERCHANT_ONBOARD,
                        PermissionName.MERCHANT_ADMINISTER,
                        PermissionName.FEE_ADMINISTER,
                        PermissionName.PAYOUT_DESTINATION_APPROVE);
    }

    @Test
    @DisplayName("RECONCILIATION_OPERATOR grants exactly the two evidence permissions and the"
            + " resolve permission")
    void reconciliationOperatorGrantsExactlyTwo() {
        // The fifth population (P8-TSK-003, ADR-0066, owner decision O1): introducing and
        // attesting settlement evidence, and investigating it, are one desk today - and that
        // desk CHECKS the money-operating desk's work, so the exact set is what keeps it from
        // quietly gaining a posting or refund permission and becoming able to both move money
        // and vouch for the evidence that reconciles it. RECONCILIATION_RESOLVE joined with
        // P8-TSK-015 (ADR-0071 section 10): a template-bound, four-eyes correction through
        // the ledger's OWNED calls - never a posting or adjustment permission - and never
        // the controller's, whose tolerances it would otherwise let hide its own breaks.
        assertThat(RoleName.RECONCILIATION_OPERATOR.permissions())
                .as("checking the money's reconciliation is not operating the money")
                .containsExactlyInAnyOrder(
                        PermissionName.SETTLEMENT_INGEST,
                        PermissionName.RECONCILIATION_INVESTIGATE,
                        PermissionName.RECONCILIATION_RESOLVE,
                        // P9-TSK-013: reading an FX trade's provenance - a read, never a mover.
                        PermissionName.FX_INVESTIGATE);
    }

    @Test
    @DisplayName("RECONCILIATION_CONTROLLER grants exactly RECONCILIATION_ADMINISTER")
    void reconciliationControllerGrantsExactlyOne() {
        // The sixth population (P8-TSK-007, ADR-0067 section 8, owner decision O1's second
        // role): adopting history into the register decides what every proof and break is
        // judged against, so the controller is disjoint even from the operator desk it
        // oversees - the exact set is what keeps this role from quietly gaining the
        // evidence permissions and becoming able to both feed the register and rule it.
        assertThat(RoleName.RECONCILIATION_CONTROLLER.permissions())
                .as("controlling the register is not feeding or investigating it")
                .containsExactlyInAnyOrder(PermissionName.RECONCILIATION_ADMINISTER);
    }

    @Test
    @DisplayName("FX_CONTROLLER grants exactly FX_ADMINISTER and CROSSBORDER_ADMINISTER")
    void fxControllerGrantsExactlyOne() {
        // The seventh population (P9-TSK-007, ADR-0075 section 7): the margin every conversion
        // freezes and posts is revenue policy, so whoever sets it must not also be able to
        // reverse trades (LEDGER_OPERATOR's, P9-TSK-025) or clear screenings - the exact set is
        // what keeps the role from quietly gaining either.
        assertThat(RoleName.FX_CONTROLLER.permissions())
                .as("setting prices is not operating, reconciling or reversing the money")
                .containsExactlyInAnyOrder(PermissionName.FX_ADMINISTER, PermissionName.CROSSBORDER_ADMINISTER);
    }

    @Test
    @DisplayName("CREDIT_POLICY_OFFICER grants exactly CREDIT_POLICY_ADMINISTER and CREDIT_INVESTIGATE - never CREDIT_UNDERWRITE")
    void creditPolicyOfficerGrantsExactlyTwo() {
        // The eighth population (P10-TSK-003, ADR-0084): the policy's author answers for what it
        // decided, and must not decide the cases it refers.
        assertThat(RoleName.CREDIT_POLICY_OFFICER.permissions())
                .as("writing and investigating credit policy is not deciding the queue")
                .containsExactlyInAnyOrder(PermissionName.CREDIT_POLICY_ADMINISTER, PermissionName.CREDIT_INVESTIGATE)
                .doesNotContain(PermissionName.CREDIT_UNDERWRITE);
    }

    @Test
    @DisplayName("UNDERWRITER grants exactly CREDIT_UNDERWRITE - neither credit-policy permission")
    void underwriterGrantsExactlyOne() {
        // The ninth population (P10-TSK-003, ADR-0089): the queue's decider cannot write the policy
        // that referred the case, nor read evidence outside the case.
        assertThat(RoleName.UNDERWRITER.permissions())
                .as("deciding cases within the policy is not writing or investigating it")
                .containsExactlyInAnyOrder(PermissionName.CREDIT_UNDERWRITE)
                .doesNotContain(PermissionName.CREDIT_POLICY_ADMINISTER, PermissionName.CREDIT_INVESTIGATE);
    }

    @Test
    @DisplayName("LENDING_OFFICER grants exactly LENDING_ADMINISTER and LENDING_INVESTIGATE - neither capital nor servicing")
    void lendingOfficerGrantsExactlyTwo() {
        // The tenth population (P11-TSK-002, ADR-0090, ADR-0092): whoever prices loans answers for what
        // the terms produced, and neither funds the book nor services a borrower's account.
        assertThat(RoleName.LENDING_OFFICER.permissions())
                .as("setting and investigating loan terms is neither committing capital nor servicing")
                .containsExactlyInAnyOrder(PermissionName.LENDING_ADMINISTER, PermissionName.LENDING_INVESTIGATE)
                .doesNotContain(
                        PermissionName.LENDING_CAPITAL_ADMINISTER,
                        PermissionName.LOAN_SERVICE,
                        PermissionName.LOAN_SERVICE_APPROVE,
                        PermissionName.CREDIT_POLICY_ADMINISTER,
                        PermissionName.CREDIT_UNDERWRITE);
    }

    @Test
    @DisplayName("LENDING_TREASURY_OFFICER grants exactly LENDING_CAPITAL_ADMINISTER")
    void lendingTreasuryOfficerGrantsExactlyOne() {
        // The eleventh population (P11-TSK-002, ADR-0096, L1): committing the platform's own money to
        // lend is treasury's judgement alone - not pricing, not servicing, not operating the ledger.
        assertThat(RoleName.LENDING_TREASURY_OFFICER.permissions())
                .as("funding the book is a decision of its own")
                .containsExactlyInAnyOrder(PermissionName.LENDING_CAPITAL_ADMINISTER)
                .doesNotContain(PermissionName.LENDING_ADMINISTER, PermissionName.LEDGER_POST, PermissionName.LEDGER_ADJUST);
    }

    @Test
    @DisplayName("LOAN_SERVICING_AGENT grants exactly LOAN_SERVICE and LOAN_SERVICE_APPROVE - four-eyes inside the role")
    void loanServicingAgentGrantsExactlyTwo() {
        // The twelfth population (P11-TSK-002, ADR-0099): proposing and approving a correction are one
        // population's work; that no person approves their own act - nor services their own party's
        // loan - is held between two holders at the domain and by CHECK, by each act's task (the
        // UNDERWRITER shape). The agent holds neither the terms nor the capital.
        assertThat(RoleName.LOAN_SERVICING_AGENT.permissions())
                .as("servicing an account is not pricing the product or funding the book")
                .containsExactlyInAnyOrder(PermissionName.LOAN_SERVICE, PermissionName.LOAN_SERVICE_APPROVE)
                .doesNotContain(
                        PermissionName.LENDING_ADMINISTER,
                        PermissionName.LENDING_INVESTIGATE,
                        PermissionName.LENDING_CAPITAL_ADMINISTER);
    }

    @Test
    @DisplayName("BNPL_OFFICER grants exactly BNPL_ADMINISTER and BNPL_INVESTIGATE - neither servicing nor lending's or"
            + " credit's policy")
    void bnplOfficerGrantsExactlyTwo() {
        // The thirteenth population (P12-TSK-002, ADR-0101, ADR-0102): whoever sets the BNPL products' terms answers for
        // what they produced, and neither services an obligation nor resolves a dispute.
        assertThat(RoleName.BNPL_OFFICER.permissions())
                .as("setting and investigating BNPL terms is not servicing, nor another context's policy")
                .containsExactlyInAnyOrder(PermissionName.BNPL_ADMINISTER, PermissionName.BNPL_INVESTIGATE)
                .doesNotContain(
                        PermissionName.BNPL_SERVICE,
                        PermissionName.BNPL_SERVICE_APPROVE,
                        PermissionName.BNPL_DISPUTE_RESOLVE,
                        PermissionName.LENDING_ADMINISTER,
                        PermissionName.CREDIT_POLICY_ADMINISTER);
    }

    @Test
    @DisplayName("BNPL_SERVICING_AGENT grants exactly BNPL_SERVICE, BNPL_SERVICE_APPROVE and BNPL_DISPUTE_RESOLVE -"
            + " four-eyes inside the role")
    void bnplServicingAgentGrantsExactlyThree() {
        // The fourteenth population (P12-TSK-002, ADR-0106): proposing, approving another's correction and resolving a
        // dispute are one population's work; that no person approves or resolves their own act - nor on their own
        // party's obligation - is held between two holders at the domain and by CHECK, by each act's task (the
        // LOAN_SERVICING_AGENT shape). The agent holds neither the terms nor lending's servicing.
        assertThat(RoleName.BNPL_SERVICING_AGENT.permissions())
                .as("servicing an obligation is not pricing the product")
                .containsExactlyInAnyOrder(
                        PermissionName.BNPL_SERVICE, PermissionName.BNPL_SERVICE_APPROVE, PermissionName.BNPL_DISPUTE_RESOLVE)
                .doesNotContain(
                        PermissionName.BNPL_ADMINISTER,
                        PermissionName.BNPL_INVESTIGATE,
                        PermissionName.LOAN_SERVICE,
                        PermissionName.LOAN_SERVICE_APPROVE);
    }

    @Test
    @DisplayName("RISK_ANALYST grants exactly RISK_INVESTIGATE and RISK_CASE_APPROVE - four-eyes inside the role, nothing"
            + " of the policy, the override or the filing")
    void riskAnalystGrantsExactlyTwo() {
        // The fifteenth population (P13-TSK-001, ADR-0107, ADR-0110): the desk that investigates checks an
        // investigation's conclusion between two holders (the LOAN_SERVICING_AGENT shape) and neither writes the rules
        // that produce the outcomes it investigates nor files with the authority.
        assertThat(RoleName.RISK_ANALYST.permissions())
                .as("investigating and approving an investigation's conclusion is one population's work")
                .containsExactlyInAnyOrder(PermissionName.RISK_INVESTIGATE, PermissionName.RISK_CASE_APPROVE)
                .doesNotContain(
                        PermissionName.RISK_POLICY_ADMINISTER,
                        PermissionName.RISK_OVERRIDE,
                        PermissionName.AML_REPORT_APPROVE,
                        PermissionName.AML_REPORT_FILE,
                        PermissionName.CREDIT_INVESTIGATE,
                        PermissionName.BNPL_INVESTIGATE);
    }

    @Test
    @DisplayName("RISK_OFFICER grants exactly RISK_POLICY_ADMINISTER and RISK_OVERRIDE - never the analyst's reads, never"
            + " credit's policy")
    void riskOfficerGrantsExactlyTwo() {
        // The sixteenth population (P13-TSK-001, ADR-0108, ADR-0109): whoever answers for what the engine judges by also
        // answers for the one act that sets a judgement aside; the override door serves the assessment it overrides
        // under RISK_OVERRIDE, so the officer needs no analyst's read.
        assertThat(RoleName.RISK_OFFICER.permissions())
                .as("the policy and the override are one desk's, and not the investigator's")
                .containsExactlyInAnyOrder(PermissionName.RISK_POLICY_ADMINISTER, PermissionName.RISK_OVERRIDE)
                .doesNotContain(
                        PermissionName.RISK_INVESTIGATE,
                        PermissionName.RISK_CASE_APPROVE,
                        PermissionName.AML_REPORT_APPROVE,
                        PermissionName.AML_REPORT_FILE,
                        PermissionName.CREDIT_POLICY_ADMINISTER);
    }

    @Test
    @DisplayName("AML_OFFICER grants exactly AML_REPORT_APPROVE and AML_REPORT_FILE - the filing authority is its own"
            + " desk, never the investigator's")
    void amlOfficerGrantsExactlyTwo() {
        // The seventeenth population (P13-TSK-001, ADR-0111): the regulatory filing, with its tipping-off obligation, is
        // held by a population that is not the one working the case; the draft door serves the disposed case's evidence
        // under the officer's own permission. Four eyes inside the role: the drafter never approves.
        assertThat(RoleName.AML_OFFICER.permissions())
                .as("approving and filing a regulatory report is the filing desk's alone")
                .containsExactlyInAnyOrder(PermissionName.AML_REPORT_APPROVE, PermissionName.AML_REPORT_FILE)
                .doesNotContain(
                        PermissionName.RISK_INVESTIGATE,
                        PermissionName.RISK_CASE_APPROVE,
                        PermissionName.RISK_POLICY_ADMINISTER,
                        PermissionName.RISK_OVERRIDE,
                        PermissionName.KYC_REVIEW);
    }

    @Test
    @DisplayName("every pair of grants is disjoint, so the populations really are separate")
    void theGrantsArePairwiseDisjoint() {
        // No exact-set assertion alone says the SETS do not overlap - each pins its own role.
        // Pairwise over values() rather than a hand-picked pair (the P2-TSK-004 assertion,
        // generalised by P3-TSK-007 when the third role arrived), so a fourth role is held to
        // the property without anyone editing this test - the stale-list defect, closed the way
        // this repository closes it. A permission added to two roles fails here even if
        // somebody edits both exact-set tests to match.
        RoleName[] roles = RoleName.values();
        for (int i = 0; i < roles.length; i++) {
            for (int j = i + 1; j < roles.length; j++) {
                assertThat(roles[i].permissions())
                        .as("%s and %s must stay disjoint populations", roles[i], roles[j])
                        .doesNotContainAnyElementsOf(roles[j].permissions());
            }
        }
    }

    @Test
    @DisplayName("every permission is granted by some role, so none is unreachable")
    void noPermissionIsOrphaned() {
        // A permission no role grants is a check that can never pass - an endpoint declaring it
        // would be permanently refused, and the failure would present as an authorization bug rather
        // than as a missing grant. Cheap to state now; the thing nobody notices at the fifth role.
        Set<PermissionName> granted = EnumSet.noneOf(PermissionName.class);
        for (RoleName role : RoleName.values()) {
            granted.addAll(role.permissions());
        }
        assertThat(granted)
                .as("a permission no role grants is a check that can never pass")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(PermissionName.class));
    }

    @Test
    @DisplayName("the granted set cannot be widened at run time")
    void theGrantedSetIsImmutable() {
        // Set.copyOf in the constructor, asserted rather than trusted. A mutable set here would let
        // any caller holding a RoleName add a permission for every request on every instance,
        // permanently, with nothing failing - which is privilege escalation by accident.
        assertThatThrownBy(
                        () ->
                                RoleName.ADMINISTRATOR
                                        .permissions()
                                        .add(PermissionName.IDENTITY_SUSPEND))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the SQL value list is exactly the declared roles")
    void theSqlValueListMatchesTheDeclaration() {
        // The consumer is V010's CHECK constraint, reconciled by RoleAssignmentMigrationTest. This
        // is the other half: that the generator produces what the reconciliation compares. Kept and
        // made load-bearing, where PermissionName's identical method was deleted as dead - the
        // P1-TSK-013 disposition, where isLiveAt was kept and idleBoundAfterUseAt removed.
        assertThat(RoleName.sqlValueList())
                .isEqualTo(
                        "'ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR',"
                                + " 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR',"
                                + " 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER',"
                                + " 'CREDIT_POLICY_OFFICER', 'UNDERWRITER',"
                                + " 'LENDING_OFFICER', 'LENDING_TREASURY_OFFICER', 'LOAN_SERVICING_AGENT',"
                                + " 'BNPL_OFFICER', 'BNPL_SERVICING_AGENT',"
                                + " 'RISK_ANALYST', 'RISK_OFFICER', 'AML_OFFICER'");
    }
}
