package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatoryReportingProvider.CallKind;
import com.finapp.accounting.RegulatoryReportingProvider.Outcome;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of a return (`P14-TSK-018`; {@code INV-ACC-13}, {@code INV-HIST-02}): a figure path's closed grammar and what it resolves to; the strict reader of canonical text; the closed vocabulary of an obligation's configuration; the validation's rules, per currency and never a zero; and what a provider's
 * answer is taken to say.
 */
@DisplayName("a return's pure parts (P14-TSK-018)")
class RegulatoryFormTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CanonicalJson.Obj CONTENT = CanonicalJson.object().put("definition", "X").put("secret", RegulatoryWorld.money("EUR", 1)).put("body", RegulatoryWorld.body(100_00, 90_00, 50_00, 50_00));

    // ------------------------------------------------------------------ figure paths

    @Test
    @DisplayName("a path resolves to one amount with its currency and scale; a wildcard to one per currency, sorted, its recorded path naming the currency")
    void aPathResolvesToAnAmount() {
        FigurePath.Figure one = FigurePath.of("body.equation[currency=EUR].assets").resolve(CONTENT).get(0);
        assertThat(one.minor()).isEqualTo(BigInteger.valueOf(100_00));
        assertThat(one.currency()).isEqualTo("EUR");
        assertThat(one.scale()).isEqualTo(2);
        assertThat(one.path()).isEqualTo("body.equation[currency=EUR].assets");

        List<FigurePath.Figure> each = FigurePath.of("body.equation[currency=*].liabilities").resolve(CONTENT);
        assertThat(each).extracting(FigurePath.Figure::currency).containsExactly("EUR", "USD");
        assertThat(each).extracting(FigurePath.Figure::path).containsExactly("body.equation[currency=EUR].liabilities", "body.equation[currency=USD].liabilities");
        assertThat(FigurePath.of("body.equation[currency=*].liabilities").perCurrency()).isTrue();
        assertThat(each.get(0).toString()).doesNotContain("9000");
    }

    @Test
    @DisplayName("refused, never a zero: nothing there, more than one, an array taken whole, a selector on what is not an array, no amount, a currency that is not the one selected")
    void aPathThatIsNotOneAmountIsRefused() {
        assertThat(why("body.equation[currency=CHF].assets")).isEqualTo(FigurePath.Why.NOTHING);
        assertThat(why("body.nothing.here")).isEqualTo(FigurePath.Why.NOTHING);
        assertThat(why("body.equation[currency=EUR].nothing")).isEqualTo(FigurePath.Why.NOTHING);
        assertThat(why("body.equation[currency=EUR].assets.minor")).isEqualTo(FigurePath.Why.NOT_AN_AMOUNT);
        assertThat(why("body.rows[glAccount=1000,currency=EUR].closing")).isEqualTo(FigurePath.Why.MORE_THAN_ONE);
        assertThat(why("body.rows[glAccount=1000,currency=*].closing")).isEqualTo(FigurePath.Why.MORE_THAN_ONE);
        assertThat(why("body.equation.assets")).isEqualTo(FigurePath.Why.MORE_THAN_ONE);
        CanonicalJson.Obj twoCurrencies = CanonicalJson.object().put("body", CanonicalJson.object().put("rows", CanonicalJson.array()
                .add(CanonicalJson.object().put("glAccount", "1000").put("currency", "EUR").put("closing", RegulatoryWorld.money("EUR", 1)))
                .add(CanonicalJson.object().put("glAccount", "1000").put("currency", "USD").put("closing", RegulatoryWorld.money("USD", 1)))));
        assertThatThrownBy(() -> FigurePath.of("body.rows[glAccount=1000].closing").resolve(twoCurrencies)).as("two rows found and no wildcard asked for: never two lines").isInstanceOf(FigurePath.Unresolved.class).hasMessageContaining("MORE_THAN_ONE");
        assertThat(why("body.equation[currency=EUR].assets[x=y].minor")).isEqualTo(FigurePath.Why.NOTHING);
        assertThat(why("body.equation[currency=EUR].currency")).isEqualTo(FigurePath.Why.NOT_AN_AMOUNT);
        CanonicalJson.Obj lying = CanonicalJson.object().put("body", CanonicalJson.object().put("rows", CanonicalJson.array()
                .add(CanonicalJson.object().put("currency", "EUR").put("closing", RegulatoryWorld.money("USD", 1)))));
        assertThatThrownBy(() -> FigurePath.of("body.rows[currency=EUR].closing").resolve(lying)).isInstanceOf(FigurePath.Unresolved.class).hasMessageContaining("NOT_AN_AMOUNT");
        assertThatThrownBy(() -> FigurePath.of("body.a.b").resolve(CanonicalJson.object())).isInstanceOf(FigurePath.Unresolved.class);
    }

    private static FigurePath.Why why(String path) {
        try {
            FigurePath.of(path).resolve(CONTENT);
        } catch (FigurePath.Unresolved unresolved) {
            assertThat(unresolved.getMessage()).doesNotContain("10000");
            return unresolved.why();
        }
        throw new AssertionError("expected a refusal: " + path);
    }

    @Test
    @DisplayName("the grammar is closed: the header is out of reach, the wildcard is a currency's, once, and nothing else parses")
    void theGrammarIsClosed() {
        for (String bad : List.of("", "secret.minor", "body", "body.", "body..x", "body.x[", "body.x[]", "body.x[a]", "body.x[a=b,a=c].y", "body.x[a=*].y", "body.x[currency=*].y[currency=*].z", "body.x[a=b]", "body.x; DROP TABLE t",
                "body.x[a='b'].y", "body.a.b.c.d.e.f.g.h.i", "body." + "x".repeat(300))) {
            assertThatThrownBy(() -> FigurePath.of(bad)).as(bad).isInstanceOf(FigurePath.Malformed.class);
        }
        assertThat(FigurePath.of("body.a[b=c,currency=*].d").perCurrency()).isTrue();
        assertThat(FigurePath.of("body.a.b").perCurrency()).isFalse();
    }

    // ------------------------------------------------------------------ the strict reader

    @Test
    @DisplayName("canonical text reads back as what wrote it, every escape of the form included")
    void canonicalTextRoundTrips() {
        CanonicalJson.Obj written = CanonicalJson.object()
                .put("escapes", "quote \" back \\ tab \t line \n return \r feed \f back \b control \u0001 and \u00e9 \ud83d\ude00")
                .put("flag", true).put("off", false).putNull("none").put("whole", 12)
                .put("nested", CanonicalJson.array().add(CanonicalJson.object().put("a", "b")).add("c").addNull().add(true));
        String text = CanonicalJson.text(written);

        assertThat(CanonicalJson.text(RunContentParser.object(text))).isEqualTo(text);
    }

    @Test
    @DisplayName("anything else is refused, never normalised: whitespace, a number, unsorted or repeated keys, an escape outside the form, trailing text; and the refusal names a position, never the text")
    void anythingElseIsRefused() {
        for (String bad : List.of("", " {}", "{} ", "[]", "\"a\"", "{\"a\":1}", "{\"a\": \"b\"}", "{\"b\":\"1\",\"a\":\"2\"}", "{\"a\":\"1\",\"a\":\"2\"}", "{\"a\":\"\\/\"}", "{\"a\":\"\\u00E9\"}", "{\"a\":\"\\q\"}", "{\"a\":\"x", "{\"a\":tru}", "{\"a\":\"\\u12\"}",
                "{\"a\":\"\\u0000\"}", "{\"a\":[\"b\",]}", "{\"a\":\"b\"}x")) {
            assertThatThrownBy(() -> RunContentParser.object(bad)).as(bad).isInstanceOf(RunContentParser.NotCanonical.class);
        }
        assertThatThrownBy(() -> RunContentParser.object("{\"secret-figure\":1}")).isInstanceOf(RunContentParser.NotCanonical.class).hasMessageNotContaining("secret-figure");
    }

    // ------------------------------------------------------------------ the configuration

    private static RegulatoryObligationConfiguration.Source source() {
        return new RegulatoryObligationConfiguration.Source("TRIAL_BALANCE", 1);
    }

    private static RegulatoryObligationConfiguration.Line line(String code) {
        return new RegulatoryObligationConfiguration.Line(code, "A line", "TRIAL_BALANCE", "body.a[currency=*].b");
    }

    private static RegulatoryObligationConfiguration.Rule rule(String code, RegulatoryRuleType type, List<String> left, List<String> right) {
        return new RegulatoryObligationConfiguration.Rule(code, type, left, right);
    }

    @Test
    @DisplayName("a configuration is data, round-tripping through its canonical text; incoherent ones are refused whole")
    void aConfigurationIsData() {
        RegulatoryObligationConfiguration configuration = RegulatoryWorld.configuration();
        assertThat(RegulatoryObligationConfiguration.parse(configuration.canonical())).isEqualTo(configuration);
        assertThat(configuration.source("TRIAL_BALANCE").version()).isEqualTo(1);

        List<Runnable> incoherent = List.of(
                () -> new RegulatoryObligationConfiguration(null, List.of(line("A")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(), List.of(line("A")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("A")), List.of()),
                () -> new RegulatoryObligationConfiguration(List.of(source(), source()), List.of(line("A")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("A"), line("A")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("lower")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("lower"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(new RegulatoryObligationConfiguration.Line("A", "A line", "OTHER", "body.a.b")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(new RegulatoryObligationConfiguration.Line("A", "A line", "TRIAL_BALANCE", "SELECT 1")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(new RegulatoryObligationConfiguration.Line("A", "<script>", "TRIAL_BALANCE", "body.a.b")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("A")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("B"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("A")), List.of(rule("R", RegulatoryRuleType.EQUALS, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("A")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of("A")))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("A")), List.of(rule("R", null, List.of("A"), List.of()))),
                () -> new RegulatoryObligationConfiguration(List.of(source()), List.of(line("A")), List.of(rule("R", RegulatoryRuleType.NON_NEGATIVE, List.of(), List.of())))
        );
        for (Runnable bad : incoherent) {
            assertThatThrownBy(bad::run).isInstanceOf(RegulatoryObligationConfiguration.Invalid.class);
        }
    }

    @Test
    @DisplayName("a stored configuration naming a rule type outside the vocabulary is refused: never SQL or script from a row")
    void aRuleOutsideTheVocabularyIsRefused() {
        String script = RegulatoryWorld.configuration().canonical().replace("\"type\":\"EQUALS\"", "\"type\":\"RUN_THIS_SQL\"");
        assertThatThrownBy(() -> RegulatoryObligationConfiguration.parse(script)).isInstanceOf(RegulatoryObligationConfiguration.Invalid.class);
        assertThatThrownBy(() -> RegulatoryObligationConfiguration.parse("{ }")).isInstanceOf(RegulatoryObligationConfiguration.Invalid.class);
    }

    // ------------------------------------------------------------------ the validation

    private static RegulatoryFormLine amount(String code, String currency, long minor) {
        return new RegulatoryFormLine(1, code, "A line", currency, BigInteger.valueOf(minor), 2, ReportRunId.next(IDS), "h", "TRIAL_BALANCE", "body.a.b");
    }

    private static RegulatoryValidation validated(RegulatoryRuleType type, List<String> left, List<String> right, RegulatoryFormLine... lines) {
        List<RegulatoryObligationConfiguration.Line> mapped = Arrays.stream(lines).map(RegulatoryFormLine::code).distinct().map(RegulatoryFormTest::line).toList();
        return RegulatoryValidation.of(new RegulatoryObligationConfiguration(List.of(source()), mapped, List.of(rule("R", type, left, right))), List.of(lines));
    }

    @Test
    @DisplayName("the rules judge exact amounts, per currency: equal, at least, and never negative; scales compare by value")
    void theRulesJudgeExactAmounts() {
        assertThat(validated(RegulatoryRuleType.EQUALS, List.of("A", "B"), List.of("C"), amount("A", "EUR", 1), amount("B", "EUR", 2), amount("C", "EUR", 3)).passed()).isTrue();
        assertThat(validated(RegulatoryRuleType.EQUALS, List.of("A"), List.of("C"), amount("A", "EUR", 1), amount("C", "EUR", 3)).passed()).isFalse();
        assertThat(validated(RegulatoryRuleType.NOT_LESS_THAN, List.of("A"), List.of("C"), amount("A", "EUR", 3), amount("C", "EUR", 3)).passed()).isTrue();
        assertThat(validated(RegulatoryRuleType.NOT_LESS_THAN, List.of("A"), List.of("C"), amount("A", "EUR", 2), amount("C", "EUR", 3)).passed()).isFalse();
        assertThat(validated(RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of(), amount("A", "EUR", 0)).passed()).isTrue();
        assertThat(validated(RegulatoryRuleType.NON_NEGATIVE, List.of("A"), List.of(), amount("A", "EUR", -1)).passed()).isFalse();
        assertThat(validated(RegulatoryRuleType.EQUALS, List.of("A"), List.of("C"), amount("A", "EUR", 900_719_925_474_099_300L), amount("C", "EUR", 900_719_925_474_099_200L)).passed()).as("exact, past what a double can tell apart").isFalse();
        RegulatoryFormLine sameValue = new RegulatoryFormLine(1, "C", "A line", "EUR", BigInteger.valueOf(100), 3, ReportRunId.next(IDS), "h", "TRIAL_BALANCE", "body.a.b");
        assertThat(validated(RegulatoryRuleType.EQUALS, List.of("A"), List.of("C"), amount("A", "EUR", 10), sameValue).passed()).isTrue();
    }

    @Test
    @DisplayName("never across currencies, and never a zero for what is not there: a line missing in a currency another holds is a finding that fails")
    void aMissingLineIsAFinding() {
        RegulatoryValidation acrossCurrencies = validated(RegulatoryRuleType.EQUALS, List.of("A"), List.of("B"), amount("A", "EUR", 5), amount("B", "USD", 5));
        assertThat(acrossCurrencies.passed()).isFalse();
        assertThat(acrossCurrencies.findings()).extracting(RegulatoryValidation.Finding::currency).containsExactly("EUR", "USD");
        assertThat(acrossCurrencies.findings().get(0).detail()).containsExactly("MISSING:B");

        RegulatoryValidation parsed = RegulatoryValidation.parse(acrossCurrencies.canonical());
        assertThat(parsed).isEqualTo(acrossCurrencies);
        assertThat(acrossCurrencies.canonical()).doesNotContain("500");
    }

    // ------------------------------------------------------------------ a provider's answer

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an answer that can be believed is read as it says, its bytes kept and hashed")
    void aBelievableAnswerIsReadAsItSays() {
        RegulatoryAnswerReading acknowledged = RegulatoryAnswerReading.of(CallKind.SUBMIT, Answer.acknowledged("REF-1", bytes("{\"ok\":true}")));
        assertThat(acknowledged.read()).isEqualTo(Outcome.ACKNOWLEDGED);
        assertThat(acknowledged.definite()).isTrue();
        assertThat(acknowledged.retained()).isEqualTo(bytes("{\"ok\":true}"));
        assertThat(acknowledged.rawSha256()).isEqualTo(CanonicalJson.sha256("{\"ok\":true}"));
        assertThat(acknowledged.receivedBytes()).isEqualTo(11);
        assertThat(acknowledged.truncated()).isFalse();
        assertThat(acknowledged.anomaly()).isEmpty();
        assertThat(RegulatoryAnswerReading.of(CallKind.INQUIRE, Answer.notReceived(bytes("no"))).read()).isEqualTo(Outcome.NOT_RECEIVED);
        assertThat(RegulatoryAnswerReading.of(CallKind.INQUIRE, Answer.rejected(Optional.empty(), "CODE_1", bytes("no"))).rejectionCode()).contains("CODE_1");
    }

    @Test
    @DisplayName("an oversized answer keeps its first bytes, its whole length and whole hash, and decides nothing, whatever it says")
    void anOversizedAnswerDecidesNothing() {
        byte[] huge = new byte[RegulatoryAnswerReading.MAX_RAW_BYTES + 10];
        Arrays.fill(huge, (byte) 'x');
        RegulatoryAnswerReading reading = RegulatoryAnswerReading.of(CallKind.SUBMIT, Answer.acknowledged("REF-1", huge));

        assertThat(reading.read()).isEqualTo(Outcome.UNKNOWN);
        assertThat(reading.stated()).isEqualTo(Outcome.ACKNOWLEDGED);
        assertThat(reading.anomaly()).contains(RegulatoryAnswerReading.Anomaly.OVERSIZED);
        assertThat(reading.providerReference()).isEmpty();
        assertThat(reading.truncated()).isTrue();
        assertThat(reading.retained()).hasSize(RegulatoryAnswerReading.MAX_RAW_BYTES);
        assertThat(reading.receivedBytes()).isEqualTo(huge.length);
        assertThat(reading.rawSha256()).isEqualTo(CanonicalJson.sha256(new String(huge, StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a malformed answer decides nothing: a reference or code of no allowed shape, an acknowledgement with no reference, a rejection with no code, a not received naming a reference")
    void aMalformedAnswerDecidesNothing() {
        List<Answer> malformed = List.of(
                new Answer(Outcome.ACKNOWLEDGED, Optional.empty(), Optional.empty(), bytes("a")),
                new Answer(Outcome.ACKNOWLEDGED, Optional.of("<script>alert(1)</script>"), Optional.empty(), bytes("b")),
                new Answer(Outcome.REJECTED, Optional.empty(), Optional.empty(), bytes("c")),
                new Answer(Outcome.REJECTED, Optional.empty(), Optional.of("drop table"), bytes("d")),
                new Answer(Outcome.NOT_RECEIVED, Optional.of("REF-1"), Optional.empty(), bytes("e")),
                new Answer(Outcome.UNKNOWN, Optional.empty(), Optional.of("CODE"), bytes("f")));
        for (Answer answer : malformed) {
            RegulatoryAnswerReading reading = RegulatoryAnswerReading.of(CallKind.INQUIRE, answer);
            assertThat(reading.read()).as(answer.toString()).isEqualTo(Outcome.UNKNOWN);
            assertThat(reading.anomaly()).contains(RegulatoryAnswerReading.Anomaly.MALFORMED);
            assertThat(reading.providerReference()).isEmpty();
            assertThat(reading.rejectionCode()).isEmpty();
            assertThat(reading.retained()).isEqualTo(answer.raw());
        }
    }

    @Test
    @DisplayName("a send answered not received contradicts itself: read unknown; only an inquiry can say nothing arrived")
    void aSendAnsweredNotReceivedIsContradictory() {
        RegulatoryAnswerReading sent = RegulatoryAnswerReading.of(CallKind.SUBMIT, Answer.notReceived(bytes("a")));
        assertThat(sent.read()).isEqualTo(Outcome.UNKNOWN);
        assertThat(sent.anomaly()).contains(RegulatoryAnswerReading.Anomaly.CONTRADICTORY);
        RegulatoryAnswerReading resent = RegulatoryAnswerReading.of(CallKind.RESEND, Answer.notReceived(bytes("a")));
        assertThat(resent.read()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("the bytes are copied in and out, and never printed")
    void theBytesAreNeverPrinted() {
        byte[] raw = bytes("PROVIDER-TEXT");
        Answer answer = Answer.acknowledged("REF-1", raw);
        raw[0] = 'X';
        assertThat(answer.raw()[0]).isEqualTo((byte) 'P');
        answer.raw()[0] = 'Y';
        assertThat(answer.raw()[0]).isEqualTo((byte) 'P');
        RegulatoryAnswerReading reading = RegulatoryAnswerReading.of(CallKind.SUBMIT, answer);
        assertThat(answer.toString()).doesNotContain("PROVIDER-TEXT").doesNotContain("REF-1");
        assertThat(reading.toString()).doesNotContain("PROVIDER-TEXT").doesNotContain("REF-1");
    }
}
