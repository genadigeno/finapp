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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No production type holds session state (`P1-TSK-013`, {@code INV-IDN-03}).
 *
 * <h2>This is the task's acceptance criterion, and it is a build rule because discipline is not one</h2>
 *
 * <p>{@code INV-IDN-03} requires a revoked session to be refused <strong>on the next request, on
 * every instance</strong>. That holds by construction only while the database is the single
 * authority: a process-local cache in front of the session store turns revocation into
 * <em>"refused once every instance's cache has expired"</em>, which is not revocation, and
 * <em>"log out everywhere"</em> after a suspected compromise becomes a promise the architecture
 * cannot keep.
 *
 * <p>ADR-0030 chose PostgreSQL over Redis for exactly this, and the transition recorded the
 * temptation as risk **R7** — <em>"session state tempts process-local storage, exactly the shape
 * ADR-0024's rules do not catch"</em>. They do not catch it because a cache need not be static, need
 * not be a lock, and need not be mutable in any way ADR-0024's four patterns recognise: an instance
 * field holding a {@code Map<SessionToken, Session>} on a singleton bean is all of it.
 *
 * <p>So this rule catches the shape those cannot: <strong>a field that holds sessions</strong>,
 * however it is declared.
 *
 * <h2>What it looks for, and what it deliberately does not</h2>
 *
 * <p>Any production field whose type — or whose generic parameters — mention a session type, other
 * than the aggregate itself and the components that name it in a signature. It does not attempt to
 * recognise "a cache" by name or by library, because the next one will be called something else and
 * will come from somewhere else.
 *
 * <p>It cannot see a cache built out of {@code Object}, and that is stated rather than implied. What
 * it makes impossible is the version somebody actually writes.
 */
@Tag("architecture")
@DisplayName("no production type holds session state (P1-TSK-013)")
class NoProcessLocalSessionStateTest {

    /**
     * The type whose retention is a cache.
     *
     * <p>{@code Session} only. The first version also listed {@code SessionId} and
     * {@code SessionToken} and matched by substring, which flagged {@code Session.status} and
     * {@code Session.id} - because {@code SessionStatus} and {@code SessionId} <em>contain</em> the
     * string {@code Session}. Word-boundary matching on the full generic signature, and one type.
     *
     * <p>An identifier or a token held in a field is not a cache: neither answers whether a session
     * is live, so neither can make a revoked session usable. Retaining the <strong>aggregate</strong>
     * is what does, because that is the object a consumer would then read {@code isLiveAt} from
     * instead of asking the database.
     */
    private static final String SESSION_TYPE = "com.finapp.identity.Session";

    /**
     * Fields that legitimately mention a session type without retaining one.
     *
     * <p>An entry here is a claim that a field mentioning a session does not retain one, which is
     * exactly the claim this rule exists to stop being made informally. Making it formally, in one
     * place, with the reason written down, is the point of the list rather than a way around it.
     *
     * <p><strong>One entry, and it is a return value.</strong> {@code SessionRotation.Rotated} is
     * the result of a single call — the new session and the token to hand the client — constructed
     * per rotation and discarded when the caller is done with it. It cannot make a revoked session
     * usable, because nothing consults it on a later request; a cache can, because that is what a
     * cache is for.
     *
     * <p>The rule cannot tell a return value from a cache by inspecting a field, and it should not
     * try: the alternative considered was to flag only <em>collections</em> of sessions, which would
     * have let a singleton hold one session indefinitely. Narrowing the detector to admit this would
     * have cost more than naming it.
     */
    private static final Set<String> PERMITTED =
            Set.of("com.finapp.identity.SessionRotation$Rotated.session");

    @Test
    @DisplayName("every exemption still names a field that exists")
    void theExemptionsAreNotStale() {
        // A permitted entry naming a field that has been renamed or deleted is an exemption nobody
        // can evaluate, and it silently stops protecting anything. The same reasoning P0-TSK-041
        // applied to its two exemptions: proven load-bearing, or removed.
        assertThat(allSessionMentioningFields())
                .as("an exemption that names nothing is an exemption that has stopped applying")
                .containsAll(PERMITTED);
    }

    @Test
    @DisplayName("no field anywhere in production code retains a session")
    void nothingHoldsASession() {
        assertThat(fieldsHoldingSessions())
                .as(
                        "a field holding sessions is a process-local cache, and INV-IDN-03 becomes"
                            + " 'refused once every instance's cache has expired' - which is not"
                            + " revocation (ADR-0030, transition risk R7)")
                .isEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees every module, and it can recognise a session field")
    void theGuardSeesEveryModule() {
        Set<String> analysed = new TreeSet<>();
        productionClasses().stream()
                .map(ProductionModules::of)
                .filter(Objects::nonNull)
                .forEach(analysed::add);

        assertThat(analysed)
                .as("the sweep must see every module that has production code, or it bounds only"
                        + " some of them")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());

        // And it must actually be able to recognise the shape. Without this the rule above passes
        // over a detector that matches nothing - the "green while checking nothing" failure this
        // repository has met repeatedly.
        JavaClasses fixture = new ClassFileImporter().importClasses(HoldsSessions.class);
        assertThat(sessionFieldsIn(fixture))
                .as("the detector can see a field that retains sessions")
                .isNotEmpty();
    }

    /** What this rule exists to make impossible: a session cache on a singleton. */
    @SuppressWarnings("unused")
    private static final class HoldsSessions {
        private final java.util.Map<String, com.finapp.identity.Session> cache =
                new java.util.HashMap<>();
    }

    // -----------------------------------------------------------------

    private static List<String> fieldsHoldingSessions() {
        return sessionFieldsIn(productionClasses());
    }

    /** Every field mentioning a session, exemptions included — for the staleness check. */
    private static List<String> allSessionMentioningFields() {
        List<String> mentioning = new ArrayList<>();
        for (JavaClass javaClass : productionClasses()) {
            if (javaClass.getName().equals(SESSION_TYPE)) {
                continue;
            }
            for (JavaField field : javaClass.getFields()) {
                if (retainsASession(field)) {
                    mentioning.add(field.getFullName());
                }
            }
        }
        return mentioning;
    }

    private static List<String> sessionFieldsIn(JavaClasses classes) {
        List<String> holding = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            // The aggregate's own fields are not a cache of itself.
            if (javaClass.getName().equals(SESSION_TYPE)) {
                continue;
            }
            for (JavaField field : javaClass.getFields()) {
                if (PERMITTED.contains(field.getFullName()) || !retainsASession(field)) {
                    continue;
                }
                holding.add(field.getFullName());
            }
        }
        return holding;
    }

    /**
     * Whether a field retains sessions, reading its <strong>generic</strong> signature.
     *
     * <p>The raw type is not enough and that is the whole difficulty: a
     * {@code Map<String, Session>} has a raw type of {@code Map}, which mentions no session at all.
     * The cache lives in the parameters, so the parameters are what must be read.
     */
    private static boolean retainsASession(JavaField field) {
        String signature = field.reflect().getGenericType().getTypeName();
        // Word boundary, so SessionStatus and SessionId are not mistaken for Session. Substring
        // matching flagged both on the first run, which is how the difference was noticed.
        return SESSION_BOUNDARY.matcher(signature).find();
    }

    private static final java.util.regex.Pattern SESSION_BOUNDARY =
            java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(SESSION_TYPE) + "\\b");

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
