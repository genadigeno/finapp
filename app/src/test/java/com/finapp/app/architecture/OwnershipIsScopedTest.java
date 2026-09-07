package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every resource-scoped persistence operation is classified (`P1-TSK-021`, ADR-0031).
 *
 * <h2>Why a build rule exists at all, when the ADR said none could</h2>
 *
 * <p>ADR-0031 records: <em>"An operation missing its ownership check is not detectable by the
 * boundary rule ... no build rule closes this."</em> That is true of the <strong>boundary</strong>
 * rule and it is not the end of the matter, because {@code INV-IDN-05} taught the lesson one
 * milestone earlier: <em>a list of tests is a snapshot</em>, and the operation added in Phase 4 will
 * not be in it.
 *
 * <p>So this does what {@code MfaBypassPathsAreEnumeratedTest} does for session origins. It does not
 * decide whether an operation is safe. It forces every operation that <em>could</em> be unsafe to be
 * <strong>classified</strong>, and fails the build on a new one.
 *
 * <h2>The five correct statements that look exactly like the defect</h2>
 *
 * <p>Surveying {@code identity} found five statements targeting a row <strong>by primary key with no
 * owner predicate</strong> — {@code SessionStore.revoke}, {@code touch},
 * {@code MfaEnrolmentStore.confirm}, {@code consumeStep}, {@code CredentialStore.supersede}. All
 * five are correct, and in SQL all five are indistinguishable from the defect ADR-0031 describes.
 *
 * <p>The difference is <strong>provenance</strong>: an identifier that came from an owner-constrained
 * read is safe, and one that came from a request is not. A rule that merely forbade the shape would
 * have produced five false positives on its first run — and ADR-0019's own reasoning is that a rule
 * with an exemption list is a rule somebody turns off. So the rule classifies rather than forbids.
 *
 * <h2>What this closes, and what it does not — stated rather than implied</h2>
 *
 * <ul>
 *   <li><strong>Closes:</strong> a new resource-scoped store operation shipping with nobody having
 *       decided how ownership is established. It also fails when an {@link Scope#OWNER_SCOPED}
 *       statement loses its owner predicate, which the behavioural test catches too — two controls
 *       blind in different directions, the {@code P1-TSK-020} argument.
 *   <li><strong>Does not close:</strong> an {@code OWNER_SCOPED} statement binding the
 *       <em>wrong</em> owner. The shape would be right and the parameter wrong. Only the negative
 *       behavioural test catches that, which is why both exist and why {@link #NEGATIVE_TESTS}
 *       is held against the register.
 *   <li><strong>Does not close:</strong> whether an {@code AUTHORITATIVE_ID} claim is true. That
 *       claim is a review artefact; its value is that a sixth one cannot be added without somebody
 *       writing the sentence.
 *   <li><strong>Does not close:</strong> an ownership decision taken somewhere that issues no SQL.
 * </ul>
 */
@Tag("architecture")
@DisplayName("every resource-scoped persistence operation is classified (P1-TSK-021)")
class OwnershipIsScopedTest {

    /** How an operation establishes that the caller may act on <em>this</em> resource. */
    private enum Scope {

        /**
         * The owner is a predicate in the statement itself.
         *
         * <p>Never a load-then-compare: that is a TOCTOU race, and it checks a copy of the truth
         * rather than the truth (ADR-0031).
         */
        OWNER_SCOPED,

        /**
         * The resource identifier can only have come from an owner-constrained read.
         *
         * <p>The entry names that read. The check happened there; repeating it would be checking
         * the same fact twice against the same authority.
         */
        AUTHORITATIVE_ID,

        /**
         * The rows have no owner at all.
         *
         * <p>Platform infrastructure — an outbox row belongs to a flow, not to a party — so there is
         * no ownership question to answer. <strong>This is an escape hatch and is written down as
         * one:</strong> like {@link #AUTHORITATIVE_ID} it is a review artefact rather than a
         * mechanical proof, and its value is that labelling a customer-owned table with it requires
         * somebody to type a sentence that is false.
         */
        NOT_OWNED
    }

    /**
     * Every persistence method taking a resource identifier, and how ownership is established.
     *
     * <p>An entry is a claim, and writing the claim is the point of the list rather than a way past
     * it. A method that appears here for the first time is the moment to ask whether the identifier
     * can reach it from a request.
     */
    private static final Map<String, Entry> REGISTER =
            Map.ofEntries(
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revokeOwned",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "DELETE /v1/sessions/{id} - the only production operation whose"
                                        + " resource identifier comes from the request. The owner is"
                                        + " the proven session's identity.")),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revokeAll",
                            new Entry(
                                    Scope.OWNER_SCOPED,
                                    "Bulk revocation. The SessionId it takes is the session to"
                                        + " SPARE, never the target; every row it touches is"
                                        + " selected by identity_id. The detector found this"
                                        + " PRIVATE helper rather than the two public methods that"
                                        + " delegate to it, which is more accurate than the register"
                                        + " I first wrote: the statement is here.")),
                    Map.entry(
                            "com.finapp.platform.outbox.OutboxRelay.markPublished",
                            new Entry(
                                    Scope.NOT_OWNED,
                                    "An outbox row belongs to a flow, not to a party. Nobody can"
                                        + " own it, so there is no ownership question - and the"
                                        + " entry exists because the rule sweeps every module rather"
                                        + " than a list of the ones that matter today. Phase 3's"
                                        + " ledger identifiers will surface here on the day they are"
                                        + " declared.")),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.revoke",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.SessionRotation.rotate",
                                    "Called only by rotation, which holds the proven current"
                                        + " Session. No request can name the row. P1-TSK-016 found"
                                        + " the request-facing version of this taking an owner and"
                                        + " never checking it; that path is revokeOwned now.")),
                    Map.entry(
                            "com.finapp.identity.JdbcSessionStore.touch",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcSessionStore.findLive",
                                    "The interceptor touches the session it just authenticated by"
                                        + " token. The token IS the proof of ownership, so the row"
                                        + " is the caller's by construction.")),
                    Map.entry(
                            "com.finapp.identity.JdbcMfaEnrolmentStore.confirm",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcMfaEnrolmentStore.findByStatus",
                                    "MfaEnrolmentService.confirm resolves the enrolment FROM the"
                                        + " session's identity. No enrolment identifier appears in"
                                        + " the request at all - which is why /v1/me/mfa is"
                                        + " /me/ rather than /mfa/{id}.")),
                    Map.entry(
                            "com.finapp.identity.JdbcMfaEnrolmentStore.consumeStep",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcMfaEnrolmentStore.findByStatus",
                                    "MfaChallenge.elevate resolves the active factor from the"
                                        + " session's identity before spending its step.")),
                    Map.entry(
                            "com.finapp.identity.JdbcCredentialStore.supersede",
                            new Entry(
                                    Scope.AUTHORITATIVE_ID,
                                    "com.finapp.identity.JdbcCredentialStore.findActive",
                                    "Upgrade-on-use supersedes the credential it just verified,"
                                        + " which was read by identity. Nothing accepts a credential"
                                        + " identifier from anywhere.")));

    /**
     * The negative test that proves each {@link Scope#OWNER_SCOPED} predicate is load-bearing.
     *
     * <p>The build rule sees the <em>shape</em> of the statement and cannot see that it binds the
     * right owner. Only a behavioural test can, so the register is held against the suite: an
     * owner-scoped operation with no named negative test fails the build.
     */
    private static final Map<String, String> NEGATIVE_TESTS =
            Map.of(
                    "com.finapp.identity.JdbcSessionStore.revokeOwned",
                    "com.finapp.app.domain.SessionOwnershipDatabaseTest"
                            + ".revocationIsRefusedForSomebodyElsesSession",
                    "com.finapp.identity.JdbcSessionStore.revokeAll",
                    "com.finapp.app.domain.SessionRevocationDatabaseTest"
                            + ".revokeAllIsScopedToItsIdentity");

    /** The owner column. One name, because one module owns every table this rule covers. */
    private static final String OWNER_PREDICATE = "identity_id = ?";

    /**
     * Predicates that establish <em>whose</em> row this is.
     *
     * <p>Two, each a real proof rather than a convenience. {@code identity_id = ?} names the owner
     * directly. {@code token_hash = ?} is the session lookup: a session token is a bearer credential,
     * so presenting it <strong>is</strong> the proof of ownership — which is why the interceptor may
     * touch the row it just authenticated without a second check.
     *
     * <p>A third would need writing down, which is the point of the set being small and explicit.
     */
    private static final Set<String> OWNERSHIP_PREDICATES =
            Set.of(OWNER_PREDICATE, "token_hash = ?");

    private record Entry(Scope scope, String authoritativeRead, String reason) {
        Entry(Scope scope, String reason) {
            this(scope, null, reason);
        }
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("no persistence method takes a resource identifier without being classified")
    void everyResourceScopedOperationIsClassified() {
        assertThat(resourceScopedPersistenceMethods())
                .as("a persistence method that targets a resource by its own identifier is the"
                        + " shape ADR-0031's ownership defect takes. Classify it in REGISTER as"
                        + " OWNER_SCOPED (the owner is a predicate in the statement) or"
                        + " AUTHORITATIVE_ID (the identifier can only come from an owner-constrained"
                        + " read, which the entry must name)")
                .isEqualTo(new TreeSet<>(REGISTER.keySet()));
    }

    @Test
    @DisplayName("an OWNER_SCOPED statement actually carries the owner predicate")
    void ownerScopedStatementsCarryThePredicate() {
        List<String> missing = new ArrayList<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() != Scope.OWNER_SCOPED) {
                        return;
                    }
                    if (!statementOf(method).contains(OWNER_PREDICATE)) {
                        missing.add(method);
                    }
                });

        // Read from the source rather than inferred from the signature, because that is what
        // catches the defect: dropping `AND identity_id = ?` leaves the signature untouched. The
        // behavioural test catches it too, and neither replaces the other - a static sweep cannot
        // see which owner is bound, and a behavioural test cannot fail a build (P1-TSK-020).
        //
        // Read from the STRING LITERALS only, and that is not fastidiousness. The first version
        // searched the whole method body, and the mutation removing the predicate SURVIVED - because
        // revokeOwned's own comment reads "identity_id = ? IS the ownership check". A `contains`
        // over source text matches prose, so the rule was reporting a control it did not have,
        // which is worse than none because it is believed (P0-TST-008). Third occurrence of this
        // class in two tasks: right about the property, wrong about where to look.
        assertThat(missing)
                .as("an OWNER_SCOPED method whose statement has lost `" + OWNER_PREDICATE + "` is"
                        + " classified as safe and is not")
                .isEmpty();
    }

    @Test
    @DisplayName("an AUTHORITATIVE_ID entry names a read that exists")
    void authoritativeReadsExist() {
        JavaClasses production = productionClasses();
        List<String> stale = new ArrayList<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() != Scope.AUTHORITATIVE_ID) {
                        return;
                    }
                    if (!methodExists(production, entry.authoritativeRead())) {
                        stale.add(method + " -> " + entry.authoritativeRead());
                    }
                });

        // The P1-TSK-019 gate's own finding, applied here from the start: that guard checked its
        // origin TYPES resolved and not that the METHODS did, so a rename left it matching nothing
        // while still reporting coverage. An entry naming a read that no longer exists is a claim
        // nobody can evaluate.
        assertThat(stale)
                .as("an AUTHORITATIVE_ID claim naming a read that does not exist is a claim about"
                        + " nothing, and the operation it excuses becomes unexamined")
                .isEmpty();
    }

    @Test
    @DisplayName("an AUTHORITATIVE_ID provenance is itself ownership-establishing")
    void authoritativeReadsAreThemselvesScoped() {
        JavaClasses production = productionClasses();
        List<String> unscoped = new ArrayList<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() != Scope.AUTHORITATIVE_ID) {
                        return;
                    }
                    // Provenance that issues no SQL cannot be checked this way, and SessionRotation
                    // is the case: it holds a proven Session object rather than reading one. That
                    // limit is stated rather than papered over - checking it would require knowing
                    // where the caller's Session came from, which is a taint question.
                    if (!issuesSql(production, entry.authoritativeRead())) {
                        return;
                    }
                    String statement = statementOf(entry.authoritativeRead());
                    if (OWNERSHIP_PREDICATES.stream().noneMatch(statement::contains)) {
                        unscoped.add(method + " -> " + entry.authoritativeRead());
                    }
                });

        // Without this the AUTHORITATIVE_ID class is an unchecked escape hatch, and five of the
        // seven entries rest on it. The whole justification is "the identifier came from an
        // owner-constrained read" - so if that read stops being owner-constrained, every operation
        // it excuses becomes unscoped at once, silently, and the register still reads as a control.
        assertThat(unscoped)
                .as("an AUTHORITATIVE_ID entry claims its identifier came from an owner-constrained"
                        + " read. A read scoped by nothing but a primary key proves no ownership,"
                        + " and every operation citing it inherits the gap")
                .isEmpty();
    }

    @Test
    @DisplayName("every OWNER_SCOPED operation names a negative test that exists")
    void ownerScopedOperationsHaveNegativeTests() {
        TreeSet<String> ownerScoped = new TreeSet<>();
        REGISTER.forEach(
                (method, entry) -> {
                    if (entry.scope() == Scope.OWNER_SCOPED) {
                        ownerScoped.add(method);
                    }
                });

        assertThat(new TreeSet<>(NEGATIVE_TESTS.keySet()))
                .as("INV-AUD-03 requires a PASSING NEGATIVE TEST per authorization check, and"
                        + " ADR-0031 requires one per resource-scoped operation. The build rule"
                        + " cannot see which owner is bound; the negative test is what does")
                .isEqualTo(ownerScoped);

        JavaClasses tests = testClasses();
        List<String> missing =
                NEGATIVE_TESTS.values().stream()
                        .filter(named -> !methodExists(tests, named))
                        .toList();
        assertThat(missing)
                .as("a named negative test that does not exist is the pattern this phase has met"
                        + " five times: a claim that reads as true, so the next reader stops looking")
                .isEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees production code and finds real methods")
    void theGuardHasTeeth() {
        assertThat(productionClasses())
                .as("the sweep must actually import production classes")
                .isNotEmpty();

        // Without this, every assertion above passes over a detector that matches nothing - the
        // "green while checking nothing" failure this repository has met repeatedly.
        assertThat(resourceScopedPersistenceMethods())
                .as("the detector must find the operations that exist today")
                .isNotEmpty();

        // And the source reader must actually return a body, or ownerScopedStatementsCarryThePredicate
        // would fail for the wrong reason - or, worse, a `contains` over the whole file would pass
        // for the wrong reason.
        assertThat(methodSource("com.finapp.identity.JdbcSessionStore.revokeOwned"))
                .as("the method-body extraction must find the statement it claims to read")
                .contains("UPDATE")
                .contains(OWNER_PREDICATE);
    }

    @Test
    @DisplayName("party owns no resource-scoped operation, and that is a fact rather than a gap")
    void partyHasNothingToScope() {
        // Recorded rather than left implicit. `party` has no store: registration creates a Party and
        // a Customer, and nothing reads either by an identifier a caller supplied. So there is no
        // ownership surface to protect - and this assertion is what turns that from an assumption
        // into something that fails the build when it stops being true.
        assertThat(resourceScopedPersistenceMethods())
                .as("a party persistence operation taking a resource identifier is the first"
                        + " ownership surface in that module, and needs classifying here")
                .noneMatch(method -> method.startsWith("com.finapp.party."));
    }

    // -----------------------------------------------------------------

    /**
     * Production methods that issue SQL and take a resource identifier.
     *
     * <p>Detected structurally, never by name. <em>Issues SQL</em> is a call to
     * {@link java.sql.Connection#prepareStatement}, which cannot be dodged by renaming a class out
     * of the {@code *Store} convention. <em>Resource identifier</em> is an {@code EntityId} subtype
     * other than {@code IdentityId} — so a new aggregate's identifier is in scope the day it is
     * declared, without anyone remembering.
     */
    private static TreeSet<String> resourceScopedPersistenceMethods() {
        TreeSet<String> found = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!issuesSql(method) || !takesAResourceIdentifier(method)) {
                    continue;
                }
                found.add(javaClass.getName() + "." + method.getName());
            }
        }
        return found;
    }

    /** Whether a named method issues SQL. Absent methods are handled by {@code authoritativeReadsExist}. */
    private static boolean issuesSql(JavaClasses classes, String qualified) {
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        String method = qualified.substring(qualified.lastIndexOf('.') + 1);
        return classes.contain(owner)
                && classes.get(owner).getMethods().stream()
                        .filter(candidate -> candidate.getName().equals(method))
                        .anyMatch(OwnershipIsScopedTest::issuesSql);
    }

    private static boolean issuesSql(JavaMethod method) {
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            if (call.getTargetOwner().isAssignableTo(java.sql.Connection.class)
                    && call.getName().equals("prepareStatement")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A parameter naming a resource rather than its owner.
     *
     * <p>{@code IdentityId} is excluded because it <em>is</em> the owner: an operation scoped by it
     * is scoped by definition. Everything else derived from {@code EntityId} names something an
     * identity might own.
     */
    private static boolean takesAResourceIdentifier(JavaMethod method) {
        return method.getRawParameterTypes().stream()
                .anyMatch(
                        parameter ->
                                parameter.isAssignableTo(
                                                com.finapp.sharedkernel.id.EntityId.class)
                                        && !parameter
                                                .getName()
                                                .equals("com.finapp.identity.IdentityId"));
    }

    private static boolean methodExists(JavaClasses classes, String qualified) {
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        String method = qualified.substring(qualified.lastIndexOf('.') + 1);
        return classes.contain(owner)
                && classes.get(owner).getMethods().stream()
                        .anyMatch(candidate -> candidate.getName().equals(method));
    }

    /**
     * The string literals of one method, concatenated - the SQL and nothing else.
     *
     * <p>A comment cannot satisfy this, which is the whole point: SQL is only SQL if it is inside a
     * literal.
     */
    private static String statementOf(String qualified) {
        StringBuilder literals = new StringBuilder();
        java.util.regex.Matcher quoted =
                java.util.regex.Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
                        .matcher(methodSource(qualified));
        while (quoted.find()) {
            literals.append(quoted.group());
        }
        return literals.toString();
    }

    /** The body of one method, read from source - the only place a SQL literal exists. */
    private static String methodSource(String qualified) {
        String owner = qualified.substring(0, qualified.lastIndexOf('.'));
        String method = qualified.substring(qualified.lastIndexOf('.') + 1);
        String source = readSourceOf(owner);

        int signature = declarationOf(source, method);
        int open = source.indexOf('{', signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char character = source.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("Unbalanced braces reading " + qualified);
    }

    /**
     * Where the method is <em>declared</em>, never where it is called.
     *
     * <p>The first version searched for {@code " name("} and took the first hit. In
     * {@code JdbcSessionStore} that is {@code return revokeAll(unitOfWork, ...)} inside
     * {@code revokeAllFor} — a <strong>call site</strong> — so the extraction would have read the
     * wrong body and the predicate assertion would have been about a method nobody chose. Found by
     * running it rather than by reading it, which is how this class of defect is always found here.
     */
    private static int declarationOf(String source, String method) {
        int from = 0;
        while (true) {
            int hit = source.indexOf(method + "(", from);
            if (hit < 0) {
                throw new IllegalStateException("No declaration of " + method + " in that source");
            }
            // The line-feed codepoint, not System.lineSeparator(): this reads a source FILE, whose
            // endings are LF in this repository regardless of the platform running the build.
            int lineStart = source.lastIndexOf(10, hit) + 1;
            String line = source.substring(lineStart, hit).trim();
            if (line.startsWith("public ")
                    || line.startsWith("private ")
                    || line.startsWith("protected ")) {
                return hit;
            }
            from = hit + 1;
        }
    }

    private static String readSourceOf(String type) {
        String module = type.substring("com.finapp.".length());
        module = module.substring(0, module.indexOf('.'));
        Path path =
                repositoryRoot()
                        .resolve(module)
                        .resolve("src/main/java")
                        .resolve(type.replace('.', '/') + ".java");
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the source of " + type, e);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }

    private static JavaClasses testClasses() {
        return new ClassFileImporter().importPackages("com.finapp");
    }
}
