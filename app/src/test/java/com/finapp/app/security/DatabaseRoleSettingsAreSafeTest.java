package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.ConfigurationRegister;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code idle_in_transaction_session_timeout} on {@code finapp_app} (`P15-TSK-031`, routed by `P15-TSK-035`): the register's row, the
 * two places a role is made and the bound the value has to sit between ({@code CONFIGURATION_REGISTER.md} section 5).
 *
 * <p>The bounds are read, never copied, and the floor is <strong>the four waits named below, a hand list and not a census of every
 * transaction</strong>: the read-only snapshot a desk or a close check holds on this role, armed at the report budget (report
 * generation itself runs as {@code finapp_reporting}), an operational report's door, the outbox relay's wait for a broker
 * acknowledgement, and a thread waiting on the pool for a second connection. The ceiling is the census's page, since the setting exists
 * to end the stall it pages on before it does.
 */
@Tag("architecture")
@DisplayName("the role-level idle-in-transaction timeout is above the waits named here and below the census's stall page (P15-TSK-031)")
class DatabaseRoleSettingsAreSafeTest {

    private static final String REGISTER = "docs/architecture/CONFIGURATION_REGISTER.md";
    private static final String LOCAL_ROLES = "infra/postgres/initdb/00-roles.sql";
    private static final String BOOTSTRAP = "migrator/src/main/java/com/finapp/migrator/RoleBootstrap.java";
    private static final String CORRECTNESS_RULES = "infra/prometheus/rules/correctness.yml";
    private static final String SETTING = "idle_in_transaction_session_timeout";

    private static String registerText;
    private static List<ConfigurationRegister.Row> rows;

    @BeforeAll
    static void read() {
        SecretInventoryIsCompleteTest.readEverything();
        registerText = SecretInventoryIsCompleteTest.read(REGISTER);
        rows = ConfigurationRegister.rows(registerText);
    }

    @Test
    @DisplayName("one row, and the local script and the bootstrap step set exactly that on finapp_app")
    void theRegisterAndTheTwoScriptsAgree() {
        List<List<String>> settings = table();
        assertThat(settings).hasSize(1);
        assertThat(settings.get(0).get(0)).isEqualTo("finapp_app");
        assertThat(settings.get(0).get(1)).isEqualTo(SETTING);
        String value = settings.get(0).get(2);
        String statement = "ALTER ROLE finapp_app SET " + SETTING + " = '" + value + "'";
        assertThat(SecretInventoryIsCompleteTest.read(LOCAL_ROLES)).as("a laptop's and every test database's role").contains(statement);
        // RoleBootstrapTest runs the step's statements; infra/scripts/migrator-verify.sh reads rolconfig off a real server.
        assertThat(SecretInventoryIsCompleteTest.read(BOOTSTRAP)).as("a deployed role, the statement built and run")
                .contains("APP_IDLE_IN_TRANSACTION_TIMEOUT = \"" + value + "\"")
                .contains("\"ALTER ROLE finapp_app SET " + SETTING + " = '\" + APP_IDLE_IN_TRANSACTION_TIMEOUT")
                .contains("statement.execute(APP_ROLE_SETTING);");
    }

    @Test
    @DisplayName("only finapp_app carries it: the migrator's DDL and the reporting role's read-only snapshot have their own bounds")
    void onlyTheApplicationRoleCarriesIt() {
        String local = SecretInventoryIsCompleteTest.read(LOCAL_ROLES);
        assertThat(local).doesNotContainPattern("ALTER ROLE finapp_(migrator|reporting) SET " + SETTING);
    }

    @Test
    @DisplayName("the value exceeds each named wait inside a finapp_app transaction, each read from the configuration, and no overlay moves one")
    void theValueIsAboveTheNamedWaits() {
        Duration value = value();
        for (String property : List.of("finapp.accounting.report.generation-timeout", "finapp.accounting.report.operational-timeout", "finapp.kafka.ack-timeout")) {
            ConfigurationRegister.Row row = rows.stream().filter(r -> r.property().equals(property)).findFirst().orElseThrow();
            assertThat(value).as("%s is a wait inside one transaction", property).isGreaterThan(Duration.parse(row.defaultCell()));
            assertThat(List.of(row.ci(), row.staging(), row.production())).as("%s is not raised anywhere this test cannot read", property)
                    .containsOnly("default");
        }
        Map<String, Object> application = SecretInventoryIsCompleteTest.flatten(SecretInventoryIsCompleteTest.application);
        Object connectionTimeout = application.get("spring.datasource.hikari.connection-timeout");
        assertThat(connectionTimeout).as("the pool's wait is read").isNotNull();
        assertThat(value).as("the pool's wait").isGreaterThan(Duration.ofMillis(Long.parseLong(String.valueOf(connectionTimeout))));
    }

    @Test
    @DisplayName("the value is below the census's stall page: it ends the stall before a person is woken for it")
    void theValueIsBelowTheStallPage() {
        Matcher stalled = Pattern.compile("max\\(finapp_accounting_source_census_lag_seconds\\) > (\\d+)\\s+for:", Pattern.DOTALL)
                .matcher(rulesWithoutCommentsUpTo("SourceCensusStalled"));
        assertThat(stalled.find()).as("SourceCensusStalled's threshold is read").isTrue();
        assertThat(value()).isLessThan(Duration.ofSeconds(Long.parseLong(stalled.group(1))));
    }

    @Test
    @DisplayName("the value's unit is one the server reads: minutes, spelled as PostgreSQL spells them")
    void theValueIsInTheServersGrammar() {
        assertThat(table().get(0).get(2)).matches("\\d+min");
    }

    private static Duration value() {
        Matcher minutes = Pattern.compile("(\\d+)min").matcher(table().get(0).get(2));
        assertThat(minutes.matches()).isTrue();
        return Duration.ofMinutes(Long.parseLong(minutes.group(1)));
    }

    private static String rulesWithoutCommentsUpTo(String alert) {
        String text = SecretInventoryIsCompleteTest.read(CORRECTNESS_RULES);
        int at = text.indexOf("- alert: " + alert);
        assertThat(at).as("the rule exists").isNotNegative();
        return text.substring(at).replaceAll("(?m)^\\s*#.*$", "");
    }

    private static List<List<String>> table() {
        String[] lines = registerText.split("\\R", -1);
        int index = 0;
        while (index < lines.length && !lines[index].startsWith("| Role | Setting | Value |")) {
            index++;
        }
        assertThat(index).as("the register has a table of role settings").isLessThan(lines.length);
        List<List<String>> table = new ArrayList<>();
        for (index += 2; index < lines.length && lines[index].startsWith("|"); index++) {
            table.add(SecretInventoryIsCompleteTest.cells(lines[index]));
        }
        return table;
    }
}
