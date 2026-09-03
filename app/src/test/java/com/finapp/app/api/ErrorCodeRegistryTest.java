package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.testing.RepositoryPaths;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The error-code taxonomy and its catalogue are one definition.
 *
 * <p>{@code P0-TSK-024} requires error codes to be "enumerated and documented". Enumerated is the
 * easy half; documented drifts the moment somebody adds a code in a hurry, and a drifted
 * catalogue fails in the worst direction — it looks complete, because the missing code is missing
 * from both the document and anyone's memory of it.
 *
 * <p>Lives in {@code app} for the same reason as {@code AuditableActionRegistryTest}: codes
 * belong to the modules that raise them, so no single module sees the whole taxonomy.
 */
@Tag("architecture")
class ErrorCodeRegistryTest {

    private static final String CATALOGUE = "docs/architecture/ERROR_CONTRACT.md";

    /** {@code module.CodeName} — namespaced so two modules cannot give one string two meanings. */
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9]*\\.[A-Z][A-Za-z0-9]*");

    @Test
    @DisplayName("every declared code is in the catalogue, and every catalogued code is declared")
    void theTaxonomyAndTheCatalogueAgree() {
        assertThat(declaredCodes())
                .as(
                        """
                        The error-code taxonomy and docs/architecture/ERROR_CONTRACT.md disagree. \
                        Either a code was added without being documented — so no client author \
                        can discover it — or one was documented and then removed, leaving clients \
                        handling a code that can no longer occur. Update §3 of that document.""")
                .isEqualTo(cataloguedCodes());
    }

    @Test
    @DisplayName("the guard is not vacuous: it can see codes and the catalogue")
    void theGuardSeesRealContent() {
        assertThat(declaredCodes()).contains("api.NotFound", "api.InternalError");
        assertThat(cataloguedCodes()).contains("api.NotFound", "api.InternalError");
    }

    @Test
    @DisplayName("the catalogue records each code's status, and agrees with the code")
    void statusesAgree() {
        // A disagreement is not cosmetic: a client author reading "409" and receiving 422 writes
        // handling that never fires.
        String catalogue = readCatalogue();
        for (ErrorCode code : codes()) {
            String row = rowFor(catalogue, code.code());
            assertThat(row)
                    .as("catalogue row for %s must record status %s", code.code(), code.status())
                    .contains("| " + code.status() + " |");
        }
    }

    @Test
    @DisplayName("every implementation is an enum, so the taxonomy is enumerable")
    void implementationsAreEnums() {
        List<String> notEnums =
                implementations().stream()
                        .filter(javaClass -> !javaClass.isEnum())
                        .map(JavaClass::getName)
                        .toList();

        assertThat(notEnums).isEmpty();
    }

    @Test
    @DisplayName("codes are namespaced, unique, and carry a title")
    void codesAreWellFormed() {
        List<ErrorCode> codes = codes();

        assertThat(codes.stream().map(ErrorCode::code).filter(c -> !CODE.matcher(c).matches()).toList())
                .as("codes must look like module.CodeName")
                .isEmpty();
        assertThat(codes.stream().map(ErrorCode::code).toList())
                .as("a collision would give one string two meanings")
                .doesNotHaveDuplicates();
        assertThat(codes.stream().filter(c -> c.title() == null || c.title().isBlank()).toList())
                .as("the title is what a human sees; it cannot be empty")
                .isEmpty();
    }

    @Test
    @DisplayName("every status is one a client can act on")
    void statusesArePlausible() {
        // A 2xx or 3xx here would mean an error rendered as a success, which no client checks
        // for. Caught at build time rather than by whoever notices their error handling never
        // runs.
        assertThat(codes().stream().filter(c -> c.status() < 400 || c.status() > 599).toList())
                .isEmpty();
    }

    // -----------------------------------------------------------------

    private static Set<String> declaredCodes() {
        return codes().stream().map(ErrorCode::code).collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<ErrorCode> codes() {
        return DeclaredErrorCodes.all();
    }

    private static List<JavaClass> implementations() {
        return DeclaredErrorCodes.implementations();
    }

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

    private static String readCatalogue() {
        return RepositoryPaths.read(CATALOGUE);
    }

}
