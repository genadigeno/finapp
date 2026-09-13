package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No production type holds consent state (`P2-TSK-019`, {@code INV-CNS-03}).
 *
 * <p>{@code NoProcessLocalSessionStateTest}'s shape, for the reason its javadoc gives applied to
 * lawful basis: {@code INV-CNS-03} requires a withdrawal to block the gated capability
 * <strong>on the next decision, on every instance</strong>, and that holds by construction only
 * while the database is the single authority. A process-local copy — a field holding a
 * {@code ConsentRecord}, a memoised {@code ConsentText} whose {@code requires_reconsent} the
 * derivation reads — turns it into <em>"blocked once every instance's copy has expired"</em>,
 * which is not immediacy, and an eventually-withdrawn consent is an unwithdrawn consent.
 *
 * <p>ADR-0024's four patterns cannot catch this shape (a cache need not be static, a lock, or
 * recognisably mutable), which is exactly why the session rule exists and why this one mirrors
 * it. Like that rule, it cannot see a cache built out of {@code Object} or a bare boolean keyed
 * by party — stated rather than implied; what it makes impossible is the version somebody
 * actually writes.
 */
@Tag("architecture")
@DisplayName("no production type holds consent state (P2-TSK-019)")
class NoProcessLocalConsentStateTest {

    /**
     * The types whose retention is a cache.
     *
     * <p>{@code ConsentRecord} is the fact the derivation orders; {@code ConsentText} carries
     * {@code requires_reconsent}, which the derivation reads per decision — a memoised text
     * would keep a lapsed grant alive past the version that lapsed it ({@code INV-CNS-04}).
     * Word-boundary matching, so {@code ConsentRecordId} — which <em>contains</em>
     * {@code ConsentRecord} — is not mistaken for it (the session rule's own first-run lesson).
     */
    private static final List<String> CONSENT_TYPES =
            List.of("com.finapp.consent.ConsentRecord", "com.finapp.consent.ConsentText");

    /**
     * Empty, and staying empty is the healthy state: nothing in production legitimately
     * retains a consent fact today. The first entry must arrive with its claim written down,
     * the session rule's regime.
     */
    private static final Set<String> PERMITTED = Set.of();

    @Test
    @DisplayName("no field anywhere in production code retains consent state")
    void nothingHoldsConsentState() {
        assertThat(consentFieldsIn(productionClasses()))
                .as(
                        "a field holding consent facts is a process-local cache, and INV-CNS-03"
                            + " becomes 'blocked once every instance's copy has expired' - which"
                            + " is not withdrawal (ADR-0037)")
                .isEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees every module, and it recognises the shape")
    void theGuardSeesEveryModule() {
        Set<String> analysed = new TreeSet<>();
        productionClasses().stream()
                .map(ProductionModules::of)
                .filter(Objects::nonNull)
                .forEach(analysed::add);

        assertThat(analysed)
                .as("the sweep must see every module that has production code, or it bounds"
                        + " only some of them")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());

        JavaClasses fixture = new ClassFileImporter().importClasses(HoldsConsent.class);
        assertThat(consentFieldsIn(fixture))
                .as("the detector can see a field that retains consent facts")
                .hasSize(2);
    }

    /** What this rule exists to make impossible: a consent cache on a singleton. */
    @SuppressWarnings("unused")
    private static final class HoldsConsent {
        private final java.util.Map<java.util.UUID, com.finapp.consent.ConsentRecord> latest =
                new java.util.HashMap<>();
        private com.finapp.consent.ConsentText currentText;
    }

    // -----------------------------------------------------------------

    private static List<String> consentFieldsIn(JavaClasses classes) {
        List<String> holding = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            // A guarded type's own fields are not a cache of itself.
            if (CONSENT_TYPES.contains(javaClass.getName())) {
                continue;
            }
            for (JavaField field : javaClass.getFields()) {
                if (PERMITTED.contains(field.getFullName()) || !retainsConsent(field)) {
                    continue;
                }
                holding.add(field.getFullName());
            }
        }
        return holding;
    }

    /** Reads the generic signature: a {@code Map<UUID, ConsentRecord>}'s raw type is Map. */
    private static boolean retainsConsent(JavaField field) {
        String signature = field.reflect().getGenericType().getTypeName();
        return BOUNDARIES.stream().anyMatch(pattern -> pattern.matcher(signature).find());
    }

    private static final List<Pattern> BOUNDARIES =
            CONSENT_TYPES.stream()
                    .map(type -> Pattern.compile(Pattern.quote(type) + "\\b"))
                    .toList();

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
