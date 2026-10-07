package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditAttribute;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code findata-sim-a}'s normalisation against its golden files (`P10-TSK-007`, the bureau's shape): each payload
 * under {@code credit/golden/findata-sim-a/} normalises to exactly its {@code .expected} rendering - the
 * attributes sorted by code, every value in a fixed textual form - or to the unavailable answer it
 * must be. A change to the mapping is a change to these files, reviewed with a new
 * {@code NORMALISER_VERSION}; a malformed payload's surviving fields never become attributes.
 */
@DisplayName("findata-sim-a normalises its golden payloads exactly (P10-TSK-007)")
class FinancialDataNormalisationGoldenTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("every golden payload normalises to its expected answer")
    void everyPayloadNormalisesToItsGoldenAnswer() throws Exception {
        List<String> mismatches = new ArrayList<>();
        List<Path> payloads = payloads();
        for (Path payload : payloads) {
            String expected = Files.readString(expectedFor(payload), StandardCharsets.UTF_8);
            String actual = render(SimulatedFinancialDataAdapter.normalise(Files.readAllBytes(payload), EUR));
            if (!actual.equals(expected)) {
                mismatches.add(payload.getFileName() + ":\n  expected " + expected + "  actual   " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("the golden set is not vacuous: it covers received, partial, foreign currency, malformed and unknown")
    void theGoldenSetCoversEveryBranch() throws Exception {
        List<String> firstLines = new ArrayList<>();
        for (Path payload : payloads()) {
            firstLines.add(Files.readString(expectedFor(payload), StandardCharsets.UTF_8).lines().findFirst().orElse(""));
        }
        assertThat(firstLines).hasSizeGreaterThanOrEqualTo(8)
                .anyMatch(line -> line.startsWith("RECEIVED"))
                .anyMatch(line -> line.startsWith("PARTIAL"))
                .anyMatch(line -> line.equals("UNAVAILABLE MALFORMED evidence=kept"))
                .anyMatch(line -> line.equals("UNAVAILABLE UNKNOWN_STATUS evidence=kept"));
    }

    // -----------------------------------------------------------------

    /** The canonical rendering: the answer's kind, then the attributes sorted by code. */
    static String render(CreditDataAnswer answer) {
        StringBuilder text = new StringBuilder();
        List<CreditAttribute> attributes;
        switch (answer) {
            case CreditDataAnswer.Received received -> {
                text.append("RECEIVED ").append(received.providerCode()).append(" normaliser=")
                        .append(received.normaliserVersion()).append(" retrievedAt=").append(received.retrievedAt());
                attributes = received.attributes();
            }
            case CreditDataAnswer.Partial partial -> {
                text.append("PARTIAL ").append(partial.providerCode()).append(" normaliser=")
                        .append(partial.normaliserVersion()).append(" retrievedAt=").append(partial.retrievedAt());
                attributes = partial.attributes();
            }
            case CreditDataAnswer.Unavailable unavailable -> {
                return "UNAVAILABLE " + unavailable.cause() + " evidence="
                        + (unavailable.evidence().isPresent() ? "kept" : "none") + "\n";
            }
        }
        text.append('\n');
        attributes.stream().sorted(Comparator.comparing(attribute -> attribute.code().name()))
                .forEach(attribute -> text.append(attribute.code()).append('=').append(value(attribute.value())).append('\n'));
        return text.toString();
    }

    private static String value(AttributeValue value) {
        return switch (value) {
            case AttributeValue.IntegerValue integer -> Long.toString(integer.value());
            case AttributeValue.MoneyValue money ->
                    money.value().toBigDecimal().toPlainString() + " " + money.value().currency().code();
            case AttributeValue.BooleanValue bool -> Boolean.toString(bool.value());
            case AttributeValue.CodeValue code -> code.value();
            case AttributeValue.Absent absent -> "ABSENT";
        };
    }

    private static List<Path> payloads() throws IOException, URISyntaxException {
        URL directory = FinancialDataNormalisationGoldenTest.class.getClassLoader().getResource("credit/golden/findata-sim-a");
        assertThat(directory).as("the golden directory is on the test classpath").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Path expectedFor(Path payload) {
        String name = payload.getFileName().toString();
        return payload.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".expected");
    }
}
