package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.accounting.GlSnapshots;
import com.finapp.app.telemetry.ConfigurationRegister;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.risk.EvaluationBudget;
import com.finapp.risk.RiskProofs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The data source's socket timeout ({@code P16-TSK-022}, ADR-0139; {@code THREAT_MODEL.md} section 6 item 42 (h)): how long one read
 * of a database connection may wait for the server before the driver gives the connection up.
 *
 * <p>After a failover a connection the network black-holed never answers. Without a socket timeout a request or a tick holding one
 * waited until its pod was replaced. A socket timeout fails the other way when it is too short: a statement that legitimately runs
 * longer fails every time it runs, and a proof or a census that never finishes leaves the platform blind to its own correctness. So
 * the value has a floor and a ceiling, each read, never copied:
 *
 * <ol>
 *   <li>it is set, in seconds, on the writer's driver properties, and not zero (zero is the driver's "wait for ever");
 *   <li>its floor: every statement bound the code arms, on either pool, plus a minute for the server to answer a statement it
 *       cancelled. The bounds are the register's rows, read with the defaults the code holds them to, and none is moved by an
 *       overlay this test cannot read. The longest is report generation's budget, armed on the writer's pool by the shared report
 *       snapshots as well as on the reporting pool;
 *   <li>the bounds are discovered, not listed: the classes that arm {@code statement_timeout} are exactly the ones read here, every
 *       bound property those classes' beans read is among the bounds checked, and every {@code statement-timeout} the code reads is
 *       too. A new arming site, or a new bound fed to one, fails here until it is weighed against the socket timeout;
 *   <li>the reporting pool carries it, because it takes the writer's driver properties;
 *   <li>its ceiling: a black-holed connection freed with ten minutes of the in-region recovery objective (ADR-0128, tier 1) left for
 *       the promotion, the Service's move and a person's step;
 *   <li>the load scope measures the longest statement each run, the evidence ADR-0139's revision rule reads (its runs owed under
 *       {@code POSTPONED_ISSUES.md} PI-025).
 * </ol>
 *
 * <p>Hermetic: it reads files and builds the reporting pool, which is lazy and connects to nothing.
 */
@Tag("architecture")
@DisplayName("the data source's socket timeout is above every statement bound the code arms and frees a black-holed connection in time (P16-TSK-022)")
class DatabaseSocketTimeoutTest {

    private static final String APPLICATION = "app/src/main/resources/application.yaml";
    private static final String REGISTER = "docs/architecture/CONFIGURATION_REGISTER.md";
    private static final String RECOVERY_OBJECTIVES = "docs/adr/ADR-0128-recovery-objectives-and-the-post-recovery-gate.md";
    private static final String LOAD_STATEMENTS = "infra/scripts/kind-rehearsal/load/statements.sql";

    private static final String SOCKET_TIMEOUT = "spring.datasource.hikari.data-source-properties.socketTimeout";

    /** The time the server is given to answer a statement it cancelled at its bound: ADR-0139's margin. */
    private static final Duration ANSWER_MARGIN = Duration.ofMinutes(1);

    /** What the in-region recovery objective keeps for the promotion, the Service's move and a person's step: ADR-0139. */
    private static final Duration LEFT_FOR_THE_FAILOVER = Duration.ofMinutes(10);

    /** The report budgets: each arms a report snapshot's {@code statement_timeout} ({@code JdbcReportSnapshots}). */
    private static final Set<String> REPORT_BUDGETS =
            Set.of("finapp.accounting.report.generation-timeout", "finapp.accounting.report.operational-timeout");

    /**
     * Every class that arms {@code statement_timeout}, and why its bound is one this test reads: a register property, or a constant
     * read below. A file arming one that is not here fails {@link #theBoundsAreDiscovered()}.
     */
    private static final Map<String, String> ARMING_SITES = Map.of(
            "accounting/src/main/java/com/finapp/accounting/reporting/ReadOnlySnapshot.java",
            "re-arms what is left of the report budget inside a report snapshot",
            "accounting/src/main/java/com/finapp/accounting/SourceCensus.java", "finapp.accounting.source-census.statement-timeout",
            "app/src/main/java/com/finapp/app/accounting/GlSnapshots.java",
            "the checks', the gauges' and the GL reads' statement-timeout, or GlSnapshots.STATEMENT_TIMEOUT",
            "app/src/main/java/com/finapp/app/accounting/JdbcReportSnapshots.java", "the two report budgets",
            "risk/src/main/java/com/finapp/risk/JdbcEvaluationBudget.java", "EvaluationBudget.READ_TIMEOUT",
            "risk/src/main/java/com/finapp/risk/RiskProofs.java", "finapp.risk.proofs.statement-timeout, or RiskProofs.STATEMENT_TIMEOUT");

    /** A statement bound armed in Java: {@code set_config('statement_timeout', ...)}, as every site arms it. */
    private static final Pattern ARMS = Pattern.compile("set_config\\('statement_timeout'");

    /** A property the code reads with a default: {@code Value("${name:default}")}, the annotation spelled short or in full. */
    private static final Pattern READS = Pattern.compile("Value\\(\"\\$\\{([a-z][a-z0-9.-]*)[:}]");

    /** The classes whose construction hands a bound to an arming site. */
    private static final Pattern FEEDS_A_SITE = Pattern.compile(
            "new GlSnapshots\\(|new JdbcReportSnapshots\\(|new SourceCensus\\(|new JdbcEvaluationBudget\\(|RiskProofs\\.|ReadOnlySnapshot");

    private static Path root;
    private static Duration socketTimeout;
    private static String socketTimeoutText;
    private static Map<String, ConfigurationRegister.Row> register;

    @BeforeAll
    static void read() {
        root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        Map<String, Object> flat = new TreeMap<>();
        flatten("", new Yaml(new SafeConstructor(new LoaderOptions())).load(application()), flat);
        Object value = flat.get(SOCKET_TIMEOUT);
        assertThat(value).as("%s sets %s", APPLICATION, SOCKET_TIMEOUT).isNotNull();
        socketTimeoutText = String.valueOf(value);
        assertThat(socketTimeoutText).as("the driver reads whole seconds").matches("[0-9]+");
        socketTimeout = Duration.ofSeconds(Long.parseLong(socketTimeoutText));
        register = new TreeMap<>();
        ConfigurationRegister.rows(RepositoryPaths.read(REGISTER)).forEach(row -> register.put(row.property(), row));
    }

    @Test
    @DisplayName("the writer's driver properties set a socket timeout, in seconds, and not zero, the driver's wait for ever")
    void theDataSourceGivesUpABlackHoledConnection() {
        assertThat(socketTimeout).as("zero would be no timeout at all").isPositive();
        assertThat(application()).as("stated beside connectTimeout, in the writer's driver properties")
                .containsPattern("(?m)^      data-source-properties:\\n(?:        .*\\n)*        socketTimeout: " + socketTimeoutText + "$");
    }

    @Test
    @DisplayName("it is above every statement bound the code arms, plus a minute for the server's answer, and no overlay moves a bound")
    void itIsAboveEveryArmedBound() {
        Map<String, Duration> bounds = new TreeMap<>();
        for (String property : boundProperties()) {
            ConfigurationRegister.Row row = register.get(property);
            assertThat(row).as("%s is a row of %s", property, REGISTER).isNotNull();
            bounds.put(property, Duration.parse(row.defaultCell()));
            assertThat(List.of(row.local(), row.test(), row.ci(), row.staging(), row.production()))
                    .as("%s is moved nowhere this test cannot read", property).containsOnly("default");
        }
        bounds.put("GlSnapshots.STATEMENT_TIMEOUT", GlSnapshots.STATEMENT_TIMEOUT);
        bounds.put("RiskProofs.STATEMENT_TIMEOUT", RiskProofs.STATEMENT_TIMEOUT);
        bounds.put("EvaluationBudget.READ_TIMEOUT", EvaluationBudget.READ_TIMEOUT);
        assertThat(bounds).as("the bounds were read: the seven registered and the three constants").hasSizeGreaterThanOrEqualTo(10);
        bounds.forEach((name, bound) -> assertThat(bound.plus(ANSWER_MARGIN))
                .as("%s (%s) and a minute for the server's answer stay within the socket timeout (%s)", name, bound, socketTimeout)
                .isLessThanOrEqualTo(socketTimeout));
        assertThat(bounds.get("finapp.accounting.report.generation-timeout")).as("the longest is the report generation budget")
                .isEqualTo(bounds.values().stream().max(Duration::compareTo).orElseThrow());
    }

    @Test
    @DisplayName("the bounds are discovered: the arming sites are the ones weighed here, and every bound fed to one is checked")
    void theBoundsAreDiscovered() {
        Set<String> arming = new TreeSet<>();
        Set<String> fed = new TreeSet<>();
        Set<String> statementTimeouts = new TreeSet<>();
        for (Path file : mainSources()) {
            String text = read(file);
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (ARMS.matcher(text).find()) {
                arming.add(relative);
            }
            Matcher reads = READS.matcher(text);
            boolean feeds = FEEDS_A_SITE.matcher(text).find();
            while (reads.find()) {
                String property = reads.group(1);
                if (property.endsWith("statement-timeout")) {
                    statementTimeouts.add(property);
                }
                if (feeds && property.endsWith("timeout")) {
                    fed.add(property);
                }
            }
        }
        assertThat(arming).as("the classes arming statement_timeout, each weighed against the socket timeout")
                .isEqualTo(ARMING_SITES.keySet());
        assertThat(boundProperties()).as("every timeout an arming site's bean reads").containsAll(fed);
        assertThat(boundProperties()).as("every statement-timeout the code reads").containsAll(statementTimeouts);
        assertThat(fed).as("the beans feeding the sites were read").contains("finapp.accounting.report.generation-timeout",
                "finapp.accounting.checks.statement-timeout", "finapp.risk.proofs.statement-timeout");
    }

    @Test
    @DisplayName("the reporting pool carries it, taking the writer's driver properties")
    void theReportingPoolCarriesIt() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/finapp")
                .withProperty(SOCKET_TIMEOUT, socketTimeoutText)
                .withProperty(ReportingPool.USERNAME_PROPERTY, "finapp_reporting")
                .withProperty(ReportingPool.LOGIN_PROPERTY, "supplied-by-the-test")
                .withProperty(ReportingPool.POOL_SIZE_PROPERTY, "1");
        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(environment, new SimpleMeterRegistry())) {
            assertThat(pool.driverProperty("socketTimeout")).as("report generation, armed at its budget, given up past it")
                    .isEqualTo(socketTimeoutText);
        }
    }

    @Test
    @DisplayName("a black-holed connection is freed with ten minutes of the in-region recovery objective left for the failover itself")
    void itFreesABlackHoledConnectionInTime() {
        Matcher tier = Pattern.compile("Tier 1, [^.]*?RTO (\\d+)\\s+minutes").matcher(RepositoryPaths.read(RECOVERY_OBJECTIVES));
        assertThat(tier.find()).as("ADR-0128's tier 1 recovery time is read").isTrue();
        Duration objective = Duration.ofMinutes(Long.parseLong(tier.group(1)));
        assertThat(socketTimeout.plus(LEFT_FOR_THE_FAILOVER)).as("the socket timeout inside the %s objective", objective)
                .isLessThanOrEqualTo(objective);
    }

    @Test
    @DisplayName("the load scope measures each statement's longest execution, the evidence the decision's revision rule reads")
    void theLoadScopeMeasuresTheLongestStatement() {
        String statements = RepositoryPaths.read(LOAD_STATEMENTS);
        assertThat(statements).as("every 10 s of a load, finapp's statements by their longest execution")
                .contains("SELECT 'statements_longest', s.queryid, s.calls, round(s.max_exec_time::numeric, 1)")
                .contains("ORDER BY s.max_exec_time DESC");
    }

    /** The register's statement bounds: every {@code statement-timeout} row and the two report budgets. */
    private static Set<String> boundProperties() {
        Set<String> properties = new TreeSet<>(REPORT_BUDGETS);
        register.keySet().stream().filter(property -> property.endsWith(".statement-timeout")).forEach(properties::add);
        assertThat(properties).as("the register's bounds were read").hasSizeGreaterThanOrEqualTo(7);
        return properties;
    }

    private static String application() {
        return RepositoryPaths.read(APPLICATION).replace("\r\n", "\n");
    }

    /** Every Java source under a module's {@code src/main/java}, each module a directory of the repository's root. */
    private static List<Path> mainSources() {
        try (Stream<Path> modules = Files.list(root)) {
            List<Path> sources = new ArrayList<>();
            for (Path main : modules.map(module -> module.resolve("src/main/java")).filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
                }
            }
            assertThat(sources).as("the modules' sources were read").hasSizeGreaterThan(500);
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void flatten(String prefix, Object node, Map<String, Object> flat) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, value, flat));
        } else {
            flat.put(prefix, node);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
