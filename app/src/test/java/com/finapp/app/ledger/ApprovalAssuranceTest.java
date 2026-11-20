package com.finapp.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.accounting.PriorPeriodAdjustmentController;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The approvals that post a person's decision take {@code MULTI_FACTOR} (`P14-TSK-022`, the owner's rulings (r) and of 2026-11-04): a prior-period adjustment's, and {@code LEDGER_ADJUST}'s, the wider door.
 * So do the three that move the ledger's cutoff or freeze or supersede a month's FINAL runs (`P14-TSK-024`, the owner's acceptance of its item (b)): a period's unlock, close and reopen approvals.
 * Since the owner's D-6 (finapp-admin-panel ADR-C131 decision 3, `P17-TSK-102`) every other act on these doors asks for it too - a proposal, a rejection, an end, a lock - and only the reads stay at a
 * session's assurance; {@code EveryOperatorActRequiresAFactorTest} holds that over every handler. The real interceptor over each real handler, so the refusal, its code and what it
 * runs before are proven with no database; a real session row against the real door is {@code AdjustmentEndpointDatabaseTest}'s, owed. Model: {@code LoanWriteOffDeskTest}'s pin and {@code RiskStepUpRoutesTest}.
 * <em>(Read "neither door's proposal, read or rejection", "their ends, proposals, the close's rejection and the reads do not, since none of them moves anything", and the display name "the two
 * approvals' step-up: MULTI_FACTOR exactly there", until `P17-TSK-102`, 2026-11-17.)</em>
 */
@DisplayName("the approvals' step-up, and since the owner's D-6 every act's: MULTI_FACTOR, never at a read (P14-TSK-022, P17-TSK-102)")
class ApprovalAssuranceTest {

    private static Method ledger(String name, Class<?>... parameters) throws Exception {
        return AdjustmentController.class.getMethod(name, parameters);
    }

    private static List<Method> accountingMethods() {
        return List.of(PriorPeriodAdjustmentController.class.getDeclaredMethods());
    }

    private static Method priorPeriod(String name) {
        return accountingMethods().stream().filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }

    /** A read: a {@code GET} handler. Every other handler on these controllers is an act (a {@code POST} or a {@code DELETE}). */
    private static boolean read(Method method) {
        return method.isAnnotationPresent(GetMapping.class);
    }

    /**
     * <em>(Read "LEDGER_ADJUST's door, pinned: the approval MULTI_FACTOR; the proposal, the read and the rejection not", and {@code isNull} on the proposal and the rejection, until
     * `P17-TSK-102`, 2026-11-17.)</em>
     */
    @Test
    @DisplayName("LEDGER_ADJUST's door, pinned: the approval MULTI_FACTOR, and the proposal and the rejection since the owner's D-6 (P17-TSK-102); the read not; all four LEDGER_ADJUST's")
    void theLedgerDoorIsPinned() throws Exception {
        for (Method act : List.of(ledger("approve", String.class), ledger("propose", AdjustmentRequest.class, String.class), ledger("reject", String.class))) {
            assertThat(act.getAnnotation(RequiresAssurance.class)).as(act.getName()).isNotNull().extracting(RequiresAssurance::value).isEqualTo(AssuranceLevel.MULTI_FACTOR);
            assertThat(act.getAnnotation(RequiresPermission.class).value()).as(act.getName()).isEqualTo(PermissionName.LEDGER_ADJUST);
        }
        Method view = ledger("view", String.class);
        assertThat(view.getAnnotation(RequiresAssurance.class)).as(view.getName()).isNull();
        assertThat(view.getAnnotation(RequiresPermission.class).value()).as(view.getName()).isEqualTo(PermissionName.LEDGER_ADJUST);
    }

    /**
     * <em>(Read "a prior-period adjustment's door, pinned: only the approval MULTI_FACTOR, every other route on the controller not" until `P17-TSK-102`, 2026-11-17.)</em>
     */
    @Test
    @DisplayName("a prior-period adjustment's door, pinned: every act on the controller MULTI_FACTOR since the owner's D-6 (P17-TSK-102) - the proposal, the approval and the rejection - and every read not")
    void thePriorPeriodDoorIsPinned() {
        List<String> acts = new ArrayList<>();
        for (Method method : accountingMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                RequiresAssurance declared = method.getAnnotation(RequiresAssurance.class);
                if (read(method)) {
                    assertThat(declared).as(method.getName()).isNull();
                } else {
                    acts.add(method.getName());
                    assertThat(declared).as(method.getName()).isNotNull().extracting(RequiresAssurance::value).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                }
            }
        }
        assertThat(acts).containsExactlyInAnyOrder("proposePriorPeriodAdjustment", "approvePriorPeriodAdjustment", "rejectPriorPeriodAdjustment");
    }

    @Test
    @DisplayName("below MULTI_FACTOR the real interceptor refuses each approval 403 identity.AssuranceRequired, before the permission is read; at MULTI_FACTOR and at STRONG it lets the holder through")
    void theRefusalIsTheInterceptors() throws Exception {
        for (Door door : doors()) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.approval(), AssuranceLevel.PASSWORD, door.holder())).as(door.approval().getName()).isInstanceOfSatisfying(ApiException.class, refused -> {
                assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
                assertThat(refused.errorCode().code()).isEqualTo("identity.AssuranceRequired");
                assertThat(refused.errorCode().status()).isEqualTo(403);
            });
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.approval(), AssuranceLevel.PASSWORD, Set.of())).as("no role either: it says step up, as it does anywhere the assurance is short").isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
            assertThat(SteppedUpDoor.knock(door.approval(), AssuranceLevel.MULTI_FACTOR, door.holder())).isTrue();
            assertThat(SteppedUpDoor.knock(door.approval(), AssuranceLevel.STRONG, door.holder())).isTrue();
        }
    }

    /**
     * <em>(Read "... and a password session still reaches the proposal, the read and the rejection", the method {@code theLowerRoutesAndTheRoleStand}, until `P17-TSK-102`, 2026-11-17.)</em>
     */
    @Test
    @DisplayName("at MULTI_FACTOR without the role the approval is refused as ever, so the step-up widened nobody; a password session is refused 403 identity.AssuranceRequired at the proposal and"
            + " the rejection since the owner's D-6 (P17-TSK-102) and let through at MULTI_FACTOR, and still reaches the reads")
    void theRoleStandsAndOnlyTheReadsTakeAPassword() throws Exception {
        for (Door door : doors()) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.approval(), AssuranceLevel.MULTI_FACTOR, Set.of())).as(door.approval().getName()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isNotEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
        }
        Set<RoleName> ledgerOperator = Set.of(RoleName.LEDGER_OPERATOR);
        for (Method act : List.of(ledger("propose", AdjustmentRequest.class, String.class), ledger("reject", String.class))) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(act, AssuranceLevel.PASSWORD, ledgerOperator)).as(act.getName()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
            assertThat(SteppedUpDoor.knock(act, AssuranceLevel.MULTI_FACTOR, ledgerOperator)).as(act.getName()).isTrue();
        }
        assertThat(SteppedUpDoor.knock(ledger("view", String.class), AssuranceLevel.PASSWORD, ledgerOperator)).as("view").isTrue();
        Set<RoleName> controller = Set.of(RoleName.FINANCIAL_CONTROLLER);
        Method rejection = priorPeriod("rejectPriorPeriodAdjustment");
        assertThatThrownBy(() -> SteppedUpDoor.knock(rejection, AssuranceLevel.PASSWORD, controller)).as(rejection.getName()).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
        assertThat(SteppedUpDoor.knock(rejection, AssuranceLevel.MULTI_FACTOR, controller)).as(rejection.getName()).isTrue();
        for (String read : List.of("listPendingPriorPeriodAdjustments", "readPriorPeriodAdjustment")) {
            assertThat(SteppedUpDoor.knock(priorPeriod(read), AssuranceLevel.PASSWORD, controller)).as(read).isTrue();
        }
    }

    @Test
    @DisplayName("the controller's read of a FINAL statement through the real interceptor: the controller is let through, on a password session; the accountant, the ledger's operator and a session holding no role are refused 403; the accountant's own read stays closed to the controller")
    void theControllersReadIsTheControllers() throws Exception {
        Method read = com.finapp.app.accounting.FinalStatementController.class.getMethod("readFinalStatement", String.class);
        assertThat(SteppedUpDoor.knock(read, AssuranceLevel.PASSWORD, Set.of(RoleName.FINANCIAL_CONTROLLER))).isTrue();
        for (Set<RoleName> notTheController : List.of(Set.of(RoleName.ACCOUNTANT), Set.of(RoleName.LEDGER_OPERATOR), Set.<RoleName>of())) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(read, AssuranceLevel.MULTI_FACTOR, notTheController)).as(notTheController.toString()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode().status()).isEqualTo(403));
        }
        Method lines = com.finapp.app.accounting.ReportRunLinesController.class.getMethod("readReportRunLines", String.class, String.class, String.class, String.class, String.class, String.class);
        Method bytes = com.finapp.app.accounting.ReportRunController.class.getMethod("readReportRun", String.class);
        for (Method accountants : List.of(lines, bytes)) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(accountants, AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.FINANCIAL_CONTROLLER))).as(accountants.getName()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode().status()).isEqualTo(403));
        }
    }

    /**
     * <em>(Read "... their proposals, reads and rejections, and the ordinary set approval, do not", and every other route on both controllers {@code isNull} and let through on a password
     * session, until `P17-TSK-102`, 2026-11-17.)</em>
     */
    @Test
    @DisplayName("P14-TSK-026's two approvals: a projection rebuild's (LEDGER_ADJUST) and a reference gap's (ACCOUNTING_CLOSE_APPROVE) take MULTI_FACTOR through the real interceptor; since the owner's"
            + " D-6 (P17-TSK-102) so does every other act on both controllers - the proposals, the rejections, the ordinary set approval - and the reads do not")
    void theRebuildAndTheGapApprovalsStepUp() throws Exception {
        Method rebuild = ProjectionRebuildController.class.getMethod("approveProjectionRebuild", String.class, String.class);
        Method gap = com.finapp.app.accounting.ValuationRateSetController.class.getMethod("approveValuationRateSetAcrossGap", String.class, String.class,
                com.finapp.app.accounting.ValuationRateSetController.ValuationRateSetDecisionRequest.class);
        assertThat(rebuild.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(rebuild.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.LEDGER_ADJUST);
        assertThat(gap.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(gap.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ACCOUNTING_CLOSE_APPROVE);
        for (Door door : List.of(new Door(rebuild, Set.of(RoleName.LEDGER_OPERATOR)), new Door(gap, Set.of(RoleName.FINANCIAL_CONTROLLER)))) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.approval(), AssuranceLevel.PASSWORD, door.holder())).as(door.approval().getName())
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
            assertThat(SteppedUpDoor.knock(door.approval(), AssuranceLevel.MULTI_FACTOR, door.holder())).isTrue();
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.approval(), AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.ACCOUNTANT))).as("least privilege: the accountant holds neither approval")
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode().status()).isEqualTo(403));
        }
        assertThatThrownBy(() -> SteppedUpDoor.knock(rebuild, AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.FINANCIAL_CONTROLLER))).as("a controller closes months and rebuilds no projection")
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode().status()).isEqualTo(403));
        assertThatThrownBy(() -> SteppedUpDoor.knock(gap, AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.LEDGER_OPERATOR))).as("a ledger operator approves no valuation")
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode().status()).isEqualTo(403));
        List<String> rebuildActs = new ArrayList<>();
        for (Method method : ProjectionRebuildController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class) && !method.equals(rebuild)) {
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName()).isEqualTo(PermissionName.LEDGER_ADJUST);
                if (read(method)) {
                    assertThat(method.getAnnotation(RequiresAssurance.class)).as(method.getName()).isNull();
                    assertThat(SteppedUpDoor.knock(method, AssuranceLevel.PASSWORD, Set.of(RoleName.LEDGER_OPERATOR))).as(method.getName()).isTrue();
                } else {
                    rebuildActs.add(method.getName());
                    assertThat(method.getAnnotation(RequiresAssurance.class)).as(method.getName()).isNotNull().extracting(RequiresAssurance::value).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                    assertThatThrownBy(() -> SteppedUpDoor.knock(method, AssuranceLevel.PASSWORD, Set.of(RoleName.LEDGER_OPERATOR))).as(method.getName())
                            .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
                    assertThat(SteppedUpDoor.knock(method, AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.LEDGER_OPERATOR))).as(method.getName()).isTrue();
                }
            }
        }
        assertThat(rebuildActs).containsExactlyInAnyOrder("proposeProjectionRebuild", "rejectProjectionRebuild");
        List<String> setActs = new ArrayList<>();
        for (Method method : com.finapp.app.accounting.ValuationRateSetController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class) && !method.equals(gap)) {
                if (read(method)) {
                    assertThat(method.getAnnotation(RequiresAssurance.class)).as(method.getName()).isNull();
                } else {
                    setActs.add(method.getName());
                    assertThat(method.getAnnotation(RequiresAssurance.class)).as(method.getName()).isNotNull().extracting(RequiresAssurance::value).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                }
            }
        }
        assertThat(setActs).containsExactlyInAnyOrder("captureValuationRateSet", "captureValuationRateSetAcrossGap", "approveValuationRateSet", "rejectValuationRateSet");
    }

    /**
     * <em>(Read "... and nothing else on their controllers does: the proposals, the ends, the close's rejection and the reads stay at a session's assurance" until `P17-TSK-102`,
     * 2026-11-17.)</em>
     */
    @Test
    @DisplayName("P14-TSK-024: a period's three approvals step up - the unlock's, the close's and the reopen's - and since the owner's D-6 (P17-TSK-102) every other act on their controllers too:"
            + " the lock, the proposals, the ends and the rejections; only the reads stay at a session's assurance")
    void thePeriodApprovalsArePinned() {
        List<String> approvals = List.of("approveAccountingPeriodUnlock", "approveAccountingPeriodClose", "approveAccountingPeriodReopen");
        int seen = 0;
        for (Class<?> controller : List.of(com.finapp.app.accounting.AccountingPeriodController.class, com.finapp.app.accounting.PeriodCloseController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(RequiresPermission.class)) {
                    RequiresAssurance declared = method.getAnnotation(RequiresAssurance.class);
                    if (read(method)) {
                        assertThat(declared).as(method.getName()).isNull();
                    } else {
                        assertThat(declared).as(method.getName()).isNotNull().extracting(RequiresAssurance::value).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                    }
                    if (approvals.contains(method.getName())) {
                        seen++;
                        assertThat(declared).as(method.getName()).isNotNull().extracting(RequiresAssurance::value).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                        assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName()).isEqualTo(PermissionName.ACCOUNTING_CLOSE_APPROVE);
                    }
                }
            }
        }
        assertThat(seen).isEqualTo(approvals.size());
    }

    private static List<Door> doors() throws Exception {
        return List.of(new Door(ledger("approve", String.class), Set.of(RoleName.LEDGER_OPERATOR)), new Door(priorPeriod("approvePriorPeriodAdjustment"), Set.of(RoleName.FINANCIAL_CONTROLLER)),
                new Door(period(com.finapp.app.accounting.AccountingPeriodController.class, "approveAccountingPeriodUnlock"), Set.of(RoleName.FINANCIAL_CONTROLLER)),
                new Door(period(com.finapp.app.accounting.PeriodCloseController.class, "approveAccountingPeriodClose"), Set.of(RoleName.FINANCIAL_CONTROLLER)),
                new Door(period(com.finapp.app.accounting.PeriodCloseController.class, "approveAccountingPeriodReopen"), Set.of(RoleName.FINANCIAL_CONTROLLER)));
    }

    private static Method period(Class<?> controller, String name) {
        return List.of(controller.getDeclaredMethods()).stream().filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }

    /** An approval and the role that may knock at it. */
    private record Door(Method approval, Set<RoleName> holder) {}
}
