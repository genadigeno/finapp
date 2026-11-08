package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
 * write-off. A fingerprint that names the type is the same defect answered differently: the retry finds its record and
 * is refused as a different request. They key by {@code Actor.keyedType()}, which names a person once, in the form
 * every scope already persisted.
 *
 * <p>The rule reads every production source of every module, its comments and literals removed. A <strong>keyed
 * statement</strong> builds an {@code IdempotencyKey} or a {@code RequestFingerprint}, or assigns a variable named for a
 * scope, a canonical form or a fingerprint. It must not call {@code type()}; nor may a method of the same file that a
 * keyed statement calls, or a method named for a scope, a canonical form, a fingerprint or a principal, when it is handed
 * a person (an {@code Actor} parameter, or the session's actor read inside it) - a rule's canonical form naming the
 * rule's type is not a person's. The second
 * and third clauses are the review's (the fix round, 2026-11-08): four fingerprints built in a helper -
 * {@code ProjectionRebuilds.principal}, {@code MerchantOnboarding}'s, {@code PayoutDestinations}' and
 * {@code MerchantPayouts}' {@code canonicalForm} - named the type, and the statement-only rule could not see them.
 */
@Tag("architecture")
@DisplayName("no idempotency scope or fingerprint names an actor's audit type (P15-TSK-021)")
class IdempotencyScopesKeyThePersonTest {

    private static final Pattern KEYED = Pattern.compile(
            "new\\s+IdempotencyKey\\s*\\(|RequestFingerprint\\s*\\.\\s*sha256\\s*\\("
                    + "|\\b\\w*(?:[sS]cope|[cC]anonical|[fF]ingerprint)\\w*\\s*=(?!=)");
    private static final Pattern AUDIT_TYPE = Pattern.compile("\\.\\s*type\\s*\\(\\s*\\)");
    /** A method whose name says it builds a key's subject. */
    private static final Pattern HELPER_NAME =
            Pattern.compile("(?i)\\w*(?:scope|canonical|fingerprint|principal)\\w*");
    private static final Pattern CALL = Pattern.compile("\\b([a-z]\\w*)\\s*\\(");
    private static final Pattern METHOD = Pattern.compile(
            "(?:(?:public|protected|private|static|final|synchronized|default)\\s+)*[\\w<>\\[\\]?,.]+\\s+(\\w+)\\s*"
                    + "\\(([^;{}()]*)\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{");
    /** A helper handed a person: an {@code Actor} parameter, or the session's actor read inside it. */
    private static final Pattern HANDED_AN_ACTOR = Pattern.compile("\\bActor\\b|\\bSecurityContext\\b");

    @Test
    @DisplayName("every production statement building a scope or a fingerprint, and every helper it calls, keys a"
            + " person by keyedType()")
    void everyScopeKeysThePersonOnce() throws IOException {
        List<Path> sources = productionSources();
        assertThat(sources).as("the census: every module's production sources were read").hasSizeGreaterThan(2000);
        List<String> violations = new ArrayList<>();
        int[] keyed = {0};
        int[] helpers = {0};
        for (Path source : sources) {
            for (String violation : violations(EvidenceCitations.read(source), keyed, helpers)) {
                violations.add(source.getFileName() + ": " + violation);
            }
        }
        assertThat(keyed[0]).as("the census: the scopes were found").isGreaterThan(150);
        assertThat(helpers[0]).as("the census: the helpers a keyed statement calls were read").isGreaterThan(20);
        assertThat(violations).as("a scope or fingerprint naming an actor's type(): use keyedType()").isEmpty();
    }

    @Test
    @DisplayName("the rule can fail: a scope, a fingerprint and a scope variable naming type() are caught, and so is a"
            + " helper building one; keyedType() and an unkeyed type() are not")
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
        assertThat(violations("String s = \"new IdempotencyKey(SCOPE + actor.type().name())\";")).isEmpty();

        // The review's shapes: the fingerprint's subject built by a helper the keyed statement calls ...
        assertThat(violations("""
                Recorded keyed(Actor actor, Command command) {
                    RequestFingerprint f = RequestFingerprint.sha256(form(command, actor));
                    return store.keyed(f);
                }
                private static byte[] form(Command command, Actor actor) {
                    return String.join("|", SCOPE, actor.type().name(), actor.id()).getBytes(UTF_8);
                }
                """)).singleElement().asString().contains("form");
        // ... and by a helper named for what it builds, called from a canonical form's assignment.
        assertThat(violations("""
                Recorded propose(Actor actor, Account account) {
                    String canonical = PROPOSE_SCOPE + "|propose|" + principal(actor) + "|" + account.value();
                    return keyed(canonical);
                }
                private static String principal(Actor actor) {
                    return actor.type().name() + ":" + actor.id();
                }
                """)).isNotEmpty().allSatisfy(violation -> assertThat(violation).contains("principal"));
        assertThat(violations("""
                Recorded propose(Actor actor, Account account) {
                    String canonical = PROPOSE_SCOPE + "|propose|" + principal(actor) + "|" + account.value();
                    return keyed(canonical);
                }
                private static String principal(Actor actor) {
                    return actor.keyedType().name() + ":" + actor.id();
                }
                """)).isEmpty();
        // A canonical form of something that is not a person - a rule's type - is not this rule's business.
        assertThat(violations("""
                private static String canonical(Rule rule) {
                    return rule.code() + "|" + rule.type().name();
                }
                """)).isEmpty();
        // A helper no keyed statement calls, named for nothing it builds, is not this rule's business.
        assertThat(violations("""
                void record(Actor actor) {
                    insert.setString(3, kind(actor));
                }
                private static String kind(Actor actor) {
                    return actor.type().name();
                }
                """)).isEmpty();
    }

    private static List<String> violations(String code) {
        return violations(code, new int[1], new int[1]);
    }

    private static List<String> violations(String source, int[] keyed, int[] helpers) {
        String code = codeOnly(source);
        Map<String, String> bodies = methodBodies(code);
        Set<String> found = new LinkedHashSet<>();
        Set<String> calledHelpers = new LinkedHashSet<>();
        for (String statement : statements(code)) {
            if (!KEYED.matcher(statement).find()) {
                continue;
            }
            keyed[0]++;
            if (AUDIT_TYPE.matcher(statement).find()) {
                found.add(statement.replaceAll("\\s+", " ").trim());
            }
            Matcher call = CALL.matcher(statement);
            while (call.find()) {
                if (bodies.containsKey(call.group(1))) {
                    calledHelpers.add(call.group(1));
                }
            }
        }
        for (Map.Entry<String, String> method : bodies.entrySet()) {
            boolean helper = calledHelpers.contains(method.getKey()) || HELPER_NAME.matcher(method.getKey()).matches();
            if (!helper) {
                continue;
            }
            helpers[0]++;
            if (AUDIT_TYPE.matcher(method.getValue()).find()) {
                found.add("the helper " + method.getKey() + "(...) builds a key's subject from type()");
            }
        }
        return List.copyOf(found);
    }

    /** Each method's body by its name (overloads joined), found by brace matching on the code alone. */
    private static Map<String, String> methodBodies(String code) {
        Map<String, String> bodies = new LinkedHashMap<>();
        Matcher method = METHOD.matcher(code);
        while (method.find()) {
            String name = method.group(1);
            if (Set.of("if", "for", "while", "switch", "catch", "synchronized", "return", "new").contains(name)) {
                continue;
            }
            int open = method.end() - 1;
            int depth = 0;
            int end = open;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    end = i;
                    break;
                }
            }
            String body = code.substring(open + 1, Math.max(open + 1, end));
            if (!HANDED_AN_ACTOR.matcher(method.group(2) + "\n" + body).find()) {
                // A helper given no person - a rule's canonical form, a line's - names no actor's type.
                body = "";
            }
            bodies.merge(name, body, (a, b) -> a + "\n" + b);
        }
        return bodies;
    }

    /** The source's statements, read from its code alone. */
    private static List<String> statements(String code) {
        return List.of(code.split("[;{}]"));
    }

    /**
     * The source with its comments removed and every string, text block and character literal emptied: a literal's
     * braces must not unbalance a method's body, and a comment or a message quoting a scope is not one.
     */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int close = source.indexOf("\"\"\"", i + 3);
                while (close > 0 && source.charAt(close - 1) == '\\') {
                    close = source.indexOf("\"\"\"", close + 1);
                }
                i = close < 0 ? n : close + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
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
