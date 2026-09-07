package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every path to a session is enumerated (`P1-TSK-019`, {@code INV-IDN-05}).
 *
 * <h2>This is the durable half of `P1-TST-003`, and the reason it exists is the invariant's own</h2>
 *
 * <p>{@code INV-IDN-05}: <em>"Every real MFA bypass is a path nobody enumerated."</em> A suite of
 * tests, one per path, satisfies the letter of that and not its point — the suite is a
 * <strong>snapshot</strong>, and the path added in Phase 4 will not be in it. Nobody will remember.
 *
 * <p>So the enumeration is held against the code: every production call site that brings a session
 * into existence, or changes the assurance of one, must be named here. A new one fails the build
 * and forces somebody to write down what it is and why it cannot be a bypass.
 *
 * <p>The {@code P0-TSK-037} shape — {@code ProviderFailureCoverageTest} does the same for provider
 * failure modes, and for the same reason: a coverage claim nobody checks is a coverage claim that
 * quietly stops being true.
 *
 * <h2>What the enumeration says today, and it is worth reading twice</h2>
 *
 * <p><strong>Nothing in production calls {@code Session.issue}.</strong> The only path that creates
 * a session row is {@code SessionRotation}, reached only from {@code MfaChallenge.elevate}, which
 * refuses without a verified code. So right now the honest statement is that <em>the only way a
 * session comes into existence in this platform is a proven second factor</em>.
 *
 * <p>That is an accident of sequencing rather than a design goal — {@code P1-TSK-027} will make
 * authentication issue one — and it is exactly why this guard is written now rather than then. The
 * task that adds the second path has to come here and say so.
 */
@Tag("architecture")
@DisplayName("every path to a session is enumerated (P1-TSK-019)")
class MfaBypassPathsAreEnumeratedTest {

    /**
     * The methods that create a session or change its assurance, and why each is not a bypass.
     *
     * <p>An entry is a claim, and the claim is the point of the list rather than a way around it.
     * Adding one without being able to write the right-hand column is the moment to stop and think.
     */
    private static final Map<String, String> ENUMERATED_PATHS =
            Map.of(
                    "com.finapp.identity.SessionRotation.rotate",
                    "The only writer of session rows. It does not decide the level - the caller"
                            + " states it - so it is not itself a bypass; every caller is enumerated"
                            + " below.",
                    "com.finapp.identity.MfaChallenge.elevate",
                    "The only caller that raises assurance, and it refuses without a code verified"
                            + " against an ACTIVE factor whose step has not been spent.");

    /** What a call site must invoke to count as creating or elevating a session. */
    private static final Set<String> SESSION_ORIGINS =
            Set.of(
                    "com.finapp.identity.Session.issue",
                    "com.finapp.identity.SessionStore.insert",
                    "com.finapp.identity.JdbcSessionStore.insert",
                    "com.finapp.identity.SessionRotation.rotate");

    @Test
    @DisplayName("no production path creates or elevates a session without being enumerated")
    void everyPathIsEnumerated() {
        assertThat(sessionOriginCallSites())
                .as("a new way to obtain a session is a new way to bypass MFA until somebody says"
                        + " why it is not (INV-IDN-05). Add it to ENUMERATED_PATHS with the reason,"
                        + " and add a test to MfaCannotBeBypassedDatabaseTest")
                .isEqualTo(new TreeSet<>(ENUMERATED_PATHS.keySet()));
    }

    @Test
    @DisplayName("every enumerated path still exists, so no entry is a claim about nothing")
    void noEnumeratedPathIsStale() {
        // An entry naming a method that has been renamed or deleted is a claim nobody can evaluate,
        // and it silently stops applying - the P1-TSK-015 rule for exemption lists, applied to an
        // enumeration. Without this the list could drift into pure decoration while still reading
        // as a control.
        assertThat(sessionOriginCallSites())
                .as("an enumerated path that no longer exists has stopped protecting anything")
                .containsAll(ENUMERATED_PATHS.keySet());
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees production code and can recognise an origin")
    void theGuardHasTeeth() {
        assertThat(productionClasses())
                .as("the sweep must actually import production classes")
                .isNotEmpty();

        // And it must be able to see one. Without this the assertions above pass over a detector
        // that matches nothing - the "green while checking nothing" failure this repository has met
        // repeatedly.
        assertThat(sessionOriginCallSites())
                .as("the detector finds the origins that exist today")
                .isNotEmpty();

        // The vocabulary must name METHODS that exist, not merely types.
        //
        // The first version checked types only, and the completion gate found the gap by probing: a
        // renamed store method - or a bogus entry added later - would leave the type resolving
        // perfectly while the origin silently stopped matching anything, and every path through it
        // would become invisible. A guard that reports coverage it does not have is worse than none,
        // because it is believed (the P0-TST-008 finding).
        JavaClasses production = productionClasses();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String origin : SESSION_ORIGINS) {
            String owner = origin.substring(0, origin.lastIndexOf('.'));
            String method = origin.substring(origin.lastIndexOf('.') + 1);
            if (!production.contain(owner)
                    || production.get(owner).getMethods().stream()
                            .noneMatch(candidate -> candidate.getName().equals(method))) {
                missing.add(origin);
            }
        }
        assertThat(missing)
                .as("every method named in SESSION_ORIGINS must exist, or the origin matches"
                        + " nothing and the paths through it become invisible")
                .isEmpty();
    }

    // -----------------------------------------------------------------

    /** The production methods that call something in {@link #SESSION_ORIGINS}. */
    private static TreeSet<String> sessionOriginCallSites() {
        TreeSet<String> callers = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                String target =
                        call.getTargetOwner().getName() + "." + call.getName();
                if (!SESSION_ORIGINS.contains(target)) {
                    continue;
                }
                String caller =
                        call.getOriginOwner().getName() + "." + call.getOrigin().getName();
                // A type calling its own origin method is the implementation, not a path to it:
                // JdbcSessionStore.insert IS the write, and SessionRotation calling
                // SessionStore.insert is the path. Without this the list would name every store
                // internal and say nothing about who can obtain a session.
                if (Objects.equals(call.getOriginOwner(), call.getTargetOwner())) {
                    continue;
                }
                callers.add(caller);
            }
        }
        return callers;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
