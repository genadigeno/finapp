package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.payments.PaymentProvider.AuthorizationRequest;
import com.finapp.payments.PaymentProvider.CaptureRequest;
import com.finapp.payments.PaymentProvider.RefundRequest;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The port's value types (`P5-TSK-003`): the reference charsets that make the wire and the logs
 * safe without escaping machinery, the answers' coherence, and `INV-AUD-02` at every rendering.
 */
@DisplayName("PaymentProvider value types (P5-TSK-003)")
class PaymentProviderTypesTest {

    private static final Money TEN_USD = Money.ofMinorUnits(1000, CurrencyCode.of("USD"));
    private static final ProviderIdempotencyReference OUR_REF =
            new ProviderIdempotencyReference("pay-auth-0192a7b2");
    private static final ProviderReference PSP_REF = new ProviderReference("psp_auth_1");

    @Nested
    @DisplayName("the platform-minted idempotency reference (INV-PAY-04)")
    class OurReference {

        @Test
        @DisplayName("accepts its charset, refuses everything else without echoing the value")
        void charsetIsTheRule() {
            assertThatCode(() -> new ProviderIdempotencyReference("A-1-b-2")).doesNotThrowAnyException();
            for (String bad : new String[] {"", " ", "with space", "semi;colon", "new\nline", "a_b"}) {
                assertThatThrownBy(() -> new ProviderIdempotencyReference(bad))
                        .isInstanceOf(IllegalArgumentException.class)
                        .satisfies(
                                e -> {
                                    if (!bad.isBlank()) {
                                        assertThat(e.getMessage()).doesNotContain(bad);
                                    }
                                });
            }
            assertThatThrownBy(() -> new ProviderIdempotencyReference("a".repeat(65)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new ProviderIdempotencyReference("a".repeat(64)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the provider's own reference")
    class TheirReference {

        @Test
        @DisplayName("accepts real PSP id shapes, refuses quotes, whitespace and control bytes")
        void boundedForeignIdentifier() {
            assertThatCode(() -> new ProviderReference("pi_3NxAbC:2.v1")).doesNotThrowAnyException();
            for (String bad : new String[] {"", "with space", "qu\"ote", "back\\slash", "bell\u0007"}) {
                assertThatThrownBy(() -> new ProviderReference(bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> new ProviderReference("a".repeat(129)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the requests")
    class Requests {

        @Test
        @DisplayName("a non-positive amount is refused, naming the currency and never the value")
        void nonPositiveAmountRefused() {
            Money zero = Money.ofMinorUnits(0, CurrencyCode.of("USD"));
            Money negative = Money.ofMinorUnits(-100, CurrencyCode.of("USD"));
            for (Money bad : new Money[] {zero, negative}) {
                assertThatThrownBy(
                                () ->
                                        new AuthorizationRequest(
                                                OUR_REF, InstrumentToken.of("tok-1"), bad))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("USD")
                        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("-100"));
                assertThatThrownBy(() -> new CaptureRequest(OUR_REF, PSP_REF, bad))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> new RefundRequest(OUR_REF, PSP_REF, bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("an instrument token outside its charset is refused - the wire needs no escaping")
        void tokenCharsetRefused() {
            for (String bad : new String[] {"", "tok\"quote", "tok space", "tok\nline"}) {
                assertThatThrownBy(() -> InstrumentToken.of(bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("no request rendering carries the token or the amount (INV-AUD-02)")
        void renderingsAreSafe() {
            String needleToken = "tok_needle-77";
            InstrumentToken token = InstrumentToken.of(needleToken);
            // The wrapped component masks, so even the record's generated toString is safe.
            assertThat(token.toString()).doesNotContain(needleToken);
            AuthorizationRequest authorization =
                    new AuthorizationRequest(OUR_REF, token, TEN_USD);
            assertThat(authorization.toString()).doesNotContain(needleToken).doesNotContain("1000");
            assertThat(new CaptureRequest(OUR_REF, PSP_REF, TEN_USD).toString())
                    .doesNotContain("1000");
            assertThat(new RefundRequest(OUR_REF, PSP_REF, TEN_USD).toString())
                    .doesNotContain("1000");
        }
    }

    @Nested
    @DisplayName("the answers' coherence")
    class Answers {

        private final byte[] evidence = "{\"status\":\"x\"}".getBytes(StandardCharsets.UTF_8);

        @Test
        @DisplayName("APPROVED requires the provider's reference - an unactionable approval is not knowledge")
        void approvedRequiresReference() {
            assertThatThrownBy(
                            () ->
                                    new ProviderAnswer(
                                            ProviderAnswer.Verdict.APPROVED,
                                            Optional.empty(),
                                            Optional.of(evidence)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    new QueryAnswer(
                                            QueryAnswer.Verdict.APPROVED,
                                            Optional.empty(),
                                            Optional.of(evidence)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("only APPROVED carries a reference - anywhere else the provider's id is evidence")
        void referenceOnlyWithApproved() {
            assertThatThrownBy(
                            () ->
                                    new ProviderAnswer(
                                            ProviderAnswer.Verdict.DECLINED,
                                            Optional.of(PSP_REF),
                                            Optional.of(evidence)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("NOTHING_SENT carries nothing, structurally - nothing arrived")
        void nothingSentCarriesNothing() {
            assertThatThrownBy(
                            () ->
                                    new ProviderAnswer(
                                            ProviderAnswer.Verdict.NOTHING_SENT,
                                            Optional.empty(),
                                            Optional.of(evidence)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("evidence is defensively copied in and out - retained bytes cannot be edited")
        void evidenceIsDefensivelyCopied() {
            byte[] original = evidence.clone();
            ProviderAnswer answer = ProviderAnswer.declined(original);
            original[0] = 'X';
            assertThat(answer.evidence().orElseThrow()).isEqualTo(evidence);
            answer.evidence().orElseThrow()[0] = 'Y';
            assertThat(answer.evidence().orElseThrow()).isEqualTo(evidence);
        }

        @Test
        @DisplayName("no answer rendering carries the evidence bytes (INV-AUD-02)")
        void renderingsNeverCarryEvidence() {
            byte[] needle = "PAN-SHAPED-NEEDLE-4111".getBytes(StandardCharsets.UTF_8);
            assertThat(ProviderAnswer.approved(PSP_REF, needle).toString())
                    .doesNotContain("4111")
                    .contains("evidence=true");
            assertThat(QueryAnswer.declined(needle).toString())
                    .doesNotContain("4111")
                    .contains("evidence=true");
        }
    }
}
