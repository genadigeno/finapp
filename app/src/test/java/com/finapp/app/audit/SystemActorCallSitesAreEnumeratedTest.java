package com.finapp.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every place the platform claims to be the actor is enumerated (`P1-TSK-022`, ADR-0021).
 *
 * <h2>The acceptance criterion is a claim about a set, so it is held against the code</h2>
 *
 * <p>{@code P1-TSK-022}'s acceptance: <em>"the number of {@code enterSystem()} call sites is reduced
 * to those that are genuinely the platform acting, <strong>and each is justified</strong>."</em>
 *
 * <p>A justification written once in a review is a snapshot. ADR-0021 called {@code enterSystem()}
 * <em>"the greppable list of places Phase 1 must revisit"</em> — and grep is a thing somebody has to
 * remember to run. This is that list, held against the code, so a <strong>new</strong> claim that the
 * platform is acting fails the build until somebody writes down why.
 *
 * <p>The {@code MfaBypassPathsAreEnumeratedTest} shape, and for the same reason: the site added in
 * Phase 4 will not be in anybody's memory of this review.
 *
 * <h2>Why the count is two rather than zero</h2>
 *
 * <p>{@code SECURITY_ARCHITECTURE.md} §Who is acting is explicit that <em>"revisit every
 * {@code enterSystem()}" reads as "remove every {@code enterSystem()}" and would be wrong</em>. The
 * sites that must go are those where a real actor exists and was not established. Both survivors are
 * on <strong>unauthenticated</strong> paths, where there is no other honest answer: attributing an
 * action to an identity the platform has not proven is worse than naming the platform, because the
 * record is then complete, plausible, about the wrong party, and permanent ({@code INV-HIST-03}).
 *
 * <h2>What this does not close</h2>
 *
 * <p>It cannot tell that a justification is <em>true</em>. An entry is a review artefact; its value
 * is that adding one requires somebody to type a sentence, and that a site appearing without one
 * stops the build. What it does close is the silent arrival of a third.
 */
@Tag("architecture")
@DisplayName("every system-actor call site is enumerated (P1-TSK-022)")
class SystemActorCallSitesAreEnumeratedTest {

    /** Where the platform claims to be the acting party, and why that is the honest answer. */
    private static final Map<String, String> ENUMERATED_SITES =
            Map.of(
                    "com.finapp.app.registration.RegistrationService.register",
                    "POST /v1/registrations is UNAUTHENTICATED, so there is no proven actor."
                        + " Attributing the action to the Party it creates was considered and"
                        + " rejected: it is circular, and it is unavailable on the refusal path"
                        + " where nothing was created - an actor that differs between success and"
                        + " failure is worse than a uniform honest one. What carries the information"
                        + " is the audit record's TARGET, the attempted login identifier, on both"
                        + " paths. Recorded in SECURITY_ARCHITECTURE.md as a site that STAYS.",
                    "com.finapp.app.authentication.AuthenticationService.attempt",
                    "The FAILURE BRANCH of POST /v1/authentications. There may be no identity at all"
                        + " - the login identifier may name nobody - so there is nothing to"
                        + " attribute to, and naming a guessed identity would put an unproven claim"
                        + " in a permanent record. The SUCCESS BRANCH of this same method"
                        + " establishes Actor(identityId, CUSTOMER) from the identity it just"
                        + " proved, which is asserted separately below because this enumeration is"
                        + " at METHOD granularity and cannot see which branch called.");

    @Test
    @DisplayName("no production code claims the system actor without being enumerated")
    void everySiteIsEnumerated() {
        assertThat(systemActorCallSites())
                .as("a new place claiming the platform is the actor is a new place a real actor may"
                        + " have been available and not established - which records the platform as"
                        + " having done what a person did, permanently (INV-HIST-03). Add it to"
                        + " ENUMERATED_SITES with the reason there is no honest alternative")
                .isEqualTo(new TreeSet<>(ENUMERATED_SITES.keySet()));
    }

    @Test
    @DisplayName("every enumerated site still exists, so no entry is a claim about nothing")
    void noEnumeratedSiteIsStale() {
        // An entry naming a method that has been renamed or deleted silently stops applying while
        // still reading as a live decision - the P1-TSK-015 rule for exemption lists.
        assertThat(systemActorCallSites())
                .as("an enumerated site that no longer exists has stopped justifying anything")
                .containsAll(ENUMERATED_SITES.keySet());
    }

    @Test
    @DisplayName("both survivors are on unauthenticated paths, which is why they survive")
    void bothSurvivorsAreUnauthenticated() {
        // The property that makes the count two rather than zero, asserted rather than left to the
        // prose above. A site on an AUTHENTICATED path would mean a real actor existed and was not
        // established, which is exactly what ADR-0021 built require() to prevent.
        assertThat(ENUMERATED_SITES.keySet())
                .as("every surviving system-actor site must sit on a path with no proven caller")
                .allSatisfy(
                        site ->
                                assertThat(site)
                                        .matches(
                                                name ->
                                                        name.contains(".registration.")
                                                                || name.contains(
                                                                        ".authentication."),
                                                "the only unauthenticated surfaces in Phase 1 are"
                                                    + " registration and authentication"));
    }

    @Test
    @DisplayName("the authentication site still establishes a real actor on success")
    void theAuthenticationSiteStillNamesThePersonOnSuccess() {
        // The gap method granularity creates, closed for the one site where it exists.
        //
        // AuthenticationService.attempt contains BOTH branches, so it is enumerated once - which
        // means replacing the success branch's `SecurityContext.enter(new Actor(...))` with
        // enterSystem() would change nothing this enumeration can see, while recording the platform
        // as having logged somebody in. That record is complete, plausible, about the wrong party,
        // and permanent (INV-HIST-03).
        //
        // Asserting the real-actor call is present is what makes that mutation fail. The behavioural
        // half is P1-TSK-010's AuthenticationEndpointDatabaseTest, which asserts the record names
        // the proven identity; neither replaces the other.
        assertThat(callsFrom("com.finapp.app.authentication.AuthenticationService", "attempt"))
                .as("the success branch must establish a real actor, or the platform is recorded as"
                        + " having logged somebody in")
                .contains("com.finapp.platform.security.SecurityContext.enter");
    }

    @Test
    @DisplayName("every module with production code is within reach of this rule")
    void everyModuleWithProductionCodeIsAnalysed() {
        java.util.Set<String> analysed =
                productionClasses().stream()
                        .map(com.finapp.app.architecture.ProductionModules::of)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // The sibling idiom, and it is here because P1-TSK-021's completion gate found this suite's
        // predecessor carrying a bare isNotEmpty() - the P0-TSK-008 finding, where a rule that stops
        // reaching a module reports safety it never checked. Deviating from the idiom four other
        // rule suites already use is what hid secretsAreWrapped's inversion in the first place.
        assertThat(analysed)
                .as("every module with production classes must be within reach, or an action"
                        + " declared or emitted there is simply invisible to this rule")
                .containsAll(
                        com.finapp.app.architecture.ProductionModules
                                .onClasspathWithProductionClasses());
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees production code and finds the sites")
    void theGuardHasTeeth() {
        assertThat(productionClasses())
                .as("the sweep must actually import production classes")
                .isNotEmpty();

        // Without this, everySiteIsEnumerated passes over a detector that matches nothing and the
        // list becomes decoration - the "green while checking nothing" failure this repository has
        // met repeatedly.
        assertThat(systemActorCallSites())
                .as("the detector must find the sites that exist today")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------

    /**
     * Production methods calling {@code SecurityContext.enterSystem()}.
     *
     * <p>A method <em>call</em>, so a mention in a comment or a javadoc cannot satisfy it — the
     * mistake {@code P1-TSK-021}'s gate found when a source-text {@code contains} matched prose.
     * {@code SecurityContext} itself is excluded: it declares the method.
     */
    private static TreeSet<String> systemActorCallSites() {
        TreeSet<String> sites = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            if (javaClass.getName().equals("com.finapp.platform.security.SecurityContext")) {
                continue;
            }
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                if (call.getTargetOwner()
                                .getName()
                                .equals("com.finapp.platform.security.SecurityContext")
                        && call.getName().equals("enterSystem")) {
                    sites.add(
                            call.getOriginOwner().getName() + "." + call.getOrigin().getName());
                }
            }
        }
        return sites;
    }

    /** Every method this method calls, as {@code owner.name}. */
    private static TreeSet<String> callsFrom(String type, String method) {
        TreeSet<String> called = new TreeSet<>();
        JavaClasses production = productionClasses();
        if (!production.contain(type)) {
            throw new IllegalStateException("Not on the classpath: " + type);
        }
        production.get(type).getMethodCallsFromSelf().stream()
                .filter(call -> call.getOrigin().getName().equals(method))
                .forEach(call -> called.add(call.getTargetOwner().getName() + "." + call.getName()));
        if (called.isEmpty()) {
            throw new IllegalStateException("No method " + method + " calling anything in " + type);
        }
        return called;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
