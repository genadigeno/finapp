package com.finapp.app.security;

import static com.finapp.app.security.DatabaseCredentialGuard.MARKED_LOCAL_DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The marked local default is usable against loopback and nowhere else.
 *
 * <p>{@code DOD-SEC} asks for a negative test on every control, and for the control not to be
 * bypassable by a documented path. The documented path here is the one nobody chooses on purpose:
 * not setting {@code FINAPP_DB_APP_PASSWORD}. These tests are what make that path closed rather
 * than merely discouraged.
 */
class DatabaseCredentialGuardTest {

    private static final String REAL_CREDENTIAL = "supplied-by-the-deployment";

    @ParameterizedTest
    @DisplayName("the marked default is accepted against a database on this machine")
    @ValueSource(
            strings = {
                "jdbc:postgresql://127.0.0.1:5432/finapp",
                "jdbc:postgresql://localhost:5432/finapp",
                "jdbc:postgresql://LOCALHOST:5432/finapp",
                "jdbc:postgresql://127.0.0.1:1/absent",
                // 127.0.0.0/8 is entirely loopback, not just .1 - a test fixture using .0.0.2 is
                // as local as one using .0.0.1 and must not be told otherwise.
                "jdbc:postgresql://127.9.9.9:5432/finapp",
                "jdbc:postgresql://[::1]:5432/finapp",
                // Failover list, both ends local.
                "jdbc:postgresql://127.0.0.1:5432,localhost:5433/finapp",
                // Query parameters must not be mistaken for part of the host.
                "jdbc:postgresql://127.0.0.1:5432/finapp?ApplicationName=finapp"
            })
    void localIsAllowed(String url) {
        assertThatCode(() -> DatabaseCredentialGuard.verify(url, MARKED_LOCAL_DEFAULT))
                .as("local development must keep working with no environment variable set")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("the marked default is refused against a database that is not on this machine")
    @ValueSource(
            strings = {
                "jdbc:postgresql://db.internal:5432/finapp",
                "jdbc:postgresql://10.0.3.14:5432/finapp",
                // A private address is not a local one. The credential is published; who else is
                // on the VPC is not this guard's assumption to make.
                "jdbc:postgresql://192.168.1.20:5432/finapp",
                // The failover trap: first host local, second anywhere. Checking only the first
                // would pass this.
                "jdbc:postgresql://127.0.0.1:5432,db.internal:5432/finapp",
                // A host that merely starts with the loopback digits.
                "jdbc:postgresql://127.0.0.1.example.com:5432/finapp",
                // Credentials in the URL must not be parsed as the host.
                "jdbc:postgresql://user:pw@db.internal:5432/finapp"
            })
    void remoteIsRefused(String url) {
        assertThatThrownBy(() -> DatabaseCredentialGuard.verify(url, MARKED_LOCAL_DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining(DatabaseCredentialGuard.REQUIRED_ENVIRONMENT_VARIABLE);
    }

    @ParameterizedTest
    @DisplayName("a URL with no readable host is refused, not assumed harmless")
    @ValueSource(strings = {"jdbc:postgresql:finapp", "not a url at all", "jdbc:postgresql:///"})
    void failsClosedWhenTheHostCannotBeRead(String url) {
        // Fail closed. Failing open would make any URL shape this parser does not recognise a
        // documented path around the control, which is the property DOD-SEC forbids.
        assertThatThrownBy(() -> DatabaseCredentialGuard.verify(url, MARKED_LOCAL_DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no host could be read");
    }

    @Test
    @DisplayName("a null URL is refused rather than passed through")
    void failsClosedOnAMissingUrl() {
        assertThatThrownBy(() -> DatabaseCredentialGuard.verify(null, MARKED_LOCAL_DEFAULT))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @DisplayName("a supplied credential is this guard's business nowhere at all")
    @ValueSource(
            strings = {
                "jdbc:postgresql://db.internal:5432/finapp",
                "jdbc:postgresql://127.0.0.1:5432/finapp"
            })
    void aSuppliedCredentialIsNeverRefused(String url) {
        // The guard's subject is one published value, not password quality. Judging strength here
        // would be theatre: it cannot tell a strong password from a second published one.
        assertThatCode(() -> DatabaseCredentialGuard.verify(url, REAL_CREDENTIAL))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the refusal never prints the credential")
    void theMessageDoesNotEchoTheValue() {
        // The value is published, so this is not a disclosure - it is the habit. A component whose
        // job is credential hygiene must not be the one that writes a password into a startup log
        // (INV-AUD-02), because the next credential it handles may not be published.
        assertThatThrownBy(
                        () ->
                                DatabaseCredentialGuard.verify(
                                        "jdbc:postgresql://db.internal:5432/finapp",
                                        MARKED_LOCAL_DEFAULT))
                .hasMessageNotContaining(MARKED_LOCAL_DEFAULT);
    }

    @Test
    @DisplayName("host parsing is asserted directly, so a passing case cannot hide a parse bug")
    void hostsAreReadFromTheUrl() {
        // Without this, every "allowed" case above could be passing because the parser returned
        // the wrong host that happened to look local, or the right one by luck.
        assertThat(
                        DatabaseCredentialGuard.hostsOf(
                                "jdbc:postgresql://user:pw@a.example:5432,b.example:5433/db?x=1"))
                .containsExactly("a.example", "b.example");
        assertThat(DatabaseCredentialGuard.hostsOf("jdbc:postgresql://[::1]:5432/db"))
                .containsExactly("::1");
        assertThat(DatabaseCredentialGuard.hostsOf("jdbc:postgresql:finapp")).isEmpty();
    }
}
