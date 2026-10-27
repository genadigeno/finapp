package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.RequiresIdempotencyKey;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The step-up the gate names, pinned on the three desks that carried it with no hermetic proof (`P13-DOC-001`, the review's
 * A6-1): "a {@code MULTI_FACTOR} operator session required for policy activation, ... report approval and filing" - and,
 * beyond section 11's list, a screening-list version's activation (`P13-TSK-022`). The database suites prove a lesser
 * session refused with nothing written for the approval and the activations; the filing had no evidence at all - deleting
 * its {@code @RequiresAssurance} passed every test. These pins fail the build if any step-up is dropped, or added where the
 * routes are a single holder's act or a read.
 */
@DisplayName("the risk desks' step-up: MULTI_FACTOR exactly where the gate names it, pinned on the routes (P13-DOC-001)")
class RiskStepUpRoutesTest {

    @Test
    @DisplayName("the regulatory report: approval and filing MULTI_FACTOR; the draft and the read not; the filing"
            + " AML_REPORT_FILE's, the rest AML_REPORT_APPROVE's; every act keyed")
    void theReportsApprovalAndFilingAreSteppedUp() throws Exception {
        Method draft = RiskReportController.class.getMethod("draftRegulatoryReport", String.class, String.class,
                RiskReportController.RiskReportDraftRequest.class);
        Method approve = RiskReportController.class.getMethod("approveRegulatoryReport", String.class, String.class,
                String.class, RiskReportController.RiskReportDecisionRequest.class);
        Method file = RiskReportController.class.getMethod("fileRegulatoryReport", String.class, String.class,
                String.class, RiskReportController.RiskReportDecisionRequest.class);
        Method read = RiskReportController.class.getMethod("readRegulatoryReport", String.class, String.class);

        for (Method stepped : List.of(approve, file)) {
            assertThat(stepped.getAnnotation(RequiresAssurance.class)).as(stepped.getName()).isNotNull();
            assertThat(stepped.getAnnotation(RequiresAssurance.class).value()).as(stepped.getName())
                    .isEqualTo(AssuranceLevel.MULTI_FACTOR);
        }
        for (Method notStepped : List.of(draft, read)) {
            assertThat(notStepped.getAnnotation(RequiresAssurance.class)).as(notStepped.getName()).isNull();
        }
        assertThat(file.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.AML_REPORT_FILE);
        for (Method approver : List.of(draft, approve, read)) {
            assertThat(approver.getAnnotation(RequiresPermission.class).value()).as(approver.getName())
                    .isEqualTo(PermissionName.AML_REPORT_APPROVE);
        }
        for (Method act : List.of(draft, approve, file)) {
            assertThat(act.getAnnotation(RequiresIdempotencyKey.class)).as(act.getName()).isNotNull();
        }
    }

    @Test
    @DisplayName("the policy version: the activation MULTI_FACTOR; the proposal, the rejection and the reads not; all"
            + " RISK_POLICY_ADMINISTER's")
    void thePolicysActivationIsSteppedUp() throws Exception {
        Method propose = RiskPolicyController.class.getMethod("proposeRiskPolicy", String.class,
                RiskPolicyController.RiskPolicyProposalRequest.class);
        Method activate = RiskPolicyController.class.getMethod("activateRiskPolicy", String.class, String.class,
                RiskPolicyController.RiskPolicyDecisionRequest.class);
        Method reject = RiskPolicyController.class.getMethod("rejectRiskPolicy", String.class, String.class,
                RiskPolicyController.RiskPolicyDecisionRequest.class);
        Method inForce = RiskPolicyController.class.getMethod("readRiskPolicyInForce", String.class);
        Method version = RiskPolicyController.class.getMethod("readRiskPolicyVersion", String.class);

        assertThat(activate.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        for (Method notStepped : List.of(propose, reject, inForce, version)) {
            assertThat(notStepped.getAnnotation(RequiresAssurance.class)).as(notStepped.getName()).isNull();
        }
        for (Method route : List.of(propose, activate, reject, inForce, version)) {
            assertThat(route.getAnnotation(RequiresPermission.class).value()).as(route.getName())
                    .isEqualTo(PermissionName.RISK_POLICY_ADMINISTER);
        }
    }

    @Test
    @DisplayName("the screening-list version: the activation MULTI_FACTOR (four eyes, P13-TSK-022); the recording not")
    void theListsActivationIsSteppedUp() throws Exception {
        Method record = RiskScreeningListController.class.getMethod("recordScreeningListVersion", String.class,
                RiskScreeningListController.RiskScreeningListVersionRequest.class);
        Method activate = RiskScreeningListController.class.getMethod("activateScreeningListVersion", String.class,
                String.class, RiskScreeningListController.RiskScreeningListActivationRequest.class);

        assertThat(activate.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(record.getAnnotation(RequiresAssurance.class)).isNull();
        for (Method act : List.of(record, activate)) {
            assertThat(act.getAnnotation(RequiresPermission.class).value()).as(act.getName())
                    .isEqualTo(PermissionName.RISK_POLICY_ADMINISTER);
            assertThat(act.getAnnotation(RequiresIdempotencyKey.class)).as(act.getName()).isNotNull();
        }
    }
}
