package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ApiVersion;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code API_CONVENTIONS.md} and the implementation are one definition.
 *
 * <p>The acceptance criterion for {@code P0-DOC-003} is "document matches implemented behaviour",
 * and that is exactly the kind of claim that is true on the day it is written and quietly false a
 * month later. A conventions document that has drifted is worse than none: a client author reads
 * "the limit is 1 MiB", writes code against it, and finds out otherwise in production.
 *
 * <p>So every claim in that document with a value behind it is pinned here. This is the same
 * mechanism {@code ErrorCodeRegistryTest} and {@code ArchitectureRulesAreDocumentedTest} use, for
 * the same reason: a document nothing checks is a document nothing maintains.
 *
 * <h2>What this cannot check</h2>
 *
 * <p>Prose. The argument for cursor pagination, the reason a rejected correlation header is
 * replaced rather than sanitised, whether the deprecation window should be six months — none of
 * that has a value to compare against, and pretending otherwise would produce a guard that looks
 * stronger than it is. What it does cover is every number, name and identifier a client would
 * write code against.
 */
class ApiConventionsAreAccurateTest {

    private static final String DOCUMENT = "docs/architecture/API_CONVENTIONS.md";
    private static final String ADR = "docs/adr/ADR-0015-api-versioning-and-contract-publication.md";

    @Test
    @DisplayName("the version prefix in the document is the one the application applies")
    void theVersionPrefixMatches() {
        assertThat(conventions())
                .as("the document must state the prefix the composition root actually applies")
                .contains("`" + ApiVersion.CURRENT_PREFIX + "`")
                .contains("`" + ApiVersionConfiguration.OUR_HANDLERS + "`");
    }

    @Test
    @DisplayName("the correlation header, its charset and its bound are the implemented ones")
    void theCorrelationRulesMatch() {
        String document = conventions();

        assertThat(document)
                .as("the header name a client reads")
                .contains("**`" + CorrelationFilter.HEADER + "`**");
        assertThat(document)
                .as("the length bound comes from CorrelationId, not from memory")
                .contains("maximum length " + CorrelationId.MAX_LENGTH);

        // The charset is the security-relevant half: it is what stops a correlation identifier
        // becoming a log-injection vector. A document that understated it would invite a client to
        // send something the platform rejects; overstating it is worse.
        for (String allowed : new String[] {"A-Z", "a-z", "0-9", ". _ : @ / + = -"}) {
            assertThat(document).as("allowed characters: %s", allowed).contains(allowed);
        }
        for (String forbidden : new String[] {"\n", "\r", " ", "%", "<", ">"}) {
            assertThat(CorrelationId.of("ok-value")).isNotNull();
            assertThat(catchIllegal(() -> CorrelationId.of("bad" + forbidden + "value")))
                    .as("the document promises %s is rejected", forbidden.strip().isEmpty() ? "whitespace" : forbidden)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the request-size limit and its property name are the implemented ones")
    void theRequestSizeLimitMatches() {
        // Read from the annotation on the constructor parameter rather than restated, so the
        // default and the property name cannot drift from the filter that enforces them.
        String declared = RequestSizeLimitFilter.class.getDeclaredConstructors()[0]
                .getParameters()[1]
                .getAnnotation(org.springframework.beans.factory.annotation.Value.class)
                .value();

        Matcher matcher = Pattern.compile("\\$\\{([^:]+):(\\d+)}").matcher(declared);
        assertThat(matcher.matches()).as("expected ${property:default} but was %s", declared).isTrue();

        assertThat(conventions())
                .as("the property a deployment sets")
                .contains("`" + matcher.group(1) + "`")
                .as("the default a client must respect")
                .contains("**" + matcher.group(2) + "** bytes");
    }

    @Test
    @DisplayName("the problem-detail members listed are the members actually published")
    void theProblemDetailMembersMatch() {
        // Split across two rows in the document - always-present and conditional - so the
        // assertion is that every wire member appears somewhere, not that the table is shaped a
        // particular way. Adding a member to the wire format without documenting it fails here.
        String document = conventions();

        List<String> members =
                Arrays.stream(ProblemDetailBody.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertThat(members).as("the wire format must not be empty").isNotEmpty();
        for (String member : members) {
            assertThat(document).as("member `%s` is published and must be documented", member)
                    .contains("`" + member + "`");
        }
        assertThat(document)
                .as("the media type a client matches on")
                .contains("`" + OpenApiDocument.PROBLEM_JSON + "`");
    }

    @Test
    @DisplayName("every section declares whether it is implemented or merely decided")
    void everySectionIsLabelled() {
        // The Definition of Done for a documentation task forbids "aspirational statements
        // presented as current fact". Two of this document's sections - idempotency and
        // pagination - describe behaviour that does not exist, and a reader must never be able to
        // mistake them for the ones that do. Making the label mandatory is what keeps that true
        // when somebody adds a ninth section in a hurry.
        List<String> unlabelled =
                conventions()
                        .lines()
                        .filter(line -> line.startsWith("## "))
                        .filter(line -> !line.startsWith("## 0. "))
                        .filter(line -> !line.contains("**Implemented**"))
                        .filter(line -> !line.contains("**Decided, not yet implemented**"))
                        .filter(line -> !line.contains("not decided"))
                        .toList();

        assertThat(unlabelled)
                .as("each section must say whether it describes today or a decision awaiting its first endpoint")
                .isEmpty();
    }

    @Test
    @DisplayName("every error code the document names is a code that actually exists")
    void everyNamedCodeIsDeclared() {
        // The document names api.PayloadTooLarge, api.ValidationFailed, api.MalformedRequest and
        // api.Conflict as the answers to specific situations. ErrorCodeRegistryTest reconciles the
        // CATALOGUE with the taxonomy, but nothing reconciled these mentions - so renaming a code
        // would leave this document telling a client to handle something that can never arrive,
        // which is the same failure as documenting one that was never added.
        Set<String> declared =
                DeclaredErrorCodes.all().stream().map(ErrorCode::code).collect(Collectors.toSet());
        assertThat(declared).as("the taxonomy must not be empty").isNotEmpty();

        Matcher matcher =
                Pattern.compile("`([a-z][a-z0-9]*\\.[A-Z][A-Za-z0-9]*)`").matcher(conventions());
        Set<String> named = new TreeSet<>();
        while (matcher.find()) {
            named.add(matcher.group(1));
        }

        assertThat(named).as("the document names codes, so this check is not vacuous").isNotEmpty();
        assertThat(named)
                .as("every code named in the conventions document must be one the platform can raise")
                .isSubsetOf(declared);
    }

    @Test
    @DisplayName("the documented correlation charset is the whole of the implemented one")
    void theCorrelationCharsetIsComplete() {
        // The substring check above catches a character being dropped from the document. This
        // catches the other direction, which is the one that rots quietly: widening the pattern in
        // CorrelationTokens without updating the document leaves a client believing a value it may
        // legitimately send will be rejected. Derived from the implementation rather than restated,
        // so it cannot go stale.
        String charsetLine =
                conventions()
                        .lines()
                        .filter(line -> line.contains("allowed characters are"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the document must state the allowed characters"));

        Set<String> required = new TreeSet<>();
        for (char candidate = 0x21; candidate < 0x7F; candidate++) {
            if (!accepts(candidate)) {
                continue;
            }
            if (candidate >= 'A' && candidate <= 'Z') {
                required.add("A-Z");
            } else if (candidate >= 'a' && candidate <= 'z') {
                required.add("a-z");
            } else if (candidate >= '0' && candidate <= '9') {
                required.add("0-9");
            } else {
                required.add(String.valueOf(candidate));
            }
        }

        assertThat(required).as("CorrelationId must accept something").isNotEmpty();
        for (String token : required) {
            assertThat(charsetLine)
                    .as("the implementation accepts %s, so the document must say so", token)
                    .contains(token);
        }
    }

    @Test
    @DisplayName("the deprecation windows agree with ADR-0015")
    void theDeprecationWindowsAgreeWithTheAdr() {
        // This document restates the deprecation policy for a client author who will never read an
        // ADR. That is a deliberate second copy - and a second copy of anything is exactly what
        // this class refuses to allow for error codes, so it does not get a free pass here either.
        assertThat(months(conventions()))
                .as("the windows a client is promised must be the windows ADR-0015 decided")
                .isEqualTo(months(RepositoryPaths.read(ADR)))
                .as("both documents must actually state windows")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: it can see the document and its sections")
    void theGuardSeesRealContent() {
        assertThat(conventions()).contains("# API Conventions");
        assertThat(conventions().lines().filter(line -> line.startsWith("## ")).count())
                .as("a document that lost its sections would pass every other assertion here")
                .isGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("the error-code catalogue is referenced, never copied")
    void theCatalogueIsNotDuplicated() {
        // ERROR_CONTRACT.md owns the codes and is reconciled with the taxonomy by
        // ErrorCodeRegistryTest. A second table here would be unguarded, would drift, and would
        // drift in the worst direction - looking authoritative while being wrong. Individual
        // codes may be named as examples; a table row may not.
        List<String> catalogueRows =
                conventions()
                        .lines()
                        .map(String::strip)
                        .filter(line -> line.startsWith("| `api."))
                        .toList();

        assertThat(catalogueRows)
                .as("the codes live in ERROR_CONTRACT.md; reference them, do not restate them")
                .isEmpty();
        assertThat(conventions()).contains("ERROR_CONTRACT.md");
    }

    // -----------------------------------------------------------------

    private static String conventions() {
        return RepositoryPaths.read(DOCUMENT);
    }

    /** The deprecation windows a document states, as a sorted set - wording-independent. */
    private static Set<String> months(String document) {
        Matcher matcher = Pattern.compile("\\*\\*(\\d+) months\\*\\*").matcher(document);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static boolean accepts(char candidate) {
        return !catchIllegal(() -> CorrelationId.of("a" + candidate + "b"));
    }

    private static boolean catchIllegal(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException rejected) {
            return true;
        }
    }
}
