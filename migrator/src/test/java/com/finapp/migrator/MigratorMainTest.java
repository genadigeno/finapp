package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

/** What the entrypoint refuses before it opens a connection, and what it never prints ({@code P15-TSK-004}). */
@DisplayName("the migrator's entrypoint refuses before it connects (P15-TSK-004)")
class MigratorMainTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    @TempDir
    Path sql;

    private static final Map<String, String> LOCAL = Map.of(
            "FINAPP_DB_URL", "jdbc:postgresql://127.0.0.1:1/finapp", "FINAPP_DB_MIGRATOR_PASSWORD", "a-throwaway-password");

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private void aDirectoryForEverySchema(boolean withAScript) throws IOException {
        for (String schema : MigrationPlan.schemasInBuildOrder()) {
            Files.createDirectories(sql.resolve(schema));
            if (withAScript) {
                Files.writeString(sql.resolve(schema).resolve("V001__x.sql"), "SELECT 1;\n");
            }
        }
    }

    @Test
    @DisplayName("a server's error detail reaches no exception of the migrator's or the bootstrap's: it would be row data in the Job's log")
    void aFailedStatementCarriesNoRowData() {
        PGSimpleDataSource migrator = (PGSimpleDataSource) MigratorEnvironment.from(LOCAL).dataSource();
        PGSimpleDataSource bootstrap = (PGSimpleDataSource) RoleBootstrapMain.dataSource("jdbc:postgresql://127.0.0.1:1/finapp", "master", "x");
        ServerErrorMessage server = new ServerErrorMessage(
                "SERROR\0C23505\0Mduplicate key value violates unique constraint \"k\"\0DKey (value)=(4242) already exists.\0\0");
        for (PGSimpleDataSource dataSource : List.of(migrator, bootstrap)) {
            assertThat(dataSource.getLogServerErrorDetail()).isFalse();
            assertThat(new PSQLException(server, dataSource.getLogServerErrorDetail()).getMessage())
                    .as("the exception that printStackTrace writes to the Job's log")
                    .contains("duplicate key value")
                    .doesNotContain("4242");
        }
        assertThat(new PSQLException(server, true).getMessage())
                .as("the driver's default, which is why this is set")
                .contains("4242");
    }

    @ParameterizedTest
    @MethodSource("tlsRefusals")
    @DisplayName("a TLS connection that cannot be verified is refused, not retried: the CA, the host, the root certificate, no TLS at all")
    void aTlsFailureIsRefused(SQLException failure) throws IOException {
        assertThat(FailureClass.beforeAnythingRan(failure)).isEqualTo(ExitCode.REFUSED);
        assertThat(FailureClass.of(failure)).as("as the driver reports it, class 08: what it would have been").isEqualTo(FailureClass.UNAVAILABLE);
    }

    private static Stream<SQLException> tlsRefusals() {
        return Stream.of(
                new PSQLException("SSL error: PKIX path building failed", PSQLState.CONNECTION_FAILURE,
                        new SSLHandshakeException("PKIX path building failed", new CertificateException("unable to find valid certification path"))),
                new PSQLException("SSL error: no shared cipher", PSQLState.CONNECTION_FAILURE, new SSLHandshakeException("no cipher suites in common")),
                new PSQLException("The hostname db could not be verified by hostnameverifier PgjdbcHostnameVerifier.", PSQLState.CONNECTION_FAILURE),
                new PSQLException("Could not open SSL root certificate file /etc/ca.pem.", PSQLState.CONNECTION_FAILURE, new FileNotFoundException("/etc/ca.pem")),
                new PSQLException("Loading the SSL root certificate /etc/ca.pem into a TrustManager failed.", PSQLState.CONNECTION_FAILURE, new CertificateException("garbage")),
                new PSQLException("The server does not support SSL.", PSQLState.CONNECTION_REJECTED));
    }

    @ParameterizedTest
    @MethodSource("networkFailures")
    @DisplayName("a connection dropped during the handshake, or refused, is transient: another attempt may succeed")
    void aDroppedConnectionIsTransient(SQLException failure) {
        assertThat(FailureClass.beforeAnythingRan(failure)).isEqualTo(ExitCode.TRANSIENT);
    }

    private static Stream<SQLException> networkFailures() {
        return Stream.of(
                new PSQLException("SSL error: Remote host terminated the handshake", PSQLState.CONNECTION_FAILURE,
                        new SSLHandshakeException("Remote host terminated the handshake", new EOFException("SSL peer shut down incorrectly"))),
                new PSQLException("SSL error: Connection reset", PSQLState.CONNECTION_FAILURE, new SSLException("Connection reset", new SocketException("Connection reset"))),
                new PSQLException("The connection attempt failed.", PSQLState.CONNECTION_UNABLE_TO_CONNECT, new ConnectException("Connection refused")));
    }

    @Test
    @DisplayName("any argument is refused: there is no second thing this process can be asked to do, clean included")
    void takesNoArguments() {
        assertThat(MigratorMain.run(new String[] {"clean"}, LOCAL, sql, out)).isEqualTo(ExitCode.REFUSED);
        assertThat(output()).contains("result=REFUSED");
    }

    @Test
    @DisplayName("no URL, no run: exit 12, and the code a FailJob rule can name")
    void noUrl() {
        ExitCode exit = MigratorMain.run(new String[0], Map.of("FINAPP_DB_MIGRATOR_PASSWORD", "x"), sql, out);
        assertThat(exit).isEqualTo(ExitCode.REFUSED);
        assertThat(exit.getCode()).isEqualTo(12);
    }

    @Test
    @DisplayName("a schema with no directory is a refused image, not 'no migrations to apply'")
    void aMissingDirectoryIsRefused() {
        assertThat(MigratorMain.run(new String[0], LOCAL, sql.resolve("nowhere"), out)).isEqualTo(ExitCode.REFUSED);
        assertThat(output()).contains("cannot read");
    }

    @Test
    @DisplayName("a schema's directory with no scripts in it is refused too: Flyway would call that nothing to do")
    void anEmptyDirectoryIsRefused() throws IOException {
        aDirectoryForEverySchema(false);
        assertThat(MigratorMain.run(new String[0], LOCAL, sql, out)).isEqualTo(ExitCode.REFUSED);
        assertThat(output()).contains("no migrations in");
    }

    @Test
    @DisplayName("a database that is not there is transient, and its failure prints neither the password nor the URL's value")
    void anUnreachableDatabaseIsTransientAndPrintsNoSecret() throws IOException {
        aDirectoryForEverySchema(true);
        ExitCode exit = MigratorMain.run(new String[0], LOCAL, sql, out);
        assertThat(exit).as("nothing listens on port 1").isEqualTo(ExitCode.TRANSIENT);
        assertThat(output()).doesNotContain("a-throwaway-password").doesNotContain("127.0.0.1:1");
    }
}
