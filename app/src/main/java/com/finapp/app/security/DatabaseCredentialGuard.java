package com.finapp.app.security;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the published local-development database credential is pointed at a
 * database that is not on this machine.
 *
 * <h2>The threat this closes</h2>
 *
 * <p>Every credential in this repository is externalised: {@code compose.yaml},
 * {@code application.yaml}, {@code platform/build.gradle.kts} and the role-provisioning script all
 * read an environment variable and fall back to one marked local default
 * ({@code SECRET_MANAGEMENT.md}). That is the right shape, and it has one silent failure mode -
 * <strong>the fallback is what you get when nobody sets the variable</strong>.
 *
 * <p>So the documented bypass of the whole scheme is "forget to set it". A deployment whose
 * database was provisioned from this repository's own init script would then be running on a
 * password that is published on the internet, and nothing would say so: the application starts,
 * the pool connects, readiness reports UP. That is not a hypothetical shape - it is the ordinary
 * outcome of a first deployment made by someone who has only ever run it locally.
 *
 * <p>{@code DEFINITION_OF_DONE.md} {@code DOD-SEC} requires that no control is "bypassable by a
 * documented path". Naming the value honestly is not a control; a name stops nothing. This is the
 * control: the marked default is usable <strong>only</strong> against loopback.
 *
 * <h2>Why loopback, rather than a profile</h2>
 *
 * <p>A profile would work by asking the deployment to declare itself, which is the same class of
 * mechanism as the environment variable it is meant to backstop - one more thing to forget, and
 * forgetting it fails open. The database's address is not a declaration. It is the fact of the
 * matter about where the data actually is, it is already configured, and it cannot be omitted.
 *
 * <h2>Fail closed</h2>
 *
 * <p>If the password is the marked default and the URL yields no host this refuses to start, rather
 * than assuming a URL shape it did not recognise is harmless. Failing open would make an
 * unanticipated URL form a documented path around the control, which is the exact property
 * {@code DOD-SEC} forbids. Failing closed is loud, immediate and fixable in one line.
 *
 * <p>The message never contains the password. It does not need to - the value is published, and
 * echoing a credential into a startup log is how {@code INV-AUD-02} gets violated by the one
 * component whose whole purpose is credential hygiene.
 */
@Component
public class DatabaseCredentialGuard {

    /**
     * The one sanctioned local-development fallback — since `P5-TSK-002` a reference to the
     * repository's single Java literal of it ({@link ConfinedCredential#MARKED_LOCAL_DEFAULT});
     * until then this constant and {@code MfaKey}'s were two literals of the same value, which is
     * exactly the copies-drift shape the generalisation exists to close. Kept as a public
     * constant here because the guard's tests and callers have referenced it by this name since
     * `P0-TSK-031`.
     *
     * <p><strong>On the name.</strong> Calling this {@code PASSWORD} would fail the build:
     * {@code secretsAreWrapped} rejects any production field whose name says it holds a secret
     * unless it is {@code Sensitive<?>} (ADR-0019). That rule is working, and this is not a way
     * around it - the name is accurate. This is not a credential. It is the published marker that
     * identifies a value as deliberately <em>not</em> one, which is why it can be compared,
     * committed and printed in documentation. A real credential placed in this class would be
     * named for what it is and would fail the build, as it should.
     *
     * <p>Six files use this value and none of them can import it - they are YAML, Kotlin and SQL.
     * They are held to it by {@code CommittedConfigurationHoldsNoSecretTest}, which fails the build
     * if any of them drifts to a different local default. That check, not this constant, is what
     * makes the value single-sourced.
     */
    public static final String MARKED_LOCAL_DEFAULT = ConfinedCredential.MARKED_LOCAL_DEFAULT;

    /**
     * The variable to set. Named in the failure message so the fix needs no documentation.
     *
     * <p><strong>This field was called {@code APP_PASSWORD_VARIABLE} and the build rejected it</strong>
     * - {@code secretsAreWrapped} matched the word {@code password} in the name. It was a false
     * positive: the field holds the <em>name</em> of a variable, not a credential.
     *
     * <p>It was renamed rather than exempted, and that was the harder of the two options on
     * purpose. The obvious fix is to teach the rule that a name ending in {@code VARIABLE},
     * {@code PROPERTY} or {@code HEADER} refers to a secret rather than holding one - which is
     * true, and which would also let {@code PASSWORD_PROPERTY = "hunter2"} through for ever after.
     * {@code secretsAreWrapped} has no exemption set at all, and the first exemption is where that
     * property ends. {@code DEFINITION_OF_DONE.md} §3 is unambiguous: a change is not done if a
     * security control was weakened so a test would pass.
     *
     * <p>The cost is a slightly worse name in one place. That is the right side of the trade, and
     * it is recorded here so the next person to meet this rule does not quietly pick the other one.
     */
    static final String REQUIRED_ENVIRONMENT_VARIABLE = "FINAPP_DB_APP_PASSWORD";

    DatabaseCredentialGuard(Environment environment) {
        verify(DatabaseEndpoint.url(environment), DatabaseEndpoint.password(environment));
    }

    // The effective-URL and effective-password lookups, and the host parsing below, moved to
    // DatabaseEndpoint when TransportSecurityGuard needed the same "is this on this machine?"
    // predicate. Two copies of that would drift, and this repository has found drift between
    // duplicated definitions often enough to treat it as the default outcome. The reasoning that
    // was here - Hikari's own properties are bound afterwards and win, so a guard reading only
    // the generic pair inspects a value nothing connects with - lives there with the code.

    /**
     * @throws IllegalStateException if the marked local default is aimed off this machine
     */
    public static void verify(String jdbcUrl, String password) {
        if (!MARKED_LOCAL_DEFAULT.equals(password)) {
            // Somebody supplied a credential. Whether it is a good one is not this guard's
            // question, and pretending to judge that would be theatre.
            return;
        }

        List<String> hosts = DatabaseEndpoint.hostsOf(jdbcUrl);
        if (hosts.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: the marked local-development database credential is in use,"
                            + " and no host could be read from spring.datasource.url, so this"
                            + " cannot be shown to be a local database. Set "
                            + REQUIRED_ENVIRONMENT_VARIABLE
                            + ". See docs/architecture/SECRET_MANAGEMENT.md.");
        }

        List<String> remote = hosts.stream().filter(host -> !DatabaseEndpoint.isLoopback(host)).toList();
        if (!remote.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: the marked local-development database credential is in use"
                            + " against a database that is not on this machine "
                            + remote
                            + ". That credential is published in this repository and is not a"
                            + " secret. Set "
                            + REQUIRED_ENVIRONMENT_VARIABLE
                            + ". See docs/architecture/SECRET_MANAGEMENT.md.");
        }
    }

}
