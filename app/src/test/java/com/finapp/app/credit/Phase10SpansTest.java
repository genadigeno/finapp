package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.SpannedCreditDataSource;
import com.finapp.app.telemetry.SpannedDecider;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.CreditDataSource;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.CreditSpans;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionRequestId;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 10's spans (`P10-TSK-020`, PHASE_10_PLAN.md section 15): every leg the plan names is a {@link CreditSpans}
 * constant recorded in main code; the decorators record each leg under its name with NO attribute - never an amount,
 * score, attribute or party; and a later leg runs in the scope of the correlation its request stored, its own step's
 * correlation the cause - derived from the plan, so a leg added there without a span fails here.
 */
@DisplayName("Phase 10's spans are recorded, by name, linked by the request's correlation (P10-TSK-020)")
class Phase10SpansTest {

    private static final com.finapp.sharedkernel.id.IdGenerator IDS =
            new com.finapp.sharedkernel.id.IdGenerator(java.time.Clock.systemUTC(), new java.security.SecureRandom());

    private static final Pattern SPAN_LIST = Pattern.compile(
            "spans through the platform `Spans` port with no attribute at all\\s*\\(([^)]*)\\)");
    private static final Pattern NAME = Pattern.compile("`([a-z]+(?:\\.[a-z]+)+)`");

    @Test
    @DisplayName("every span the plan names is a CreditSpans constant, and every constant is recorded in main code")
    void everyPlannedSpanIsRecorded() throws Exception {
        String plan = Files.readString(repositoryFile("docs/project/PHASE_10_PLAN.md"), StandardCharsets.UTF_8);
        Matcher list = SPAN_LIST.matcher(plan);
        assertThat(list.find()).as("the plan's span list must parse").isTrue();
        Set<String> planned = new TreeSet<>();
        Matcher name = NAME.matcher(list.group(1));
        while (name.find()) {
            planned.add(name.group(1));
        }
        assertThat(planned).as("five legs: submission, collection, freeze, evaluation, decision").hasSize(5);

        Map<String, String> constants = new TreeMap<>();
        for (Field field : CreditSpans.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        assertThat(constants.values()).containsExactlyInAnyOrderElementsOf(planned);

        String sources = sources(repositoryFile("app/src/main/java")) + sources(repositoryFile("credit/src/main/java"));
        assertThat(constants.keySet()).allSatisfy(constant ->
                assertThat(sources).as("CreditSpans.%s is recorded somewhere in main code", constant)
                        .contains("CreditSpans." + constant));
    }

    @Test
    @DisplayName("a source's pull is recorded as credit.data.collect with no attribute; code, kind and answer pass through")
    void theCollectionLegIsSpanned() {
        Recording spans = new Recording();
        CreditDataAnswer unavailable =
                new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.TIMEOUT, Optional.empty());
        CreditDataSource source = new SpannedCreditDataSource(new com.finapp.credit.CreditBureau() {
            @Override
            public String code() {
                return UnconfiguredBureau.CODE;
            }

            @Override
            public CreditDataAnswer pull(CreditDataPull request) {
                return unavailable;
            }
        }, spans);
        assertThat(source.pull(new CreditDataPull("ref-1", UUID.randomUUID().toString(), CreditProduct.CREDIT_LINE)))
                .isSameAs(unavailable);
        assertThat(source.code()).isEqualTo(UnconfiguredBureau.CODE);
        assertThat(source.kind()).isEqualTo(CreditSourceKind.BUREAU);
        assertThat(spans.names).containsExactly(CreditSpans.COLLECT);
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
    }

    @Test
    @DisplayName("the platform's decision is recorded as credit.decision.decide with no attribute; what it did passes"
            + " through")
    void theDecisionLegIsSpanned() {
        Recording spans = new Recording();
        Decider decider = new SpannedDecider((id, correlation) -> Decider.Decided.REFERRED, spans);
        assertThat(decider.decide(DecisionRequestId.next(IDS), CorrelationId.of("step-1")))
                .isEqualTo(Decider.Decided.REFERRED);
        assertThat(spans.names).containsExactly(CreditSpans.DECIDE);
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
    }

    @Test
    @DisplayName("a later leg runs in the request's stored correlation, caused by its own step; none stored or unusable,"
            + " it runs unscoped - never failed")
    void aLaterLegCarriesTheRequestsCorrelation() {
        CorrelationId step = CorrelationId.of("step-correlation-1");
        Correlation inside = CreditFlowScope.within(Optional.of("submission-correlation-1"), step,
                () -> CorrelationContext.current().orElseThrow());
        assertThat(inside.correlationId().value()).isEqualTo("submission-correlation-1");
        assertThat(inside.cause()).map(cause -> cause.value()).contains("step-correlation-1");
        assertThat(CorrelationContext.current()).as("the scope is closed after the leg").isEmpty();

        assertThat(CreditFlowScope.within(Optional.empty(), step, CorrelationContext::current)).isEmpty();
        assertThat(CreditFlowScope.within(Optional.of(" "), step, () -> "ran"))
                .as("a stored value the correlation type refuses: the leg still runs").isEqualTo("ran");
        assertThat(CreditFlowScope.NONE.forRequest(DecisionRequestId.next(IDS), step,
                CorrelationContext::current)).as("no lookup: unscoped").isEmpty();
    }

    /** Records each span's name and attributes, running the work. */
    private static final class Recording implements Spans {
        final List<String> names = new ArrayList<>();
        final List<Map<String, String>> attributes = new ArrayList<>();

        @Override
        public <T> T within(String name, Map<String, String> identifiers, Supplier<T> work) {
            names.add(name);
            attributes.add(identifiers);
            return work.get();
        }
    }

    private static String sources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<String> texts = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")
                    && !path.getFileName().toString().equals("CreditSpans.java")).toList()) {
                texts.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            return String.join("\n", texts);
        }
    }

    private static Path repositoryFile(String relative) {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve(relative))) {
            here = here.getParent();
        }
        assertThat(here).as("could not locate %s", relative).isNotNull();
        return here.resolve(relative);
    }
}
