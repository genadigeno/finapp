package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * {@code OPERATIONS_RUNBOOK.md} section 10 names only what exists (`P14-TSK-020`): the review's recurring finding is a runbook naming a door its reader cannot open. Every alert links to a section that exists and that names it, and one alert with no link fails. Every route section 10 names
 * is a route of the running application, with the method it says; a route written beside a permission requires that permission; every permission, meter, property, alert and code it names exists. All of it read from the text against the live context and the sources, with no list kept here. It holds the words and no more: whether a step
 * helps an operator is a reader's to judge.
 */
@Tag("slice")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent", "finapp.accounting.sweeper.enabled=false"})
@DisplayName("the runbook's section 10 names only what exists (P14-TSK-020)")
class AccountingRunbookTest {

    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final Pattern CITED = Pattern.compile("section (\\d+\\.\\d+)");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern ROUTE = Pattern.compile("^(?:(GET|POST|PUT|DELETE) )?((?:/v1/|\\.\\.\\./)[A-Za-z0-9/{}._-]*)(?:\\?.*)?$");
    private static final Pattern ROUTE_THEN_PERMISSION = Pattern.compile("`(GET|POST|PUT|DELETE) ((?:/v1/|\\.\\.\\./)[A-Za-z0-9/{}._-]*)` \\(`([A-Z][A-Z_]+)`\\)");
    private static final Pattern PERMISSION_LIKE = Pattern.compile("^(ACCOUNTING|REGULATORY|LEDGER|BNPL|LOAN|RECONCILIATION|SETTLEMENT)_[A-Z_]+$");
    private static final Pattern METER_LIKE = Pattern.compile("^(finapp\\.[a-z0-9._-]+)(\\{.*})?$");
    private static final Pattern ALERT_LIKE = Pattern.compile("^(Accounting|Regulatory|WriteOff)[A-Z][A-Za-z]+$");
    private static final Pattern CODE_LIKE = Pattern.compile("^(?:\\d{3} )?((?:accounting|regulatory)\\.[A-Z][A-Za-z]+)$");
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]*}");

    @Autowired private MeterRegistry registry;
    @Autowired private ReportDefinitions definitions;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping routes;

    @Test
    @DisplayName("every alert's description links to a runbook section, each section exists and names the alert: one alert with no link fails")
    void everyAlertLinksToASectionThatNamesIt() {
        String runbook = RepositoryPaths.read(RUNBOOK);
        assertThat(AccountingAlertRulesTest.ruleList()).isNotEmpty().allSatisfy(rule -> {
            String alert = String.valueOf(rule.get("alert"));
            String description = String.valueOf(AccountingAlertRulesTest.map(rule.get("annotations")).get("description"));
            int link = description.indexOf("OPERATIONS_RUNBOOK.md");
            assertThat(link).as("%s links to the runbook", alert).isNotNegative();
            Matcher cited = CITED.matcher(description.substring(link));
            assertThat(cited.find()).as("%s cites a section", alert).isTrue();
            do {
                assertThat(section(runbook, cited.group(1))).as("section %s names %s", cited.group(1), alert).contains("`" + alert + "`");
            } while (cited.find());
        });
    }

    @Test
    @DisplayName("the scope's procedures are written, the planned marker gone: close calendar, lock, refused posting, failed check, divergence, reopen, first mapping activation")
    void theProceduresAreThere() {
        String ten = sectionTen();
        assertThat(ten).doesNotContain("`P14-TSK-020` writes the rest");
        List<String> headings = ten.lines().filter(line -> line.startsWith("### ")).map(String::toLowerCase).toList();
        for (String procedure : List.of("close calendar", "a lock", "refused posting", "failed check", "a divergence", "a reopen", "first mapping activation", "a month that cannot be proposed")) {
            assertThat(headings).as("a section on %s", procedure).anySatisfy(heading -> assertThat(heading).contains(procedure));
        }
    }

    @Test
    @DisplayName("every route section 10 names is a route of the running application, with the method it says")
    void everyRouteExists() {
        Map<String, Set<String>> known = routesAndMethods();
        Set<String> paths = known.keySet();
        List<String> found = new ArrayList<>();
        Matcher token = BACKTICKED.matcher(sectionTen());
        while (token.find()) {
            Matcher route = ROUTE.matcher(token.group(1));
            if (!route.matches()) {
                continue;
            }
            found.add(token.group(1));
            String method = route.group(1);
            String wanted = normalised(route.group(2));
            List<String> matching = paths.stream().filter(path -> matches(path, wanted)).toList();
            assertThat(matching).as("the route `%s` exists", token.group(1)).isNotEmpty();
            if (method != null) {
                assertThat(matching.stream().flatMap(path -> known.get(path).stream()).toList()).as("the route `%s` takes %s", token.group(1), method).contains(method);
            }
        }
        assertThat(found).as("the section names routes, or this checks nothing").hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("a route written beside a permission requires that permission")
    void aRouteBesidePermissionRequiresIt() {
        Map<String, Set<String>> required = routesAndPermissions();
        Matcher pair = ROUTE_THEN_PERMISSION.matcher(sectionTen());
        int checked = 0;
        while (pair.find()) {
            String wanted = pair.group(1) + " " + normalised(pair.group(2));
            List<String> matching = required.keySet().stream().filter(route -> route.startsWith(pair.group(1) + " "))
                    .filter(route -> matches(route.substring(route.indexOf(' ') + 1), wanted.substring(wanted.indexOf(' ') + 1))).toList();
            assertThat(matching).as("`%s %s` is a permissioned route", pair.group(1), pair.group(2)).isNotEmpty();
            assertThat(matching.stream().flatMap(route -> required.get(route).stream()).toList()).as("`%s %s` requires `%s`", pair.group(1), pair.group(2), pair.group(3)).contains(pair.group(3));
            checked++;
        }
        assertThat(checked).as("the section pairs some routes with their permissions, or this checks nothing").isGreaterThan(10);
    }

    @Test
    @DisplayName("every permission, meter, property, alert and code section 10 names exists")
    void everyNameExists() throws IOException {
        Set<String> permissions = Arrays.stream(PermissionName.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> notPermissions = new TreeSet<>(Arrays.stream(CloseCheckCode.values()).map(Enum::name).toList());
        notPermissions.addAll(definitions.codes());
        Set<String> meters = registry.getMeters().stream().map(Meter::getId).map(Meter.Id::getName).collect(Collectors.toSet());
        Set<String> alerts = AccountingAlertRulesTest.rules().keySet();
        String sources = mainSources();
        Matcher token = BACKTICKED.matcher(sectionTen());
        int named = 0;
        while (token.find()) {
            String text = token.group(1);
            if (PERMISSION_LIKE.matcher(text).matches()) {
                // A check's code or a report's reads like a permission; each is held to its own declaration.
                assertThat(permissions.contains(text) || notPermissions.contains(text) || sources.contains("\"" + text + "\""))
                        .as("`%s` is a permission, a check, a report or a declared code", text).isTrue();
                named++;
            }
            Matcher meter = METER_LIKE.matcher(text);
            if (meter.matches()) {
                String name = meter.group(1).replaceAll("=.*$", "");
                assertThat(meters.contains(name) || sources.contains("${" + name) || sources.contains("\"" + name + "\""))
                        .as("`%s` is a meter or a property the code reads", text).isTrue();
                named++;
            }
            if (ALERT_LIKE.matcher(text).matches() && !sources.contains("class " + text + " ")) {
                assertThat(alerts).as("the alert `%s` exists", text).contains(text);
                named++;
            }
            Matcher code = CODE_LIKE.matcher(text);
            if (code.matches()) {
                assertThat(sources).as("the code `%s` exists", text).contains("\"" + code.group(1) + "\"");
                named++;
            }
        }
        assertThat(named).as("the section names these, or this checks nothing").isGreaterThan(50);
    }

    @Test
    @DisplayName("the guard has teeth: a route, a method and a permission that do not exist are told apart from those that do")
    void theGuardHasTeeth() {
        Set<String> paths = routesAndMethods().keySet();
        assertThat(paths.stream().anyMatch(path -> matches(path, normalised(".../periods/{periodId}/lock")))).isTrue();
        assertThat(paths.stream().anyMatch(path -> matches(path, normalised(".../periods/{periodId}/force-close")))).as("a door that does not exist").isFalse();
        assertThat(routesAndMethods().get(paths.stream().filter(path -> matches(path, normalised(".../periods/{periodId}/lock"))).findFirst().orElseThrow())).containsExactly("POST");
        assertThat(ROUTE_THEN_PERMISSION.matcher("`GET .../periods/{periodId}/close-run` (`ACCOUNTING_READ`)").find()).isTrue();
    }

    // ------------------------------------------------------------------

    /** Does {@code path} (a route, variables blanked) answer {@code wanted}: a whole route, or {@code ...}'s suffix of one. */
    private static boolean matches(String path, String wanted) {
        if (wanted.startsWith(".../")) {
            return (path + "/").endsWith(wanted.substring(3) + "/") || path.endsWith(wanted.substring(3));
        }
        return path.equals(wanted) || path.equals(wanted.replaceFirst("^/v1", ""));
    }

    /** A path's variables blanked; a segment in capitals names a variable's value, as {@code .../operational-reports/CLOSE_STATUS} does. */
    private static String normalised(String route) {
        String blanked = VARIABLE.matcher(route).replaceAll("{}").replaceAll("/[A-Z][A-Z_]+(?=/|$)", "/{}");
        return blanked.endsWith("/") ? blanked.substring(0, blanked.length() - 1) : blanked;
    }

    private Map<String, Set<String>> routesAndMethods() {
        Map<String, Set<String>> known = new LinkedHashMap<>();
        routes.getHandlerMethods().forEach((info, handler) -> pathsOf(info).forEach(path -> known.computeIfAbsent(path, ignored -> new TreeSet<>())
                .addAll(info.getMethodsCondition().getMethods().stream().map(Enum::name).toList())));
        return known;
    }

    private Map<String, Set<String>> routesAndPermissions() {
        Map<String, Set<String>> required = new LinkedHashMap<>();
        routes.getHandlerMethods().forEach((info, handler) -> {
            String permission = permissionOf(handler);
            if (permission != null) {
                pathsOf(info).forEach(path -> info.getMethodsCondition().getMethods().forEach(method ->
                        required.computeIfAbsent(method.name() + " " + path, ignored -> new TreeSet<>()).add(permission)));
            }
        });
        return required;
    }

    private static String permissionOf(HandlerMethod handler) {
        RequiresPermission requires = handler.getMethodAnnotation(RequiresPermission.class);
        return requires == null ? null : requires.value().name();
    }

    private static Stream<String> pathsOf(RequestMappingInfo info) {
        return info.getPatternValues().stream().filter(Objects::nonNull).map(AccountingRunbookTest::normalised);
    }

    /** One section, from its heading to the next of its level or above. */
    private static String section(String runbook, String number) {
        String heading = "\n### " + number + " ";
        int at = runbook.indexOf(heading);
        assertThat(at).as("section %s exists", number).isPositive();
        Matcher next = Pattern.compile("\\n#{2,3} ").matcher(runbook);
        return next.find(at + 1) ? runbook.substring(at, next.start()) : runbook.substring(at);
    }

    private static String sectionTen() {
        String runbook = RepositoryPaths.read(RUNBOOK);
        int start = runbook.indexOf("\n## 10. ");
        assertThat(start).as("the runbook has an accounting section 10").isPositive();
        int end = runbook.indexOf("\n## ", start + 1);
        return runbook.substring(start, end < 0 ? runbook.length() : end);
    }

    /** Every main source of the app and accounting modules, as one text: where a property a section names is read, or a code declared. */
    private static String mainSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String root : List.of("app/src/main/java", "accounting/src/main/java", "app/src/main/resources")) {
            Path base = RepositoryPaths.locate("settings.gradle.kts").getParent().resolve(root);
            try (Stream<Path> files = Files.walk(base)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        all.append(Files.readString(file)).append('\n');
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException(unreadable);
                    }
                });
            }
        }
        return all.toString();
    }
}
