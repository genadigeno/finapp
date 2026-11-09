package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one-time bootstrap step gives a deployed database the local script's roles and is never the migration Job
 * ({@code P15-TSK-004}; {@code THREAT_MODEL.md} T7, T33). The step against a real database is {@code migrator-verify.sh}.
 */
@DisplayName("the one-time role bootstrap (P15-TSK-004)")
class RoleBootstrapTest {

    private static final String LOCAL_SCRIPT = "infra/postgres/initdb/00-roles.sql";
    private static final Pattern LOCAL_ROLE = Pattern.compile("(?is)CREATE\\s+ROLE\\s+(\\w+)\\s+(.*?);");
    private static final Pattern LOCAL_GRANT =
            Pattern.compile("GRANT ([A-Z, ]+) ON DATABASE %I TO (\\w+)");

    private static Map<String, String> passwords() {
        Map<String, String> environment = new HashMap<>();
        environment.put("FINAPP_DB_MIGRATOR_PASSWORD", "one-long-throwaway");
        environment.put("FINAPP_DB_APP_PASSWORD", "two-long-throwaway");
        environment.put("FINAPP_DB_REPORTING_PASSWORD", "three-long-throwaway");
        return environment;
    }

    private static List<String> tokens(String clause) {
        return Arrays.stream(clause.replaceAll("(?i)PASSWORD\\s+'[^']*'", " ").toUpperCase().split("[^A-Z_]+"))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    @Test
    @DisplayName("every role of the local script is created, with every one of its attributes and the same database rights")
    void everyRoleCarriesTheLocalScriptsAttributes() {
        String local = RepositoryFiles.read(LOCAL_SCRIPT);
        Map<String, List<String>> localRoles = new TreeMap<>();
        Matcher role = LOCAL_ROLE.matcher(local);
        while (role.find()) {
            localRoles.put(role.group(1), tokens(role.group(2)));
        }
        assertThat(localRoles).as("the local script's roles were read").containsOnlyKeys("finapp_migrator", "finapp_app", "finapp_reporting");
        assertThat(RoleBootstrap.ROLES.stream().map(RoleBootstrap.Role::name).collect(Collectors.toSet()))
                .as("a role the migrations grant to must exist before they run")
                .isEqualTo(localRoles.keySet());
        Set<String> created = Set.copyOf(tokens(RoleBootstrap.ATTRIBUTES));
        localRoles.forEach((name, attributes) -> assertThat(created).as("%s's local attributes", name).containsAll(attributes));
        assertThat(created)
                .as("nothing that lifts a right, and the two a managed master could otherwise hand down refused outright")
                .contains("NOSUPERUSER", "NOCREATEDB", "NOCREATEROLE", "NOINHERIT", "NOBYPASSRLS", "NOREPLICATION", "LOGIN")
                .doesNotContain("SUPERUSER", "CREATEDB", "CREATEROLE", "INHERIT", "BYPASSRLS", "REPLICATION");

        Map<String, String> localGrants = LOCAL_GRANT.matcher(local).results()
                .collect(Collectors.toMap(match -> match.group(2), match -> match.group(1)));
        assertThat(RoleBootstrap.ROLES.stream().collect(Collectors.toMap(RoleBootstrap.Role::name, RoleBootstrap.Role::databaseRights)))
                .as("the migrator creates its schemas; nothing else may")
                .isEqualTo(localGrants);
    }

    @Test
    @DisplayName("a role that exists holding a right it must not is a refusal, each right on its own")
    void anExistingRoleHoldingARightIsRefused() {
        RoleBootstrap.Standing clean = new RoleBootstrap.Standing(true, false, false, false, false, false, false, 0);
        assertThat(clean.violations()).isEmpty();
        assertThat(new RoleBootstrap.Standing(false, false, false, false, false, false, false, 0).violations()).containsExactly("NOLOGIN");
        assertThat(new RoleBootstrap.Standing(true, true, false, false, false, false, false, 0).violations()).containsExactly("SUPERUSER");
        assertThat(new RoleBootstrap.Standing(true, false, true, false, false, false, false, 0).violations()).containsExactly("CREATEDB");
        assertThat(new RoleBootstrap.Standing(true, false, false, true, false, false, false, 0).violations()).containsExactly("CREATEROLE");
        assertThat(new RoleBootstrap.Standing(true, false, false, false, true, false, false, 0).violations()).containsExactly("INHERIT");
        assertThat(new RoleBootstrap.Standing(true, false, false, false, false, true, false, 0).violations()).containsExactly("BYPASSRLS");
        assertThat(new RoleBootstrap.Standing(true, false, false, false, false, false, true, 0).violations()).containsExactly("REPLICATION");
        assertThat(new RoleBootstrap.Standing(true, false, false, false, false, false, false, 1).violations())
                .containsExactly("1 role membership(s)");
    }

    @Test
    @DisplayName("the statement carries the SCRAM verifier and never the password")
    void thePasswordNeverReachesTheServer() {
        String statement = RoleBootstrap.createStatement(RoleBootstrap.ROLES.get(0), "one-long-throwaway");
        assertThat(statement)
                .startsWith("CREATE ROLE finapp_migrator LOGIN")
                .contains("PASSWORD 'SCRAM-SHA-256$4096:")
                .doesNotContain("one-long-throwaway");
    }

    @Test
    @DisplayName("finapp_app is given the server's own spelling of its idle-in-transaction timeout, and no other role is")
    void theApplicationRoleCarriesTheIdleTimeout() {
        assertThat(RoleBootstrap.APP_IDLE_IN_TRANSACTION_TIMEOUT).matches("\\d+min");
        assertThat(RepositoryFiles.read("infra/postgres/initdb/00-roles.sql"))
                .as("the local script sets the same value")
                .contains("ALTER ROLE finapp_app SET idle_in_transaction_session_timeout = '" + RoleBootstrap.APP_IDLE_IN_TRANSACTION_TIMEOUT + "'");
    }

    @Test
    @DisplayName("a blank, repeated or marked-local password is refused before anything connects")
    void passwordsAreRefused() {
        assertThat(RoleBootstrap.passwords(passwords())).hasSize(3);

        Map<String, String> missing = passwords();
        missing.remove("FINAPP_DB_APP_PASSWORD");
        assertThatThrownBy(() -> RoleBootstrap.passwords(missing)).isInstanceOf(RefusedConfiguration.class).hasMessageContaining("required");

        Map<String, String> marked = passwords();
        marked.put("FINAPP_DB_APP_PASSWORD", MigratorEnvironment.MARKED_LOCAL_DEFAULT);
        assertThatThrownBy(() -> RoleBootstrap.passwords(marked)).isInstanceOf(RefusedConfiguration.class).hasMessageContaining("marked local");

        Map<String, String> repeated = passwords();
        repeated.put("FINAPP_DB_REPORTING_PASSWORD", repeated.get("FINAPP_DB_APP_PASSWORD"));
        assertThatThrownBy(() -> RoleBootstrap.passwords(repeated)).isInstanceOf(RefusedConfiguration.class).hasMessageContaining("repeats");
    }

    @Test
    @DisplayName("the step refuses a master that is one of its own roles, a missing master and any argument, before connecting")
    void theMasterCredentialIsItsOwn() {
        Map<String, String> environment = passwords();
        environment.put("FINAPP_DB_URL", "jdbc:postgresql://127.0.0.1:1/finapp");
        assertThat(bootstrapExit(environment)).as("no master credential").isEqualTo(ExitCode.REFUSED);
        environment.put("FINAPP_DB_MASTER_USER", "finapp_migrator");
        environment.put("FINAPP_DB_MASTER_PASSWORD", "x");
        assertThat(bootstrapExit(environment)).as("the migrator cannot bootstrap itself").isEqualTo(ExitCode.REFUSED);
        assertThat(RoleBootstrapMain.run(new String[] {"--anything"}, environment, quiet())).isEqualTo(ExitCode.REFUSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"42710", "23505"})
    @DisplayName("a collision with another database's bootstrap runs the step once more, in either form the server reports it")
    void aCollisionIsRunAgain(String sqlState) {
        int[] attempts = {0};
        assertThatThrownBy(() -> RoleBootstrapMain.applyTwice(failing(attempts, sqlState, "XX000"), Map.of(), quiet()))
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .as("the second attempt's own failure, so it did run")
                .isEqualTo("XX000");
        assertThat(attempts[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("any other failure is not run again")
    void anotherFailureIsNotRunAgain() {
        int[] attempts = {0};
        assertThatThrownBy(() -> RoleBootstrapMain.applyTwice(failing(attempts, "42501", "XX000"), Map.of(), quiet()))
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo("42501");
        assertThat(attempts[0]).isEqualTo(1);
    }

    private static DataSource failing(int[] attempts, String first, String then) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
            if (!method.getName().equals("getConnection")) {
                throw new UnsupportedOperationException(method.getName());
            }
            throw new SQLException("refused", attempts[0]++ == 0 ? first : then);
        });
    }

    @Test
    @DisplayName("the migrator never reads the master credential, and the image's entrypoint is the migrator")
    void theBootstrapIsNeverPartOfTheJob() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(RepositoryFiles.root().resolve("migrator/src/main/java"))) {
            sources = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("RoleBootstrapMain.java"))
                    .toList();
        }
        assertThat(sources).as("every source of the module but the step's own entrypoint was read").hasSizeGreaterThanOrEqualTo(8);
        for (Path source : sources) {
            assertThat(RepositoryFiles.read(source))
                    .as("%s must not reach the master user's credential", source.getFileName())
                    .doesNotContain("FINAPP_DB_MASTER");
        }
        assertThat(RepositoryFiles.read("Dockerfile"))
                .contains("\"com.finapp.migrator.MigratorMain\"]")
                .doesNotContain("\"com.finapp.migrator.RoleBootstrapMain\"]");
    }

    private static ExitCode bootstrapExit(Map<String, String> environment) {
        return RoleBootstrapMain.run(new String[0], environment, quiet());
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
