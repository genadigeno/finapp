package com.finapp.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.administration.AccessReviewController;
import com.finapp.app.crossborder.CorridorAdministrationController;
import com.finapp.app.fx.FxAdministrationController;
import com.finapp.app.fx.FxTradeReversalController;
import com.finapp.app.merchant.PayoutDestinationController;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The access review's two step-up rulings, on the doors (`P15-TSK-039`; {@code PRIVILEGED_ACCESS_REVIEW.md} §6): a payout
 * destination's proposal and approval, and Phase 9's approvals, restarts and reversals, need {@code MULTI_FACTOR}, as does the
 * read that lists who holds a money role with no factor. The real
 * interceptor over each real handler, as {@code ApprovalAssuranceTest} does. A real session row at the real doors is the
 * endpoint database suites', owed.
 */
@DisplayName("the review's two rulings hold on the doors: MULTI_FACTOR, exactly there (P15-TSK-039)")
class RuledDoorsStepUpTest {

    private record Door(Method method, Set<RoleName> holder) {}

    private static Door door(Class<?> controller, String name, RoleName... holder) {
        Method method = Stream.of(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(controller.getSimpleName() + "#" + name));
        return new Door(method, Set.of(holder));
    }

    private static List<Door> doors() {
        return List.of(
                door(PayoutDestinationController.class, "proposePayoutDestination", RoleName.MERCHANT_ADMINISTRATOR),
                door(PayoutDestinationController.class, "approvePayoutDestination", RoleName.MERCHANT_ADMINISTRATOR),
                door(FxAdministrationController.class, "approveFxPricingPolicy", RoleName.FX_CONTROLLER),
                door(FxAdministrationController.class, "approveFxEnableRequest", RoleName.FX_CONTROLLER),
                door(CorridorAdministrationController.class, "approveCorridorPolicy", RoleName.FX_CONTROLLER),
                door(CorridorAdministrationController.class, "approveCorridorEnableRequest", RoleName.FX_CONTROLLER),
                door(FxTradeReversalController.class, "proposeFxTradeReversal", RoleName.LEDGER_OPERATOR),
                door(FxTradeReversalController.class, "approveFxTradeReversal", RoleName.LEDGER_OPERATOR),
                door(AccessReviewController.class, "listFactorlessHoldings", RoleName.ADMINISTRATOR),
                // P15-TSK-032: a role's revocation (the design's Q1, decided), and - item 42 (e), decided - the grant, the
                // suspension and the reinstatement: no administrative act on a password alone.
                door(com.finapp.app.administration.IdentityAdministrationController.class, "revokeRole", RoleName.ADMINISTRATOR),
                door(com.finapp.app.administration.IdentityAdministrationController.class, "assignRole", RoleName.ADMINISTRATOR),
                door(com.finapp.app.administration.IdentityAdministrationController.class, "suspendIdentity", RoleName.ADMINISTRATOR),
                door(com.finapp.app.administration.IdentityAdministrationController.class, "reinstateIdentity", RoleName.ADMINISTRATOR));
    }

    @Test
    @DisplayName("each door declares it, beside the permission it always declared")
    void eachDoorDeclaresIt() {
        for (Door door : doors()) {
            assertThat(door.method().getAnnotation(RequiresAssurance.class)).as(door.method().getName()).isNotNull();
            assertThat(door.method().getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
            assertThat(door.method().getAnnotation(RequiresPermission.class)).as(door.method().getName()).isNotNull();
        }
    }

    @Test
    @DisplayName("below MULTI_FACTOR the real interceptor refuses each, before the permission is read; at it or above the holder passes")
    void aPasswordSessionIsRefusedAtEach() throws Exception {
        for (Door door : doors()) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.method(), AssuranceLevel.PASSWORD, door.holder()))
                    .as(door.method().getName())
                    .isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
            assertThat(SteppedUpDoor.knock(door.method(), AssuranceLevel.MULTI_FACTOR, door.holder())).as(door.method().getName()).isTrue();
            assertThat(SteppedUpDoor.knock(door.method(), AssuranceLevel.STRONG, door.holder())).as(door.method().getName()).isTrue();
        }
    }

    @Test
    @DisplayName("the order at a declared door: a password session with no role is told to step up, as at every declared door")
    void theLevelIsJudgedBeforeThePermission() {
        for (Door door : doors()) {
            assertThatThrownBy(() -> SteppedUpDoor.knock(door.method(), AssuranceLevel.PASSWORD, Set.of()))
                    .as(door.method().getName())
                    .isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED));
        }
    }

    @Test
    @DisplayName("what stops a change stays quick: a rejection, a withdrawal and the kill switch itself need no step-up")
    void theFailSafeDoorsAreLeftAlone() {
        for (Door door : List.of(
                door(PayoutDestinationController.class, "rejectPayoutDestination"),
                door(PayoutDestinationController.class, "withdrawPayoutDestination"),
                door(FxAdministrationController.class, "rejectFxPricingPolicy"),
                door(FxAdministrationController.class, "setFxPairAvailability"),
                door(CorridorAdministrationController.class, "rejectCorridorPolicy"),
                door(FxTradeReversalController.class, "rejectFxTradeReversal"))) {
            assertThat(door.method().getAnnotation(RequiresAssurance.class)).as(door.method().getName()).isNull();
        }
        assertThat(door(FxAdministrationController.class, "setFxPairAvailability").method().getAnnotation(RequiresPermission.class).value())
                .isEqualTo(PermissionName.FX_ADMINISTER);
    }
}
