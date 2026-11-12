package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.context.SmartLifecycle;

/**
 * {@code DISTRIBUTED_EXECUTION.md} section 3, the component register, held both ways against the code (`P15-REM-004` (a),
 * 2026-11-12). Until then nothing read it: {@link NoSingleInstanceAssumptionRulesTest} held the code's process-local side and
 * {@code ShutdownOrderTest} counted the lifecycle beans, and the register drifted between them - the Phase 15 exit review struck
 * two stale rows and corrected the drain's count by hand.
 *
 * <p><strong>What it holds.</strong>
 * <ul>
 *   <li>Every lifecycle component - a concrete production {@link SmartLifecycle} of {@code com.finapp}, read from the bytecode: the
 *       schedules, the consumers, the gauge refresher, the telemetry flush, forty beans of thirty-eight classes today - is named in
 *       a Component cell, by its own name or, nested, by its top-level class's.
 *   <li>The code's arbiters: every production class whose code (comments aside) takes a PostgreSQL advisory lock is named in an
 *       Owner cell of the register's namespace table, which says of itself that every component taking one reserves a namespace
 *       there; every namespace the code takes - a {@code …NAMESPACE = n} constant of such a class, or a literal first argument, in
 *       Java or in a migration's SQL - is a row of that table, every row's namespace is taken, and none is registered twice.
 *   <li>Every class {@link NoSingleInstanceAssumptionRulesTest} exempts (the lease-protected schedulers, the permitted
 *       process-local state) is named in a Component cell: the exemption's comment says its row is here.
 *   <li>The other way: every class a Component or Owner cell names exists, as a production or a test class, and every
 *       {@code …Schedule} a Component cell names is a lifecycle component. A note written {@code *( … )*} is history and is not
 *       read, so a renamed class is kept there rather than deleted.
 *   <li>The drain row's "<em>n</em> schedule classes" is the number of lifecycle classes exposing a public {@code drain()}.
 * </ul>
 *
 * <p><strong>What it does not hold.</strong> That a row's strategy is right - that is the row's review and the counted races it
 * cites. A class named only in prose, outside code marks, is not counted as named. Row locks, unique constraints and conditional
 * updates arbitrate everywhere and are the rows' own business; the advisory namespaces are the one cross-component key space, so
 * they are the arbiters held here.
 */
@Tag("architecture")
@DisplayName("DISTRIBUTED_EXECUTION.md section 3 holds the code both ways: every lifecycle component, advisory lock and exemption has its row,"
        + " every namespace taken is registered and every registered one taken, and every class a row names is real (P15-REM-004 (a))")
class DistributedExecutionRegisterTest {

    private static final String REGISTER = "docs/architecture/DISTRIBUTED_EXECUTION.md";
    private static final String COMPONENT_HEADER = "| Component | State | Multi-instance strategy | Authoritative? |";
    private static final String NAMESPACE_HEADER = "| Namespace | Owner | Key |";
    private static final String DRAIN_ROW = "Shutdown and drain";

    /** A note: history the register keeps beside a row, never read as a claim about the code. */
    private static final Pattern NOTE = Pattern.compile("\\*\\(.*?\\)\\*");
    private static final Pattern SPAN = Pattern.compile("`([^`]+)`");

    /** A class name leading a code span: {@code Name}, {@code Name.member}, {@code Name#test}, {@code Name::method}, {@code Name<T>}. */
    private static final Pattern CLASS_NAME = Pattern.compile("^([A-Z][A-Za-z0-9]*)(?=$|[.#(<]|::)");
    private static final Pattern LOWER = Pattern.compile("[a-z]");

    private static final Pattern ADVISORY_CALL = Pattern.compile("pg_(?:try_)?advisory_xact_lock(?:_shared)?\\(");
    private static final Pattern ADVISORY_LITERAL = Pattern.compile("pg_(?:try_)?advisory_xact_lock(?:_shared)?\\(\\s*(\\d+)\\s*,");
    private static final Pattern NAMESPACE_CONSTANT = Pattern.compile("\\b(?:[A-Z]+_)*NAMESPACE\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern NAMESPACE_CELL = Pattern.compile("^`(\\d+)`$");
    private static final Pattern SCHEDULE_CLASSES = Pattern.compile("\\((\\d+) schedule classes");
    private static final Pattern DECLARED_TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z][A-Za-z0-9_]*)");

    /** The register as parsed: the Component cell of every row of the first table, and every row of the namespace table. */
    record Register(List<String> components, List<NamespaceRow> namespaces) {}

    /** A namespace row: its namespace when it is a number ({@code null} for the one-argument form), and its Owner cell. */
    record NamespaceRow(Integer namespace, String owner) {}

    /**
     * The code as this guard reads it. {@code lifecycle}: each lifecycle class and the names that may stand for it;
     * {@code schedules}: the lifecycle classes exposing {@code drain()}; {@code advisory}: each class taking an advisory lock and
     * the namespaces its code names; {@code taken}: every namespace the code and the migrations take; {@code known}: every
     * production and test class name; {@code exempted}: the classes the single-instance rules exempt.
     */
    record Code(Map<String, Set<String>> lifecycle, Set<String> schedules, Map<String, Set<Integer>> advisory, Set<Integer> taken,
            Set<String> known, Set<String> exempted) {}

    private static Path root;
    private static Register register;
    private static Code code;

    @BeforeAll
    static void readTheRegisterAndTheCode() {
        root = EvidenceCitations.repositoryRoot();
        register = parse(EvidenceCitations.read(root.resolve(REGISTER)));
        code = readTheCode();
    }

    @Test
    @DisplayName("not vacuous: the register's two tables and the code's lifecycle components, advisory locks and namespaces were all read")
    void bothSidesWereRead() {
        assertThat(register.components()).as("the component table's rows, wrapped ones joined").hasSizeGreaterThanOrEqualTo(195);
        assertThat(register.namespaces()).as("the namespace table's rows").hasSizeGreaterThanOrEqualTo(16);
        assertThat(names(register.components())).as("the classes the Component cells name").hasSizeGreaterThan(330);
        assertThat(code.lifecycle()).as("the lifecycle classes: ShutdownOrderTest's forty beans").hasSizeGreaterThanOrEqualTo(38);
        assertThat(code.schedules()).as("the schedule classes").hasSizeGreaterThanOrEqualTo(35);
        assertThat(code.advisory()).as("the classes taking an advisory lock").hasSizeGreaterThanOrEqualTo(20);
        assertThat(code.taken()).as("the namespaces taken, in Java and in the migrations").hasSizeGreaterThanOrEqualTo(15);
        assertThat(code.exempted()).as("the single-instance rules' exemptions").hasSizeGreaterThanOrEqualTo(19);
    }

    @Test
    @DisplayName("every lifecycle component of the code is named in a row of section 3, and every schedule a row names is one")
    void everyLifecycleComponentHasARow() {
        assertThat(lifecycleViolations(register, code)).isEmpty();
    }

    @Test
    @DisplayName("every class taking an advisory lock is named in the namespace table, every namespace the code or a migration takes is a row"
            + " of it, every row's namespace is taken, and none is registered twice")
    void everyAdvisoryLockIsRegisteredWithItsNamespace() {
        assertThat(advisoryViolations(register, code)).isEmpty();
    }

    @Test
    @DisplayName("every class the single-instance rules exempt is named in a row of section 3")
    void everyExemptionHasARow() {
        assertThat(exemptionViolations(register, code)).isEmpty();
    }

    @Test
    @DisplayName("every class a Component or Owner cell names exists in the code, its notes apart, and the drain row counts the code's schedule classes")
    void everyClassARowNamesIsRealAndTheDrainCountIsTheCodes() {
        assertThat(nameViolations(register, code)).isEmpty();
        assertThat(drainViolations(register, code)).isEmpty();
    }

    @Test
    @DisplayName("each rule refuses its plant: a lifecycle component, an advisory lock or an exemption with no row, a namespace unregistered or"
            + " registered for nothing or twice, a name the code does not have, a schedule that is not a lifecycle component, a drain"
            + " count off by one; and a register that holds passes, a renamed class kept in a note included")
    void everyRuleRefusesItsPlant() {
        Code planted = new Code(Map.of("com.x.ASchedule", Set.of("ASchedule"), "com.x.ABeans$Life", Set.of("Life", "ABeans")),
                Set.of("ASchedule"), Map.of("AStore", Set.of(7), "ACutoff", Set.of()), Set.of(7, 2),
                Set.of("ASchedule", "ABeans", "Life", "AStore", "ACutoff", "AThing", "BSchedule", "AContext"), Set.of("AContext"));
        List<String> components = List.of(
                "`ASchedule` — a schedule *(read \"`GoneSchedule`\" until a rename)*",
                "The beans — `ABeans`",
                "`AContext` — a ThreadLocal",
                DRAIN_ROW + " — the 1 schedules (1 schedule classes) through `AThing`");
        List<NamespaceRow> namespaces = List.of(new NamespaceRow(2, "`V009`'s trigger"), new NamespaceRow(7, "`AStore.lockSubject`"),
                new NamespaceRow(null, "`ACutoff`, the one-argument form"));
        Register holds = new Register(components, namespaces);
        assertThat(violations(holds, planted)).as("a register that holds, a renamed class kept in a note").isEmpty();

        assertThat(lifecycleViolations(new Register(components.subList(1, 4), namespaces), planted))
                .as("a lifecycle component no row names").anySatisfy(problem -> assertThat(problem).contains("com.x.ASchedule"));
        assertThat(lifecycleViolations(new Register(List.of("`ASchedule`", "`AContext`", DRAIN_ROW + " (1 schedule classes)"), namespaces),
                planted)).as("a nested lifecycle class whose top-level class no row names").hasSize(1);
        assertThat(advisoryViolations(new Register(components, namespaces.subList(0, 1)), planted))
                .as("an advisory lock with no row in the namespace table").anySatisfy(problem -> assertThat(problem).contains("AStore"));
        assertThat(advisoryViolations(holds, new Code(planted.lifecycle(), planted.schedules(), planted.advisory(), Set.of(7, 2, 8),
                planted.known(), planted.exempted()))).as("a namespace the code takes and nobody registered")
                .anySatisfy(problem -> assertThat(problem).contains("8"));
        assertThat(advisoryViolations(new Register(components, List.of(namespaces.get(0), namespaces.get(1), namespaces.get(2),
                new NamespaceRow(9, "`AThing`"))), planted)).as("a namespace registered that nothing takes")
                .anySatisfy(problem -> assertThat(problem).contains("9"));
        assertThat(advisoryViolations(new Register(components, List.of(namespaces.get(0), namespaces.get(1), namespaces.get(2),
                new NamespaceRow(7, "`AThing`"))), planted)).as("a namespace registered twice")
                .anySatisfy(problem -> assertThat(problem).contains("twice"));
        assertThat(exemptionViolations(new Register(List.of(components.get(0), components.get(1), components.get(3)), namespaces), planted))
                .as("an exemption with no row").anySatisfy(problem -> assertThat(problem).contains("AContext"));
        assertThat(nameViolations(new Register(List.of(components.get(0), "`GoneSchedule` — renamed", components.get(3)), namespaces), planted))
                .as("a name the code no longer has").anySatisfy(problem -> assertThat(problem).contains("GoneSchedule"));
        assertThat(nameViolations(new Register(components, List.of(new NamespaceRow(7, "`GoneStore.lock`"))), planted))
                .as("an owner the code no longer has").anySatisfy(problem -> assertThat(problem).contains("GoneStore"));
        assertThat(nameViolations(new Register(List.of("`BSchedule` — not a lifecycle component"), namespaces), planted))
                .as("a schedule a row names that the code does not run as one").anySatisfy(problem -> assertThat(problem).contains("BSchedule"));
        assertThat(drainViolations(new Register(List.of(DRAIN_ROW + " — (2 schedule classes)"), namespaces), planted))
                .as("a drain row whose count is not the code's").hasSize(1);
        assertThat(drainViolations(new Register(List.of("`ASchedule`"), namespaces), planted)).as("no drain row at all").hasSize(1);
    }

    // ------------------------------------------------------------------------------------------------ the rules

    static List<String> violations(Register register, Code code) {
        List<String> problems = new ArrayList<>();
        problems.addAll(lifecycleViolations(register, code));
        problems.addAll(advisoryViolations(register, code));
        problems.addAll(exemptionViolations(register, code));
        problems.addAll(nameViolations(register, code));
        problems.addAll(drainViolations(register, code));
        return problems;
    }

    static List<String> lifecycleViolations(Register register, Code code) {
        Set<String> named = names(register.components());
        List<String> problems = new ArrayList<>();
        code.lifecycle().forEach((component, standsFor) -> {
            if (Collections.disjoint(standsFor, named)) {
                problems.add(component + " is a lifecycle component, and no row of section 3 names it (by " + standsFor + ")");
            }
        });
        return problems;
    }

    static List<String> advisoryViolations(Register register, Code code) {
        List<String> problems = new ArrayList<>();
        Set<String> owners = names(register.namespaces().stream().map(NamespaceRow::owner).toList());
        code.advisory().keySet().stream().filter(taker -> !owners.contains(taker)).forEach(taker ->
                problems.add(taker + " takes an advisory lock, and no row of the namespace table names it"));
        Set<Integer> registered = new TreeSet<>();
        for (NamespaceRow row : register.namespaces()) {
            if (row.namespace() != null && !registered.add(row.namespace())) {
                problems.add("namespace " + row.namespace() + " is registered twice");
            }
        }
        code.taken().stream().filter(namespace -> !registered.contains(namespace)).forEach(namespace ->
                problems.add("namespace " + namespace + " is taken by the code, and the namespace table has no row for it"));
        registered.stream().filter(namespace -> !code.taken().contains(namespace)).forEach(namespace ->
                problems.add("namespace " + namespace + " is registered, and nothing in the code or the migrations takes it"));
        return problems;
    }

    static List<String> exemptionViolations(Register register, Code code) {
        Set<String> named = names(register.components());
        List<String> problems = new ArrayList<>();
        code.exempted().stream().filter(exempted -> !named.contains(exempted)).forEach(exempted ->
                problems.add(exempted + " is exempted by the single-instance rules, and no row of section 3 names it"));
        return problems;
    }

    static List<String> nameViolations(Register register, Code code) {
        List<String> problems = new ArrayList<>();
        Set<String> lifecycleNames = new TreeSet<>();
        code.lifecycle().values().forEach(lifecycleNames::addAll);
        for (String name : names(register.components())) {
            if (!code.known().contains(name)) {
                problems.add("a Component cell names " + name + ", which is no class of the code: rename it, or keep the old name in a note");
            } else if (name.endsWith("Schedule") && !lifecycleNames.contains(name)) {
                problems.add("a Component cell names " + name + " as a schedule, and it is no lifecycle component of the code");
            }
        }
        for (String name : names(register.namespaces().stream().map(NamespaceRow::owner).toList())) {
            if (!code.known().contains(name)) {
                problems.add("an Owner cell names " + name + ", which is no class of the code");
            }
        }
        return problems;
    }

    static List<String> drainViolations(Register register, Code code) {
        List<String> rows = register.components().stream().filter(cell -> cell.strip().startsWith(DRAIN_ROW)).toList();
        if (rows.size() != 1) {
            return List.of("section 3 has " + rows.size() + " rows for " + DRAIN_ROW + ", and one is its count's place");
        }
        Matcher count = SCHEDULE_CLASSES.matcher(rows.get(0));
        if (!count.find()) {
            return List.of("the " + DRAIN_ROW + " row states no count of schedule classes");
        }
        if (Integer.parseInt(count.group(1)) != code.schedules().size()) {
            return List.of("the " + DRAIN_ROW + " row counts " + count.group(1) + " schedule classes, and the code has "
                    + code.schedules().size() + ": " + code.schedules());
        }
        return List.of();
    }

    /** The class names the cells name in code spans, their notes apart: a leading identifier with a lower-case letter in it. */
    static Set<String> names(List<String> cells) {
        Set<String> names = new TreeSet<>();
        for (String cell : cells) {
            Matcher span = SPAN.matcher(NOTE.matcher(cell).replaceAll(" "));
            while (span.find()) {
                Matcher name = CLASS_NAME.matcher(span.group(1));
                if (name.find() && LOWER.matcher(name.group(1)).find()) {
                    names.add(name.group(1));
                }
            }
        }
        return names;
    }

    // ------------------------------------------------------------------------------------------------ the register

    /** Both tables of section 3, a row wrapped over several lines read as one. */
    static Register parse(String document) {
        String[] lines = document.replace("\r\n", "\n").split("\n", -1);
        int start = -1;
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("## 3. ")) {
                start = i;
            } else if (start >= 0 && lines[i].startsWith("## ")) {
                end = i;
                break;
            }
        }
        assertThat(start).as("%s has a section 3", REGISTER).isNotNegative();
        List<String> section = List.of(lines).subList(start, end);
        List<String> components = new ArrayList<>();
        for (List<String> row : table(section, COMPONENT_HEADER)) {
            components.add(row.get(0));
        }
        List<NamespaceRow> namespaces = new ArrayList<>();
        for (List<String> row : table(section, NAMESPACE_HEADER)) {
            Matcher number = NAMESPACE_CELL.matcher(row.get(0));
            namespaces.add(new NamespaceRow(number.matches() ? Integer.valueOf(number.group(1)) : null, row.get(1)));
        }
        return new Register(components, namespaces);
    }

    private static List<List<String>> table(List<String> section, String header) {
        int at = section.indexOf(header);
        assertThat(at).as("section 3 has the table %s", header).isNotNegative();
        assertThat(section.get(at + 1)).as("its separator").startsWith("|---");
        List<String> rows = new ArrayList<>();
        for (String line : section.subList(at + 2, section.size())) {
            if (line.isBlank() || line.startsWith("#")) {
                break;
            }
            if (line.startsWith("|")) {
                rows.add(line);
            } else {
                rows.set(rows.size() - 1, rows.get(rows.size() - 1) + " " + line);
            }
        }
        List<List<String>> cells = new ArrayList<>();
        for (String row : rows) {
            List<String> split = new ArrayList<>();
            for (String cell : row.strip().substring(1).split("(?<!\\\\)\\|", -1)) {
                split.add(cell.strip());
            }
            cells.add(split);
        }
        return cells;
    }

    // ------------------------------------------------------------------------------------------------ the code

    private static Code readTheCode() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
        Map<String, Set<String>> lifecycle = new TreeMap<>();
        Set<String> schedules = new TreeSet<>();
        for (JavaClass javaClass : production) {
            if (javaClass.isInterface() || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                    || !javaClass.isAssignableTo(SmartLifecycle.class)) {
                continue;
            }
            Set<String> standsFor = new TreeSet<>();
            if (!javaClass.getSimpleName().isEmpty()) {
                standsFor.add(javaClass.getSimpleName());
            }
            standsFor.add(topLevel(javaClass.getName()));
            lifecycle.put(javaClass.getName(), standsFor);
            boolean drains = javaClass.getAllMethods().stream().anyMatch(method -> method.getName().equals("drain")
                    && method.getRawParameterTypes().isEmpty() && method.getModifiers().contains(JavaModifier.PUBLIC));
            if (drains) {
                schedules.add(javaClass.getSimpleName());
            }
        }

        Map<String, Set<Integer>> advisory = new TreeMap<>();
        Set<Integer> taken = new TreeSet<>();
        Set<String> known = new TreeSet<>();
        for (Path source : files("src/main/java", ".java")) {
            String text = EvidenceCitations.read(source);
            String name = baseName(source);
            known.add(name);
            Matcher declared = DECLARED_TYPE.matcher(text);
            while (declared.find()) {
                known.add(declared.group(1));
            }
            String withoutComments = withoutComments(text, true);
            if (ADVISORY_CALL.matcher(withoutComments).find()) {
                Set<Integer> namespaces = new TreeSet<>();
                namespaces.addAll(numbers(ADVISORY_LITERAL, withoutComments));
                namespaces.addAll(numbers(NAMESPACE_CONSTANT, withoutComments));
                advisory.put(name, namespaces);
                taken.addAll(namespaces);
            }
        }
        for (Path migration : files("src/main/resources/db/migration", ".sql")) {
            taken.addAll(numbers(ADVISORY_LITERAL, withoutComments(EvidenceCitations.read(migration), false)));
        }
        for (Path test : files("src/test/java", ".java")) {
            known.add(baseName(test));
        }

        Set<String> exempted = new TreeSet<>();
        NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS.forEach(name -> exempted.add(name.substring(name.lastIndexOf('.') + 1)));
        NoSingleInstanceAssumptionRulesTest.PERMITTED_PROCESS_LOCAL_STATE.forEach(field -> {
            String owner = field.substring(0, field.lastIndexOf('.'));
            exempted.add(owner.substring(owner.lastIndexOf('.') + 1));
        });
        return new Code(lifecycle, schedules, advisory, taken, known, exempted);
    }

    private static String topLevel(String binaryName) {
        String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        int nested = simple.indexOf('$');
        return nested < 0 ? simple : simple.substring(0, nested);
    }

    private static Set<Integer> numbers(Pattern pattern, String text) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            numbers.add(Integer.valueOf(matcher.group(1)));
        }
        return numbers;
    }

    /** Java's block and line comments, or SQL's, removed: a namespace a comment mentions is taken by nothing. */
    private static String withoutComments(String text, boolean java) {
        String blocks = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        return java ? blocks.replaceAll("//[^\\n]*", " ") : blocks.replaceAll("--[^\\n]*", " ");
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    /** Every file of every module under {@code <module>/<within>} with the extension. */
    private static List<Path> files(String within, String extension) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path directory = module.resolve(within);
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(directory)) {
                    walk.filter(path -> path.toString().endsWith(extension)).sorted().forEach(found::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
