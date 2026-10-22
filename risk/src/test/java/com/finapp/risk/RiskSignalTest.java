package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signal vocabulary and the signal itself (`P13-TSK-005`): the kinds closed and pinned to the events that exist,
 * each kind's subject and schema, every refusal a mapping defect meets before a row exists, and the device rule -
 * a {@code DEVICE} subject is identity's hash and never a description (OQ-4; {@code INV-AUD-02}).
 */
@DisplayName("risk signals: the closed kinds, the typed attributes, the device rule (P13-TSK-005)")
class RiskSignalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("thirteen kinds - one per consumed signal-recording event that exists today - each from one event type")
    void theKindsAreClosedAndEachBornFromOneEvent() {
        assertThat(names(SignalKind.values())).containsExactly(
                "CREDENTIAL_CHANGED", "RECOVERY_INITIATED", "RECOVERY_COMPLETED", "IDENTITY_SUSPENDED",
                "ACCOUNT_OPENED", "BENEFICIARY_REGISTERED", "BENEFICIARY_ACTIVATED", "BENEFICIARY_BLOCKED",
                "MERCHANT_ONBOARDED", "LOAN_DELINQUENCY_CHANGED", "LOAN_DEFAULTED", "BNPL_DELINQUENCY_CHANGED",
                "BNPL_DEFAULTED");
        assertThat(Arrays.stream(SignalKind.values()).map(SignalKind::sourceEventType)).containsExactly(
                "identity.CredentialChanged", "identity.RecoveryInitiated", "identity.RecoveryCompleted",
                "identity.IdentitySuspended", "accounts.AccountOpened", "crossborder.BeneficiaryRegistered",
                "crossborder.BeneficiaryActivated", "crossborder.BeneficiaryBlocked", "merchant.MerchantOnboarded",
                "lending.LoanDelinquencyChanged", "lending.LoanDefaulted", "bnpl.BnplDelinquencyChanged",
                "bnpl.BnplDefaulted");
        // Each event type names exactly one kind, and nothing the plan listed that does not exist is a kind.
        assertThat(Arrays.stream(SignalKind.values()).map(SignalKind::sourceEventType).distinct())
                .hasSize(SignalKind.values().length);
        assertThat(SignalKind.recordedFrom("identity.AuthenticationFailed"))
                .as("names no identity by design (INV-IDN-07): a signal from it would have no subject")
                .isEmpty();
        assertThat(SignalKind.recordedFrom("kyc.CounterpartyScreeningDecided"))
                .as("its subject is only in crossborder's request-reference convention; the outcome arrives as"
                        + " crossborder's own BeneficiaryActivated / BeneficiaryBlocked")
                .isEmpty();
        assertThat(SignalKind.recordedFrom("bnpl.BnplDefaulted")).contains(SignalKind.BNPL_DEFAULTED);
    }

    @Test
    @DisplayName("each kind is about one subject kind, and the two kinds the plan did not name are the events' own aggregates")
    void eachKindHasItsSubject() {
        assertThat(SignalKind.CREDENTIAL_CHANGED.subject()).isEqualTo(SubjectKind.IDENTITY);
        assertThat(SignalKind.RECOVERY_COMPLETED.subject()).isEqualTo(SubjectKind.IDENTITY);
        assertThat(SignalKind.IDENTITY_SUSPENDED.subject()).isEqualTo(SubjectKind.IDENTITY);
        assertThat(SignalKind.ACCOUNT_OPENED.subject()).isEqualTo(SubjectKind.ACCOUNT);
        assertThat(SignalKind.BENEFICIARY_REGISTERED.subject()).isEqualTo(SubjectKind.BENEFICIARY);
        assertThat(SignalKind.BENEFICIARY_BLOCKED.subject()).isEqualTo(SubjectKind.BENEFICIARY);
        assertThat(SignalKind.MERCHANT_ONBOARDED.subject()).isEqualTo(SubjectKind.MERCHANT);
        assertThat(SignalKind.LOAN_DEFAULTED.subject()).isEqualTo(SubjectKind.CREDIT_AGREEMENT);
        assertThat(SignalKind.BNPL_DELINQUENCY_CHANGED.subject()).isEqualTo(SubjectKind.CREDIT_AGREEMENT);
        assertThat(names(SubjectKind.values())).containsExactly(
                "PARTY", "ACCOUNT", "DEVICE", "MERCHANT", "BENEFICIARY", "SESSION", "IDENTITY", "CREDIT_AGREEMENT");
    }

    @Test
    @DisplayName("each kind's attributes are the columns the event's codes land in - typed, every one a table column, none twice")
    void eachKindDeclaresItsSchema() {
        assertThat(SignalKind.CREDENTIAL_CHANGED.attributes()).isEmpty();
        assertThat(attributeNames(SignalKind.RECOVERY_INITIATED)).containsExactly("request_ref", "channel_ref");
        assertThat(attributeNames(SignalKind.IDENTITY_SUSPENDED)).containsExactly("status");
        assertThat(attributeNames(SignalKind.ACCOUNT_OPENED)).containsExactly("product");
        assertThat(attributeNames(SignalKind.BENEFICIARY_REGISTERED)).containsExactly("country", "currency");
        assertThat(attributeNames(SignalKind.BENEFICIARY_ACTIVATED)).containsExactly("country", "currency", "previous_status");
        assertThat(attributeNames(SignalKind.MERCHANT_ONBOARDED)).containsExactly("currency");
        assertThat(attributeNames(SignalKind.LOAN_DELINQUENCY_CHANGED)).containsExactly(
                "product", "business_date", "days_past_due", "bucket", "previous_bucket", "defaulted", "agreement_version");
        assertThat(attributeNames(SignalKind.LOAN_DEFAULTED)).containsExactly(
                "product", "business_date", "days_past_due", "bucket", "defaulted", "agreement_version");
        assertThat(attributeNames(SignalKind.BNPL_DELINQUENCY_CHANGED))
                .isEqualTo(attributeNames(SignalKind.LOAN_DELINQUENCY_CHANGED));
        assertThat(attributeNames(SignalKind.BNPL_DEFAULTED)).isEqualTo(attributeNames(SignalKind.LOAN_DEFAULTED));
        for (SignalKind kind : SignalKind.values()) {
            assertThat(SignalKind.ATTRIBUTE_COLUMNS).containsAll(attributeNames(kind));
        }
        assertThat(SignalKind.LOAN_DEFAULTED.attribute("days_past_due").map(SignalKind.Attribute::type))
                .contains(SignalKind.AttributeType.DAYS);
        assertThat(SignalKind.LOAN_DEFAULTED.attribute("previous_bucket")).isEmpty();
    }

    @Test
    @DisplayName("every attribute type admits its canonical form and refuses a name, a description, an amount, a value past its bound")
    void theAttributeTypesAreBounds() {
        assertThat(SignalKind.AttributeType.CODE.admits("B2_30_59")).isTrue();
        assertThat(SignalKind.AttributeType.CODE.admits("Ada Lovelace")).isFalse();
        assertThat(SignalKind.AttributeType.CODE.admits("x".repeat(65).toUpperCase())).isFalse();
        assertThat(SignalKind.AttributeType.CODE.admits("")).isFalse();
        assertThat(SignalKind.AttributeType.COUNTRY.admits("DE")).isTrue();
        assertThat(SignalKind.AttributeType.COUNTRY.admits("DEU")).isFalse();
        assertThat(SignalKind.AttributeType.CURRENCY.admits("EUR")).isTrue();
        assertThat(SignalKind.AttributeType.CURRENCY.admits("EU")).isFalse();
        assertThat(SignalKind.AttributeType.DATE.admits("2026-10-23")).isTrue();
        assertThat(SignalKind.AttributeType.DATE.admits("2026-13-01")).isFalse();
        assertThat(SignalKind.AttributeType.DAYS.admits("0")).isTrue();
        assertThat(SignalKind.AttributeType.DAYS.admits("36500")).isTrue();
        assertThat(SignalKind.AttributeType.DAYS.admits("36501")).isFalse();
        assertThat(SignalKind.AttributeType.DAYS.admits("-1")).isFalse();
        assertThat(SignalKind.AttributeType.DAYS.admits("01")).isFalse();
        assertThat(SignalKind.AttributeType.FLAG.admits("true")).isTrue();
        assertThat(SignalKind.AttributeType.FLAG.admits("TRUE")).isFalse();
        assertThat(SignalKind.AttributeType.VERSION.admits("1")).isTrue();
        assertThat(SignalKind.AttributeType.VERSION.admits("0")).isFalse();
        assertThat(SignalKind.AttributeType.REF.admits(UUID.randomUUID().toString())).isTrue();
        assertThat(SignalKind.AttributeType.REF.admits("not-a-uuid")).isFalse();
        assertThat(SignalKind.AttributeType.REF.admits(null)).isFalse();
    }

    @Test
    @DisplayName("a signal carries exactly its kind's attributes: a missing one, an unknown one or one past its bound is refused by name, never by value")
    void attributesAreExactlyTheSchema() {
        Map<String, String> loan = loanAttributes();
        assertThatCode(() -> SignalAttributes.of(SignalKind.LOAN_DEFAULTED, loan)).doesNotThrowAnyException();

        Map<String, String> missing = new HashMap<>(loan);
        missing.remove("bucket");
        assertThatThrownBy(() -> SignalAttributes.of(SignalKind.LOAN_DEFAULTED, missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");

        Map<String, String> unknown = new HashMap<>(loan);
        unknown.put("note", "SOMETHING");
        assertThatThrownBy(() -> SignalAttributes.of(SignalKind.LOAN_DEFAULTED, unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note");

        Map<String, String> outside = new HashMap<>(loan);
        outside.put("days_past_due", "99999");
        assertThatThrownBy(() -> SignalAttributes.of(SignalKind.LOAN_DEFAULTED, outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days_past_due")
                .hasMessageNotContaining("99999");

        Map<String, String> free = new HashMap<>(loan);
        free.put("product", "a loan for Ada Lovelace");
        assertThatThrownBy(() -> SignalAttributes.of(SignalKind.LOAN_DEFAULTED, free))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Ada");

        assertThat(SignalAttributes.of(SignalKind.CREDENTIAL_CHANGED, Map.of()).isEmpty()).isTrue();
        assertThat(SignalAttributes.of(SignalKind.LOAN_DEFAULTED, loan).toString())
                .as("the names, never the values")
                .contains("bucket")
                .doesNotContain("B4_90_PLUS");
    }

    @Test
    @DisplayName("a signal's subject is its kind's subject, its source is its kind's event, its attributes its kind's schema")
    void aSignalHoldsTheThreeAgreements() {
        UUID loan = UUID.randomUUID();
        RiskSignal signal = new RiskSignal(
                RiskSignalId.next(IDS), SignalKind.LOAN_DEFAULTED, SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan),
                source("lending.LoanDefaulted", "lending"), Instant.parse("2026-10-22T08:00:00Z"),
                SignalAttributes.of(SignalKind.LOAN_DEFAULTED, loanAttributes()));
        assertThat(signal.subject().ref()).isEqualTo(loan.toString());
        assertThat(signal.attributes().get("bucket")).contains("B4_90_PLUS");

        assertThatThrownBy(() -> new RiskSignal(
                RiskSignalId.next(IDS), SignalKind.LOAN_DEFAULTED, SignalSubject.of(SubjectKind.PARTY, loan),
                source("lending.LoanDefaulted", "lending"), Instant.now(CLOCK),
                SignalAttributes.of(SignalKind.LOAN_DEFAULTED, loanAttributes())))
                .as("a loan's default is about the credit agreement, not a party the event never named")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskSignal(
                RiskSignalId.next(IDS), SignalKind.LOAN_DEFAULTED, SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan),
                source("bnpl.BnplDefaulted", "bnpl"), Instant.now(CLOCK),
                SignalAttributes.of(SignalKind.LOAN_DEFAULTED, loanAttributes())))
                .as("a kind is born from its one event type")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskSignal(
                RiskSignalId.next(IDS), SignalKind.LOAN_DEFAULTED, SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan),
                source("lending.LoanDefaulted", "lending"), Instant.now(CLOCK),
                SignalAttributes.of(SignalKind.ACCOUNT_OPENED, Map.of("product", "WALLET"))))
                .as("attributes built for another kind do not fit")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a device is its hash: 64 lowercase hex, the canonical SHA-256 of the description - a description never becomes a subject (OQ-4)")
    void aDeviceIsOnlyEverAHash() {
        DeviceHash hash = DeviceHash.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0");
        assertThat(hash.value()).matches("^[0-9a-f]{64}$");
        assertThat(DeviceHash.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0")).isEqualTo(hash);
        assertThat(DeviceHash.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0")).isNotEqualTo(hash);
        assertThat(DeviceHash.parse(hash.value())).isEqualTo(hash);
        assertThat(SignalSubject.device(hash).ref()).isEqualTo(hash.value());

        assertThatThrownBy(() -> DeviceHash.parse("Chrome on Windows"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Chrome");
        assertThatThrownBy(() -> DeviceHash.parse(hash.value().toUpperCase()))
                .as("one canonical form, so two spellings of one device are not two devices")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignalSubject(SubjectKind.DEVICE, "Chrome on Windows"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Chrome");
        assertThatThrownBy(() -> new SignalSubject(SubjectKind.DEVICE, UUID.randomUUID().toString()))
                .as("not even an identifier: a device has no id, only its hash")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalSubject.of(SubjectKind.DEVICE, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeviceHash.of(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every other subject is a canonical UUID - never free text, never an uppercase or braced spelling")
    void everyOtherSubjectIsACanonicalUuid() {
        UUID id = UUID.randomUUID();
        assertThat(SignalSubject.of(SubjectKind.IDENTITY, id).ref()).isEqualTo(id.toString());
        assertThatThrownBy(() -> new SignalSubject(SubjectKind.IDENTITY, id.toString().toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignalSubject(SubjectKind.ACCOUNT, "Ada Lovelace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Ada");
        assertThatThrownBy(() -> new SignalSubject(SubjectKind.DEVICE, DeviceHash.of("x").value().substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a source event is bounded like the envelope it came from")
    void aSourceEventIsBounded() {
        assertThatThrownBy(() -> source("", "lending")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source("x".repeat(201), "lending")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source("lending.LoanDefaulted", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskSignal.SourceEvent(EventId.next(IDS), "lending.LoanDefaulted", 0, "lending",
                CorrelationId.of("flow"), CausationId.of("cause")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    static Map<String, String> loanAttributes() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("product", "PERSONAL_LOAN");
        attributes.put("business_date", "2026-10-22");
        attributes.put("days_past_due", "91");
        attributes.put("bucket", "B4_90_PLUS");
        attributes.put("defaulted", "true");
        attributes.put("agreement_version", "1");
        return attributes;
    }

    static RiskSignal.SourceEvent source(String type, String producer) {
        return new RiskSignal.SourceEvent(EventId.next(IDS), type, 1, producer, CorrelationId.of("flow-1"),
                CausationId.of("cause-1"));
    }

    private static List<String> attributeNames(SignalKind kind) {
        return kind.attributes().stream().map(SignalKind.Attribute::name).toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
