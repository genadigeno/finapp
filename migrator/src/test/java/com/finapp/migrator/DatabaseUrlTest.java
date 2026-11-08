package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;

/** A migrator's URL is read as the driver reads it, and anything that could move it is refused ({@code P15-TSK-004}). */
@DisplayName("the migrator's database URL (P15-TSK-004)")
class DatabaseUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://127.0.0.1:5432/finapp",
        "jdbc:postgresql://localhost/finapp",
        "jdbc:postgresql://[::1]:5432/finapp",
        "jdbc:postgresql://127.0.0.1:5432/finapp?connectTimeout=10"
    })
    @DisplayName("loopback needs no TLS")
    void loopback(String url) {
        assertThat(DatabaseUrl.verify(url)).isTrue();
    }

    @Test
    @DisplayName("off this machine, verify-full is what is accepted")
    void verifyFull() {
        assertThat(DatabaseUrl.verify("jdbc:postgresql://db.internal:5432/finapp?sslmode=verify-full&sslrootcert=/etc/ca.pem"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://db.internal:5432/finapp",
        "jdbc:postgresql://db.internal:5432/finapp?sslmode=require",
        "jdbc:postgresql://db.internal:5432/finapp?sslmode=verify-ca",
        "jdbc:postgresql://db.internal:5432/finapp?sslmode=VERIFY-FULL",
        "jdbc:postgresql://localhost.db.internal:5432/finapp",
        "jdbc:postgresql://127.0.0.1.db.internal:5432/finapp"
    })
    @DisplayName("off this machine, anything short of verify-full is refused, a name that merely begins like loopback included")
    void plaintextOffLoopback(String url) {
        assertThatThrownBy(() -> DatabaseUrl.verify(url)).isInstanceOf(RefusedConfiguration.class).hasMessageContaining("verify-full");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://localhost:5432/finapp?host=db.internal",
        "jdbc:postgresql://localhost:5432/finapp?PGHOST=db.internal",
        "jdbc:postgresql://localhost:5432/finapp?service=production",
        "jdbc:postgresql://db.internal/finapp?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory",
        "jdbc:postgresql://db.internal/finapp?sslmode=verify-full&sslhostnameverifier=x",
        "jdbc:postgresql://localhost/finapp?options=-c%20lock_timeout=0",
        "jdbc:postgresql://localhost/finapp?user=postgres",
        "jdbc:postgresql://localhost/finapp?password=hunter2",
        "jdbc:postgresql://db.internal/finapp?SSLMODE=verify-full"
    })
    @DisplayName("a parameter that moves the host, undoes the verification, resets the bound or carries a credential is refused by name")
    void parametersThatChangeWhatTheUrlMeans(String url) {
        assertThatThrownBy(() -> DatabaseUrl.verify(url))
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("does not accept");
    }

    @Test
    @DisplayName("the driver itself reads a host parameter over the authority, which is why the refusal exists")
    void theDriverReallyReadsTheParameter() {
        assertThat(Driver.parseURL("jdbc:postgresql://localhost:5432/finapp?host=db.internal", null).getProperty("PGHOST"))
                .isEqualTo("db.internal");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://127.0.0.1,db.internal:5432/finapp",
        "jdbc:postgresql:finapp",
        "jdbc:mysql://127.0.0.1/finapp",
        "postgresql://127.0.0.1/finapp",
        "jdbc:postgresql://127.0.0.1/a/b",
        "   "
    })
    @DisplayName("a URL with two hosts, or one the driver would not read this way, is refused")
    void unreadable(String url) {
        assertThatThrownBy(() -> DatabaseUrl.verify(url)).isInstanceOf(RefusedConfiguration.class);
    }

    @Test
    @DisplayName("a parameter given twice is refused: which one wins is the driver's detail")
    void aParameterTwice() {
        assertThatThrownBy(() -> DatabaseUrl.verify("jdbc:postgresql://db.internal/finapp?sslmode=disable&sslmode=verify-full"))
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("the password is required, has no default, is refused as the marked local credential off loopback and never prints")
    void thePassword() {
        String local = "jdbc:postgresql://127.0.0.1/finapp";
        String remote = "jdbc:postgresql://db.internal/finapp?sslmode=verify-full";
        assertThatThrownBy(() -> MigratorEnvironment.from(Map.of("FINAPP_DB_URL", local)))
                .isInstanceOf(RefusedConfiguration.class);
        assertThatThrownBy(() -> MigratorEnvironment.from(Map.of(
                        "FINAPP_DB_URL", remote, "FINAPP_DB_MIGRATOR_PASSWORD", MigratorEnvironment.MARKED_LOCAL_DEFAULT)))
                .isInstanceOf(RefusedConfiguration.class);
        assertThat(MigratorEnvironment.from(Map.of(
                                "FINAPP_DB_URL", local, "FINAPP_DB_MIGRATOR_PASSWORD", MigratorEnvironment.MARKED_LOCAL_DEFAULT))
                        .user())
                .as("the build's default, and the local credential is accepted on loopback as the build accepts it")
                .isEqualTo("finapp_migrator");
        assertThat(MigratorEnvironment.from(Map.of("FINAPP_DB_URL", local, "FINAPP_DB_MIGRATOR_PASSWORD", "s3cret")).toString())
                .doesNotContain("s3cret");
    }

    @Test
    @DisplayName("no URL at all is refused rather than defaulted to a developer's database")
    void noUrl() {
        assertThatThrownBy(() -> MigratorEnvironment.from(Map.of("FINAPP_DB_MIGRATOR_PASSWORD", "x")))
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("required");
    }
}
