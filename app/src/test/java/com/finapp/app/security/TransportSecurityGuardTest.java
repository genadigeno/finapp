package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A database off this machine is reached with verified TLS, or not at all.
 *
 * <p>{@code DOD-SEC} asks for a negative test on every control and for the control not to be
 * bypassable by a documented path. Here the documented path is the driver's own default: no
 * {@code sslmode} at all, which connects unencrypted and reports nothing. That was measured against
 * this repository's container, not assumed - unset and {@code prefer} both connected in the clear.
 */
class TransportSecurityGuardTest {

    private static final Optional<String> NOTHING_CONFIGURED = Optional.empty();

    @ParameterizedTest
    @DisplayName("a database on this machine needs no TLS, whatever sslmode says")
    @ValueSource(
            strings = {
                "jdbc:postgresql://127.0.0.1:5432/finapp",
                "jdbc:postgresql://localhost:5432/finapp",
                "jdbc:postgresql://127.0.0.1:1/absent",
                "jdbc:postgresql://[::1]:5432/finapp",
                "jdbc:postgresql://127.0.0.1:5432,localhost:5433/finapp"
            })
    void loopbackIsExempt(String url) {
        // Loopback does not leave the host. Requiring TLS here would make every developer
        // provision certificates for a container, and a setup step that elaborate is one people
        // work around - which costs more security than it buys.
        assertThatCode(() -> TransportSecurityGuard.verify(url, NOTHING_CONFIGURED))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("a remote database with no sslmode is refused - the driver's silent default")
    @ValueSource(
            strings = {
                "jdbc:postgresql://db.internal:5432/finapp",
                "jdbc:postgresql://10.0.3.14:5432/finapp",
                "jdbc:postgresql://192.168.1.20:5432/finapp",
                // The failover trap: first host local, second anywhere.
                "jdbc:postgresql://127.0.0.1:5432,db.internal:5432/finapp"
            })
    void remoteWithoutTlsIsRefused(String url) {
        assertThatThrownBy(() -> TransportSecurityGuard.verify(url, NOTHING_CONFIGURED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("connects unencrypted")
                .hasMessageContaining(TransportSecurityGuard.REQUIRED_MODE);
    }

    /** Every driver mode that is not {@link TransportSecurityGuard#REQUIRED_MODE}. */
    private static final Set<String> REFUSED_MODES =
            Set.of("disable", "allow", "prefer", "require", "verify-ca");

    @ParameterizedTest
    @DisplayName("a mode that encrypts but does not authenticate the server is refused")
    @ValueSource(strings = {"disable", "allow", "prefer", "require", "verify-ca"})
    void weakerModesAreRefused(String mode) {
        // `require` encrypts and verifies nothing, so it stops passive eavesdropping and not an
        // active attacker presenting their own certificate. `verify-ca` checks the issuer but not
        // the hostname, so it accepts a valid certificate issued for a different host. On the path
        // to a financial database, neither is the bar.
        assertThatThrownBy(
                        () ->
                                TransportSecurityGuard.verify(
                                        "jdbc:postgresql://db.internal:5432/finapp",
                                        Optional.of(mode)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not verify the server's certificate");
    }

    @Test
    @DisplayName("every mode the driver defines is either the required one or proven refused")
    void theModeVocabularyIsTheDriversOwn() {
        // The list in weakerModesAreRefused happens to be exactly the driver's other five. Nothing
        // checked that, so a driver upgrade adding a mode would leave it silently untested - the
        // "list that went stale" defect this repository has found in CI, in a coverage guard and in
        // a privilege check. Derived from the driver, it cannot rot: a new mode fails the build and
        // asks whether it verifies the server.
        // Reflection, because the driver is a runtime-only dependency: it is on the test runtime
        // classpath and deliberately not on the compile one, and widening that to reference an
        // enum would put a driver type in front of every test in this module.
        Set<String> defined = new TreeSet<>();
        try {
            Class<?> sslMode = Class.forName("org.postgresql.jdbc.SslMode");
            java.lang.reflect.Field value = sslMode.getField("value");
            for (Object mode : sslMode.getEnumConstants()) {
                defined.add((String) value.get(mode));
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "org.postgresql.jdbc.SslMode was not found on the test runtime classpath, so"
                            + " this guard is checking nothing. If the driver moved or was renamed,"
                            + " that is the thing to look at - not this assertion.",
                    e);
        }

        assertThat(defined)
                .as("the driver must actually define modes, or the comparison below is vacuous")
                .isNotEmpty();

        Set<String> accounted = new TreeSet<>(REFUSED_MODES);
        accounted.add(TransportSecurityGuard.REQUIRED_MODE);

        assertThat(defined)
                .as("a driver mode this test does not classify is a mode nobody decided about")
                .isEqualTo(accounted);
    }

    @Test
    @DisplayName("a database on this machine is exempt even with a weak mode configured")
    void loopbackIsExemptWhateverTheModeSays() {
        // The exemption is about where the database is, not about what was configured. Without
        // this, narrowing the exemption to "loopback AND nothing configured" would pass every
        // other test here while breaking a developer who had set sslmode for another reason.
        assertThatCode(
                        () ->
                                TransportSecurityGuard.verify(
                                        "jdbc:postgresql://127.0.0.1:5432/finapp?sslmode=disable",
                                        Optional.of("prefer")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verify-full is accepted, so the rule is satisfiable")
    void verifyFullIsAccepted() {
        // The positive control. Without it the rule could be refusing everything, and every
        // assertion above would still pass.
        assertThatCode(
                        () ->
                                TransportSecurityGuard.verify(
                                        "jdbc:postgresql://db.internal:5432/finapp",
                                        Optional.of("verify-full")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the mode is matched case-insensitively, as the driver reads it")
    void caseDoesNotMatter() {
        assertThatCode(
                        () ->
                                TransportSecurityGuard.verify(
                                        "jdbc:postgresql://db.internal:5432/finapp",
                                        Optional.of("VERIFY-FULL")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("a URL with no readable host is refused, not assumed local")
    @ValueSource(strings = {"jdbc:postgresql:finapp", "not a url at all"})
    void failsClosedWhenTheHostCannotBeRead(String url) {
        assertThatThrownBy(() -> TransportSecurityGuard.verify(url, NOTHING_CONFIGURED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sslmode in the URL query string is read, not only the Hikari property")
    void theModeIsReadFromTheUrl() {
        // Both are real ways to set it. A guard reading only one is bypassable through the other
        // while looking correct - which is the defect this class's sibling actually had.
        assertThat(
                        TransportSecurityGuard.fromUrl(
                                "jdbc:postgresql://db.internal:5432/finapp?sslmode=verify-full&x=1"))
                .contains("verify-full");
        assertThat(TransportSecurityGuard.fromUrl("jdbc:postgresql://db.internal:5432/finapp"))
                .isEmpty();
        assertThat(
                        TransportSecurityGuard.fromUrl(
                                "jdbc:postgresql://db.internal:5432/finapp?ApplicationName=finapp"))
                .isEmpty();
    }

    @Test
    @DisplayName("verify-full set in the URL alone satisfies the guard")
    void theUrlFormSatisfiesTheGuard() {
        // The source M3 exposed as untested: verify() must read the URL's query string itself, not
        // only the Hikari property. Dropping that source used to pass every test in this class.
        assertThatCode(
                        () ->
                                TransportSecurityGuard.verify(
                                        "jdbc:postgresql://db.internal:5432/finapp?sslmode=verify-full",
                                        NOTHING_CONFIGURED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a weak mode in the URL is refused even when the property says verify-full")
    void everyConfiguredSourceMustVerify() {
        // Precedence-independent by construction. `sslmode` can come from the URL or from a
        // data-source property, and the URL was MEASURED to win in both directions - so trusting
        // that would make this control correct only for as long as a driver implementation detail
        // holds. Requiring every configured source to say the same safe thing cannot be wrong
        // about which one the driver picks.
        assertThatThrownBy(
                        () ->
                                TransportSecurityGuard.verify(
                                        "jdbc:postgresql://db.internal:5432/finapp?sslmode=disable",
                                        Optional.of("verify-full")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not verify");
    }

    @Test
    @DisplayName("and the reverse: a weak property is refused even when the URL says verify-full")
    void aWeakPropertyIsAlsoRefused() {
        assertThatThrownBy(
                        () ->
                                TransportSecurityGuard.verify(
                                        "jdbc:postgresql://db.internal:5432/finapp?sslmode=verify-full",
                                        Optional.of("prefer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not verify");
    }
}
