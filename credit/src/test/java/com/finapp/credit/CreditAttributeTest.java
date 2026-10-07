package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The normalised attribute model (`P10-TSK-005`; PHASE_10_PLAN.md section 12.2, {@code INV-CRD-07},
 * {@code INV-CRD-10}): a value has its code's type or is absent, every answer's shape is enforced,
 * and nothing renders a value.
 */
@DisplayName("credit attributes and bureau answers hold their shape and render no value (P10-TSK-005)")
class CreditAttributeTest {

    private static final AttributeProvenance PROVENANCE =
            new AttributeProvenance.Provider(CreditSourceKind.BUREAU, "bureau-test", 1);
    private static final Money BALANCE = Money.ofMinorUnits(420_050, CurrencyCode.of("EUR"));

    @Test
    @DisplayName("a value must have its code's declared type - or be absent, which every type admits")
    void aValueHasItsCodesType() {
        assertThat(new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                new AttributeValue.MoneyValue(BALANCE), PROVENANCE).absent()).isFalse();
        assertThat(new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                new AttributeValue.Absent(), PROVENANCE).absent()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditAttribute(
                CreditAttributeCode.BUREAU_TOTAL_BALANCE, new AttributeValue.IntegerValue(4200), PROVENANCE));
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditAttribute(
                CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, new AttributeValue.CodeValue("YES"), PROVENANCE));
        assertThatIllegalArgumentException().isThrownBy(() -> new AttributeValue.CodeValue("not a token"));
    }

    @Test
    @DisplayName("a received answer has no absent attribute; a partial one has at least one; one attribute per code")
    void answersHoldTheirShape() {
        CreditAttribute present = new CreditAttribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS,
                new AttributeValue.IntegerValue(4), PROVENANCE);
        CreditAttribute absent = new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                new AttributeValue.Absent(), PROVENANCE);
        CreditEvidence evidence = new CreditEvidence(new byte[] {1, 2, 3});
        Instant at = Instant.parse("2026-10-07T09:00:00Z");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CreditDataAnswer.Received("b", 1, at, List.of(present, absent), evidence));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CreditDataAnswer.Received("b", 1, at, List.of(), evidence));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CreditDataAnswer.Partial("b", 1, at, List.of(present), evidence));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CreditDataAnswer.Received("b", 1, at, List.of(present, present), evidence));
        assertThat(new CreditDataAnswer.Partial("b", 1, at, List.of(present, absent), evidence).absentCodes())
                .containsExactly(CreditAttributeCode.BUREAU_TOTAL_BALANCE);
    }

    @Test
    @DisplayName("no attribute, value, evidence or answer renders the value it holds")
    void nothingRendersAValue() {
        CreditAttribute balance = new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                new AttributeValue.MoneyValue(BALANCE), PROVENANCE);
        CreditAttribute score = new CreditAttribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE,
                new AttributeValue.IntegerValue(712), PROVENANCE);
        CreditEvidence evidence = new CreditEvidence("{\"secret\":\"4200.50\"}".getBytes());
        CreditDataAnswer answer = new CreditDataAnswer.Received(
                "b", 1, Instant.parse("2026-10-07T09:00:00Z"), List.of(balance, score), evidence);
        String rendered = answer + " " + balance + " " + score + " " + new CreditDataAnswer.Unavailable(
                CreditDataAnswer.UnavailableCause.MALFORMED, Optional.of(evidence));
        assertThat(rendered).doesNotContain("4200").doesNotContain("712").doesNotContain("secret")
                .contains("BUREAU_TOTAL_BALANCE").contains("bytes]");
    }

    @Test
    @DisplayName("evidence is copied in and out - the caller's array cannot change what was received")
    void evidenceIsDefensive() {
        byte[] bytes = {1, 2, 3};
        CreditEvidence evidence = new CreditEvidence(bytes);
        bytes[0] = 9;
        evidence.bytes()[1] = 9;
        assertThat(evidence.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a bureau request names a platform reference and a subject")
    void aRequestIsWellFormed() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CreditDataPull("not a reference!", "S-1", CreditProduct.PERSONAL_LOAN));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CreditDataPull("R-1", " ", CreditProduct.PERSONAL_LOAN));
        assertThat(CreditBureau.ATTRIBUTES).hasSize(7)
                .allSatisfy(code -> assertThat(code.name()).startsWith("BUREAU_"));
    }
}
