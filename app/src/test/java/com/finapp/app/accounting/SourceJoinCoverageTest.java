package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.PostingRule;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.SourceJoinCatalogue;
import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.SourceDoor;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SourceNamespace;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The census's reach, held from the register in code (`P15-TSK-035`; the entry's "no namespace is skipped silently" and its probe:
 * one namespace's source join dropped must be red here). Every {@code (door, namespace)} {@link PostingRuleRegister#CURRENT} registers has
 * a join; a join answers for a registered namespace only; each statement names its record and stays inside its own schema.
 */
@Tag("architecture")
@DisplayName("every registered posting namespace has a source join (P15-TSK-035)")
class SourceJoinCoverageTest {

    private static final List<JournalSources> SOURCES = SourceCensusComposition.sources();
    private static final SourceJoinCatalogue CATALOGUE = new SourceJoinCatalogue(SOURCES);

    @Test
    @DisplayName("every namespace the rule register holds has a join - read from the register, a namespace without one is red")
    void everyRegisteredNamespaceHasASourceJoin() {
        Set<SourceNamespace> registered = registered();
        assertThat(registered).as("not vacuous: the register's posting namespaces").hasSizeGreaterThan(50);
        Set<SourceNamespace> joined = CATALOGUE.joins().stream().map(SourceJoin::namespace).collect(Collectors.toSet());
        assertThat(joined).as("a namespace registered with no source join is skipped silently").containsAll(registered);
    }

    @Test
    @DisplayName("every join answers for a registered namespace: a join for a namespace nobody writes is a stale read of nothing")
    void everyJoinAnswersForARegisteredNamespace() {
        Set<SourceNamespace> registered = registered();
        assertThat(CATALOGUE.joins()).allSatisfy(join -> assertThat(registered).contains(join.namespace()));
    }

    @Test
    @DisplayName("each statement names its table and what it compares; every statement stays inside its own module's schema")
    void eachJoinStatesItselfAndStaysHome() {
        Pattern table = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_]+)\\.");
        for (JournalSources module : SOURCES) {
            assertThat(module.joins()).as("%s has something to answer for", module.module()).isNotEmpty();
            for (SourceJoin join : module.joins()) {
                assertThat(join.statement()).as("%s states which record and what is compared", join.namespace()).isNotBlank().contains(module.module() + ".");
                Set<String> schemas = new TreeSet<>();
                Matcher reference = table.matcher(join.sql());
                while (reference.find()) {
                    schemas.add(reference.group(1));
                }
                assertThat(schemas).as("%s: %s reads another module's tables", module.module(), join.namespace()).containsOnly(module.module());
            }
        }
    }

    @Test
    @DisplayName("the namespaces compared by presence alone are this named set, and only this file says so in code")
    void presenceOnlyIsTheNamedSet() throws IOException {
        Set<SourceNamespace> presenceOnly = CATALOGUE.joins().stream()
                .filter(join -> join.statement().contains("presence only"))
                .map(SourceJoin::namespace)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(presenceOnly).as("a presence-only join is a decision, named here, not a default")
                .containsExactly(new SourceNamespace(SourceDoor.ADJUSTMENT, "ACCOUNTING"));
        // Text: SourceFigures.presenceOnly is not reachable without a row; but a port saying "presence" in code and not in its statement is what this checks.
        Set<String> filesUsingIt = new TreeSet<>();
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith("JournalSources.java") && path.toString().contains("src" + java.io.File.separator + "main"))
                    .filter(path -> read(path).contains("SourceFigures.presenceOnly("))
                    .forEach(path -> filesUsingIt.add(path.getFileName().toString()));
        }
        assertThat(filesUsingIt).containsExactly("AccountingJournalSources.java");
    }

    /** The register's own namespaces: (door, namespace), distinct, read from code. */
    private static Set<SourceNamespace> registered() {
        Map<String, SourceNamespace> named = new TreeMap<>();
        for (PostingRule rule : PostingRuleRegister.CURRENT.rules()) {
            SourceNamespace namespace = new SourceNamespace(SourceDoor.valueOf(rule.type().name()), rule.namespace());
            named.put(namespace.door() + " " + namespace.namespace(), namespace);
        }
        return Set.copyOf(named.values());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(Objects.requireNonNull(failure));
        }
    }
}
