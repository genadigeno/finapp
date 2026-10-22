package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.MonitoredKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * The posting-key namespace register against the code that posts (`P13-TSK-019`; ADR-0111 decision 2;
 * {@code INV-RSK-09}): every posting-key namespace a module's production source declares - a {@code *_KEY_PREFIX},
 * {@code POSTING_PREFIX} or {@code *_KEY} constant, a {@code postingKey} local, or the literal at a
 * {@code new PostingCommand(} - is one {@link MonitoredKind} names, or is named here as a key the ledger records only
 * under a {@code REVERSAL} (classified by its type, never by its namespace); and every namespace the register names
 * is still declared somewhere, so the register carries no dead row. A new namespace anywhere in the platform fails
 * this build before it could ever be recorded {@code UNCLASSIFIED} in production.
 *
 * <p>A source scan, not a classpath rule: ArchUnit cannot read a constant's value, and the namespace is a value. The
 * scan is deliberately literal - it reads what a reviewer reads - and it is the guard the backlog asks for ("the
 * register's test fails on a namespace the ledger adds that the register does not know").
 */
@Tag("architecture")
@DisplayName("the posting-key namespace register knows every namespace the code posts (P13-TSK-019)")
class PostingKeyNamespaceRegisterTest {

    /** A constant or local that carries a posting key's prefix. */
    private static final Pattern DECLARED_PREFIX = Pattern.compile(
            "(?:KEY_PREFIX|POSTING_PREFIX|_KEY|\\bpostingKey)\\s*=\\s*\"([a-z][a-z0-9.-]*:)\"");

    /** The literal at a posting command's construction, on the line or the three that follow. */
    private static final Pattern INLINE_AT_POSTING = Pattern.compile("\"([a-z][a-z0-9.-]*:)\"\\s*\\+");

    /**
     * Keys with a posting key's shape that reach the ledger only as a {@code REVERSAL}'s or {@code ADJUSTMENT}'s
     * command key - classified by the entry's type, so the register names no kind for them - or that never reach the
     * ledger at all. Each one a decision, with the task that declared it.
     */
    private static final Set<String> NOT_A_POSTING_NAMESPACE = Set.of(
            // P12-TSK-024 / P11-TSK-024: a repayment reversal and a fee waiver are ledger REVERSALs of the original.
            "bnpl.repayment-reversal:", "bnpl.waiver:", "lending.repayment-reversal:", "lending.waiver:");

    @Test
    @DisplayName("every declared posting-key namespace is registered or named a non-posting key; every registered one is"
            + " declared")
    void theRegisterAndTheCodeAgree() {
        TreeMap<String, String> declared = declaredNamespaces();
        assertThat(declared).as("the scan must find the namespaces the platform posts under, or it guards nothing")
                .hasSizeGreaterThan(30);

        Set<String> registered = new TreeSet<>(MonitoredKind.registeredNamespaces());
        TreeMap<String, String> unknown = new TreeMap<>();
        declared.forEach((namespace, where) -> {
            if (!registered.contains(namespace) && !NOT_A_POSTING_NAMESPACE.contains(namespace)) {
                unknown.put(namespace, where);
            }
        });
        assertThat(unknown)
                .as("a posting-key namespace the register does not know would be recorded UNCLASSIFIED in production:"
                        + " add it to MonitoredKind with its kind, or to NOT_A_POSTING_NAMESPACE with the task that"
                        + " decided it never posts")
                .isEmpty();

        TreeSet<String> dead = new TreeSet<>(registered);
        dead.removeAll(declared.keySet());
        assertThat(dead)
                .as("a registered namespace no production source declares is a dead row that reads as coverage")
                .isEmpty();

        TreeSet<String> misfiled = new TreeSet<>(NOT_A_POSTING_NAMESPACE);
        misfiled.retainAll(registered);
        assertThat(misfiled).as("a key is a posting namespace or not; never both").isEmpty();
    }

    @Test
    @DisplayName("the scan reads what a reviewer reads: a constant, a local and an inline literal are each found")
    void theScanIsNotVacuous() {
        String source = """
                static final String POSTING_KEY_PREFIX = "sample.posting:";
                static final String FEE_KEY = "sample.fee:";
                String postingKey = "sample.local:" + id;
                PostingResult posted = postings.post(unitOfWork, new PostingCommand(
                        "sample.inline:" + attemptId.value(),
                        today, today, reference, lines));
                String unrelated = "not-a-key";
                """;
        TreeMap<String, String> found = new TreeMap<>();
        scan(source.lines().toList(), "sample", found);
        assertThat(found.keySet()).containsExactly("sample.fee:", "sample.inline:", "sample.local:", "sample.posting:");
    }

    // ------------------------------------------------------------------ the scan

    private static TreeMap<String, String> declaredNamespaces() {
        Path root = repositoryRoot();
        TreeMap<String, String> found = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path main = module.resolve("src").resolve("main").resolve("java");
                if (!Files.isDirectory(main) || module.getFileName().toString().equals("risk")) {
                    continue;
                }
                try (Stream<Path> sources = Files.walk(main)) {
                    for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                        scan(Files.readAllLines(source, StandardCharsets.UTF_8),
                                root.relativize(source).toString().replace('\\', '/'), found);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static void scan(List<String> lines, String where, TreeMap<String, String> found) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("//") || line.stripLeading().startsWith("*")) {
                continue;
            }
            Matcher declared = DECLARED_PREFIX.matcher(line);
            while (declared.find()) {
                found.putIfAbsent(declared.group(1), where + ":" + (i + 1));
            }
            if (line.contains("new PostingCommand(")) {
                for (int j = i; j < Math.min(i + 4, lines.size()); j++) {
                    Matcher inline = INLINE_AT_POSTING.matcher(lines.get(j));
                    if (inline.find()) {
                        found.putIfAbsent(inline.group(1), where + ":" + (j + 1));
                        break;
                    }
                }
            }
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("the repository root (settings.gradle.kts) is not above the working directory");
        }
        return current;
    }
}
