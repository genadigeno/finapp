package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxProvider;
import com.finapp.payments.CorridorRail;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.RailId;
import com.finapp.platform.telemetry.Spans;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 9's spans (`P9-TSK-027`, PHASE_9_PLAN.md section 15's trace): every leg the plan names is a {@link Phase9Spans}
 * constant recorded in main code, and the decorators record each adapter leg under its name with no attribute but an
 * identifier - derived from the plan, so a leg added there without a span fails here.
 */
@DisplayName("Phase 9's spans are recorded, by name (P9-TSK-027)")
class Phase9SpansTest {

    private static final Pattern SPAN_LIST = Pattern.compile(
            "spans through the platform `Spans` port with identifier attributes only\\s*\\(([^)]*)\\)");
    private static final Pattern NAME = Pattern.compile("`([a-z]+(?:\\.[a-z]+)+)`");

    @Test
    @DisplayName("every span the plan names is a Phase9Spans constant, and every constant is recorded in main code")
    void everyPlannedSpanIsRecorded() throws Exception {
        String plan = String.join("\n", read(repositoryFile("docs/project/PHASE_9_PLAN.md")));
        Matcher list = SPAN_LIST.matcher(plan);
        assertThat(list.find()).as("the plan's span list must parse").isTrue();
        Set<String> planned = new TreeSet<>();
        Matcher name = NAME.matcher(list.group(1));
        while (name.find()) {
            planned.add(name.group(1));
        }
        assertThat(planned).as("eleven legs").hasSize(11);

        Map<String, String> constants = new java.util.TreeMap<>();
        for (Field field : Phase9Spans.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        assertThat(constants.values()).containsExactlyInAnyOrderElementsOf(planned);

        Path main = repositoryFile("app/src/main/java");
        String sources;
        try (Stream<Path> files = Files.walk(main)) {
            List<String> texts = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")
                    && !path.getFileName().toString().equals("Phase9Spans.java")).toList()) {
                texts.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            sources = String.join("\n", texts);
        }
        assertThat(constants.keySet()).allSatisfy(constant ->
                assertThat(sources).as("Phase9Spans.%s is recorded somewhere in main code", constant)
                        .contains("Phase9Spans." + constant));
    }

    @Test
    @DisplayName("the corridor rail's legs are recorded under their names; the beneficiary exchange is not")
    void theCorridorLegsAreSpanned() {
        Recording spans = new Recording();
        CorridorRail rail = new SpannedCorridorRail(new NullCorridorRail(), spans);
        rail.send(null);
        rail.inquire(new EndToEndReference("abcdefghijklmnop"));
        rail.recall(new EndToEndReference("abcdefghijklmnop"));
        rail.exchangeBeneficiary(null);
        assertThat(spans.names).containsExactly(
                Phase9Spans.OUTBOUND_DISPATCH, Phase9Spans.OUTBOUND_RESOLVE, Phase9Spans.OUTBOUND_RECALL);
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
    }

    @Test
    @DisplayName("the FX provider's legs are recorded under their names, the code passing through")
    void theFxProviderLegsAreSpanned() {
        Recording spans = new Recording();
        FxProvider provider = new SpannedFxProvider(new NullFxProvider(), spans);
        provider.firmQuote(null);
        provider.execute(null);
        provider.inquire("T-1");
        assertThat(provider.code()).isEqualTo("fx-sim-a");
        assertThat(spans.names).containsExactly(
                Phase9Spans.PROVIDER_QUOTE, Phase9Spans.COVER_DISPATCH, Phase9Spans.COVER_RESOLVE);
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

    private static final class NullCorridorRail implements CorridorRail {
        @Override
        public RailId id() {
            return RailId.of("corridor-sim-a");
        }

        @Override
        public BeneficiaryExchange exchangeBeneficiary(BeneficiaryGrant grant) {
            return null;
        }

        @Override
        public SendAnswer send(CreditInstruction instruction) {
            return null;
        }

        @Override
        public InquiryAnswer inquire(EndToEndReference reference) {
            return null;
        }

        @Override
        public RecallAnswer recall(EndToEndReference reference) {
            return null;
        }
    }

    private static final class NullFxProvider implements FxProvider {
        @Override
        public String code() {
            return "fx-sim-a";
        }

        @Override
        public FirmQuoteAnswer firmQuote(FirmQuoteRequest request) {
            return null;
        }

        @Override
        public ExecutionAnswer execute(ExecutionRequest request) {
            return null;
        }

        @Override
        public ExecutionAnswer inquire(String clientReference) {
            return null;
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

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
