package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No idempotency scope or request fingerprint names an actor's audit type (`P15-TSK-021`; {@code INV-IDEM-*}).
 *
 * <p>The session interceptor types a person per request: an operator act {@code EMPLOYEE}, the same identity on its own
 * account {@code CUSTOMER}, and every request before {@code P15-TSK-021} {@code CUSTOMER}. Eighty scopes were built as
 * {@code SCOPE + actor.type().name() + ":" + actor.id()}, so the same person's retry would have been keyed twice: a
 * request committed under {@code CUSTOMER:<id>} by an instance of the previous release, its response lost, retried onto
 * an instance of this one under {@code EMPLOYEE:<id>}, finds no record and acts again - a second refund, a second
 * write-off. They key by {@code Actor.keyedType()}, which names a person once, in the form every scope already persisted.
 *
 * <p>The rule reads every production source of every module: a statement that builds an {@code IdempotencyKey}, a
 * {@code RequestFingerprint}, or assigns a variable named for a scope, must not call {@code type()} - an actor's
 * {@code keyedType()} is the one way to name its population there.
 */
@Tag("architecture")
@DisplayName("no idempotency scope or fingerprint names an actor's audit type (P15-TSK-021)")
class IdempotencyScopesKeyThePersonTest {

    private static final Pattern KEYED = Pattern.compile(
            "new\\s+IdempotencyKey\\s*\\(|RequestFingerprint\\s*\\.\\s*sha256\\s*\\(|\\b\\w*[sS]cope\\w*\\s*=(?!=)");
    private static final Pattern AUDIT_TYPE = Pattern.compile("\\.\\s*type\\s*\\(\\s*\\)");

    @Test
    @DisplayName("every production statement building a scope or a fingerprint keys a person by keyedType()")
    void everyScopeKeysThePersonOnce() throws IOException {
        List<Path> sources = productionSources();
        assertThat(sources).as("the census: every module's production sources were read").hasSizeGreaterThan(2000);
        List<String> violations = new ArrayList<>();
        int keyed = 0;
        for (Path source : sources) {
            for (String statement : statements(EvidenceCitations.read(source))) {
                if (KEYED.matcher(statement).find()) {
                    keyed++;
                    if (AUDIT_TYPE.matcher(statement).find()) {
                        violations.add(source.getFileName() + ": " + statement.replaceAll("\\s+", " ").trim());
                    }
                }
            }
        }
        assertThat(keyed).as("the census: the scopes were found").isGreaterThan(150);
        assertThat(violations).as("a scope or fingerprint naming an actor's type(): use keyedType()").isEmpty();
    }

    @Test
    @DisplayName("the rule can fail: a scope, a fingerprint and a scope variable naming type() are caught; keyedType()"
            + " and an unkeyed type() are not")
    void theRuleCanFail() {
        assertThat(violations("IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + \":\" + actor.id(), k);"))
                .hasSize(1);
        assertThat(violations("RequestFingerprint f = RequestFingerprint.sha256((S + actor.type().name()).getBytes());"))
                .hasSize(1);
        assertThat(violations("String scope =\n PREFIX\n + requester.type().name().toLowerCase(Locale.ROOT)\n + id;"))
                .hasSize(1);
        assertThat(violations("IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + \":\" + actor.id(), k);"))
                .isEmpty();
        assertThat(violations("insert.setString(3, actor.type().name());")).isEmpty();
        assertThat(violations("// new IdempotencyKey(SCOPE + actor.type().name())\nint x = 1;")).isEmpty();
    }

    private static List<String> violations(String code) {
        return statements(code).stream().filter(statement -> KEYED.matcher(statement).find())
                .filter(statement -> AUDIT_TYPE.matcher(statement).find()).toList();
    }

    /** The source's statements, its comments removed. */
    private static List<String> statements(String source) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        return List.of(code.split("[;{}]"));
    }

    private static List<Path> productionSources() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(path -> path.toString().endsWith(".java")).sorted().forEach(sources::add);
                }
            }
        }
        return sources;
    }
}
