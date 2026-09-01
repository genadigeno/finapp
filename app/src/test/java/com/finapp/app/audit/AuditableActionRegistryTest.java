package com.finapp.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditableAction;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The auditable-action registry and its catalogue are one definition.
 *
 * <p><strong>Why this lives in {@code app}.</strong> Actions are declared per module — the
 * platform sits below every business module and cannot enumerate their vocabulary — so no single
 * module can see the whole registry. {@code app} depends on all of them, which makes it the only
 * place the complete set exists.
 *
 * <p><strong>What it prevents.</strong> {@code INV-AUD-01}'s enforcement is "{@code DOMAIN} +
 * auditable-action registry", verified by completeness checking at the Phase 15 gate. A registry
 * maintained by hand against a document drifts, and a drifted registry fails in the worst
 * direction: the completeness report looks complete because the missing action is missing from
 * both sides. Deriving the expectation from the code is what makes the check self-maintaining —
 * the same reasoning as {@code CorrelationSinkCoverageTest} and
 * {@code ArchitectureRulesAreDocumentedTest}.
 *
 * <p>Deliberately a plain JUnit test rather than an ArchUnit rule suite: it reasons about enum
 * constants and their values, which requires reflection rather than static analysis, and it is
 * not an architecture rule that {@code MODULE_ARCHITECTURE.md} §6 should list.
 */
class AuditableActionRegistryTest {

    private static final String CATALOGUE = "docs/architecture/AUDITABLE_ACTIONS.md";

    /**
     * {@code module.ActionName}. Namespaced so two modules cannot collide on a bare name and
     * merge two different actions into one line of the trail.
     */
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9]*\\.[A-Z][A-Za-z0-9]*");

    @Test
    @DisplayName("every declared action is in the catalogue, and every catalogued action is declared")
    void theRegistryAndTheCatalogueAgree() {
        Set<String> declared = declaredCodes();
        Set<String> catalogued = cataloguedCodes();

        assertThat(declared)
                .as(
                        """
                        The auditable-action registry and docs/architecture/AUDITABLE_ACTIONS.md \
                        disagree. Either an action was declared without being catalogued — so the \
                        Phase 15 completeness check would never look for it — or an action was \
                        catalogued and then removed, leaving auditors a list of things that no \
                        longer happen. Update §3 of that document.""")
                .isEqualTo(catalogued);
    }

    @Test
    @DisplayName("the guard is not vacuous: it can see actions and the catalogue")
    void theGuardSeesRealContent() {
        // Without this, a broken path or a failed import would compare two empty sets and the
        // guard would pass while checking nothing - the failure mode that makes a coverage guard
        // worse than none.
        assertThat(declaredCodes()).contains("outbox.EventAbandoned");
        assertThat(cataloguedCodes()).contains("outbox.EventAbandoned");
    }

    @Test
    @DisplayName("every implementation is an enum, so the registry is enumerable")
    void implementationsAreEnums() {
        // Enumerability is the whole point: the Phase 15 gate must be able to ask "what must be
        // audited" and get a complete answer. A non-enum implementation - a class built at run
        // time from configuration, say - would answer with whatever happened to be constructed.
        List<String> notEnums =
                implementations().stream()
                        .filter(javaClass -> !javaClass.isEnum())
                        .map(JavaClass::getName)
                        .toList();

        assertThat(notEnums)
                .as("AuditableAction implementations must be enums so the registry can be enumerated")
                .isEmpty();
    }

    @Test
    @DisplayName("codes are namespaced by module, so two modules cannot collide on a bare name")
    void codesAreNamespaced() {
        List<String> malformed =
                actions().stream().map(AuditableAction::code).filter(code -> !CODE.matcher(code).matches()).toList();

        assertThat(malformed)
                .as("codes must look like module.ActionName (see AUDITABLE_ACTIONS.md §2)")
                .isEmpty();
    }

    @Test
    @DisplayName("no two actions share a code, in any module")
    void codesAreUnique() {
        // A collision would merge two different actions into one line of the audit trail, and
        // the merge would be invisible: both would appear, correctly counted, under one name.
        List<String> codes = actions().stream().map(AuditableAction::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every action carries a description an auditor can assess")
    void everyActionIsDescribed() {
        // The registry's readers are not the people who wrote it. A code with no description is
        // a row in a completeness report nobody can judge.
        List<String> undescribed =
                actions().stream()
                        .filter(action -> action.description() == null || action.description().isBlank())
                        .map(AuditableAction::code)
                        .toList();

        assertThat(undescribed).isEmpty();
    }

    @Test
    @DisplayName("the catalogue records whether each action requires a reason, and agrees with the code")
    void reasonRequirementsAgree() {
        // The requiresReason() flag is enforced by AuditRecord, so a disagreement between code
        // and catalogue is not cosmetic: the document would tell an operator a reason is optional
        // for an action their call is about to be rejected for omitting.
        String catalogue = readCatalogue();
        for (AuditableAction action : actions()) {
            String row = rowFor(catalogue, action.code());
            boolean documentedAsRequired = row.contains("**Yes**");
            assertThat(documentedAsRequired)
                    .as("catalogue row for %s must match requiresReason() = %s", action.code(), action.requiresReason())
                    .isEqualTo(action.requiresReason());
        }
    }

    // -----------------------------------------------------------------

    private static Set<String> declaredCodes() {
        return actions().stream().map(AuditableAction::code).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every constant of every {@link AuditableAction} enum in production code. */
    private static List<AuditableAction> actions() {
        List<AuditableAction> actions = new ArrayList<>();
        for (JavaClass javaClass : implementations()) {
            Class<?> loaded = javaClass.reflect();
            if (!loaded.isEnum()) {
                continue;
            }
            for (Object constant : loaded.getEnumConstants()) {
                actions.add((AuditableAction) constant);
            }
        }
        return actions;
    }

    private static List<JavaClass> implementations() {
        JavaClasses imported =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.finapp");
        return imported.stream()
                .filter(javaClass -> javaClass.isAssignableTo(AuditableAction.class))
                .filter(javaClass -> !javaClass.getName().equals(AuditableAction.class.getName()))
                .toList();
    }

    /** Codes named in the catalogue's tables, as `code` in the first column. */
    private static Set<String> cataloguedCodes() {
        Set<String> codes = new TreeSet<>();
        for (String line : readCatalogue().lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("| `")) {
                continue;
            }
            String code = trimmed.substring(3, trimmed.indexOf('`', 3));
            if (CODE.matcher(code).matches()) {
                codes.add(code);
            }
        }
        return codes;
    }

    private static String rowFor(String catalogue, String code) {
        return catalogue
                .lines()
                .filter(line -> line.strip().startsWith("| `" + code + "`"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no catalogue row for " + code));
    }

    /**
     * Reads the catalogue, searching upwards from the working directory.
     *
     * <p>The same resolution {@code ArchitectureRulesAreDocumentedTest} uses, and for the same
     * reason: a path relative to one assumed working directory works under Gradle and breaks in
     * an IDE, and it breaks with "the catalogue must exist" — a failure that reads as a missing
     * document rather than a misconfigured test.
     *
     * <p>Not found is a hard failure, never a skip. A test that cannot locate the catalogue and
     * quietly passes would report agreement without having compared anything.
     */
    private static String readCatalogue() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(CATALOGUE);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read " + candidate, e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Could not find " + CATALOGUE + " above " + Path.of("").toAbsolutePath());
    }
}
