package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tipping-off control (`P17-TSK-126`, ADR-0155 decision 4): a reviewer's text that names a screening, a list, a suspicion or
 * an investigation, carries one of the platform's identifiers or a link, or breaks its bounds cannot be built - and the ordinary
 * words of a request for information can.
 */
@DisplayName("a request to the customer never tips them off (P17-TSK-126)")
class CustomerRequestTextTest {

    private static final String OPENING = "Please upload a clearer copy of your document. ";

    static Stream<String> everyTerm() {
        return Stream.concat(CustomerRequestText.STEMS.stream(), CustomerRequestText.PHRASES.stream());
    }

    @ParameterizedTest(name = "\"{0}\" is refused inside a sentence")
    @MethodSource("everyTerm")
    void everyListedTermIsRefused(String term) {
        assertThatThrownBy(() -> CustomerRequestText.of(OPENING + "This concerns " + term + " checks we ran."))
                .isInstanceOfSatisfying(CustomerRequestText.Refused.class,
                        refused -> assertThat(refused.rule()).isEqualTo(CustomerRequestText.Rule.SCREENING_VOCABULARY));
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
        "Your name appeared on a SANCTIONS list, please explain.",
        "We found you may be Politically-Exposed; send proof of address.",
        "You are a PEP according to our provider, please confirm.",
        "Our watch-list review needs your passport again please.",
        "Your account was Sanctioned; send us the document again.",
        "Please explain the s­anctions match on your file, thanks.",
        "Please explain the ｓａｎｃｔｉｏｎ listing for your name.",
        "We are investigating your transfers; send bank statements.",
        "Because of adverse media about you, send your passport.",
        "A suspicious-activity review needs your source of funds.",
        "Your funds are frozen until you send proof of address.",
        "Under AML rules please send your proof of address soon."
    })
    void evasionsByCaseHyphenSoftHyphenOrWidthAreRefused(String text) {
        assertThatThrownBy(() -> CustomerRequestText.of(text))
                .isInstanceOfSatisfying(CustomerRequestText.Refused.class,
                        refused -> assertThat(refused.rule()).isEqualTo(CustomerRequestText.Rule.SCREENING_VOCABULARY));
    }

    @ParameterizedTest(name = "\"{0}\" is admitted")
    @ValueSource(strings = {
        "Please upload a clearer photo of your passport: all four corners must be visible and nothing may be blurred.",
        "The name on your application does not match the name on your passport. Please upload a document showing your"
                + " current legal name, or explain the difference.",
        "Please upload a utility bill or bank statement from the last three months showing your residential address.",
        "The driving licence you uploaded had expired. Please upload a document that is valid today.",
        "Please tell us where the money you plan to deposit comes from, for example your salary or savings.",
        "A screenshot is fine if it shows the whole page.\nPlease include your laundry service invoice\tas proof of address.",
        "Please confirm your date of birth; the document reads differently from the form you filled in."
    })
    void ordinaryRequestsAreAdmitted(String text) {
        assertThat(CustomerRequestText.of(text).value()).isEqualTo(text);
    }

    @Test
    @DisplayName("one of the platform's identifiers is refused: a case, check or task id names what was looked at")
    void anInternalIdentifierIsRefused() {
        assertThatThrownBy(() -> CustomerRequestText.of(
                        OPENING + "Reference 0192f1d2-7b3c-7c1e-9a2b-3c4d5e6f7a8b when you write back."))
                .isInstanceOfSatisfying(CustomerRequestText.Refused.class,
                        refused -> assertThat(refused.rule()).isEqualTo(CustomerRequestText.Rule.INTERNAL_IDENTIFIER));
    }

    @ParameterizedTest(name = "\"{0}\" is refused as a link")
    @ValueSource(strings = {
        "Please upload your passport at https://example.test/upload today.",
        "Please upload your passport at www.example.test before Friday.",
        "Please e-mail your passport to someone@example.test before Friday."
    })
    void aLinkOrAnAddressIsRefused(String text) {
        assertThatThrownBy(() -> CustomerRequestText.of(text))
                .isInstanceOfSatisfying(CustomerRequestText.Refused.class,
                        refused -> assertThat(refused.rule()).isEqualTo(CustomerRequestText.Rule.LINK));
    }

    @Test
    @DisplayName("the bounds are 20 to 1,000 characters, and no control character but a line break or a tab")
    void theBoundsHold() {
        assertThat(CustomerRequestText.of("x".repeat(CustomerRequestText.MIN_LENGTH)).value()).hasSize(20);
        assertThat(CustomerRequestText.of("x".repeat(CustomerRequestText.MAX_LENGTH)).value()).hasSize(1000);
        assertThatThrownBy(() -> CustomerRequestText.of("x".repeat(CustomerRequestText.MIN_LENGTH - 1)))
                .isInstanceOfSatisfying(CustomerRequestText.Refused.class,
                        refused -> assertThat(refused.rule()).isEqualTo(CustomerRequestText.Rule.LENGTH));
        assertThatThrownBy(() -> CustomerRequestText.of("x".repeat(CustomerRequestText.MAX_LENGTH + 1)))
                .isInstanceOfSatisfying(CustomerRequestText.Refused.class,
                        refused -> assertThat(refused.rule()).isEqualTo(CustomerRequestText.Rule.LENGTH));
        assertThatThrownBy(() -> CustomerRequestText.of(OPENING + "Thanks\u0007 a lot."))
                .isInstanceOfSatisfying(CustomerRequestText.Refused.class,
                        refused -> assertThat(refused.rule()).isEqualTo(CustomerRequestText.Rule.CHARACTERS));
    }

    @Test
    @DisplayName("a refusal names its rule and never echoes the text; toString never carries it either")
    void neitherTheRefusalNorTheValuePrintsTheText() {
        String secret = OPENING + "Unique-marker-7f3a and the sanctions list.";
        assertThatThrownBy(() -> CustomerRequestText.of(secret)).hasMessageNotContaining("Unique-marker-7f3a");
        assertThat(CustomerRequestText.of(OPENING + "Unique-marker-7f3a").toString()).doesNotContain("Unique-marker-7f3a");
    }

    @Test
    @DisplayName("a stored text is read back as written: a rule added since does not unwrite history")
    void aStoredTextIsNotScreenedAgain() {
        assertThat(CustomerRequestText.stored("written before a rule").value()).isEqualTo("written before a rule");
    }
}
