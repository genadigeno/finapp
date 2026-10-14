package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.lending.LendingSpans;
import com.finapp.lending.LoanId;
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
 * Lending's spans (`P11-TSK-030`, PHASE_11_PLAN.md section 15): every step the plan names is a {@link LendingSpans}
 * constant recorded in main code - derived from the plan, so a step added there without a span fails here; every span is
 * recorded with NO attribute; a sweep leg runs in the correlation its row stored; and a door on an existing row records its
 * span in the row's correlation caused by the request's, while the act itself stays in the request's own scope - so
 * nothing the act writes changes.
 */
@DisplayName("lending's spans are recorded, by name, with no attribute, linked by the flow's stored correlation"
        + " (P11-TSK-030)")
class LendingSpansTest {

    private static final Pattern SPAN_LIST = Pattern.compile(
            "spans through the platform `Spans` port with no attribute at all\\s*\\(([^)]*)\\)");
    private static final Pattern NAME = Pattern.compile("`([a-z]+(?:\\.[a-z]+)+)`");

    @Test
    @DisplayName("every span the plan names is a LendingSpans constant, and every constant is recorded in main code")
    void everyPlannedSpanIsRecorded() throws Exception {
        String plan = Files.readString(repositoryFile("docs/project/PHASE_11_PLAN.md"), StandardCharsets.UTF_8);
        Matcher list = SPAN_LIST.matcher(plan);
        assertThat(list.find()).as("the plan's span list must parse").isTrue();
        Set<String> planned = new TreeSet<>();
        Matcher name = NAME.matcher(list.group(1));
        while (name.find()) {
            planned.add(name.group(1));
        }
        assertThat(planned).as("one span per step the plan names").hasSize(17);

        Map<String, String> constants = new TreeMap<>();
        for (Field field : LendingSpans.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        assertThat(constants.values()).containsExactlyInAnyOrderElementsOf(planned);

        String sources = sources(repositoryFile("app/src/main/java"));
        assertThat(constants.keySet()).allSatisfy(constant ->
                assertThat(sources).as("LendingSpans.%s is recorded somewhere in main code", constant)
                        .contains("LendingSpans." + constant));
    }

    @Test
    @DisplayName("a sweep leg runs in its row's stored correlation, the span with no attribute; none stored or unusable,"
            + " it runs unscoped - never failed; the scope is closed after the leg")
    void aSweepLegCarriesTheStoredCorrelation() {
        Recording spans = new Recording();
        Correlation inside = LendingFlowScope.leg(spans, LendingSpans.SERVICE, Optional.of("acceptance-correlation-1"),
                () -> CorrelationContext.current().orElseThrow());
        assertThat(inside.correlationId().value()).isEqualTo("acceptance-correlation-1");
        assertThat(spans.names).containsExactly(LendingSpans.SERVICE);
        assertThat(spans.seen.get(0)).map(seen -> seen.correlationId().value()).contains("acceptance-correlation-1");
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
        assertThat(CorrelationContext.current()).isEmpty();

        assertThat(LendingFlowScope.leg(spans, LendingSpans.DISBURSE, Optional.empty(), CorrelationContext::current))
                .isEmpty();
        assertThat(LendingFlowScope.leg(spans, LendingSpans.DISBURSE, Optional.of(" "), () -> "ran"))
                .as("a stored value the correlation type refuses: the leg still runs").isEqualTo("ran");
        assertThat(LendingFlowScope.NONE.forLoan(LendingSpans.PAYOUT, LoanId.of(UUID.randomUUID()),
                CorrelationContext::current)).as("no lookup: unscoped").isEmpty();
    }

    @Test
    @DisplayName("a door on an existing row records its span in the row's correlation caused by the request's, and runs"
            + " the act in the request's own scope - nothing the act writes changes; unlinked when nothing is stored")
    @SuppressWarnings("try")
    void aDoorIsLinkedButTheActStaysInTheRequestsScope() {
        Recording spans = new Recording();
        Correlation request = Correlation.startingWith(CorrelationId.of("request-correlation-1"));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(request)) {
            Correlation acting = LendingFlowScope.linked(spans, LendingSpans.REPAY,
                    Optional.of("acceptance-correlation-2"), () -> CorrelationContext.current().orElseThrow());
            assertThat(acting).as("the act runs in the request's own scope").isEqualTo(request);
            Correlation spanned = spans.seen.get(0).orElseThrow();
            assertThat(spanned.correlationId().value()).isEqualTo("acceptance-correlation-2");
            assertThat(spanned.cause()).map(cause -> cause.value()).contains("request-correlation-1");
            assertThat(CorrelationContext.current()).contains(request);

            Correlation unlinked = LendingFlowScope.linked(spans, LendingSpans.DRAW, Optional.empty(),
                    () -> CorrelationContext.current().orElseThrow());
            assertThat(unlinked).isEqualTo(request);
            assertThat(spans.seen.get(1)).contains(request);
        }
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
        assertThat(LendingFlowScope.NONE.doorOnLoan(LendingSpans.PAYOFF, "not-a-uuid", () -> "ran")).isEqualTo("ran");
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

    private static Path repositoryFile(String relative) {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve(relative))) {
            here = here.getParent();
        }
        assertThat(here).as("could not locate %s", relative).isNotNull();
        return here.resolve(relative);
    }
}
