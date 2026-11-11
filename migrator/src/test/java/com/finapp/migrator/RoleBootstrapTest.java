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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
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
        assertThat(localRoles).as("the local script's roles were read").containsOnlyKeys("finapp_migrator", "finapp_app", "finapp_reporting",
                "finapp_app_a", "finapp_app_b", "finapp_reporting_a", "finapp_reporting_b");
        assertThat(RoleBootstrap.ROLES.stream().map(RoleBootstrap.Role::name).collect(Collectors.toSet()))
                .as("a role the migrations grant to must exist before they run, and its alternating users with it (P15-TSK-020)")
                .isEqualTo(localRoles.keySet());
        Matcher raw = LOCAL_ROLE.matcher(local);
        while (raw.find()) {
            if (raw.group(1).matches(".*_[ab]")) {
                assertThat(raw.group(2)).as("%s is created with no password, locally as deployed", raw.group(1)).doesNotContainIgnoringCase("PASSWORD");
            }
        }
        for (RoleBootstrap.Role alternate : RoleBootstrap.ROLES.stream().filter(RoleBootstrap.Role::alternate).toList()) {
            assertThat(local).as("the local script gives %s the bootstrap's one membership", alternate.name())
                    .contains(RoleBootstrap.membershipStatement(alternate) + ";");
        }
        Set<String> created = Set.copyOf(tokens(RoleBootstrap.ATTRIBUTES));
        localRoles.forEach((name, attributes) -> assertThat(created).as("%s's local attributes", name).containsAll(attributes));
        assertThat(created)
                .as("nothing that lifts a right, and the two a managed master could otherwise hand down refused outright")
                .contains("NOSUPERUSER", "NOCREATEDB", "NOCREATEROLE", "NOINHERIT", "NOBYPASSRLS", "NOREPLICATION", "LOGIN")
                .doesNotContain("SUPERUSER", "CREATEDB", "CREATEROLE", "INHERIT", "BYPASSRLS", "REPLICATION");

        Map<String, String> localGrants = LOCAL_GRANT.matcher(local).results()
                .collect(Collectors.toMap(match -> match.group(2), match -> match.group(1)));
        assertThat(RoleBootstrap.ROLES.stream().filter(granted -> granted.databaseRights() != null)
                        .collect(Collectors.toMap(RoleBootstrap.Role::name, RoleBootstrap.Role::databaseRights)))
                .as("the migrator creates its schemas; nothing else may; an alternating user has no right of its own")
                .isEqualTo(localGrants);
    }

    @Test
    @DisplayName("each alternating user is created with no password and given exactly its carrier's membership, inherit on, set off (P15-TSK-020)")
    void theAlternatingUsersHoldTheirCarriersGrantsByMembershipAlone() throws SQLException {
        List<RoleBootstrap.Role> alternates = RoleBootstrap.ROLES.stream().filter(RoleBootstrap.Role::alternate).toList();
        assertThat(alternates).extracting(role -> role.name() + ">" + role.carrier())
                .containsExactly("finapp_app_a>finapp_app", "finapp_app_b>finapp_app", "finapp_reporting_a>finapp_reporting",
                        "finapp_reporting_b>finapp_reporting");
        assertThat(alternates).allSatisfy(role -> {
            assertThat(role.passwordVariable()).as("no password is generated for it").isNull();
            assertThat(role.databaseRights()).as("its CONNECT is its carrier's").isNull();
        });

        List<String> executed = new ArrayList<>();
        RoleBootstrap.apply(master(executed, Catalogue.empty()), RoleBootstrap.passwords(passwords()));
        assertThat(executed).contains("CREATE ROLE finapp_app_a " + RoleBootstrap.ATTRIBUTES,
                "GRANT finapp_app TO finapp_app_a WITH INHERIT TRUE, SET FALSE",
                "CREATE ROLE finapp_reporting_b " + RoleBootstrap.ATTRIBUTES,
                "GRANT finapp_reporting TO finapp_reporting_b WITH INHERIT TRUE, SET FALSE");
        assertThat(executed).filteredOn(sql -> sql.startsWith("CREATE ROLE finapp_app_a") || sql.startsWith("CREATE ROLE finapp_reporting_"))
                .allSatisfy(sql -> assertThat(sql).doesNotContain("PASSWORD"));
        assertThat(executed).as("no database right is granted to an alternating user")
                .filteredOn(sql -> sql.contains("ON DATABASE") && sql.matches("(?s).*TO finapp_[a-z]+_[ab]'.*")).isEmpty();
        assertThat(executed).as("the carriers' are").anyMatch(sql -> sql.contains("GRANT CONNECT ON DATABASE %I TO finapp_app'"));
        assertThat(executed.indexOf("CREATE ROLE finapp_app_a " + RoleBootstrap.ATTRIBUTES))
                .as("the carrier exists before its member is made").isGreaterThan(indexStartingWith(executed, "CREATE ROLE finapp_app LOGIN"));
    }

    @Test
    @DisplayName("an alternating user holding another membership, or the options wrong, and a stranger able to use a carrier's rights, each stop the step")
    void aMembershipOutOfPlaceStopsTheStep() {
        Catalogue wrongOptions = Catalogue.present().membership("finapp_app_b", "finapp_app", true, true, false);
        assertThatThrownBy(() -> RoleBootstrap.apply(master(new ArrayList<>(), wrongOptions), RoleBootstrap.passwords(passwords())))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("finapp_app_b holds");

        Catalogue anotherCarrier = Catalogue.present().membership("finapp_reporting_a", "finapp_app", true, false, false);
        assertThatThrownBy(() -> RoleBootstrap.apply(master(new ArrayList<>(), anotherCarrier), RoleBootstrap.passwords(passwords())))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("finapp_reporting_a holds");

        Catalogue stranger = Catalogue.present().user("finapp_app", "someone_else");
        List<String> executed = new ArrayList<>();
        assertThatThrownBy(() -> RoleBootstrap.apply(master(executed, stranger), RoleBootstrap.passwords(passwords())))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("someone_else can use finapp_app's rights");
        assertThat(executed).as("nothing committed").doesNotContain("COMMIT");

        RoleBootstrap.Standing alternate = new RoleBootstrap.Standing(true, false, false, false, false, false, false, 1);
        assertThat(alternate.violations(1)).as("an alternating user holds exactly its one membership").isEmpty();
        assertThat(new RoleBootstrap.Standing(true, false, false, false, false, false, false, 0).violations(1)).containsExactly("0 role membership(s)");
    }

    private static int indexStartingWith(List<String> executed, String prefix) {
        for (int i = 0; i < executed.size(); i++) {
            if (executed.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
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
    @DisplayName("finapp_app's idle-in-transaction timeout is in the server's own spelling, and the local script sets the same")
    void theApplicationRoleCarriesTheIdleTimeout() {
        assertThat(RoleBootstrap.APP_IDLE_IN_TRANSACTION_TIMEOUT).matches("\\d+min");
        assertThat(RepositoryFiles.read("infra/postgres/initdb/00-roles.sql"))
                .as("the local script sets the same value")
                .contains("ALTER ROLE finapp_app SET idle_in_transaction_session_timeout = '" + RoleBootstrap.APP_IDLE_IN_TRANSACTION_TIMEOUT + "'");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("every run sets the idle timeout on finapp_app and its alternating users and on no other role, before it commits, whether the roles are new or already exist")
    void theBootstrapSetsTheTimeoutOnTheApplicationRoleAlone(boolean rolesExist) throws SQLException {
        List<String> executed = new ArrayList<>();
        RoleBootstrap.apply(master(executed, rolesExist ? Catalogue.present() : Catalogue.empty()), RoleBootstrap.passwords(passwords()));
        String setting = " SET idle_in_transaction_session_timeout = '" + RoleBootstrap.APP_IDLE_IN_TRANSACTION_TIMEOUT + "'";
        assertThat(executed).filteredOn(sql -> sql.startsWith("ALTER ROLE"))
                .as("a role setting is the user's, and a membership does not carry it (P15-TSK-020)")
                .containsExactly("ALTER ROLE finapp_app" + setting, "ALTER ROLE finapp_app_a" + setting, "ALTER ROLE finapp_app_b" + setting);
        assertThat(executed.get(executed.size() - 1)).as("inside the step's one transaction").isEqualTo("COMMIT");
        if (rolesExist) {
            assertThat(executed).as("a role that exists is never created, granted or given a password again")
                    .noneMatch(sql -> sql.startsWith("CREATE ROLE") || sql.startsWith("GRANT finapp_"));
        }
    }

    /**
     * What the catalogue holds, for a fake master: the roles present, each user's memberships as {@code carrier|inherit|set|admin},
     * and the users of each carrier. {@link #present()} is a correct deployment; each change makes one thing out of place.
     */
    private record Catalogue(boolean rolesExist, Map<String, List<Object[]>> memberships, Map<String, List<String>> users) {

        static Catalogue empty() {
            return new Catalogue(false, new HashMap<>(), new HashMap<>());
        }

        /** Every role present, each alternating user a member of its carrier, inherit on and set off, and nobody else a user. */
        static Catalogue present() {
            Catalogue catalogue = new Catalogue(true, new HashMap<>(), new HashMap<>());
            for (RoleBootstrap.Role role : RoleBootstrap.ROLES) {
                if (role.alternate()) {
                    catalogue.memberships.put(role.name(), new ArrayList<>(List.<Object[]>of(new Object[] {role.carrier(), true, false, false})));
                    catalogue.users.computeIfAbsent(role.carrier(), carrier -> new ArrayList<>()).add(role.name());
                }
            }
            return catalogue;
        }

        Catalogue membership(String user, String carrier, boolean inherit, boolean set, boolean admin) {
            memberships.put(user, new ArrayList<>(List.<Object[]>of(new Object[] {carrier, inherit, set, admin})));
            return this;
        }

        Catalogue user(String carrier, String user) {
            users.computeIfAbsent(carrier, ignored -> new ArrayList<>()).add(user);
            return this;
        }

        /** After a fresh run creates the alternates: what the step's own statements made. */
        List<Object[]> membershipsOf(String user) {
            if (!rolesExist) {
                return RoleBootstrap.ROLES.stream().filter(role -> role.name().equals(user) && role.alternate())
                        .map(role -> new Object[] {role.carrier(), true, false, false}).toList();
            }
            return memberships.getOrDefault(user, List.of());
        }

        List<String> usersOf(String carrier) {
            if (!rolesExist) {
                return RoleBootstrap.ROLES.stream().filter(role -> carrier.equals(role.carrier())).map(RoleBootstrap.Role::name).toList();
            }
            return users.getOrDefault(carrier, List.of());
        }
    }

    /** A cursor over rows of values, as a fake ResultSet. */
    private static ResultSet rows(List<Object[]> rows) {
        int[] at = {-1};
        return fake(ResultSet.class, (name, args) -> switch (name) {
            case "next" -> ++at[0] < rows.size();
            case "getString" -> (String) rows.get(at[0])[(Integer) args[0] - 1];
            case "getBoolean" -> (Boolean) rows.get(at[0])[(Integer) args[0] - 1];
            case "getLong" -> (Long) rows.get(at[0])[(Integer) args[0] - 1];
            default -> null;
        });
    }

    /** A master connection recording what the step executes against {@code catalogue}: statements as run, queries as {@code QUERY <sql>}. */
    private static Connection master(List<String> executed, Catalogue catalogue) {
        Statement statement = fake(Statement.class, (name, args) -> {
            if (name.equals("execute")) {
                executed.add((String) args[0]);
                return false;
            }
            if (name.equals("executeQuery")) {
                executed.add("QUERY " + args[0]);
                return rows(List.<Object[]>of(new Object[] {"GRANT"}));
            }
            return null;
        });
        return fake(Connection.class, (name, args) -> {
            if (name.equals("commit")) {
                executed.add("COMMIT");
            }
            return switch (name) {
                case "createStatement" -> statement;
                case "prepareStatement" -> prepared((String) args[0], catalogue);
                default -> null;
            };
        });
    }

    private static PreparedStatement prepared(String sql, Catalogue catalogue) {
        String[] bound = new String[1];
        return fake(PreparedStatement.class, (name, args) -> {
            if (name.equals("setString")) {
                bound[0] = (String) args[1];
                return null;
            }
            if (!name.equals("executeQuery")) {
                return null;
            }
            if (sql.equals(RoleBootstrap.MEMBERSHIPS_OF)) {
                return rows(catalogue.membershipsOf(bound[0]));
            }
            if (sql.equals(RoleBootstrap.USERS_OF)) {
                return rows(catalogue.usersOf(bound[0]).stream().map(user -> new Object[] {user}).toList());
            }
            if (!catalogue.rolesExist()) {
                return rows(List.of());
            }
            long memberships = catalogue.membershipsOf(bound[0]).size();
            return rows(List.<Object[]>of(new Object[] {true, false, false, false, false, false, false, memberships}));
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, BiFunction<String, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> answer.apply(method.getName(), args));
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
