package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.BureauAnswer;
import com.finapp.credit.BureauAnswer.UnavailableCause;
import com.finapp.credit.BureauRequest;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The credit bureau contract (`P10-TSK-005`; ADR-0085 section 2, {@code INV-CRD-10},
 * {@code INV-LIFE-03}, {@code INV-CRD-12}, {@code INV-CRD-07}): what every {@link CreditBureau}
 * adapter must do, whatever its wire. Every adapter's battery subclasses this and supplies the
 * adapter and the levers that make its provider misbehave.
 *
 * <p>The decisive property: <strong>no answer of a faulty provider ever carries an attribute</strong>
 * - a timeout, silence, a malformed body, an unknown status and a provider error are each
 * {@code Unavailable}, never data, and a partial report states its gaps as {@code Absent}.
 */
abstract class CreditBureauContract {

    /** The adapter under test, against a freshly started provider. */
    protected abstract CreditBureau bureau();

    /** A subject reference the adapter can resolve - distinct values name distinct people. */
    protected abstract String subject(int person);

    /** Arms the provider's next novel pull to misbehave. */
    protected abstract void arm(Misbehaviour misbehaviour);

    /** Reports the provider really produced - one per reference, however often asked. */
    protected abstract int countedPulls();

    /** The ways a bureau misbehaves, as the contract names them. */
    enum Misbehaviour {
        PARTIAL, FOREIGN_CURRENCY, MALFORMED, UNKNOWN_STATUS, PROVIDER_ERROR, TIMEOUT, SLOW_BEYOND_TIMEOUT, RESPONSE_LOST
    }

    @Test
    void aCleanReportIsReceivedWithEveryAttributeAndItsProvenance() {
        BureauAnswer answer = bureau().pull(request("R-1", 1));
        assertThat(answer).isInstanceOf(BureauAnswer.Received.class);
        BureauAnswer.Received received = (BureauAnswer.Received) answer;
        assertThat(codes(received.attributes())).containsExactlyInAnyOrderElementsOf(CreditBureau.ATTRIBUTES);
        assertThat(received.providerCode()).isEqualTo(bureau().code());
        assertThat(received.normaliserVersion()).isPositive();
        assertThat(received.evidence().bytes()).isNotEmpty();
        assertThat(received.attributes()).allSatisfy(attribute -> assertThat(attribute.provenance())
                .isEqualTo(new AttributeProvenance.Provider(
                        CreditSourceKind.BUREAU, bureau().code(), received.normaliserVersion())));
        assertThat(received.attributes())
                .filteredOn(attribute -> attribute.code().valueType() == com.finapp.credit.AttributeValueType.MONEY)
                .allSatisfy(attribute -> assertThat(((AttributeValue.MoneyValue) attribute.value()).value().currency())
                        .isEqualTo(CreditProduct.PERSONAL_LOAN.currency()));
        assertThat(countedPulls()).isEqualTo(1);
    }

    @Test
    void aPartialReportStatesItsGapsAsAbsentAndDefaultsNothing() {
        arm(Misbehaviour.PARTIAL);
        BureauAnswer answer = bureau().pull(request("R-2", 1));
        assertThat(answer).isInstanceOf(BureauAnswer.Partial.class);
        BureauAnswer.Partial partial = (BureauAnswer.Partial) answer;
        assertThat(codes(partial.attributes())).containsAll(CreditBureau.ATTRIBUTES);
        assertThat(partial.absentCodes()).isNotEmpty().isSubsetOf(CreditBureau.ATTRIBUTES);
        assertThat(partial.attributes()).filteredOn(attribute -> partial.absentCodes().contains(attribute.code()))
                .allSatisfy(attribute -> assertThat(attribute.value()).isEqualTo(new AttributeValue.Absent()));
    }

    @Test
    void aForeignCurrencyBalanceIsAbsentNeverConverted() {
        arm(Misbehaviour.FOREIGN_CURRENCY);
        BureauAnswer answer = bureau().pull(request("R-3", 1));
        assertThat(answer).as("partial data, never a conversion (INV-CRD-12)").isInstanceOf(BureauAnswer.Partial.class);
        BureauAnswer.Partial partial = (BureauAnswer.Partial) answer;
        assertThat(partial.absentCodes()).contains(CreditAttributeCode.BUREAU_TOTAL_BALANCE);
        assertThat(byCode(partial.attributes()).get(CreditAttributeCode.CURRENCY_NOT_SUPPORTED).value())
                .as("the marker names the source kind")
                .isEqualTo(new AttributeValue.CodeValue(CreditSourceKind.BUREAU.name()));
        assertThat(partial.attributes())
                .filteredOn(attribute -> attribute.value() instanceof AttributeValue.MoneyValue)
                .allSatisfy(attribute -> assertThat(((AttributeValue.MoneyValue) attribute.value()).value().currency())
                        .isEqualTo(CreditProduct.PERSONAL_LOAN.currency()));
    }

    @Test
    void aMalformedBodyIsUnavailableWithItsBytesKeptAndNoAttribute() {
        arm(Misbehaviour.MALFORMED);
        BureauAnswer answer = bureau().pull(request("R-4", 1));
        assertUnavailable(answer, UnavailableCause.MALFORMED);
        assertThat(((BureauAnswer.Unavailable) answer).evidence()).as("the bytes kept as evidence").isPresent();
    }

    @Test
    void anUnknownStatusIsUnavailableNeverData() {
        arm(Misbehaviour.UNKNOWN_STATUS);
        assertUnavailable(bureau().pull(request("R-5", 1)), UnavailableCause.UNKNOWN_STATUS);
    }

    @Test
    void aProviderErrorIsUnavailable() {
        arm(Misbehaviour.PROVIDER_ERROR);
        assertUnavailable(bureau().pull(request("R-6", 1)), UnavailableCause.PROVIDER_ERROR);
    }

    @Test
    void silenceIsATimeout() {
        arm(Misbehaviour.TIMEOUT);
        assertUnavailable(bureau().pull(request("R-7", 1)), UnavailableCause.TIMEOUT);
    }

    @Test
    void aBureauSlowerThanTheClientIsATimeout() {
        arm(Misbehaviour.SLOW_BEYOND_TIMEOUT);
        assertUnavailable(bureau().pull(request("R-8", 1)), UnavailableCause.TIMEOUT);
    }

    @Test
    void aDuplicateDeliveryAnswersTheFirstReportAndCostsNothing() {
        BureauAnswer first = bureau().pull(request("R-9", 1));
        BureauAnswer again = bureau().pull(request("R-9", 1));
        assertThat(again).isEqualTo(first);
        assertThat(countedPulls()).isEqualTo(1);
    }

    @Test
    void aLostResponseReAskedUnderTheSameReferenceAnswersTheFirstReportOnePull() {
        arm(Misbehaviour.RESPONSE_LOST);
        BureauAnswer lost = bureau().pull(request("R-10", 1));
        assertThat(lost).as("a lost response is not an answer").isInstanceOf(BureauAnswer.Unavailable.class);
        BureauAnswer reAsked = bureau().pull(request("R-10", 1));
        assertThat(reAsked).isInstanceOf(BureauAnswer.Received.class);
        assertThat(countedPulls()).as("the report produced before the loss is the one answered").isEqualTo(1);
    }

    @Test
    void tenCallersOneReferenceOnePull() throws Exception {
        int callers = 10;
        CyclicBarrier start = new CyclicBarrier(callers);
        ExecutorService instances = Executors.newFixedThreadPool(callers);
        try {
            List<Future<BureauAnswer>> answers = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                answers.add(instances.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return bureau().pull(request("R-11", 1));
                }));
            }
            Set<BureauAnswer> distinct = new HashSet<>();
            for (Future<BureauAnswer> answer : answers) {
                distinct.add(answer.get(30, TimeUnit.SECONDS));
            }
            assertThat(distinct).as("every caller reads the one report").hasSize(1)
                    .allSatisfy(answer -> assertThat(answer).isInstanceOf(BureauAnswer.Received.class));
        } finally {
            instances.shutdownNow();
        }
        assertThat(countedPulls()).as("ten callers under one reference cost one pull").isEqualTo(1);
    }

    @Test
    void theSamePersonReadsTheSameReportUnderAnyReference() {
        BureauAnswer.Received first = (BureauAnswer.Received) bureau().pull(request("R-12", 1));
        BureauAnswer.Received second = (BureauAnswer.Received) bureau().pull(request("R-13", 1));
        BureauAnswer.Received other = (BureauAnswer.Received) bureau().pull(request("R-14", 2));
        assertThat(values(second)).isEqualTo(values(first));
        assertThat(values(other)).as("another person, another report").isNotEqualTo(values(first));
        assertThat(countedPulls()).isEqualTo(3);
    }

    @Test
    void noAnswerRendersAValue() {
        arm(Misbehaviour.PARTIAL);
        for (BureauAnswer answer : List.of(bureau().pull(request("R-15", 1)), bureau().pull(request("R-16", 2)))) {
            String rendered = answer.toString();
            List<CreditAttribute> attributes = answer instanceof BureauAnswer.Received received
                    ? received.attributes()
                    : ((BureauAnswer.Partial) answer).attributes();
            for (CreditAttribute attribute : attributes) {
                assertThat(attribute.toString()).isEqualTo("CreditAttribute[" + attribute.code() + "]");
                assertThat(attribute.value().toString()).doesNotContainPattern("[0-9]");
                if (attribute.value() instanceof AttributeValue.MoneyValue money) {
                    assertThat(rendered).doesNotContain(money.value().toBigDecimal().toPlainString());
                }
            }
            String attributesPart = rendered.substring(rendered.indexOf("attributes="), rendered.indexOf("evidence="));
            assertThat(attributesPart.replaceAll("_(24|72)M", "")).as("no value inside the attributes' rendering")
                    .doesNotContainPattern("[0-9]");
            assertThat(rendered).doesNotContain("report_").contains("bytes]");
        }
    }

    // -----------------------------------------------------------------

    protected BureauRequest request(String reference, int person) {
        return new BureauRequest(reference, subject(person), CreditProduct.PERSONAL_LOAN);
    }

    private static void assertUnavailable(BureauAnswer answer, UnavailableCause cause) {
        assertThat(answer).as("never data").isInstanceOf(BureauAnswer.Unavailable.class);
        assertThat(((BureauAnswer.Unavailable) answer).cause()).isEqualTo(cause);
    }

    private static Set<CreditAttributeCode> codes(List<CreditAttribute> attributes) {
        return attributes.stream().map(CreditAttribute::code).collect(Collectors.toSet());
    }

    private static Map<CreditAttributeCode, CreditAttribute> byCode(List<CreditAttribute> attributes) {
        return attributes.stream().collect(Collectors.toMap(CreditAttribute::code, Function.identity()));
    }

    private static Map<CreditAttributeCode, AttributeValue> values(BureauAnswer.Received received) {
        return received.attributes().stream().collect(Collectors.toMap(CreditAttribute::code, CreditAttribute::value));
    }
}
