package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.app.accounting.PeriodCloseDesk;
import com.finapp.app.accounting.RegulatoryAttentionMetrics;
import com.finapp.app.accounting.RegulatorySubmissionMetrics;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
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
 * {@code OPERATIONS_RUNBOOK.md} section 10 held to the code (`P14-TSK-020`): the review's recurring finding is a runbook naming a door its reader cannot open. Read from the text against the live context and the sources, with no list kept here.
 *
 * <p><strong>Exactly what is held.</strong> Every alert of {@code accounting.yml} links to a section that exists and names it. A backticked route is a route of the running application: a whole {@code /v1/...} path, or a {@code .../} shorthand beginning with a literal segment and matched only under
 * {@code /operator/accounting} and {@code /operator/regulatory}, with the method it says. A route written beside a permission, in {@code (`X`)}, {@code under `X`} or {@code with `X`}, requires that permission on every route it matches. A permission-shaped name is a permission, a check, a report or a word of the code,
 * comments stripped. A backticked meter is registered, each label value it writes on a real series and each bare key on the meter; section 10.6's shorthand, {@code attention{kind=...}} and a bare
 * {@code outcome=...}, is read as {@code finapp.accounting.regulatory.attention} and {@code finapp.accounting.regulatory.submission}, whose label values it holds the same way (`P14-TSK-023`'s fix round, M-1: until then the shorthand was
 * read as nothing, and a kind no series has passed). A property is read by the code. An alert is a rule of any rules file. An {@code accounting.*}, {@code regulatory.*}, {@code ledger.*}, {@code identity.*}, {@code bnpl.*} or
 * {@code lending.*} code is a string of the code, and a status written before an accounting code is that code's.
 *
 * <p><strong>What is not held.</strong> JSON fields, reasons and states in prose, a permission written any other way, a route's request or response, and whether a step helps an operator: a reader's to judge.
 */
@Tag("slice")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent", "finapp.accounting.sweeper.enabled=false"})
@DisplayName("the runbook's section 10 names only what exists (P14-TSK-020)")
class AccountingRunbookTest {

    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final Pattern CITED = Pattern.compile("section (\\d+\\.\\d+)");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern ROUTE = Pattern.compile("^(?:(GET|POST|PUT|DELETE) )?((?:/v1/|\\.\\.\\./)[A-Za-z0-9/{}._-]*)(?:\\?.*)?$");
    private static final Pattern ROUTE_THEN_PERMISSION = Pattern.compile("`(GET|POST|PUT|DELETE) ((?:/v1/|\\.\\.\\./)[A-Za-z0-9/{}._-]*)`[^`\\n]{0,60}?(?:\\(|\\bunder |\\bwith )`([A-Z][A-Z_]+)`");
    private static final Pattern PERMISSION_LIKE = Pattern.compile("^(ACCOUNTING|REGULATORY|LEDGER|BNPL|LOAN|RECONCILIATION|SETTLEMENT)_[A-Z_]+$");
    private static final Pattern METER_LIKE = Pattern.compile("^(finapp\\.[a-z0-9._-]+)(\\{.*})?$");
    private static final Pattern ALERT_LIKE = Pattern.compile("^(Accounting|Regulatory|Reconciliation|WriteOff)[A-Z][A-Za-z]+$");
    private static final Pattern CODE_LIKE = Pattern.compile("^(?:(\\d{3}) )?((?:accounting|regulatory|ledger|identity|bnpl|lending)\\.[A-Z][A-Za-z]+)$");
    private static final Pattern LABEL = Pattern.compile("([a-z_]+)(?:=\"?([A-Za-z0-9_.-]+)\"?)?");
    private static final Pattern ALERT_RULE = Pattern.compile("(?m)^\\s*-\\s*alert:\\s*([A-Za-z]+)\\s*$");
    /** Section 10.6's shorthand for the two regulatory meters it names in full beside it: {@code attention{kind=...}}, the braces captured, and a bare {@code outcome=...}. */
    private static final Pattern SHORTHAND = Pattern.compile("^(?:attention(\\{[^}]*})|outcome=[A-Za-z0-9_.-]+)$");
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
            if (wanted.startsWith(".../")) {
                assertThat(wanted.substring(4).split("/")[0]).as("`%s` begins with a literal segment, or it could mean any route", token.group(1)).isNotEqualTo("{}");
            }
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
        Set<String> permissions = Arrays.stream(PermissionName.values()).map(Enum::name).collect(Collectors.toSet());
        Matcher pair = ROUTE_THEN_PERMISSION.matcher(sectionTen());
        int checked = 0;
        while (pair.find()) {
            if (!permissions.contains(pair.group(3))) {
                continue; // `MULTI_FACTOR` and its like: another test's.
            }
            String wanted = normalised(pair.group(2));
            List<String> matching = required.keySet().stream().filter(route -> route.startsWith(pair.group(1) + " "))
                    .filter(route -> matches(route.substring(route.indexOf(' ') + 1), wanted)).toList();
            assertThat(matching).as("`%s %s` is a permissioned route", pair.group(1), pair.group(2)).isNotEmpty();
            assertThat(matching).as("every route `%s %s` could mean requires `%s`", pair.group(1), pair.group(2), pair.group(3))
                    .allSatisfy(route -> assertThat(required.get(route)).contains(pair.group(3)));
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
        Set<String> alerts = allAlerts();
        String sources = mainSources();
        Map<String, Integer> statuses = Arrays.stream(AccountingErrorCode.values()).collect(Collectors.toMap(AccountingErrorCode::code, AccountingErrorCode::status));
        Matcher token = BACKTICKED.matcher(sectionTen());
        int named = 0;
        while (token.find()) {
            String text = token.group(1);
            if (PERMISSION_LIKE.matcher(text).matches()) {
                // A check's code, a report's or a proof's reads like a permission; each is held to its own declaration, a whole word of the sources.
                assertThat(permissions.contains(text) || notPermissions.contains(text) || Pattern.compile("(?<![A-Za-z0-9_])" + text + "(?![A-Za-z0-9_])").matcher(sources).find())
                        .as("`%s` is a permission, a check, a report or a declared code", text).isTrue();
                named++;
            }
            Matcher meter = METER_LIKE.matcher(text);
            if (meter.matches()) {
                String name = meter.group(1);
                assertThat(meters.contains(name) || sources.contains("${" + name) || sources.contains("\"" + name + "\""))
                        .as("`%s` is a meter or a property the code reads", text).isTrue();
                if (meter.group(2) != null) {
                    assertLabels(text, name, meter.group(2));
                }
                named++;
            }
            if (ALERT_LIKE.matcher(text).matches() && !text.endsWith("Test") && !Pattern.compile("\\b(class|interface|record|enum) " + text + "\\b").matcher(sources).find()) {
                assertThat(alerts).as("the alert `%s` exists", text).contains(text);
                named++;
            }
            Matcher shorthand = SHORTHAND.matcher(text);
            if (shorthand.matches()) {
                String name = shorthand.group(1) != null ? RegulatoryAttentionMetrics.ATTENTION : RegulatorySubmissionMetrics.SUBMISSION;
                assertThat(meters).as("`%s` is shorthand for a registered meter", text).contains(name);
                assertLabels(text, name, shorthand.group(1) != null ? shorthand.group(1) : "{" + text + "}");
                named++;
            }
            Matcher code = CODE_LIKE.matcher(text);
            if (code.matches()) {
                assertThat(sources).as("the code `%s` exists", text).contains("\"" + code.group(2) + "\"");
                if (code.group(1) != null && statuses.containsKey(code.group(2))) {
                    assertThat(statuses.get(code.group(2))).as("`%s`: the status is the code's", text).isEqualTo(Integer.valueOf(code.group(1)));
                }
                named++;
            }
        }
        assertThat(named).as("the section names these, or this checks nothing").isGreaterThan(50);
    }

    @Test
    @DisplayName("P14-TSK-023: the close calendar names the monthly safeguarding run: who issues it, when, under which permission, and that nothing issues it for them; the route and permission are held by this suite's own rules")
    void theCalendarNamesTheMonthlyReturn() {
        String calendar = section(RepositoryPaths.read(RUNBOOK), "10.7");

        assertThat(calendar).contains("`SAFEGUARDING_POSITION`").contains("once it is `CLOSED`").contains("an accountant issues the run").contains("`POST /v1/operator/accounting/report-runs` (`ACCOUNTING_REPORT_GENERATE`)").contains("**Nothing issues it for them**").contains("`PI-011`");
    }

    @Test
    @DisplayName("P14-TSK-023: sections 10.6 and 10.11 say what a reopen's receipts now do, in the receipt's own field names, and say what the list leaves out; no word says the receipt does not list them, or to check a gauge before and after")
    void theReopensSectionsSayWhatItsReceiptsDo() {
        String runbook = RepositoryPaths.read(RUNBOOK);
        String six = section(runbook, "10.6");
        String eleven = section(runbook, "10.11");
        Set<String> fields = Arrays.stream(PeriodCloseDesk.PeriodCloseReceipt.class.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());

        for (String field : List.of("overtakenReturns", "overtakenReturnsTotal")) {
            assertThat(fields).contains(field);
            assertThat(six).contains("`" + field + "`");
            assertThat(eleven).contains("`" + field + "`");
        }
        assertThat(fields).contains("overtakenReturnsNote");
        assertThat(six).contains("`overtakenReturnsNote`").contains("**It is information:** it stops nothing").contains("**It is not everything in flight:**").contains("`APPROVED` and about to send");
        assertThat(eleven).contains("an empty one is not nothing in flight");
        for (String section : List.of(six, eleven)) {
            assertThat(section).doesNotContain("does not list").doesNotContain("before and after").doesNotContain("`P14-TSK-023`).");
        }
    }

    @Test
    @DisplayName("P14-TSK-024: sections 10.3, 10.7 and 10.11 name the doors that end a pending unlock and a pending reopen, by whom; 10.11 sends the approver to the read before deciding and says the receipt can differ; no step still says no door withdraws, to approve and lock again, or to ask the proposer for the list")
    void theSectionsNameTheEnds() {
        String runbook = RepositoryPaths.read(RUNBOOK);
        String three = section(runbook, "10.3");
        String seven = section(runbook, "10.7");
        String eleven = section(runbook, "10.11");
        for (String section : List.of(three, seven)) {
            assertThat(section).contains("`POST .../periods/{periodId}/unlock-rejection` (`ACCOUNTING_CLOSE_APPROVE`)").contains("`POST .../periods/{periodId}/unlock-withdrawal` (`ACCOUNTING_CLOSE_PROPOSE`)");
        }
        assertThat(eleven).contains("`POST .../periods/{periodId}/reopen-rejection` (`ACCOUNTING_CLOSE_APPROVE`)").contains("`POST .../periods/{periodId}/reopen-withdrawal` (`ACCOUNTING_CLOSE_PROPOSE`)")
                .contains("**The approver reads it before deciding**: `GET .../periods/{periodId}/close-proposal` (`ACCOUNTING_CLOSE_APPROVE`)").contains("differs from what was read before deciding");
        assertThat(seven).contains("`concludedAt`");
        for (String section : List.of(three, seven, eleven)) {
            assertThat(section).doesNotContainIgnoringCase("no door withdraws").doesNotContain("approve it and lock again").doesNotContain("asks the proposer").doesNotContain("still open)");
        }
    }

    @Test
    @DisplayName("the guard has teeth: a route, a method and a permission that do not exist are told apart from those that do")
    void theGuardHasTeeth() {
        Set<String> paths = routesAndMethods().keySet();
        assertThat(paths.stream().anyMatch(path -> matches(path, normalised(".../periods/{periodId}/lock")))).isTrue();
        assertThat(paths.stream().anyMatch(path -> matches(path, normalised(".../periods/{periodId}/force-close")))).as("a door that does not exist").isFalse();
        assertThat(routesAndMethods().get(paths.stream().filter(path -> matches(path, normalised(".../periods/{periodId}/lock"))).findFirst().orElseThrow())).containsExactly("POST");
        assertThat(ROUTE_THEN_PERMISSION.matcher("`GET .../periods/{periodId}/close-run` (`ACCOUNTING_READ`)").find()).isTrue();
        assertThat(ROUTE_THEN_PERMISSION.matcher("`POST .../submissions/{submissionId}/non-delivery` under `REGULATORY_REPORT_SUBMIT`").find()).isTrue();
        assertThat(SHORTHAND.matcher("attention{kind=stale_unsent}").matches()).as("M-1: the shorthand is read").isTrue();
        assertThat(SHORTHAND.matcher("outcome=run_bytes_differ").matches()).isTrue();
        assertThat(registry.find(RegulatoryAttentionMetrics.ATTENTION).tag("kind", "stale_unsent").meters()).as("a kind the shorthand writes is a real series").isNotEmpty();
        assertThat(registry.find(RegulatoryAttentionMetrics.ATTENTION).tag("kind", "no_such_kind").meters()).as("and one no series has is told apart").isEmpty();
        assertThat(ROUTE_THEN_PERMISSION.matcher("`GET /v1/operator/regulatory/submissions` lists what stands, with `REGULATORY_REPORT_PREPARE`").find()).isTrue();
        assertThat(paths.stream().filter(path -> matches(path, normalised(".../breaks/{breakId}"))).toList()).as("a shorthand reads accounting's and regulatory's routes, never reconciliation's of that name")
                .isNotEmpty().allSatisfy(path -> assertThat(path).contains("/operator/accounting/"));
    }

    // ------------------------------------------------------------------

    /** Does {@code path} (a route, variables blanked) answer {@code wanted}: a whole route, or {@code ...}'s suffix of an accounting or regulatory one. */
    private static boolean matches(String path, String wanted) {
        if (wanted.startsWith(".../")) {
            return (path.contains("/operator/accounting/") || path.contains("/operator/regulatory/")) && path.endsWith(wanted.substring(3));
        }
        return path.equals(wanted) || path.equals(wanted.replaceFirst("^/v1", ""));
    }

    /** A path's variables blanked; a segment in capitals names a variable's value, as {@code .../operational-reports/CLOSE_STATUS} does. */
    private static String normalised(String route) {
        String blanked = VARIABLE.matcher(route).replaceAll("{}").replaceAll("/[A-Z][A-Z_]+(?=/|$)", "/{}");
        return blanked.endsWith("/") ? blanked.substring(0, blanked.length() - 1) : blanked;
    }

    /** Each {@code key="value"} a meter's token writes is a real series of it, and each bare {@code key} a tag of it. */
    private void assertLabels(String text, String name, String braces) {
        Matcher label = LABEL.matcher(braces.substring(1, braces.length() - 1));
        while (label.find()) {
            String key = label.group(1);
            String value = label.group(2);
            if (value != null) {
                assertThat(registry.find(name).tag(key, value).meters()).as("`%s`: %s=%s is a series of %s", text, key, value, name).isNotEmpty();
            } else {
                assertThat(registry.find(name).tagKeys(key).meters()).as("`%s`: %s is a tag of %s", text, key, name).isNotEmpty();
            }
        }
    }

    /** Every alert of every rules file: a section may name another module's. */
    private static Set<String> allAlerts() throws IOException {
        Set<String> alerts = new TreeSet<>();
        Path rules = RepositoryPaths.locate("settings.gradle.kts").getParent().resolve("infra/prometheus/rules");
        try (Stream<Path> files = Files.list(rules)) {
            for (Path file : files.toList()) {
                Matcher alert = ALERT_RULE.matcher(Files.readString(file));
                while (alert.find()) {
                    alerts.add(alert.group(1));
                }
            }
        }
        assertThat(alerts).as("the rules files parse, or this checks nothing").contains("AccountingCloseOverdue", "ReconciliationRuleSetMissing");
        return alerts;
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

    /** Every main source a section may name, as one text, comments stripped so a javadoc's mention proves nothing: where a property is read, or a code declared. */
    private static String mainSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String root : List.of("app/src/main/java", "accounting/src/main/java", "ledger/src/main/java", "identity/src/main/java", "bnpl/src/main/java", "lending/src/main/java", "app/src/main/resources")) {
            Path base = RepositoryPaths.locate("settings.gradle.kts").getParent().resolve(root);
            try (Stream<Path> files = Files.walk(base)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        all.append(withoutComments(Files.readString(file))).append('\n');
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException(unreadable);
                    }
                });
            }
        }
        return all.toString();
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//.*$", " ");
    }
}
