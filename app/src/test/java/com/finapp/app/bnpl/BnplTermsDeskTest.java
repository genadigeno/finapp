package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTerms;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplTermsVersion;
import com.finapp.bnpl.BnplTermsVersionId;
import com.finapp.bnpl.BnplTermsVersionStatus;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary half of the BNPL terms door, hermetic (`P12-TSK-004`): the closed body parsed exactly into the domain's
 * words - each product's own part, engines by name, the template registered - and every unknown or misplaced word
 * {@code bnpl.TermsInvalid} before anything is keyed; the view serving the whole term set and never a reason or a
 * person. The door over HTTP - permissions, keyed replay, refusals on the error contract - is
 * {@code BnplTermsEndpointDatabaseTest}'s.
 */
@DisplayName("the BNPL terms desk parses the closed body into the domain's words (P12-TSK-004)")
class BnplTermsDeskTest {

    @Test
    @DisplayName("pay-in-4's body parses to its interest-free four-instalment terms; pay-in-N's to its plans, floor and"
            + " conventions")
    void eachProductsBodyParses() {
        BnplTerms four = BnplTermsDesk.terms(request(payIn4(), Map.of()));
        assertThat(four.product()).isEqualTo(BnplProduct.BNPL_PAY_IN_4);
        assertThat(four.nominalAnnualRate()).isEqualTo(BigDecimal.ZERO);
        assertThat(four.payIn4().orElseThrow().instalments()).isEqualTo(4);
        BnplTerms n = BnplTermsDesk.terms(request(payInN(), Map.of()));
        assertThat(n.payInN().orElseThrow().allowedTerms()).containsExactly(3, 6, 12);
        assertThat(n.payInN().orElseThrow().minFirstPeriodDays()).isEqualTo(15);
        assertThat(n.nominalAnnualRate()).isEqualTo(new BigDecimal("0.1999"));
    }

    @Test
    @DisplayName("every unknown or misplaced word is TermsInvalid: product, currency, rate, frequency, a convention, an"
            + " engine of another family, a template, another product's part")
    void everyUnknownWordIsTermsInvalid() {
        List<Map<String, Object>> defects = List.of(
                Map.of("product", "MORTGAGE"),
                Map.of("currency", "GBP"),
                Map.of("nominalAnnualRate", "eight percent"),
                Map.of("frequency", "WEEKLY"),
                Map.of("instalmentRounding", "SIDEWAYS"),
                Map.of("allocationOrder", List.of("FEES", "TIPS", "PRINCIPAL")),
                Map.of("scheduleEngine", "SCHEDULE_ENGINE_V1"),
                Map.of("refundAllocationEngine", "REFUND_ALLOCATION_ENGINE_V2"),
                Map.of("allocationEngine", "ALLOCATION_ENGINE_V9"),
                Map.of("agreementTemplateId", "BNPL_PAY_IN_N_AGREEMENT"),
                Map.of("agreementTemplateVersion", 2),
                Map.of("allowedTerms", List.of(3)),
                Map.of("nominalAnnualRate", "0.05"));
        for (Map<String, Object> defect : defects) {
            assertThatThrownBy(() -> BnplTermsDesk.terms(request(payIn4(), defect)))
                    .as(defect.toString())
                    .isInstanceOf(BnplTerms.TermsInvalid.class);
        }
        assertThatThrownBy(() -> BnplTermsDesk.terms(request(payInN(), Map.of("dayCount", "ACT_360"))))
                .isInstanceOf(BnplTerms.TermsInvalid.class);
        assertThatThrownBy(() -> BnplTermsDesk.terms(request(payInN(), Map.of("accrualEngine", "STATEMENT_ENGINE_V1"))))
                .isInstanceOf(BnplTerms.TermsInvalid.class);
        Map<String, Object> noPart = new LinkedHashMap<>(payInN());
        for (String field : List.of("allowedTerms", "minFirstPeriodDays", "interestRounding", "dayCount", "accrualEngine")) {
            noPart.put(field, null);
        }
        assertThatThrownBy(() -> BnplTermsDesk.terms(request(noPart, Map.of())))
                .as("pay-in-N without its own part").isInstanceOf(BnplTerms.TermsInvalid.class);
    }

    @Test
    @DisplayName("the view serves the whole term set and the machine row - never a reason or a person")
    void theViewServesTheTermSet() {
        BnplTerms terms = BnplTermsDesk.terms(request(payInN(), Map.of()));
        BnplTermsVersionId id = BnplTermsVersionId.of(UUID.fromString("01900000-0000-7000-8000-000000000001"));
        Instant at = Instant.parse("2026-10-17T10:00:00Z");
        BnplTermsDesk.BnplTermsView view = BnplTermsDesk.view(new BnplTermsVersion(new BnplTermsStore.VersionRow(id,
                BnplProduct.BNPL_PAY_IN_N, 2, BnplTermsVersionStatus.ACTIVE, "alice", at, Optional.of("bob"),
                Optional.of(at), Optional.of(at), Optional.empty()), terms));
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.nominalAnnualRate()).isEqualTo("0.1999");
        assertThat(view.allowedTerms()).containsExactly(3, 6, 12);
        assertThat(view.scheduleEngine()).isEqualTo("SCHEDULE_ENGINE_V1");
        assertThat(view.instalmentCount()).isNull();
        assertThat(view.disputeFourEyesThresholdMinor()).isEqualTo(25_000L);
        assertThat(Arrays.stream(BnplTermsDesk.BnplTermsView.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(name -> name.contains("reason") || name.endsWith("By"));
        assertThat(view.toString()).doesNotContain("alice").doesNotContain("bob");
    }

    // -----------------------------------------------------------------

    static Map<String, Object> payIn4() {
        Map<String, Object> fields = common();
        fields.put("product", "BNPL_PAY_IN_4");
        fields.put("nominalAnnualRate", "0");
        fields.put("frequency", "BIWEEKLY");
        fields.put("instalmentRounding", "DOWN");
        fields.put("instalmentCount", 4);
        fields.put("intervalDays", 14);
        fields.put("scheduleEngine", "BNPL_SCHEDULE_ENGINE_V1");
        fields.put("agreementTemplateId", "BNPL_PAY_IN_4_AGREEMENT");
        return fields;
    }

    static Map<String, Object> payInN() {
        Map<String, Object> fields = common();
        fields.put("product", "BNPL_PAY_IN_N");
        fields.put("nominalAnnualRate", "0.1999");
        fields.put("frequency", "MONTHLY");
        fields.put("instalmentRounding", "UP");
        fields.put("interestRounding", "HALF_EVEN");
        fields.put("dayCount", "ACT_365F");
        fields.put("allowedTerms", List.of(3, 6, 12));
        fields.put("minFirstPeriodDays", 15);
        fields.put("scheduleEngine", "SCHEDULE_ENGINE_V1");
        fields.put("accrualEngine", "ACCRUAL_ENGINE_V1");
        fields.put("agreementTemplateId", "BNPL_PAY_IN_N_AGREEMENT");
        return fields;
    }

    private static Map<String, Object> common() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("currency", "EUR");
        fields.put("lateFeeMinor", 500L);
        fields.put("lateFeeDay", 5);
        fields.put("lateFeeCapMinor", 1500L);
        fields.put("graceDays", 3);
        fields.put("delinquencyBounds", List.of(1, 30, 60, 90));
        fields.put("defaultThresholdDays", 90);
        fields.put("allocationSequence", "OLDEST_DUE_FIRST");
        fields.put("allocationOrder", List.of("FEES", "INTEREST", "PRINCIPAL"));
        fields.put("overpaymentTreatment", "HOLD_AS_CREDIT");
        fields.put("autoCollection", true);
        fields.put("partialCollection", true);
        fields.put("collectionRetryDays", 1);
        fields.put("servicingZone", "UTC");
        fields.put("offerValidityMinutes", 30);
        fields.put("disputeResponseDays", 14);
        fields.put("disputeFourEyesThresholdMinor", 25_000L);
        fields.put("payoffHorizonDays", 30);
        fields.put("allocationEngine", "ALLOCATION_ENGINE_V1");
        fields.put("refundAllocationEngine", "REFUND_ALLOCATION_ENGINE_V1");
        fields.put("agreementTemplateVersion", 1);
        fields.put("reason", "the plan's terms");
        return fields;
    }

    /** The closed body from {@code fields}, each of {@code changes} replacing one; an absent field is null. */
    static BnplTermsController.BnplTermsProposalRequest request(Map<String, Object> fields, Map<String, Object> changes) {
        Map<String, Object> all = new LinkedHashMap<>(fields);
        all.putAll(changes);
        RecordComponent[] components = BnplTermsController.BnplTermsProposalRequest.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = all.get(components[i].getName());
            types[i] = components[i].getType();
        }
        try {
            Constructor<BnplTermsController.BnplTermsProposalRequest> canonical =
                    BnplTermsController.BnplTermsProposalRequest.class.getDeclaredConstructor(types);
            return canonical.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
