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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The approvals that post a person's decision take {@code MULTI_FACTOR} (`P14-TSK-022`, the owner's rulings (r) and of 2026-11-04): a prior-period adjustment's, and {@code LEDGER_ADJUST}'s, the wider door; neither door's proposal, read or rejection.
 * So do the three that move the ledger's cutoff or freeze or supersede a month's FINAL runs (`P14-TSK-024`, the owner's acceptance of its item (b)): a period's unlock, close and reopen approvals; their ends,
 * proposals, the close's rejection and the reads do not, since none of them moves anything. The real interceptor over each real handler, so the refusal, its code and what it
 * runs before are proven with no database; a real session row against the real door is {@code AdjustmentEndpointDatabaseTest}'s, owed. Model: {@code LoanWriteOffDeskTest}'s pin and {@code RiskStepUpRoutesTest}.
 */
@DisplayName("the two approvals' step-up: MULTI_FACTOR exactly there (P14-TSK-022)")
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

    @Test
    @DisplayName("LEDGER_ADJUST's door, pinned: the approval MULTI_FACTOR; the proposal, the read and the rejection not; all four LEDGER_ADJUST's")
    void theLedgerDoorIsPinned() throws Exception {
        Method approve = ledger("approve", String.class);
        assertThat(approve.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(approve.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.LEDGER_ADJUST);
        for (Method lower : List.of(ledger("propose", AdjustmentRequest.class, String.class), ledger("view", String.class), ledger("reject", String.class))) {
            assertThat(lower.getAnnotation(RequiresAssurance.class)).as(lower.getName()).isNull();
            assertThat(lower.getAnnotation(RequiresPermission.class).value()).as(lower.getName()).isEqualTo(PermissionName.LEDGER_ADJUST);
        }
    }

    @Test
    @DisplayName("a prior-period adjustment's door, pinned: only the approval MULTI_FACTOR, every other route on the controller not")
    void thePriorPeriodDoorIsPinned() {
        for (Method method : accountingMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                RequiresAssurance declared = method.getAnnotation(RequiresAssurance.class);
                if (method.getName().equals("approvePriorPeriodAdjustment")) {
                    assertThat(declared.value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                } else {
                    assertThat(declared).as(method.getName()).isNull();
                }
            }
        }
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

    @Test
    @DisplayName("at MULTI_FACTOR without the role the approval is refused as ever, so the step-up widened nobody; and a password session still reaches the proposal, the read and the rejection")
    void theLowerRoutesAndTheRoleStand() throws Exception {
        for (Door door : doors()) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.approval(), AssuranceLevel.MULTI_FACTOR, Set.of())).as(door.approval().getName()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isNotEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
        }
        Set<RoleName> ledgerOperator = Set.of(RoleName.LEDGER_OPERATOR);
        for (Method lower : List.of(ledger("propose", AdjustmentRequest.class, String.class), ledger("view", String.class), ledger("reject", String.class))) {
            assertThat(SteppedUpDoor.knock(lower, AssuranceLevel.PASSWORD, ledgerOperator)).as(lower.getName()).isTrue();
        }
        Set<RoleName> controller = Set.of(RoleName.FINANCIAL_CONTROLLER);
        for (String lower : List.of("listPendingPriorPeriodAdjustments", "readPriorPeriodAdjustment", "rejectPriorPeriodAdjustment")) {
            assertThat(SteppedUpDoor.knock(priorPeriod(lower), AssuranceLevel.PASSWORD, controller)).as(lower).isTrue();
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
        Method lines = com.finapp.app.accounting.ReportRunLinesController.class.getMethod("readReportRunLines", String.class, String.class, String.class, String.class, String.class, Integer.class);
        Method bytes = com.finapp.app.accounting.ReportRunController.class.getMethod("readReportRun", String.class);
        for (Method accountants : List.of(lines, bytes)) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(accountants, AssuranceLevel.MULTI_FACTOR, Set.of(RoleName.FINANCIAL_CONTROLLER))).as(accountants.getName()).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode().status()).isEqualTo(403));
        }
    }

    @Test
    @DisplayName("P14-TSK-024: a period's three approvals step up - the unlock's, the close's and the reopen's - and nothing else on their controllers does: the proposals, the ends, the close's rejection and the reads stay at a session's assurance")
    void thePeriodApprovalsArePinned() {
        List<String> steppedUp = List.of("approveAccountingPeriodUnlock", "approveAccountingPeriodClose", "approveAccountingPeriodReopen");
        int seen = 0;
        for (Class<?> controller : List.of(com.finapp.app.accounting.AccountingPeriodController.class, com.finapp.app.accounting.PeriodCloseController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(RequiresPermission.class)) {
                    RequiresAssurance declared = method.getAnnotation(RequiresAssurance.class);
                    if (steppedUp.contains(method.getName())) {
                        seen++;
                        assertThat(declared.value()).as(method.getName()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                        assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName()).isEqualTo(PermissionName.ACCOUNTING_CLOSE_APPROVE);
                    } else {
                        assertThat(declared).as(method.getName()).isNull();
                    }
                }
            }
        }
        assertThat(seen).isEqualTo(steppedUp.size());
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
