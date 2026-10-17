package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplApplication;
import com.finapp.bnpl.BnplApplicationClosureReason;
import com.finapp.bnpl.BnplApplicationId;
import com.finapp.bnpl.BnplApplicationStatus;
import com.finapp.bnpl.BnplApplications;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTermsVersionId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary half of the BNPL application doors, hermetic (`P12-TSK-010`): the closed body parsed exactly into the
 * domain's words - an unknown product {@code bnpl.ProductNotOffered}, an unknown plan or another product's
 * {@code api.ValidationFailed}, before anything is keyed; the view and the keyed act's recorded response never carrying
 * the token, the session, the merchant, an amount or a score; the replay answering what was recorded. The doors over
 * HTTP - MFA, the key, the uniform 404, credit's refusals on the contract - are {@code BnplApplicationApiTest}'s and
 * {@code BnplApplicationDatabaseTest}'s.
 */
@DisplayName("the BNPL application desk parses the closed body and answers no secret (P12-TSK-010)")
class BnplApplicationDeskTest {

    @Test
    @DisplayName("the body parses to the domain's submission; an unknown product is bnpl.ProductNotOffered, an unknown or"
            + " foreign plan api.ValidationFailed")
    void theBodyParses() {
        BnplApplications.Submission parsed = BnplApplicationDesk.parse(body("BNPL_PAY_IN_N", "PAY_IN_12_MONTHS"));
        assertThat(parsed.product()).isEqualTo(BnplProduct.BNPL_PAY_IN_N);
        assertThat(parsed.plan()).isEqualTo(BnplPlan.PAY_IN_12_MONTHS);
        assertThat(parsed.sessionToken().expose()).isEqualTo("cs_secret_token");
        assertThatThrownBy(() -> BnplApplicationDesk.parse(body("MORTGAGE", "PAY_IN_4")))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(BnplErrorCode.PRODUCT_NOT_OFFERED));
        for (String plan : List.of("PAY_IN_5", "PAY_IN_3_MONTHS")) {
            assertThatThrownBy(() -> BnplApplicationDesk.parse(body("BNPL_PAY_IN_4", plan)))
                    .as(plan)
                    .isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> BnplApplicationDesk.parse(new BnplApplicationController.BnplApplicationBody(null,
                "BNPL_PAY_IN_4", "PAY_IN_4"))).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the body's record never prints its token")
    void theBodyNeverPrintsTheToken() {
        assertThat(body("BNPL_PAY_IN_4", "PAY_IN_4").toString()).doesNotContain("cs_secret_token");
    }

    @Test
    @DisplayName("the view carries no token, session, merchant, party, amount or score - and the recorded response replays"
            + " the act as it stood")
    void theViewAndTheReplay() {
        assertThat(Arrays.stream(BnplApplicationDesk.BnplApplicationView.class.getRecordComponents())
                .map(RecordComponent::getName))
                .noneMatch(name -> name.contains("token") || name.contains("ession") || name.contains("erchant")
                        || name.contains("arty") || name.contains("mount") || name.contains("inor")
                        || name.contains("core"));
        BnplApplication application = application(BnplApplicationStatus.WITHDRAWN, Optional.empty());
        BnplApplicationDesk.BnplApplicationView view = BnplApplicationDesk.view(application, List.of());
        BnplApplicationDesk.BnplApplicationView replayed =
                BnplApplicationDesk.replayed(BnplApplicationDesk.stored(view).body());
        assertThat(replayed).isEqualTo(view);
        assertThat(view.status()).isEqualTo("WITHDRAWN");
        assertThat(view.closedAt()).isEqualTo("2026-10-17T10:05:00Z");
        assertThat(new String(BnplApplicationDesk.stored(view).body(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(application.checkoutSession().toString())
                .doesNotContain(application.merchant().toString())
                .doesNotContain(application.party().toString());
    }

    @Test
    @DisplayName("a declined application's view carries credit's reasons; a closed one its closure reason")
    void theReasonsAndTheClosure() {
        BnplApplicationDesk.BnplApplicationView declined = BnplApplicationDesk.view(
                application(BnplApplicationStatus.DECLINED, Optional.empty()), List.of("We could not verify your income."));
        assertThat(declined.reasons()).containsExactly("We could not verify your income.");
        BnplApplicationDesk.BnplApplicationView closed = BnplApplicationDesk.view(application(
                BnplApplicationStatus.CLOSED_UNDECIDED, Optional.of(BnplApplicationClosureReason.SESSION_CLOSED)), List.of());
        assertThat(closed.closureReason()).isEqualTo("SESSION_CLOSED");
        assertThat(closed.reasons()).isNull();
    }

    // -----------------------------------------------------------------

    private static BnplApplicationController.BnplApplicationBody body(String product, String plan) {
        return new BnplApplicationController.BnplApplicationBody(Sensitive.of("cs_secret_token"), product, plan);
    }

    private static BnplApplication application(BnplApplicationStatus status,
            Optional<BnplApplicationClosureReason> reason) {
        return new BnplApplication(BnplApplicationId.of(UUID.fromString("01900000-0000-7000-8000-0000000000a1")),
                UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, status, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                BnplTermsVersionId.of(UUID.fromString("01900000-0000-7000-8000-0000000000a2")), 2, reason,
                Instant.parse("2026-10-17T10:00:00Z"), Optional.of(Instant.parse("2026-10-17T10:05:00Z")),
                CorrelationId.of("p12-tsk-010"));
    }
}
