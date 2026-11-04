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
 * The two approvals that post a person's decision take {@code MULTI_FACTOR} (`P14-TSK-022`, the owner's rulings (r) and of 2026-11-04): a prior-period adjustment's, and {@code LEDGER_ADJUST}'s, the wider door; neither door's proposal, read or rejection. The real interceptor over each real handler, so the refusal, its code and what it
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

    private static List<Door> doors() throws Exception {
        return List.of(new Door(ledger("approve", String.class), Set.of(RoleName.LEDGER_OPERATOR)), new Door(priorPeriod("approvePriorPeriodAdjustment"), Set.of(RoleName.FINANCIAL_CONTROLLER)));
    }

    /** An approval and the role that may knock at it. */
    private record Door(Method approval, Set<RoleName> holder) {}
}
