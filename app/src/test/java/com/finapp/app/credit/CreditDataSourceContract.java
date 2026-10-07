package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataAnswer.UnavailableCause;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditDataSource;
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
 * The credit data source contract (`P10-TSK-005`, made source-neutral by `P10-TSK-007`; ADR-0085 section 2,
 * {@code INV-CRD-10}, {@code INV-LIFE-03}, {@code INV-CRD-12}, {@code INV-CRD-07}): what every {@link CreditDataSource}
 * adapter must do, whatever its kind and its wire. A kind's own contract ({@link CreditBureauContract},
 * {@link FinancialDataProviderContract}) extends this, and every adapter's battery extends its kind's.
 *
 * <p>The decisive property: <strong>no answer of a faulty provider ever carries an attribute</strong> - a timeout,
 * silence, a malformed body, an unknown status and a provider error are each {@code Unavailable}, never data, and a
 * partial answer states its gaps as {@code Absent}.
 */
abstract class CreditDataSourceContract {

    /** The adapter under test, against a freshly started provider. */
    protected abstract CreditDataSource source();

    /** The money attribute the provider states in a foreign currency when armed to. */
    protected abstract CreditAttributeCode foreignCurrencyAttribute();

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
        CreditDataAnswer answer = source().pull(request("R-1", 1));
        assertThat(answer).isInstanceOf(CreditDataAnswer.Received.class);
        CreditDataAnswer.Received received = (CreditDataAnswer.Received) answer;
        assertThat(codes(received.attributes())).containsExactlyInAnyOrderElementsOf(source().attributes());
        assertThat(received.providerCode()).isEqualTo(source().code());
        assertThat(received.normaliserVersion()).isPositive();
        assertThat(received.evidence().bytes()).isNotEmpty();
        assertThat(received.attributes()).allSatisfy(attribute -> assertThat(attribute.provenance())
                .isEqualTo(new AttributeProvenance.Provider(
                        source().kind(), source().code(), received.normaliserVersion())));
        assertThat(received.attributes())
                .filteredOn(attribute -> attribute.code().valueType() == com.finapp.credit.AttributeValueType.MONEY)
                .allSatisfy(attribute -> assertThat(((AttributeValue.MoneyValue) attribute.value()).value().currency())
                        .isEqualTo(CreditProduct.PERSONAL_LOAN.currency()));
        assertThat(countedPulls()).isEqualTo(1);
    }

    @Test
    void aPartialReportStatesItsGapsAsAbsentAndDefaultsNothing() {
        arm(Misbehaviour.PARTIAL);
        CreditDataAnswer answer = source().pull(request("R-2", 1));
        assertThat(answer).isInstanceOf(CreditDataAnswer.Partial.class);
        CreditDataAnswer.Partial partial = (CreditDataAnswer.Partial) answer;
        assertThat(codes(partial.attributes())).containsAll(source().attributes());
        assertThat(partial.absentCodes()).isNotEmpty().isSubsetOf(source().attributes());
        assertThat(partial.attributes()).filteredOn(attribute -> partial.absentCodes().contains(attribute.code()))
                .allSatisfy(attribute -> assertThat(attribute.value()).isEqualTo(new AttributeValue.Absent()));
    }

    @Test
    void aForeignCurrencyBalanceIsAbsentNeverConverted() {
        arm(Misbehaviour.FOREIGN_CURRENCY);
        CreditDataAnswer answer = source().pull(request("R-3", 1));
        assertThat(answer).as("partial data, never a conversion (INV-CRD-12)").isInstanceOf(CreditDataAnswer.Partial.class);
        CreditDataAnswer.Partial partial = (CreditDataAnswer.Partial) answer;
        assertThat(partial.absentCodes()).contains(foreignCurrencyAttribute());
        assertThat(byCode(partial.attributes()).get(CreditAttributeCode.CURRENCY_NOT_SUPPORTED).value())
                .as("the marker names the source kind")
                .isEqualTo(new AttributeValue.CodeValue(source().kind().name()));
        assertThat(partial.attributes())
                .filteredOn(attribute -> attribute.value() instanceof AttributeValue.MoneyValue)
                .allSatisfy(attribute -> assertThat(((AttributeValue.MoneyValue) attribute.value()).value().currency())
                        .isEqualTo(CreditProduct.PERSONAL_LOAN.currency()));
    }

    @Test
    void aMalformedBodyIsUnavailableWithItsBytesKeptAndNoAttribute() {
        arm(Misbehaviour.MALFORMED);
        CreditDataAnswer answer = source().pull(request("R-4", 1));
        assertUnavailable(answer, UnavailableCause.MALFORMED);
        assertThat(((CreditDataAnswer.Unavailable) answer).evidence()).as("the bytes kept as evidence").isPresent();
    }

    @Test
    void anUnknownStatusIsUnavailableNeverData() {
        arm(Misbehaviour.UNKNOWN_STATUS);
        assertUnavailable(source().pull(request("R-5", 1)), UnavailableCause.UNKNOWN_STATUS);
    }

    @Test
    void aProviderErrorIsUnavailable() {
        arm(Misbehaviour.PROVIDER_ERROR);
        assertUnavailable(source().pull(request("R-6", 1)), UnavailableCause.PROVIDER_ERROR);
    }

    @Test
    void silenceIsATimeout() {
        arm(Misbehaviour.TIMEOUT);
        assertUnavailable(source().pull(request("R-7", 1)), UnavailableCause.TIMEOUT);
    }

    @Test
    void aBureauSlowerThanTheClientIsATimeout() {
        arm(Misbehaviour.SLOW_BEYOND_TIMEOUT);
        assertUnavailable(source().pull(request("R-8", 1)), UnavailableCause.TIMEOUT);
    }

    @Test
    void aDuplicateDeliveryAnswersTheFirstReportAndCostsNothing() {
        CreditDataAnswer first = source().pull(request("R-9", 1));
        CreditDataAnswer again = source().pull(request("R-9", 1));
        assertThat(again).isEqualTo(first);
        assertThat(countedPulls()).isEqualTo(1);
    }

    @Test
    void aLostResponseReAskedUnderTheSameReferenceAnswersTheFirstReportOnePull() {
        arm(Misbehaviour.RESPONSE_LOST);
        CreditDataAnswer lost = source().pull(request("R-10", 1));
        assertThat(lost).as("a lost response is not an answer").isInstanceOf(CreditDataAnswer.Unavailable.class);
        CreditDataAnswer reAsked = source().pull(request("R-10", 1));
        assertThat(reAsked).isInstanceOf(CreditDataAnswer.Received.class);
        assertThat(countedPulls()).as("the report produced before the loss is the one answered").isEqualTo(1);
    }

    @Test
    void tenCallersOneReferenceOnePull() throws Exception {
        int callers = 10;
        CyclicBarrier start = new CyclicBarrier(callers);
        ExecutorService instances = Executors.newFixedThreadPool(callers);
        try {
            List<Future<CreditDataAnswer>> answers = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                answers.add(instances.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return source().pull(request("R-11", 1));
                }));
            }
            Set<CreditDataAnswer> distinct = new HashSet<>();
            for (Future<CreditDataAnswer> answer : answers) {
                distinct.add(answer.get(30, TimeUnit.SECONDS));
            }
            assertThat(distinct).as("every caller reads the one report").hasSize(1)
                    .allSatisfy(answer -> assertThat(answer).isInstanceOf(CreditDataAnswer.Received.class));
        } finally {
            instances.shutdownNow();
        }
        assertThat(countedPulls()).as("ten callers under one reference cost one pull").isEqualTo(1);
    }

    @Test
    void theSamePersonReadsTheSameReportUnderAnyReference() {
        CreditDataAnswer.Received first = (CreditDataAnswer.Received) source().pull(request("R-12", 1));
        CreditDataAnswer.Received second = (CreditDataAnswer.Received) source().pull(request("R-13", 1));
        CreditDataAnswer.Received other = (CreditDataAnswer.Received) source().pull(request("R-14", 2));
        assertThat(values(second)).isEqualTo(values(first));
        assertThat(values(other)).as("another person, another report").isNotEqualTo(values(first));
        assertThat(countedPulls()).isEqualTo(3);
    }

    @Test
    void noAnswerRendersAValue() {
        arm(Misbehaviour.PARTIAL);
        for (CreditDataAnswer answer : List.of(source().pull(request("R-15", 1)), source().pull(request("R-16", 2)))) {
            String rendered = answer.toString();
            List<CreditAttribute> attributes = answer instanceof CreditDataAnswer.Received received
                    ? received.attributes()
                    : ((CreditDataAnswer.Partial) answer).attributes();
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

    protected CreditDataPull request(String reference, int person) {
        return new CreditDataPull(reference, subject(person), CreditProduct.PERSONAL_LOAN);
    }

    private static void assertUnavailable(CreditDataAnswer answer, UnavailableCause cause) {
        assertThat(answer).as("never data").isInstanceOf(CreditDataAnswer.Unavailable.class);
        assertThat(((CreditDataAnswer.Unavailable) answer).cause()).isEqualTo(cause);
    }

    private static Set<CreditAttributeCode> codes(List<CreditAttribute> attributes) {
        return attributes.stream().map(CreditAttribute::code).collect(Collectors.toSet());
    }

    private static Map<CreditAttributeCode, CreditAttribute> byCode(List<CreditAttribute> attributes) {
        return attributes.stream().collect(Collectors.toMap(CreditAttribute::code, Function.identity()));
    }

    private static Map<CreditAttributeCode, AttributeValue> values(CreditDataAnswer.Received received) {
        return received.attributes().stream().collect(Collectors.toMap(CreditAttribute::code, CreditAttribute::value));
    }
}
