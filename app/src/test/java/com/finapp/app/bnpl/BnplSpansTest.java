package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplSpans;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.RepositoryPaths;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BNPL's spans (`P12-TSK-026`, {@code PHASE_12_PLAN.md} section 15; lending's {@code LendingSpansTest}): every step the
 * plan names is a {@link BnplSpans} constant recorded in main code - derived from the plan, so a step added there without a
 * span fails here; every span is recorded with NO attribute; a sweep leg runs in the correlation its row stored; a door on
 * an existing row records its span in the row's correlation caused by the request's, while the act itself stays in the
 * request's own scope - so nothing the act writes changes; and every lookup is a fixed statement binding one identifier.
 */
@DisplayName("BNPL's spans are recorded, by name, with no attribute, linked by the flow's stored correlation"
        + " (P12-TSK-026)")
class BnplSpansTest {

    private static final com.finapp.sharedkernel.id.IdGenerator IDS =
            new com.finapp.sharedkernel.id.IdGenerator(java.time.Clock.systemUTC(), new java.security.SecureRandom());

    private static final Pattern SPAN_LIST = Pattern.compile(
            "spans through the platform\\s+`Spans`\\s+port with no attribute at all\\s*\\(([^)]*)\\)");
    private static final Pattern NAME = Pattern.compile("`([a-z]+(?:\\.[a-z]+)+)`");

    @Test
    @DisplayName("every span the plan names is a BnplSpans constant, and every constant is recorded in main code")
    void everyPlannedSpanIsRecorded() throws Exception {
        Matcher list = SPAN_LIST.matcher(RepositoryPaths.read("docs/project/PHASE_12_PLAN.md"));
        assertThat(list.find()).as("the plan's span list must parse").isTrue();
        Set<String> planned = new TreeSet<>();
        Matcher name = NAME.matcher(list.group(1));
        while (name.find()) {
            planned.add(name.group(1));
        }
        assertThat(planned).as("one span per step the plan names").hasSize(17);

        Map<String, String> constants = new TreeMap<>();
        for (Field field : BnplSpans.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        assertThat(constants.values()).containsExactlyInAnyOrderElementsOf(planned)
                .allSatisfy(span -> assertThat(span).startsWith("bnpl."));

        String sources = sources(RepositoryPaths.locate("app/build.gradle.kts").getParent().resolve("src/main/java"));
        assertThat(constants.keySet()).allSatisfy(constant ->
                assertThat(sources).as("BnplSpans.%s is recorded somewhere in main code", constant)
                        .contains("BnplSpans." + constant));
    }

    @Test
    @DisplayName("a sweep leg runs in its row's stored correlation, the span with no attribute; none stored or unusable,"
            + " it runs unscoped - never failed; the scope is closed after the leg")
    void aSweepLegCarriesTheStoredCorrelation() {
        Recording spans = new Recording();
        Correlation inside = BnplFlowScope.leg(spans, BnplSpans.SERVICE, Optional.of("acceptance-correlation-1"),
                () -> CorrelationContext.current().orElseThrow());
        assertThat(inside.correlationId().value()).isEqualTo("acceptance-correlation-1");
        assertThat(spans.names).containsExactly(BnplSpans.SERVICE);
        assertThat(spans.seen.get(0)).map(seen -> seen.correlationId().value()).contains("acceptance-correlation-1");
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
        assertThat(CorrelationContext.current()).isEmpty();

        assertThat(BnplFlowScope.leg(spans, BnplSpans.OFFER, Optional.empty(), CorrelationContext::current)).isEmpty();
        assertThat(BnplFlowScope.leg(spans, BnplSpans.EXPIRE, Optional.of(" "), () -> "ran"))
                .as("a stored value the correlation type refuses: the leg still runs").isEqualTo("ran");
        assertThat(BnplFlowScope.NONE.forObligation(BnplSpans.SERVICE, BnplObligationId.next(IDS),
                CorrelationContext::current)).as("no lookup: unscoped").isEmpty();
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
    }

    @Test
    @DisplayName("a door on an existing row records its span in the row's correlation caused by the request's, and runs"
            + " the act in the request's own scope - nothing the act writes changes; unlinked when nothing is stored")
    @SuppressWarnings("try")
    void aDoorIsLinkedButTheActStaysInTheRequestsScope() {
        Recording spans = new Recording();
        Correlation request = Correlation.startingWith(CorrelationId.of("request-correlation-1"));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(request)) {
            Correlation acting = BnplFlowScope.linked(spans, BnplSpans.REFUND, Optional.of("acceptance-correlation-2"),
                    () -> CorrelationContext.current().orElseThrow());
            assertThat(acting).as("the act runs in the request's own scope").isEqualTo(request);
            Correlation spanned = spans.seen.get(0).orElseThrow();
            assertThat(spanned.correlationId().value()).isEqualTo("acceptance-correlation-2");
            assertThat(spanned.cause()).map(cause -> cause.value()).contains("request-correlation-1");
            assertThat(CorrelationContext.current()).contains(request);

            Correlation unlinked = BnplFlowScope.linked(spans, BnplSpans.REPAY, Optional.empty(),
                    () -> CorrelationContext.current().orElseThrow());
            assertThat(unlinked).isEqualTo(request);
            assertThat(spans.seen.get(1)).contains(request);
        }
        assertThat(BnplFlowScope.linked(spans, BnplSpans.CORRECT, Optional.of("flow-3"), () -> "no request"))
                .as("no request scope: unlinked, still run").isEqualTo("no request");
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
        assertThat(BnplFlowScope.NONE.doorOnAgreement(BnplSpans.PAYOFF, "not-a-uuid", () -> "ran")).isEqualTo("ran");
        assertThat(BnplFlowScope.NONE.doorOnDispute(BnplSpans.DISPUTE_RESOLVE, null, () -> "ran")).isEqualTo("ran");
        assertThat(BnplFlowScope.uuid("not-a-uuid")).isEmpty();
    }

    @Test
    @DisplayName("every flow lookup is a fixed statement reading one stored correlation by one bound identifier - an"
            + " agreement and a dispute in their obligation's flow")
    void everyLookupIsAFixedStatement() {
        for (BnplFlowScope.Flow flow : BnplFlowScope.Flow.values()) {
            assertThat(flow.select).as(flow.name()).startsWithIgnoringCase("SELECT ")
                    .contains("correlation_id FROM bnpl.").endsWith(" = ?").doesNotContain("%s");
            assertThat(flow.select.chars().filter(character -> character == '?').count()).as(flow.name()).isEqualTo(1);
        }
        assertThat(BnplFlowScope.Flow.AGREEMENT.select).contains("bnpl.bnpl_obligation WHERE agreement_id = ?");
        assertThat(BnplFlowScope.Flow.DISPUTE.select).contains("JOIN bnpl.bnpl_obligation o");
    }

    /** Records each span's name, attributes and the correlation in scope when it started, running the work. */
    private static final class Recording implements Spans {
        final List<String> names = new ArrayList<>();
        final List<Map<String, String>> attributes = new ArrayList<>();
        final List<Optional<Correlation>> seen = new ArrayList<>();

        @Override
        public <T> T within(String name, Map<String, String> identifiers, Supplier<T> work) {
            names.add(name);
            attributes.add(identifiers);
            seen.add(CorrelationContext.current());
            return work.get();
        }
    }

    private static String sources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<String> texts = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                texts.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            return String.join("\n", texts);
        }
    }
}
