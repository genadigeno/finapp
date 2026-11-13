package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The data tier's availability, held (`P16-TSK-023`; ADR-0144; {@code docs/architecture/AVAILABILITY.md}): the single points of failure of
 * {@code PHASE_16_PLAN.md} section 10.1 each registered with its answer and its check, and the financial-operation availability matrix
 * complete, consistent with where the financial record lives, and walked by the experiments that say they walk it.
 *
 * <p><strong>What is derived, never declared.</strong> The operations' coverage is read from the two edges' generated route lists, which
 * {@code EdgeAllowListTest} renders from the handler mapping: every route served belongs to exactly one operation, so a new door cannot
 * arrive without a verdict under every failure. The components are read from the plan's own table. Which experiments walk which row is read
 * from the harness's calls, not from the document's say-so. And the kind a row must be follows from the experiments that check it: a row the
 * database's experiments check takes the financial record away, and a row the brokers' experiments check takes only the transport away.
 *
 * <p><strong>The rule.</strong> PostgreSQL is the only financial truth. No request is {@code AVAILABLE} or {@code QUEUED} while its record
 * cannot be written; none is {@code REFUSED} for the broker's sake, since only the outbox relay publishes
 * ({@code NoDirectBrokerPublicationRulesTest}).
 *
 * <p>No experiment here has run on a cluster: their runs are owed under {@code POSTPONED_ISSUES.md} PI-025. What this test holds is the text
 * a run would execute and the document it would compare against. Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the single points of failure are registered and the financial-operation matrix is complete, consistent and walked (P16-TSK-023)")
class AvailabilityMatrixTest {

    private static final String DOCUMENT = "docs/architecture/AVAILABILITY.md";
    private static final String PLAN = "docs/project/PHASE_16_PLAN.md";
    private static final String REGISTER = "docs/architecture/RESILIENCE_EXPERIMENTS.md";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String CLIENT = "infra/scripts/kind-rehearsal/Rehearsal.java";
    private static final List<String> ROUTE_LISTS = List.of("deploy/edge/base/public-routes.yaml", "deploy/edge/base/operator-routes.yaml");

    private static final List<String> VERDICTS = List.of("REFUSED", "QUEUED", "DEGRADED", "AVAILABLE");
    private static final Set<String> FAILURE_KINDS =
            Set.of("record", "record-write", "record-slow", "transport", "instance", "edge", "provider", "dependency", "zone", "signal");
    private static final Set<String> REQUEST_KINDS = Set.of("command", "read", "callback");
    private static final Set<String> ROUTELESS_KINDS = Set.of("schedule", "events");

    /**
     * The kind a row must be, from an experiment that checks it: the database taken away (E05, E06, E57, F11) or held (the restores E59,
     * E61) is the record's loss; its disk filled (E24) is its writes' loss; the brokers taken away (E08, E09, F03, F12) is the transport's.
     */
    private static final Map<String, Set<String>> KIND_OF_CHECK = Map.ofEntries(
            Map.entry("E05", Set.of("record")), Map.entry("E06", Set.of("record")), Map.entry("E57", Set.of("record")),
            Map.entry("F11", Set.of("record")), Map.entry("E59", Set.of("record")), Map.entry("E61", Set.of("record")),
            Map.entry("E24", Set.of("record-write")),
            Map.entry("E08", Set.of("transport")), Map.entry("E09", Set.of("transport")), Map.entry("F03", Set.of("transport")),
            Map.entry("F12", Set.of("transport")));

    /** A served route in a generated list: its method and its path, a literal or the edge's anchored regex. */
    private static final Pattern CLAUSE = Pattern.compile("Method\\(`([A-Z]+)`\\) && (Path|PathRegexp)\\(`([^`]+)`\\)");
    private static final Pattern LITERAL_SEGMENT = Pattern.compile("[a-z0-9-]+");
    /** What a pattern's path may hold: nothing a regex reads specially, so the harness's awk reads it as this test does. */
    private static final Pattern PATTERN_PATH = Pattern.compile("/(?:[a-z0-9-]+|\\{[A-Za-z]+})(?:/(?:[a-z0-9-]+|\\{[A-Za-z]+}))*(?:/\\*\\*)?|/\\*\\*");
    private static final Pattern ID = Pattern.compile("\\b([EFD]\\d{2})\\b");
    private static final Pattern REGISTERED = Pattern.compile("(?m)^\\| ([EFD]\\d{2}) \\|");
    private static final Pattern FUNCTION = Pattern.compile("^([A-Za-z0-9_]+)\\(\\) \\{$");
    private static final Pattern WALK_CALL = Pattern.compile("^\\s*availability_check (A\\d{2}) ");

    private static Path root;
    private static String document;
    private static String experiments;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        document = read(DOCUMENT);
        experiments = read(EXPERIMENTS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------------------------------------ reading the document

    /** The table rows of one section ({@code ## <n>. }), each split into its trimmed cells; the header and separator left out. */
    static List<List<String>> rows(String text, String section) {
        return rows(text, section, "\n## ");
    }

    /** The table rows from a heading to the next heading that starts with {@code next}. */
    static List<List<String>> rows(String text, String section, String next) {
        int start = text.indexOf("\n" + section);
        assertThat(start).as("a section %s", section).isNotNegative();
        int end = text.indexOf(next, start + 1);
        String body = text.substring(start, end < 0 ? text.length() : end);
        List<List<String>> rows = new ArrayList<>();
        boolean header = true;
        for (String line : body.split("\n")) {
            if (!line.startsWith("|")) {
                header = true;
                continue;
            }
            if (line.matches("^\\|[-:| ]+\\|$")) {
                continue;
            }
            if (header) {
                header = false;
                continue;
            }
            String[] cells = line.split("\\|", -1);
            List<String> row = new ArrayList<>();
            for (int i = 1; i < cells.length - 1; i++) {
                row.add(cells[i].trim());
            }
            rows.add(row);
        }
        return rows;
    }

    record Route(String id, Set<String> methods, Pattern path, int specificity, String text) {}

    record Operation(String id, String kind, List<Route> routes) {}

    record Failure(String id, String kind, String checkedBy, String walkedBy, Map<String, String> verdicts) {}

    /** One pattern: comma-separated methods and a path; {@code {name}} one segment, a trailing {@code /**} the path and below it. */
    static Route route(String id, String text) {
        String[] parts = text.split(" ");
        assertThat(parts).as("%s's pattern `%s` is its methods and a path", id, text).hasSize(2);
        assertThat(parts[1]).as("%s's pattern `%s` holds nothing a regex reads specially", id, text).matches(PATTERN_PATH);
        boolean prefix = parts[1].endsWith("/**");
        String path = prefix ? parts[1].substring(0, parts[1].length() - 3) : parts[1];
        StringBuilder regex = new StringBuilder("^");
        Matcher variable = Pattern.compile("\\{[A-Za-z]+}").matcher(path);
        int at = 0;
        while (variable.find()) {
            regex.append(Pattern.quote(path.substring(at, variable.start()))).append("[^/]+");
            at = variable.end();
        }
        regex.append(Pattern.quote(path.substring(at))).append(prefix ? "(/.*)?$" : "$");
        return new Route(id, new LinkedHashSet<>(Arrays.asList(parts[0].split(","))), Pattern.compile(regex.toString()),
                prefix ? path.length() : 100_000 + path.length(), text);
    }

    static Map<String, Operation> operations() {
        Map<String, Operation> operations = new LinkedHashMap<>();
        for (List<String> row : rows(document, "## 3. ")) {
            assertThat(row).as("an operation row has four cells: %s", row).hasSize(4);
            assertThat(row.get(0)).as("an operation's id").matches("O\\d{2}");
            List<Route> routes = new ArrayList<>();
            Matcher backticked = EvidenceCitations.BACKTICKED.matcher(row.get(2));
            while (backticked.find()) {
                routes.add(route(row.get(0), backticked.group(1)));
            }
            assertThat(operations.put(row.get(0), new Operation(row.get(0), row.get(3), routes))).as("%s once", row.get(0)).isNull();
        }
        return operations;
    }

    static Map<String, Failure> failures() {
        Map<String, Failure> failures = new LinkedHashMap<>();
        for (List<String> row : rows(document, "## 4. ")) {
            assertThat(row).as("a matrix row has ten cells: %s", row).hasSize(10);
            assertThat(row.get(0)).as("a failure's id").matches("A\\d{2}");
            Map<String, String> verdicts = new TreeMap<>();
            for (int v = 0; v < VERDICTS.size(); v++) {
                String cell = row.get(5 + v);
                if (cell.equals("—")) {
                    continue;
                }
                for (String id : cell.split(",")) {
                    String operation = id.trim();
                    assertThat(operation).as("%s's %s cell names operations", row.get(0), VERDICTS.get(v)).matches("O\\d{2}");
                    assertThat(verdicts.put(operation, VERDICTS.get(v))).as("%s places %s once", row.get(0), operation).isNull();
                }
            }
            assertThat(failures.put(row.get(0), new Failure(row.get(0), row.get(2), row.get(3), row.get(4), verdicts)))
                    .as("%s once", row.get(0)).isNull();
        }
        return failures;
    }

    /** Every route the two edges serve, as a method and a concrete path: a variable segment of the edge's regex read as one segment. */
    static List<String[]> servedRoutes() {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> served = new ArrayList<>();
        for (String list : ROUTE_LISTS) {
            Matcher clause = CLAUSE.matcher(read(list));
            while (clause.find()) {
                String path = clause.group(3);
                if (clause.group(2).equals("PathRegexp")) {
                    assertThat(path).as("an edge regex is anchored both ends").startsWith("^").endsWith("$");
                    StringBuilder concrete = new StringBuilder();
                    for (String segment : path.substring(2, path.length() - 1).split("/", -1)) {
                        concrete.append('/').append(LITERAL_SEGMENT.matcher(segment).matches() ? segment : "x");
                    }
                    path = concrete.toString();
                }
                if (seen.add(clause.group(1) + " " + path)) {
                    served.add(new String[] {clause.group(1), path});
                }
            }
        }
        return served;
    }

    /** The operation whose pattern serves this route most specifically, or why none does. */
    static String best(Map<String, Operation> operations, String method, String path, Map<String, Integer> wins) {
        Route winner = null;
        boolean tie = false;
        for (Operation operation : operations.values()) {
            for (Route route : operation.routes()) {
                if (!route.methods().contains(method) || !route.path().matcher(path).matches()) {
                    continue;
                }
                if (winner == null || route.specificity() > winner.specificity()) {
                    winner = route;
                    tie = false;
                } else if (route.specificity() == winner.specificity() && !route.id().equals(winner.id())) {
                    tie = true;
                }
            }
        }
        if (winner == null) {
            return "no operation serves " + method + " " + path;
        }
        if (tie) {
            return "two operations serve " + method + " " + path + " equally";
        }
        wins.merge(winner.id() + " `" + winner.text() + "`", 1, Integer::sum);
        return winner.id();
    }

    static Set<String> registered() {
        Set<String> ids = new TreeSet<>();
        Matcher row = REGISTERED.matcher(read(REGISTER));
        while (row.find()) {
            ids.add(row.group(1));
        }
        return ids;
    }

    /** Every (experiment, row) pair the harness walks: each availability_check call, under the experiment whose function makes it. */
    static Set<String> walkedInTheHarness() {
        Set<String> pairs = new TreeSet<>();
        String function = "";
        for (String line : experiments.split("\n")) {
            Matcher start = FUNCTION.matcher(line);
            if (start.matches()) {
                function = start.group(1);
            }
            Matcher call = WALK_CALL.matcher(line);
            if (call.find()) {
                assertThat(function).as("availability_check %s is called by an experiment's own step", call.group(1))
                        .matches("E\\d{2}_(fault|observe|recover)");
                pairs.add(function.substring(0, 3) + " " + call.group(1));
            }
        }
        return pairs;
    }

    /** One more reason under a key: a second reason is added to the first, never written over it. */
    private static void note(Map<String, String> problems, String key, String reason) {
        problems.merge(key, reason, (first, next) -> first + "; " + next);
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String source, String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(source);
        assertThat(start.find()).as("defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(source);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return source.substring(start.end(), end.start());
    }

    // ------------------------------------------------------------------------------------------------ the register

    @Test
    @DisplayName("every component of the plan's section 10.1 is registered with its answer and its check: a registered experiment, a"
            + " designed-only row, or N/A and its reason")
    void everyComponentOfThePlanIsRegisteredWithItsAnswer() {
        List<String> planned = new ArrayList<>();
        for (List<String> row : rows(read(PLAN), "### 10.1 ", "\n### ")) {
            planned.add(row.get(0));
        }
        assertThat(planned).as("the plan's section 10.1 read").hasSizeGreaterThanOrEqualTo(12);
        Map<String, List<String>> register = new LinkedHashMap<>();
        for (List<String> row : rows(document, "## 2. ")) {
            assertThat(row).as("a register row has six cells: %s", row).hasSize(6);
            register.put(row.get(0), row);
        }
        assertThat(register.keySet()).as("every component of the plan's section 10.1, by its own name").containsAll(planned);
        Set<String> registered = registered();
        Map<String, String> problems = new TreeMap<>();
        register.forEach((component, row) -> {
            if (row.get(3).isBlank() || row.get(4).isBlank()) {
                note(problems,component, "says whether it is a single point and what answers it");
            }
            String check = row.get(5);
            if (check.startsWith("N/A")) {
                if (!check.matches("^N/A: \\S.{9,}$")) {
                    note(problems,component, "an N/A names its reason");
                }
                return;
            }
            Matcher id = ID.matcher(check);
            boolean any = false;
            while (id.find()) {
                any = true;
                if (!registered.contains(id.group(1))) {
                    note(problems,component, id.group(1) + " is not in " + REGISTER);
                }
            }
            if (!any) {
                note(problems,component, "names no experiment and no designed-only row");
            }
        });
        assertThat(problems).as("the components' checks in %s", DOCUMENT).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ the operations

    @Test
    @DisplayName("every route the edges serve belongs to exactly one operation, every pattern serves a route, and a read is a GET")
    void everyServedRouteBelongsToExactlyOneOperation() {
        Map<String, Operation> operations = operations();
        assertThat(operations).as("the operations read").hasSizeGreaterThanOrEqualTo(19);
        List<String[]> served = servedRoutes();
        assertThat(served).as("the two edges' route lists read").hasSizeGreaterThanOrEqualTo(400);
        Map<String, Integer> wins = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (String[] route : served) {
            String owner = best(operations, route[0], route[1], wins);
            if (!owner.matches("O\\d{2}")) {
                problems.add(owner);
            }
        }
        for (Operation operation : operations.values()) {
            if (operation.routes().isEmpty()) {
                if (!ROUTELESS_KINDS.contains(operation.kind())) {
                    problems.add(operation.id() + " has no route and is a " + operation.kind() + ", not a schedule or the events");
                }
                continue;
            }
            if (!REQUEST_KINDS.contains(operation.kind())) {
                problems.add(operation.id() + " has routes and is a " + operation.kind() + ", not a command, a read or a callback");
            }
            for (Route route : operation.routes()) {
                if (!wins.containsKey(operation.id() + " `" + route.text() + "`")) {
                    problems.add(operation.id() + "'s `" + route.text() + "` is the best match of no served route");
                }
                boolean reads = route.methods().equals(Set.of("GET"));
                if (operation.kind().equals("read") != reads) {
                    problems.add(operation.id() + "'s `" + route.text() + "`: a read is a GET, and a GET is a read");
                }
            }
        }
        assertThat(problems).as("the operations of %s against %s", DOCUMENT, ROUTE_LISTS).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ the matrix

    @Test
    @DisplayName("every failure row places every operation exactly once, under one of the four verdicts, and is of a known kind")
    void everyFailureRowPlacesEveryOperationOnce() {
        Set<String> all = operations().keySet();
        Map<String, Failure> failures = failures();
        assertThat(failures).as("the matrix's rows read").hasSizeGreaterThanOrEqualTo(20);
        Map<String, String> problems = new TreeMap<>();
        failures.forEach((id, failure) -> {
            if (!failure.verdicts().keySet().equals(all)) {
                Set<String> missing = new TreeSet<>(all);
                missing.removeAll(failure.verdicts().keySet());
                Set<String> unknown = new TreeSet<>(failure.verdicts().keySet());
                unknown.removeAll(all);
                note(problems,id, "missing " + missing + ", unknown " + unknown);
            }
            if (!FAILURE_KINDS.contains(failure.kind())) {
                note(problems,id, "its kind `" + failure.kind() + "` is none of " + FAILURE_KINDS);
            }
        });
        assertThat(problems).as("the rows of %s", DOCUMENT).isEmpty();
    }

    @Test
    @DisplayName("every failure row is checked by experiments of the register or marked designed-only, and every walker is among its checks")
    void everyRowIsCheckedByARegisteredExperimentOrDesignedOnly() {
        Set<String> registered = registered();
        assertThat(registered).as("the register read").hasSizeGreaterThanOrEqualTo(60).contains("E05", "E09", "F11", "D01", "D05");
        Map<String, String> problems = new TreeMap<>();
        for (Failure failure : failures().values()) {
            Set<String> checks = new TreeSet<>();
            Matcher id = ID.matcher(failure.checkedBy());
            while (id.find()) {
                checks.add(id.group(1));
            }
            if (checks.isEmpty()) {
                note(problems,failure.id(), "names no experiment and no designed-only row");
            }
            for (String check : checks) {
                if (!registered.contains(check)) {
                    note(problems,failure.id(), check + " is not in " + REGISTER);
                }
            }
            if (!failure.walkedBy().equals("—")) {
                for (String walker : failure.walkedBy().split(",")) {
                    if (!checks.contains(walker.trim())) {
                        note(problems,failure.id(), walker.trim() + " walks the row and is not among its checks");
                    }
                }
            }
        }
        assertThat(problems).as("what checks each row of %s", DOCUMENT).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL is the only financial truth: no request answered while its record cannot be written, none refused for the"
            + " broker's sake, and a row's kind follows from the experiments that check it")
    void noRequestIsAnsweredWhileItsRecordCannotBeWritten() {
        Map<String, Operation> operations = operations();
        Map<String, String> problems = new TreeMap<>();
        for (Failure failure : failures().values()) {
            Matcher id = ID.matcher(failure.checkedBy());
            while (id.find()) {
                Set<String> must = KIND_OF_CHECK.get(id.group(1));
                if (must != null && !must.contains(failure.kind())) {
                    note(problems,failure.id() + " " + id.group(1), id.group(1) + " checks it, so it is " + must + ", not " + failure.kind());
                }
            }
            failure.verdicts().forEach((operation, verdict) -> {
                boolean request = REQUEST_KINDS.contains(operations.get(operation).kind());
                boolean read = operations.get(operation).kind().equals("read");
                String where = failure.id() + " " + operation;
                switch (failure.kind()) {
                    case "record" -> {
                        if (request && !verdict.equals("REFUSED")) {
                            note(problems,where, "the record is away, so a request is REFUSED, not " + verdict);
                        }
                        if (!request && !(verdict.equals("QUEUED") || verdict.equals("REFUSED"))) {
                            note(problems,where, "the record is away, so its work waits (QUEUED) or is refused, not " + verdict);
                        }
                    }
                    case "record-write" -> {
                        if (request && !read && !verdict.equals("REFUSED")) {
                            note(problems,where, "no write commits, so a command or a callback is REFUSED, not " + verdict);
                        }
                        if (read && (verdict.equals("AVAILABLE") || verdict.equals("QUEUED"))) {
                            note(problems,where, "no write commits, so a read is never served as normal: " + verdict);
                        }
                        if (!request && !(verdict.equals("QUEUED") || verdict.equals("REFUSED"))) {
                            note(problems,where, "no write commits, so its work waits or is refused, not " + verdict);
                        }
                    }
                    case "transport" -> {
                        if (verdict.equals("REFUSED")) {
                            note(problems,where, "only the outbox relay publishes, so the broker's loss refuses nothing");
                        }
                    }
                    default -> {}
                }
            });
        }
        assertThat(problems).as("the matrix of %s against the rule its section 1 states", DOCUMENT).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ the walk

    @Test
    @DisplayName("the experiments that walk a row are the ones the harness has walk it, each from its own step, and no others")
    void eachWalkerComparesItsRowAndOnlyItsRow() {
        Set<String> declared = new TreeSet<>();
        for (Failure failure : failures().values()) {
            if (!failure.walkedBy().equals("—")) {
                for (String walker : failure.walkedBy().split(",")) {
                    declared.add(walker.trim() + " " + failure.id());
                }
            }
        }
        assertThat(declared).as("the walks the document declares").contains("E05 A01", "E06 A01", "E09 A06", "E72 A14", "E73 A11");
        assertThat(walkedInTheHarness()).as("the walks %s makes, against those %s declares", EXPERIMENTS, DOCUMENT).isEqualTo(declared);
    }

    @Test
    @DisplayName("the walk reads the document, routes each answer as this test does, and fails on a mismatch either way or on a row it"
            + " could not show")
    void theWalkComparesBothWaysAndReadsTheDocument() {
        assertThat(experiments).as("the document itself, never a copy").contains("AVAILABILITY_MATRIX=\"$ROOT/docs/architecture/AVAILABILITY.md\"");
        String routes = function(experiments, "availability_routes");
        assertThat(routes).as("the operations' patterns, read as this test reads them")
                .contains("/^## 3\\. /{on = 1; next}").contains("gsub(/\\{[A-Za-z]+\\}/, \"[^/]+\", regex)")
                .contains("regex = exact ? \"^\" regex \"$\" : \"^\" regex \"(/.*)?$\"")
                .contains("(exact ? 100000 + length(prefix) : length(prefix))");
        assertThat(function(experiments, "availability_verdicts")).contains("/^## 4\\. /{on = 1; next}")
                .contains("split(\"REFUSED QUEUED DEGRADED AVAILABLE\", verdict, \" \")").contains("$(6 + v)");
        String check = function(experiments, "availability_check");
        assertThat(check).as("the edge's answers ended in the window, the query left off, the most specific pattern, a tie unrouted")
                .contains("$1 >= from && $1 <= to").contains("sub(/\\?.*/, \"\", path)").contains("if (spec[i] > top)")
                .contains("if (best == \"\" || tie) { unmatched++; next }").contains("if ($5 >= 200 && $5 < 300) successes[best]++");
        assertThat(check).as("both ways: a success where refused, no success where answered")
                .contains("else if (v == \"REFUSED\") outcome = (s == 0 ? \"held\" : \"MISMATCH: \"")
                .contains("else if (v == \"QUEUED\" || v == \"AVAILABLE\") outcome = (s > 0 ? \"held\" : \"MISMATCH: no success in \"")
                .contains("else if (a == 0) outcome = \"not exercised\"");
        assertThat(check).as("a mismatch fails the experiment; a row claiming service that exercised nothing could not show itself")
                .contains("EXP_STOPPED=\"${EXP_STOPPED:+$EXP_STOPPED; }the platform answered against the availability matrix's row $1: $mismatched\"")
                .contains("($2 == \"QUEUED\" || $2 == \"AVAILABLE\") {found = 1}").contains("so the row could not show itself")
                .contains("[ \"$placed\" -ge 19 ] || cannot");
        for (String walker : List.of("E05_recover", "E06_recover", "E09_recover", "E72_recover", "E73_recover")) {
            String body = function(experiments, walker);
            assertThat(body.indexOf("experiments_edge_read")).as("%s reads the edge's answers before it walks", walker)
                    .isNotNegative().isLessThan(body.indexOf("availability_check A"));
        }
    }

    @Test
    @DisplayName("the probe is planted in the run's own copy alone, with E06, and a run that sent no transfer in the window shows nothing")
    void theProbeIsPlantedInTheRunsCopyAlone() {
        String scope = function(experiments, "experiments_scope");
        assertThat(scope).contains("if [ \"$PROBE\" = matrix-mismatch ]; then")
                .contains("*\" E06 \"*) ;; *) cannot \"PROBE matrix-mismatch plants row A01, which E06 walks").contains("availability_plant");
        assertThat(scope.indexOf("availability_plant")).as("planted before any experiment runs").isLessThan(scope.indexOf("experiment_run \"$id\""));
        String plant = function(experiments, "availability_plant");
        assertThat(plant).as("the run's copy, row A01, transfers moved from REFUSED to AVAILABLE, read back")
                .contains("planted=\"$WORK/availability-planted.md\"").contains("$2 == \" A01 \" { gsub(/ O04,/, \"\", $7); $10 = \" O04 \" }")
                .contains("AVAILABILITY_MATRIX=$planted").contains("awk -F'\\t' '$1 == \"O04\" {print $2}')\" = AVAILABLE ]")
                .doesNotContain("> \"$AVAILABILITY_MATRIX\"");
        assertThat(function(experiments, "availability_check")).as("the probe's row must have been answered, or the run shows nothing")
                .contains("if [ \"$PROBE\" = matrix-mismatch ] && [ \"$1\" = A01 ]; then").contains("MISMATCH*) log \"PROBE matrix-mismatch")
                .contains("*) cannot \"PROBE matrix-mismatch: the planted row O04 read");
        assertThat(document).as("the document says so").contains("`REHEARSAL_PROBE=matrix-mismatch`, with E06");
    }

    @Test
    @DisplayName("E72 takes the telemetry back ends away through the registry, shows the blindness, and holds the fleet ready and the money"
            + " moving")
    void e72TakesTheTelemetryBackEndsAway() {
        String fault = function(experiments, "E72_fault");
        assertThat(fault).as("both scaled to zero through the registry, restored to one at stop and on every way out")
                .contains("experiments_fault_on scaled finapp-observability finapp-alertmanager 0 1")
                .contains("experiments_fault_on scaled finapp-observability finapp-prometheus 0 1").doesNotContain("kube -n finapp-observability scale");
        String observe = function(experiments, "E72_observe");
        assertThat(observe).as("the blindness shown, and a replica unready for it a stop")
                .contains("[ \"$blind\" = UNREADABLE ] || cannot").contains("experiments_unready_others - \"$E72_FAULT_AT\"")
                .contains("EXP_STOPPED=\"a replica unready while the telemetry back ends were away: $unready\"");
        String recover = function(experiments, "E72_recover");
        assertThat(recover).as("Prometheus back and reading every replica, the history read, the workload resolved, the row walked")
                .contains("count(up{job=\"finapp\"} == 1)").contains("e72_oldest").contains("[ \"$(workload_unresolved e72)\" = 0 ] || fail")
                .contains("assert_flows_ran e72").contains("availability_check A14 \"$E72_FAULT_AT\" \"$E72_FAULT_END\"");
        assertThat(function(experiments, "E72_steady")).contains("[ -n \"$E72_OLDEST_BEFORE\" ] || cannot");
    }

    @Test
    @DisplayName("E73 takes the payout rail and card tokenisation away and counts what its row names: no payout without the provider's"
            + " effect, each unknown once, no blind resend, the refused attach storing nothing")
    void e73TakesThePayoutRailAndTokenisationAway() {
        assertThat(experiments).as("the flags it puts on are cleared on every way out")
                .contains("degrade-tokenisation-unavailable").contains("degrade-payout-unavailable degrade-payout-silent");
        String fault = function(experiments, "E73_fault");
        assertThat(fault).contains("experiments_fault_on hold degrade-payout-unavailable").contains("experiments_fault_on hold degrade-tokenisation-unavailable")
                .contains("experiments_fault_on hold degrade-payout-silent").contains("e73_attach during")
                .contains("case $E73_STATUS in 2*) EXP_STOPPED=").contains("[ \"$E73_AFTER\" = \"$E73_BEFORE\" ] || EXP_STOPPED=");
        assertThat(function(experiments, "provider_stop_condition")).as("its stop condition read on every pass of the watch")
                .contains("E73)").contains("unanswered=$(e73_unanswered)");
        assertThat(function(experiments, "e73_unanswered")).as("a completed payout against the creation the provider took under its reference")
                .contains("status = 'COMPLETED'").contains("provider_idempotency_reference")
                .contains("$2 == \"POST\" && $3 == \"/payouts\" && $5 == \"effect\"");
        assertThat(function(experiments, "e73_resolve_seconds")).as("the bound read from the code's default, refused if an overlay sets it")
                .contains("finapp\\.merchant\\.payout\\.sweeper\\.dispatched-age:PT\\([0-9]*\\)M}").contains("E73_RESOLVE_SECONDS=$((2 * age * 60 + 300))");
        String recover = function(experiments, "E73_recover");
        assertThat(recover).contains("[ \"$waiting\" = 0 ] || fail").contains("e73_attach after").contains("[ \"$E73_STATUS\" = 201 ] || fail")
                .contains("[ \"$E73_AFTER\" = $((E73_BEFORE + 1)) ] || fail").contains("[ -z \"$unanswered\" ] || fail")
                .contains("[ \"$twice\" = 0 ] || fail").contains("provider_blind_resends \"$PROVIDER_START_MILLIS\"")
                .contains("[ \"$degraded\" -ge 1 ] || fail \"E73: the payout rail degraded no request, so nothing was shown: the control\"")
                .contains("availability_check A11 \"$E73_FAULT_AT\"");
        assertThat(function(experiments, "e73_resolved_twice")).contains("e.from_status = 'UNKNOWN' AND e.to_status <> 'UNKNOWN'");
        String client = read(CLIENT);
        assertThat(client).as("the client's attach, counting the customer's methods before and after")
                .contains("case \"attach-card\" -> rehearsal.attachCard(args[2]);").contains("System.out.println(\"before=\" + methodsOf(token));")
                .contains("System.out.println(\"after=\" + methodsOf(token));");
    }

    @Test
    @DisplayName("E74's reading takes every availability objective from the platform's own rules over the run, beside its target, and"
            + " judges none")
    void theObjectivesAreReadFromTheRulesAndNeverJudged() {
        String load = read(LOAD);
        String reading = function(load, "load_availability_objectives");
        assertThat(reading).as("read from the rules, never copied: the availability ones, their rates over the run")
                .contains("\"$ROOT/infra/prometheus/rules/slo-recording.yml\"").contains("status=~\"5\\.\\.\"")
                .contains("sed \"s/rate(/increase(/g; s/\\[5m\\]/[${span}s]/g\")").contains("\"$ROOT/docs/architecture/SLOS.md\"")
                .contains("[ \"$objectives\" -ge 6 ] || cannot").contains("slo-availability.tsv");
        assertThat(reading).as("recorded, never a verdict: a load run fails on correctness only").doesNotContain("fail ");
        assertThat(function(load, "load_prom_at")).contains("&time=$LOAD_ENDED").contains("sed 's/\\\\/%5C/g'");
        String run = function(load, "load_run");
        assertThat(run.indexOf("load_availability_objectives")).as("after the report, every load run").isGreaterThan(run.indexOf("    load_report"));
    }
}
