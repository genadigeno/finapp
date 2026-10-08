package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bureau-sim-b}'s normalisation against its golden files (`P10-TSK-021`): each payload under
 * {@code credit/golden/bureau-sim-b/} normalises to exactly its {@code .expected} rendering (the rendering of
 * {@link BureauNormalisationGoldenTest#render}), and the second bureau's clean, thin and foreign-currency files of a
 * person normalise to the very attributes {@code bureau-sim-a}'s reports of that person do - one meaning, two wires.
 */
@DisplayName("bureau-sim-b normalises its golden payloads exactly, and alike to bureau-sim-a (P10-TSK-021)")
class SecondBureauNormalisationGoldenTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("every golden payload normalises to its expected answer")
    void everyPayloadNormalisesToItsGoldenAnswer() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (Path payload : payloads("bureau-sim-b")) {
            String expected = Files.readString(expectedFor(payload), StandardCharsets.UTF_8);
            String actual = BureauNormalisationGoldenTest.render(
                    SimulatedSecondBureauAdapter.normalise(Files.readAllBytes(payload), EUR));
            if (!actual.equals(expected)) {
                mismatches.add(payload.getFileName() + ":\n  expected " + expected + "  actual   " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("the golden set is not vacuous: received, partial, foreign currency, malformed, unknown, and a field"
            + " present but unreadable in a thin file")
    void theGoldenSetCoversEveryBranch() throws Exception {
        List<String> firstLines = new ArrayList<>();
        for (Path payload : payloads("bureau-sim-b")) {
            firstLines.add(Files.readString(expectedFor(payload), StandardCharsets.UTF_8).lines().findFirst().orElse(""));
        }
        assertThat(firstLines).hasSizeGreaterThanOrEqualTo(10)
                .anyMatch(line -> line.startsWith("RECEIVED bureau-sim-b"))
                .anyMatch(line -> line.startsWith("PARTIAL bureau-sim-b"))
                .anyMatch(line -> line.equals("UNAVAILABLE MALFORMED evidence=kept"))
                .anyMatch(line -> line.equals("UNAVAILABLE UNKNOWN_STATUS evidence=kept"));
        assertThat(payloads("bureau-sim-b")).extracting(path -> path.getFileName().toString())
                .contains("malformed-thin-unquoted-field.json", "malformed-thin-broken-amount.json");
    }

    @Test
    @DisplayName("one person's file and report normalise to the same attributes - only the provider line differs")
    void theSamePersonNormalisesAlikeOnBothWires() throws Exception {
        for (String name : List.of("complete", "partial", "foreign-currency")) {
            String a = Files.readString(golden("bureau-sim-a").resolve(name + ".expected"), StandardCharsets.UTF_8);
            String b = Files.readString(golden("bureau-sim-b").resolve(name + ".expected"), StandardCharsets.UTF_8);
            assertThat(attributeLines(b)).as(name).isEqualTo(attributeLines(a)).isNotEmpty();
            String bActual = BureauNormalisationGoldenTest.render(SimulatedSecondBureauAdapter.normalise(
                    Files.readAllBytes(golden("bureau-sim-b").resolve(name + ".json")), EUR));
            String aActual = BureauNormalisationGoldenTest.render(SimulatedBureauAdapter.normalise(
                    Files.readAllBytes(golden("bureau-sim-a").resolve(name + ".json")), EUR));
            assertThat(attributeLines(bActual)).as(name + ", normalised now").isEqualTo(attributeLines(aActual));
        }
    }

    // -----------------------------------------------------------------

    private static List<String> attributeLines(String rendering) {
        return rendering.lines().skip(1).toList();
    }

    private static Path golden(String adapter) throws URISyntaxException {
        URL directory = SecondBureauNormalisationGoldenTest.class.getClassLoader().getResource("credit/golden/" + adapter);
        assertThat(directory).as("the golden directory is on the test classpath").isNotNull();
        return Path.of(directory.toURI());
    }

    private static List<Path> payloads(String adapter) throws IOException, URISyntaxException {
        try (Stream<Path> files = Files.list(golden(adapter))) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Path expectedFor(Path payload) {
        String name = payload.getFileName().toString();
        return payload.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".expected");
    }
}
