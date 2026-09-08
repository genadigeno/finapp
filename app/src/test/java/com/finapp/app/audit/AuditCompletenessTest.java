package com.finapp.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditableAction;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every registered auditable action is emitted, or declared not to be (`P1-TSK-022`,
 * {@code INV-AUD-01}).
 *
 * <h2>This closes the limit {@code P0-TSK-023} recorded against itself</h2>
 *
 * <p>{@code AuditableActionRegistryTest} reconciles the registry with the catalogue in three
 * directions, and its own javadoc states what it cannot do: <em>"it cannot detect a privileged
 * action that writes no audit record at all."</em> That is the gap that matters, because a registry
 * which looks complete while nothing emits half of it is precisely the false confidence ADR-0010
 * warns about — the Phase 15 completeness report would be checked against a list, and the list would
 * agree with itself.
 *
 * <p>So every action is held against the code. An action nothing references is either a
 * <strong>deliberate</strong> remainder, named here with the task that will emit it, or somebody
 * removed the audit call — and the two are indistinguishable until one of them is written down.
 *
 * <h2>What this closes and what it does not</h2>
 *
 * <ul>
 *   <li><strong>Closes:</strong> an action catalogued and never emitted; an action that silently
 *       <em>stops</em> being emitted; a remainder whose owning task nobody recorded.
 *   <li><strong>Does not close:</strong> whether the record is written on the path that matters, or
 *       with the right fields. This sees that the constant is <em>referenced</em> by production
 *       code, which is a weaker claim than <em>the operation audits itself</em>. The behavioural
 *       tests establish that, which is why both exist — the {@code P1-TSK-020} argument that two
 *       controls blind in different directions are not a duplication.
 *   <li><strong>Does not close:</strong> a privileged operation that was never given an action at
 *       all. Nothing mechanical can see an absence nobody named; that remains the Phase 15
 *       completeness verification `INV-AUD-01` schedules.
 * </ul>
 */
@Tag("architecture")
@DisplayName("every auditable action is emitted or declared not to be (P1-TSK-022)")
class AuditCompletenessTest {

    /**
     * Actions no production code emits, and the task that will emit each.
     *
     * <p>An entry is a claim about the future, so it names the task rather than saying "later". The
     * value of the list is that adding to it is a decision somebody takes deliberately, and that an
     * action dropping out of production code without an entry fails the build.
     */
    /*
     * `identity.IdentitySuspended` left this map at P1-TSK-028, which built the endpoint that
     * emits it. That is the list working in the direction it is usually not exercised in: an entry
     * is a claim about the future, and the future arriving is what removes it.
     */
    private static final Map<String, String> NOT_YET_EMITTED =
            Map.of(
                    "party.ProfileChanged",
                    "PHASE_1_PLAN.md section 7 DOES list PATCH /v1/me, and no backlog task owned"
                        + " it until the phase review created P1-TSK-030 - the eighth backlog"
                        + " defect of that class in Phase 1, and the first found by a review"
                        + " rather than by the task that tripped over it. This entry previously"
                        + " read that the plan listed no such endpoint, which was FALSE: an"
                        + " exemption is a claim that something is safe by other means, so a false"
                        + " claim is a hole with a paragraph in front of it (the P1-TSK-018"
                        + " finding, in this task's own register).",
                    "outbox.EventAbandoned",
                    "Recorded debt: the relay logs the decision and writes no record. Phase 15"
                        + " (dead-letter handling), per CURRENT_STATE.md.",
                    "outbox.EventRetryAuthorised",
                    "Recorded debt: performed today as a manual UPDATE with no tooling. Phase 15.",
                    "outbox.EventDiscarded",
                    "Recorded debt: a relay decision visible only as a log line, which ADR-0010 is"
                        + " explicit does not count as an audit trail. Phase 15.");

    @Test
    @DisplayName("no registered action is silently unemitted")
    void everyActionIsEmittedOrDeclaredNotToBe() {
        TreeSet<String> unemitted = new TreeSet<>();
        for (AuditableAction action : actions()) {
            if (!isReferencedByProductionCode(action)) {
                unemitted.add(action.code());
            }
        }

        assertThat(unemitted)
                .as("an action nobody emits and nobody declared unemitted is the gap"
                        + " AuditableActionRegistryTest states it cannot see: the registry agrees"
                        + " with the catalogue and neither knows the code stopped writing the"
                        + " record. Either emit it, or add it to NOT_YET_EMITTED with the task that"
                        + " will")
                .isEqualTo(new TreeSet<>(NOT_YET_EMITTED.keySet()));
    }

    @Test
    @DisplayName("every NOT_YET_EMITTED entry names an action that still exists")
    void noDeclarationIsStale() {
        TreeSet<String> registered = new TreeSet<>();
        actions().forEach(action -> registered.add(action.code()));

        // An entry for an action that has been renamed or removed is a claim nobody can evaluate,
        // and it silently stops applying - the P1-TSK-015 rule for exemption lists, applied to a
        // list of deliberate absences.
        assertThat(registered)
                .as("a NOT_YET_EMITTED entry naming an action that no longer exists is dead weight"
                        + " that reads as a live decision")
                .containsAll(NOT_YET_EMITTED.keySet());
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
    @DisplayName("the guard is not vacuous: it sees actions and can tell emitted from unemitted")
    void theGuardHasTeeth() {
        assertThat(actions())
                .as("the sweep must actually find the registry")
                .isNotEmpty();

        // And it must be able to distinguish the two states. Without this the assertion above
        // passes over a detector that reports everything unemitted, or everything emitted - the
        // "green while checking nothing" failure this repository has met repeatedly.
        TreeSet<String> emitted = new TreeSet<>();
        for (AuditableAction action : actions()) {
            if (isReferencedByProductionCode(action)) {
                emitted.add(action.code());
            }
        }
        assertThat(emitted)
                .as("the detector must find the actions this phase demonstrably emits")
                .contains(
                        "identity.IdentityCreated",
                        "identity.AuthenticationSucceeded",
                        "identity.SessionRevoked",
                        "identity.AuthorizationDenied",
                        "party.CustomerRegistered");
        // Derived from the map rather than listed. The first version named two constants, and one
        // of them - identity.IdentitySuspended - stopped being true the moment P1-TSK-028 built the
        // endpoint that emits it. A negative control that has to be edited whenever the codebase
        // grows is the stale-list defect this repository closes by derivation everywhere else, and
        // it fails in the direction that looks like a real defect.
        assertThat(emitted)
                .as("and must not claim the ones nothing references")
                .doesNotContainAnyElementsOf(NOT_YET_EMITTED.keySet());
    }

    // -----------------------------------------------------------------

    /**
     * Whether production code outside the declaring enum reads this constant.
     *
     * <p>A field <em>access</em>, not a name match: an enum constant is read with a {@code GETSTATIC}
     * that names the owner and the field, so this cannot be satisfied by the action appearing in a
     * comment, a string or the catalogue. The declaring enum is excluded because its own
     * {@code values()} machinery reads every constant it has.
     */
    private static boolean isReferencedByProductionCode(AuditableAction action) {
        String declaringType = ((Enum<?>) action).getDeclaringClass().getName();
        String constant = ((Enum<?>) action).name();

        for (JavaClass javaClass : productionClasses()) {
            if (javaClass.getName().equals(declaringType)) {
                continue;
            }
            for (JavaFieldAccess access : javaClass.getFieldAccessesFromSelf()) {
                if (access.getTargetOwner().getName().equals(declaringType)
                        && access.getName().equals(constant)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static java.util.List<AuditableAction> actions() {
        java.util.List<AuditableAction> actions = new java.util.ArrayList<>();
        for (JavaClass javaClass : productionClasses()) {
            if (!javaClass.isAssignableTo(AuditableAction.class)
                    || javaClass.getName().equals(AuditableAction.class.getName())) {
                continue;
            }
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

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
