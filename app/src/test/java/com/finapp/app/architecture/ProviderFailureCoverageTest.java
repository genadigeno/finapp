package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.provider.SimulatedProvider;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The provider harness covers every failure mode the platform says it must (`P0-TSK-037`).
 *
 * <p>The acceptance criterion is <em>"the harness can reproduce every failure mode listed in
 * {@code CLAUDE.md} §Failure Engineering that involves a provider"</em>. That is a checkable claim,
 * and this phase has repeatedly found such claims false when checked — so it is checked, in three
 * links that have to hold at once:
 *
 * <ol>
 *   <li>every bullet in the document is classified, as a provider concern or explicitly not one;
 *   <li>every provider concern names a {@link SimulatedProvider} method that <strong>exists</strong>;
 *   <li>every such method is <strong>actually called</strong> by the suite that proves the harness.
 * </ol>
 *
 * <p>Each link closes a different way of being wrong. Without the first, a bullet added to
 * {@code CLAUDE.md} is silently uncovered. Without the second, a renamed method leaves a
 * classification pointing at nothing. Without the third, the harness could claim a mode that no
 * test ever exercises — the exact shape of "covered on paper" this criterion was written against.
 *
 * <p>The classification itself is the one part that cannot be derived: whether "an event is late or
 * missing" is a broker concern or a provider one is a judgement. It is therefore written down with
 * its reason, and the build fails if the document and the judgement stop lining up.
 */
@Tag("architecture")
@DisplayName("Provider failure-mode coverage (P0-TSK-037)")
class ProviderFailureCoverageTest {

    private static final String FAILURE_ENGINEERING_DOCUMENT = "CLAUDE.md";
    private static final String ADR_0008 = "docs/adr/ADR-0008-provider-adapters.md";
    private static final String PROOF_SUITE = "com.finapp.platform.provider.SimulatedProviderTest";

    /**
     * A {@code CLAUDE.md} §Failure Engineering bullet that involves a provider, and the harness
     * method that reproduces it.
     */
    private enum ProviderConcern {
        REQUEST_TIMES_OUT("the request times out", "neverResponds"),
        CLIENT_RETRIES("the client retries", "failsThenSucceeds"),
        RESPONSE_LOST(
                "the database commits but the response is lost",
                "receivesTheRequestThenLosesTheResponse"),
        PROVIDER_UNAVAILABLE("a provider is unavailable", "isUnavailable"),
        UNKNOWN_STATE("a provider returns an unknown state", "returnsUnknownState"),
        WEBHOOK_DUPLICATED("a webhook is duplicated", "deliverCallback"),
        SETTLEMENT_LATE("settlement arrives late", "deliverCallbackAfter");

        private final String bullet;
        private final String harnessMethod;

        ProviderConcern(String bullet, String harnessMethod) {
            this.bullet = bullet;
            this.harnessMethod = harnessMethod;
        }
    }

    /**
     * Bullets that are deliberately <strong>not</strong> the harness's job, each with the reason
     * and where the platform does cover it.
     *
     * <p>Written down rather than omitted, because a mode that is simply absent from a coverage
     * list is indistinguishable from one nobody thought about.
     */
    private static final Map<String, String> NOT_A_PROVIDER_CONCERN =
            Map.of(
                    "the service crashes",
                            "our own process, not a provider's — P0-TST-005 kills a relay mid-publication",
                    "an event is duplicated",
                            "broker delivery — P0-TST-006 covers it at the inbox (INV-IDEM-04)",
                    "an event is late or missing",
                            "broker delivery and ordering — P0-TST-006; the provider's equivalent is"
                                    + " SETTLEMENT_LATE, which is covered",
                    "two requests race",
                            "concurrent callers of our own API — P0-TST-004 drives it at the"
                                    + " idempotency kernel",
                    "reconciliation detects a break",
                            "an outcome of comparing records, not a behaviour a provider exhibits —"
                                    + " Phase 8 owns it");

    /** The six modes ADR-0008 requires every adapter to be contract-tested against. */
    private static final Map<String, String> ADR_REQUIRED_MODES =
            Map.of(
                    "timeout", "neverResponds",
                    "5xx", "failsWith",
                    "malformed response", "respondsWithMalformedBody",
                    "delayed response", "respondsAfter",
                    "duplicate callback", "deliverCallback",
                    "unknown state", "returnsUnknownState");

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every failure mode in CLAUDE.md is classified, one way or the other")
    void everyFailureModeIsClassified() {
        Set<String> inTheDocument = failureEngineeringBullets();

        Set<String> classified = new TreeSet<>(NOT_A_PROVIDER_CONCERN.keySet());
        Stream.of(ProviderConcern.values()).forEach(concern -> classified.add(concern.bullet));

        assertThat(classified)
                .as(
                        """
                        %s section Failure Engineering and this test must name the same conditions.

                        A bullet added there and not here is a failure mode nobody decided about, \
                        and the decision is the point: either the harness reproduces it, or there \
                        is a recorded reason why it is not a provider's behaviour.""",
                        FAILURE_ENGINEERING_DOCUMENT)
                .isEqualTo(inTheDocument);
    }

    @Test
    @DisplayName("the document is actually read")
    void theDocumentIsActuallyRead() {
        // Without this, a renamed heading or a changed bullet format would empty both sets and the
        // comparison above would pass over nothing at all — the vacuity that P0-TST-007 found in a
        // privilege check and the P0-TSK-033 review found in a register parser. Named bullets
        // rather than a count, because a count is satisfied by parsing the wrong list.
        assertThat(failureEngineeringBullets())
                .as("the section must be found, and bounded — see the over-reading note below")
                .hasSizeBetween(8, 20)
                .contains(
                        "a provider is unavailable",
                        "a provider returns an unknown state",
                        "a webhook is duplicated")
                .doesNotContain("auditability", "observability");
    }

    @Test
    @DisplayName("every provider concern names a harness method that exists")
    void everyProviderConcernNamesAnExistingMethod() {
        Set<String> onTheHarness = harnessMethodNames();

        Map<String, String> missing = new TreeMap<>();
        for (ProviderConcern concern : ProviderConcern.values()) {
            if (!onTheHarness.contains(concern.harnessMethod)) {
                missing.put(concern.name(), concern.harnessMethod + " is not on SimulatedProvider");
            }
        }

        assertThat(missing)
                .as("a classification pointing at a method that does not exist covers nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("every mode ADR-0008 requires is on the harness")
    void everyModeAdr0008RequiresIsOnTheHarness() {
        // ADR-0008 is the other authority this task rests on, and it names three modes CLAUDE.md
        // does not: 5xx, malformed response, delayed response. Read from the ADR rather than
        // restated, so the two cannot drift.
        // Bounded to the contract-test SENTENCE, not the whole ADR. Searching the document was
        // the first version and it was weak in a way the mutation did not reveal: "timeout"
        // appears three times in ADR-0008 and "unknown state" twice, so both checks were satisfied
        // by unrelated sentences - "Every adapter defines explicit timeouts" would have kept the
        // check green after the contract-test requirement was deleted. Third time in this task
        // that matching a whole document instead of bounding the region was the defect.
        //
        // Whitespace-normalised, because the ADR wraps mid-phrase ("malformed\n  response") and a
        // literal match reported that it had stopped requiring it.
        String adr = contractTestSentence();
        Set<String> onTheHarness = harnessMethodNames();

        Map<String, String> problems = new TreeMap<>();
        ADR_REQUIRED_MODES.forEach(
                (mode, method) -> {
                    if (!adr.contains(mode)) {
                        problems.put(mode, "ADR-0008 no longer names this mode");
                    } else if (!onTheHarness.contains(method)) {
                        problems.put(mode, method + " is not on SimulatedProvider");
                    }
                });

        assertThat(problems)
                .as("ADR-0008 requires every adapter to be contract-tested against these")
                .isEmpty();
    }

    @Test
    @DisplayName("every harness method a concern names is actually exercised by the proof suite")
    void everyNamedMethodIsExercised() {
        // The third link, and the one that makes the criterion real rather than declarative. A
        // harness method nothing calls is a capability nobody has demonstrated - which is exactly
        // the "covered on paper" failure DEFINITION_OF_DONE section 3 forbids.
        Set<String> called = harnessMethodsCalledByTheProofSuite();

        Set<String> claimed = new TreeSet<>();
        Stream.of(ProviderConcern.values()).forEach(c -> claimed.add(c.harnessMethod));
        claimed.addAll(ADR_REQUIRED_MODES.values());

        assertThat(called)
                .as(
                        "%s must call every harness method the coverage claims. Called: %s",
                        PROOF_SUITE, called)
                .containsAll(claimed);
    }

    @Test
    @DisplayName("the proof suite is found, and its calls are actually seen")
    void theProofSuiteIsActuallyAnalysed() {
        // The vacuity guard for the test above: containsAll over an empty `called` set fails
        // loudly, but containsAll over a set built from the WRONG class would fail confusingly.
        // Assert the sweep found the suite and saw a distinctive call.
        assertThat(harnessMethodsCalledByTheProofSuite())
                .as("the proof suite must be on disk and readable, or the check above means nothing")
                .contains("baseUrl", "requestCount");
    }

    // ------------------------------------------------------------------

    /**
     * The bullet list under `## Failure Engineering`, exactly as the document has it.
     *
     * <p>Walked line by line with an explicit stop at the next heading, rather than matched with
     * one regex. The first version was a regex, and it read straight past the section into
     * `## Definition of Done`, returning "auditability" and "observability" as failure modes —
     * because `DOTALL` lets `.` match newlines, so a single `- .*` swallows the rest of the file.
     * The structural version cannot over-read, which is better than a guard against over-reading.
     */
    private static Set<String> failureEngineeringBullets() {
        Set<String> bullets = new TreeSet<>();
        boolean inSection = false;

        for (String line : readRepositoryFile(FAILURE_ENGINEERING_DOCUMENT).split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("## ")) {
                if (inSection) {
                    break;
                }
                inSection = trimmed.equals("## Failure Engineering");
            } else if (inSection && trimmed.startsWith("- ")) {
                bullets.add(trimmed.substring(2).strip());
            }
        }
        return bullets;
    }

    /**
     * ADR-0008's one sentence requiring contract tests, whitespace-normalised.
     *
     * @throws IllegalStateException if the sentence is gone — which is itself the finding, and
     *     must fail loudly rather than leave every mode check comparing against an empty string
     */
    private static String contractTestSentence() {
        String adr = readRepositoryFile(ADR_0008).replaceAll("\\s+", " ");
        String lead = "contract-tested against simulated failure:";
        int start = adr.indexOf(lead);
        if (start < 0) {
            throw new IllegalStateException(
                    ADR_0008 + " no longer requires adapters to be contract-tested against"
                            + " simulated failure. That is a decision, not a typo — this test and"
                            + " the harness both exist because of that sentence.");
        }
        int end = adr.indexOf('.', start);
        return adr.substring(start + lead.length(), end < 0 ? adr.length() : end);
    }

    private static Set<String> harnessMethodNames() {
        Set<String> names = new TreeSet<>();
        for (Method method : SimulatedProvider.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Method names invoked on {@link SimulatedProvider} by the proof suite and its nested classes.
     *
     * <p>Read from `platform`'s compiled test classes on disk, because a module's test output is
     * deliberately not on another module's classpath. `app`'s test tasks declare those directories
     * as an input and depend on their compilation — see {@code app/build.gradle.kts}, and the
     * {@code P0-TSK-036} finding that discovered why that declaration is load-bearing.
     */
    private static Set<String> harnessMethodsCalledByTheProofSuite() {
        Path testClasses = repositoryRoot().resolve("platform/build/classes/java/test");
        if (!Files.isDirectory(testClasses)) {
            return Set.of();
        }

        Set<String> called = new LinkedHashSet<>();
        List<JavaClass> suite = new ArrayList<>();
        new ClassFileImporter()
                .importPath(testClasses)
                .forEach(
                        javaClass -> {
                            if (javaClass.getName().startsWith(PROOF_SUITE)) {
                                suite.add(javaClass);
                            }
                        });

        for (JavaClass javaClass : suite) {
            javaClass.getMethodCallsFromSelf().stream()
                    .filter(
                            call ->
                                    call.getTargetOwner()
                                            .getName()
                                            .equals(SimulatedProvider.class.getName()))
                    .forEach(call -> called.add(call.getName()));
        }
        return called;
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    private static String readRepositoryFile(String relativePath) {
        Path path = repositoryRoot().resolve(relativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
