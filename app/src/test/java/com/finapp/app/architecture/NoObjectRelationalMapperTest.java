package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0033 made structural: no object-relational mapper reaches the application.
 *
 * <p><strong>Why this is a build failure rather than a review note.</strong> Three of the
 * strongest invariants in the catalogue are statements about a privilege the application must
 * <em>not</em> hold — {@code INV-HIST-03} (audit append-only), {@code INV-HIST-01} (financial
 * history never edited) and {@code INV-LED-03} (posted entries immutable), all enforced at
 * {@code DB-PRIVILEGE} by {@code finapp_app} holding no {@code UPDATE} and no {@code DELETE}
 * ({@code V008}, {@code V009}).
 *
 * <p>A privilege model is worth exactly as much as the guarantee that nothing emits a statement
 * nobody wrote. Hibernate's dirty checking emits {@code UPDATE} on its own initiative, at a flush
 * point decided by code far from the write — so whether the forbidden statement is issued depends
 * on whether an entity happened to be dirty. That is the shape of defect that passes every test
 * and fails in production. {@code DB-PRIVILEGE} ranks second to {@code DB-CONSTRAINT} in the
 * catalogue, but it is the strongest mechanism available for <em>forbidding an operation</em>: a
 * {@code CHECK} constraint cannot express "this role may not {@code UPDATE}".
 *
 * <p><strong>The classpath, not the source.</strong> An ArchUnit rule matching package names would
 * catch a class that <em>references</em> {@code jakarta.persistence}. This asserts the artefact is
 * not there at all, which additionally catches an ORM arriving transitively behind a starter —
 * the way it would actually arrive. The precedent is exact: {@code P0-TSK-027} made ADR-0011's
 * "no migrations at startup" structural by proving Flyway absent from this same classpath, rather
 * than by setting a property that says so.
 *
 * <p><strong>The runtime classpath, not the test classpath.</strong> That distinction is not
 * pedantry here — it is the correction {@code P0-TSK-035} forced on the Flyway assertion, whose
 * own comment had predicted its failure: a test-scoped dependency made the check report a false
 * positive. What matters is what the application ships.
 *
 * <p><strong>This passes vacuously today</strong>, because no ORM is on any classpath — verified
 * when it was written. That is why it carries a vacuity guard: a check that cannot see the
 * classpath must fail loudly rather than report that it found nothing wrong. It stops being
 * vacuous the first time somebody types {@code spring-boot-starter-data-jpa}, which is exactly
 * when it needs to fire.
 *
 * <p><strong>Not an ArchUnit suite, deliberately.</strong> It inspects a classpath rather than
 * compiled classes, so it declares no {@code @AnalyzeClasses} and is not discovered by
 * {@code ArchitectureRulesAreDocumentedTest}. {@code MODULE_ARCHITECTURE.md} §6 therefore names
 * this class in prose rather than with an {@code *(ArchUnit: …)*} marker — the same treatment
 * {@code SharedKernelIsolationTest} already has. Tagged {@code architecture} for the same reason
 * that test is: it is a structural assertion about the build, and it needs no infrastructure.
 */
@Tag("architecture")
@DisplayName("No object-relational mapper (ADR-0033)")
class NoObjectRelationalMapperTest {

    /**
     * Supplied by {@code app/build.gradle.kts} for every test task in this module.
     *
     * <p>It is the resolved {@code runtimeClasspath}, so it describes what the application ships
     * rather than what a test happens to see.
     */
    private static final String RUNTIME_CLASSPATH_PROPERTY = "finapp.runtime.classpath";

    /**
     * Artefact-name fragments that mean an ORM is present.
     *
     * <p>Each entry is the distribution's own artefact prefix, so a match is an artefact rather
     * than a coincidence of words:
     *
     * <ul>
     *   <li>{@code hibernate-core} / {@code hibernate-entitymanager} — the ORM implementation,
     *       however it arrived;
     *   <li>{@code jakarta.persistence-api} / {@code jakarta.persistence} — the JPA API itself,
     *       which is what {@code @Entity} needs to compile;
     *   <li>{@code spring-data-jpa} — the Spring Data module over JPA;
     *   <li>{@code spring-boot-starter-data-jpa} — the aggregate that brings all three, and the
     *       thing somebody actually adds;
     *   <li>{@code spring-data-jdbc} / {@code spring-data-relational} — Spring Data JDBC, rejected
     *       in ADR-0033 Option B on its own merits: {@code save()} deletes and re-inserts child
     *       collections, and application-minted identifiers make it default to {@code UPDATE}.
     * </ul>
     *
     * <p>{@code spring-jdbc} is deliberately absent from this list. It is the chosen mechanism —
     * {@code JdbcClient} lives in it — and forbidding it would forbid the decision.
     *
     * <p><strong>{@code hibernate-validator} is deliberately absent too, and the first version of
     * this list got that wrong.</strong> It matched {@code hibernate-} and failed on the real
     * classpath, because Bean Validation is published by the Hibernate project under that prefix
     * and arrives with {@code spring-boot-starter-validation} — which {@code P0-TSK-025} added to
     * reject requests at the boundary, and which has nothing to do with persistence. A rule that
     * forbids a correct dependency is a rule somebody turns off, which is the reasoning ADR-0019
     * used to keep {@code key} out of the {@code secretsAreWrapped} vocabulary. The names here are
     * therefore the ORM's own artefacts rather than its publisher's prefix, and
     * {@link #beanValidationIsNotMistakenForAnOrm()} keeps that carve-out honest.
     */
    private static final List<String> FORBIDDEN_ARTEFACTS =
            List.of(
                    "hibernate-core",
                    "hibernate-entitymanager",
                    "jakarta.persistence",
                    "javax.persistence",
                    "spring-data-jpa",
                    "spring-boot-starter-data-jpa",
                    "spring-data-jdbc",
                    "spring-data-relational");

    @Test
    @DisplayName("no ORM artefact is on the application's runtime classpath")
    void noObjectRelationalMapperShips() {
        String runtime = runtimeClasspath();

        for (String artefact : FORBIDDEN_ARTEFACTS) {
            assertThat(runtime)
                    .as(
                            "ADR-0033: %s must not be on the application's runtime classpath. "
                                    + "The platform enforces INV-HIST-01, INV-HIST-03 and INV-LED-03 "
                                    + "at DB-PRIVILEGE, which holds only while nothing emits a "
                                    + "statement nobody wrote.",
                            artefact)
                    .doesNotContain(artefact);
        }
    }

    @Test
    @DisplayName("the chosen mechanism is actually present")
    void theChosenMechanismIsPresent() {
        // The negative assertion above is satisfied by an empty classpath, by the wrong classpath,
        // and by a build that stopped shipping data access at all. This pins the other side:
        // ADR-0033 chose spring-jdbc, so spring-jdbc must be there. If this fails, the decision
        // has been reversed by something other than a superseding ADR.
        assertThat(runtimeClasspath())
                .as("ADR-0033 chose JdbcClient, which lives in spring-jdbc")
                .contains("spring-jdbc");
    }

    @Test
    @DisplayName("Bean Validation is present and is not mistaken for an ORM")
    void beanValidationIsNotMistakenForAnOrm() {
        // The carve-out above is only meaningful while the thing it carves out is actually there.
        // If hibernate-validator ever leaves the classpath, this fails and whoever widens the
        // forbidden list back to "hibernate-" learns from a test rather than from a red build on
        // an unrelated change. P0-TSK-041's exemptions were proven load-bearing the same way.
        assertThat(runtimeClasspath())
                .as("spring-boot-starter-validation brings hibernate-validator (P0-TSK-025)")
                .contains("hibernate-validator");

        assertThat(FORBIDDEN_ARTEFACTS)
                .as(
                        "hibernate-validator is Bean Validation, not persistence. A pattern that "
                                + "matched it would forbid a dependency the boundary validation in "
                                + "P0-TSK-025 requires.")
                .noneMatch(artefact -> "hibernate-validator".contains(artefact));
    }

    @Test
    @DisplayName("MODULE_ARCHITECTURE.md §6 still names this class")
    void theDocumentedBoundaryStillNamesThisClass() {
        // §6 claims the persistence boundary is mechanically enforced and names this class as
        // what enforces it. Nothing checked that the class existed.
        //
        // ArchitectureRulesAreDocumentedTest cannot: it discovers @AnalyzeClasses suites and
        // @ArchTest members, and this is a classpath assertion rather than an ArchUnit rule. So
        // the claim sat in exactly the state that document exists to prevent - an attribution of
        // mechanical enforcement that nothing verifies - and a rename would have left §6 naming a
        // class that does not exist. That is the "register describing something that does not
        // exist" defect this repository has closed in the column register, the auditable-action
        // registry and the mutation register.
        //
        // getSimpleName() rather than a literal, so a rename moves the expectation with the class
        // and the document is what fails.
        assertThat(readModuleArchitecture())
                .as(
                        "MODULE_ARCHITECTURE.md §6 must name %s, because it claims the persistence "
                                + "boundary is mechanically enforced and this is what enforces it",
                        getClass().getSimpleName())
                .contains(getClass().getSimpleName());
    }

    // -----------------------------------------------------------------

    /**
     * The architecture document, or a loud failure.
     *
     * <p>Found by walking upward rather than by a path relative to an assumed working directory —
     * the correction the {@code P0-TSK-023} review made, where a guard located its document under
     * Gradle and failed in an IDE with an error that read like a missing document rather than a
     * misconfigured test. A guard that cannot find its document must not pass.
     */
    private static String readModuleArchitecture() {
        java.nio.file.Path directory = java.nio.file.Path.of("").toAbsolutePath();
        while (directory != null) {
            java.nio.file.Path candidate = directory.resolve("docs/architecture/MODULE_ARCHITECTURE.md");
            if (java.nio.file.Files.isRegularFile(candidate)) {
                try {
                    return java.nio.file.Files.readString(candidate, java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException("Could not read " + candidate, e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Could not find docs/architecture/MODULE_ARCHITECTURE.md above "
                        + java.nio.file.Path.of("").toAbsolutePath());
    }

    /**
     * The classpath, or a loud failure.
     *
     * <p>Reading a missing property and comparing the empty string against a forbidden name would
     * pass every assertion above while checking nothing — the vacuity failure this repository has
     * met four times, most recently in a coverage guard that read a stale class file. A check that
     * cannot run must not look like a check that passed.
     */
    private static String runtimeClasspath() {
        String runtime = System.getProperty(RUNTIME_CLASSPATH_PROPERTY);
        assertThat(runtime)
                .as(
                        "the build must supply %s, or this test asserts nothing. It is set on every "
                                + "Test task in app/build.gradle.kts; an IDE run that bypasses Gradle "
                                + "will not have it.",
                        RUNTIME_CLASSPATH_PROPERTY)
                .isNotBlank();
        return runtime.toLowerCase(Locale.ROOT);
    }
}
