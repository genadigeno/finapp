package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/**
 * Every outbound call is bounded, every retry is classified, and the inventory says so of exactly the code there is
 * ({@code P16-TSK-018}; {@code PHASE_16_PLAN.md} section 8; {@code docs/architecture/TIMEOUTS_AND_RETRIES.md}).
 *
 * <p>A provider that stops answering must cost a bounded wait, and a retry must never resend an ambiguous operation blind. Both
 * fail quietly: a request built without its timeout compiles, a call that skips the overall bound works on every happy path, a
 * schedule added next month is in no inventory, and a JVM flag can make the HTTP client resend a {@code POST} by itself. Each
 * member refuses one way:
 *
 * <ol>
 *   <li>every HTTP client in the application's code is built with a connect timeout, and none is built without one;
 *   <li>every request is built with its own timeout (the bound to the response's headers);
 *   <li>every exchange goes through {@code OutboundExchange.send} (the bound over the whole exchange, body included), and no
 *       streaming body handler, whose body would be read outside that bound, is used anywhere;
 *   <li>the inventory's table of clients names exactly the classes that build one, each with all three bounds;
 *   <li>the inventory's table of schedules names exactly the classes that schedule work, nothing is scheduled any other way, every
 *       retry is classified, and a schedule reaching beyond the database from every instance starts at a jittered first tick -
 *       and only those;
 *   <li>the JDK's retry of every method is never switched on.
 * </ol>
 *
 * <p>Hermetic: it reads files only, the application's sources (every module's {@code src/main/java}) and the inventory.
 */
@Tag("architecture")
@DisplayName("every outbound call is bounded and every retry classified, and the inventory names exactly the code (P16-TSK-018)")
class OutboundCallsAreBoundedTest {

    private static final String INVENTORY = "docs/architecture/TIMEOUTS_AND_RETRIES.md";
    private static final String HELPER = "OutboundExchange.java";
    private static final String TICK_PHASE = "TickPhase.firstDelayMillis(";

    private static final Pattern CLIENT_BUILT = Pattern.compile("HttpClient\\s*\\.\\s*newBuilder\\s*\\(\\s*\\)");
    private static final Pattern REQUEST_BUILT = Pattern.compile("HttpRequest\\s*\\.\\s*newBuilder\\s*\\(");
    private static final Pattern CLIENT_FIELD = Pattern.compile("\\bHttpClient\\s+(\\w+)\\s*[;=,)]");
    private static final Pattern SCHEDULED = Pattern.compile("\\.\\s*schedule(?:WithFixedDelay|AtFixedRate)\\s*\\(");
    private static final Pattern STREAMING_BODY = Pattern.compile(
            "BodyHandlers\\s*\\.\\s*(ofInputStream|ofLines|ofPublisher|fromSubscriber|fromLineSubscriber)\\s*\\(");
    private static final Pattern ROW = Pattern.compile("^\\| `([A-Za-z0-9]+)` \\|");
    private static final Pattern CLASSES = Pattern.compile("^\\((a|b|c)\\)(?:, \\((a|b|c)\\))*$");
    private static final Pattern CLASS_FIRST = Pattern.compile("^\\((a|b|c)\\)");

    /** The JDK client's switch that makes it retry a {@code POST} by itself on a closed connection. */
    private static final String ALL_METHOD_RETRY = "jdk.httpclient.enableAllMethodRetry";

    /** Floors, at the code's own counts on 2026-11-13: a scan that stops finding cannot pass as a smaller codebase. */
    private static final int MIN_CLIENTS = 13;
    private static final int MIN_SCHEDULES = 35;
    private static final int MIN_OTHER_RETRIES = 9;

    private static Path root;
    private static Map<String, String> sources;
    private static String inventory;

    @BeforeAll
    static void readTheCode() throws IOException {
        root = EvidenceCitations.repositoryRoot();
        sources = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(file -> file.toString().endsWith(".java"))
                            .forEach(file -> sources.put(root.relativize(file).toString().replace('\\', '/'), read(file)));
                }
            }
        }
        inventory = read(root.resolve(INVENTORY));
    }

    private static String read(Path file) {
        return EvidenceCitations.read(file).replace("\r\n", "\n");
    }

    private static String simpleName(String path) {
        return path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length());
    }

    /** From {@code at} to the end of its statement: the builder chain an assertion may read, and no further. */
    private static String statementFrom(String source, int at) {
        int end = source.indexOf(';', at);
        return end < 0 ? source.substring(at) : source.substring(at, end);
    }

    /** One table of the inventory: its rows by their first cell's backticked name, each row's cells. */
    private static Map<String, List<String>> table(String heading) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        int start = inventory.indexOf("\n" + heading);
        assertThat(start).as("%s has its section '%s'", INVENTORY, heading).isPositive();
        int end = inventory.indexOf("\n#", start + 1);
        for (String line : inventory.substring(start, end < 0 ? inventory.length() : end).split("\n")) {
            Matcher row = ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : line.substring(1, line.length() - 1).split("\\|", -1)) {
                cells.add(cell.trim());
            }
            assertThat(rows.put(row.group(1), cells)).as("%s names %s once", heading, row.group(1)).isNull();
        }
        return rows;
    }

    @Test
    @DisplayName("every HTTP client is built with a connect timeout, and none without one")
    void everyClientHasAConnectTimeout() {
        Map<String, String> problems = new TreeMap<>();
        int built = 0;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Matcher client = CLIENT_BUILT.matcher(source.getValue());
            while (client.find()) {
                built++;
                if (!statementFrom(source.getValue(), client.start()).contains(".connectTimeout(")) {
                    problems.put(source.getKey(), "an HttpClient built with no connect timeout");
                }
            }
            if (source.getValue().contains("HttpClient.newHttpClient(")) {
                problems.put(source.getKey(), "HttpClient.newHttpClient() has no connect timeout");
            }
        }
        assertThat(built).as("clients found: the scan reads the application's code").isGreaterThanOrEqualTo(MIN_CLIENTS);
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every request is built with its own timeout, the bound to the response's headers (the entry's probe)")
    void everyRequestHasARequestTimeout() {
        Map<String, String> problems = new TreeMap<>();
        int requests = 0;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Matcher request = REQUEST_BUILT.matcher(source.getValue());
            int index = 0;
            while (request.find()) {
                requests++;
                index++;
                if (!statementFrom(source.getValue(), request.start()).contains(".timeout(")) {
                    problems.put(source.getKey() + " request " + index, "built with no timeout: its wait for the headers is unbounded");
                }
            }
        }
        assertThat(requests).as("requests found").isGreaterThanOrEqualTo(MIN_CLIENTS);
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every exchange goes through the overall bound, and no body is read as a stream outside it")
    void everyExchangeIsBoundedOverall() {
        Map<String, String> problems = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = source.getValue();
            if (source.getKey().endsWith("/" + HELPER)) {
                continue;
            }
            Matcher field = CLIENT_FIELD.matcher(code);
            Set<String> clients = new TreeSet<>();
            while (field.find()) {
                clients.add(field.group(1));
            }
            for (String client : clients) {
                if (Pattern.compile("\\b" + Pattern.quote(client) + "\\s*\\.\\s*send(?:Async)?\\s*\\(").matcher(code).find()) {
                    problems.put(source.getKey(), client + ".send: an exchange outside OutboundExchange's overall bound");
                }
            }
            if (CLIENT_BUILT.matcher(code).find() && !code.contains("OutboundExchange.send(")) {
                problems.put(source.getKey(), "builds a client and sends nothing through OutboundExchange.send");
            }
            Matcher streaming = STREAMING_BODY.matcher(code);
            if (streaming.find()) {
                problems.put(source.getKey(), "BodyHandlers." + streaming.group(1) + ": a body read after the exchange completed, outside its bound");
            }
        }
        assertThat(problems).isEmpty();
        String helper = sources.entrySet().stream().filter(source -> source.getKey().endsWith("/" + HELPER))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        assertThat(helper).as("the helper awaits the exchange for its bound, cancels it past the bound, and keeps a refused connection's class")
                .contains("exchange.get(overall.toNanos(), TimeUnit.NANOSECONDS)")
                .contains("exchange.cancel(true);\n            throw new HttpTimeoutException(")
                .contains("if (failure instanceof IOException io) {\n            return io;");
    }

    @Test
    @DisplayName("the inventory names exactly the classes that build an HTTP client, each with its three bounds")
    void theInventoryNamesEveryClient() {
        Set<String> inCode = new TreeSet<>();
        sources.forEach((path, code) -> {
            if (CLIENT_BUILT.matcher(code).find()) {
                inCode.add(simpleName(path));
            }
        });
        Map<String, List<String>> rows = table("### 2.1 ");
        assertThat(rows.keySet()).as("%s section 2.1: every class that builds a client, and no other", INVENTORY)
                .containsExactlyInAnyOrderElementsOf(inCode);
        assertThat(rows).hasSizeGreaterThanOrEqualTo(MIN_CLIENTS);
        rows.forEach((name, cells) -> assertThat(cells.subList(2, 5)).as("%s: connect, headers and overall", name)
                .containsOnly("yes"));
    }

    @Test
    @DisplayName("the inventory names exactly the schedules, each retry classified, and jitters every schedule reaching beyond the database from every instance")
    void theInventoryNamesEveryScheduleAndClassifiesIt() {
        Map<String, String> inCode = new TreeMap<>();
        Map<String, String> problems = new TreeMap<>();
        sources.forEach((path, code) -> {
            if (SCHEDULED.matcher(code).find()) {
                inCode.put(simpleName(path), code);
            }
            if (code.contains("@Scheduled")) {
                problems.put(path, "@Scheduled: work scheduled outside a schedule this inventory can name");
            }
            if (code.contains(TICK_PHASE) && !SCHEDULED.matcher(code).find() && !path.endsWith("/TickPhase.java")) {
                problems.put(path, "draws a first tick and schedules nothing");
            }
        });
        Map<String, List<String>> rows = table("### 3.1 ");
        assertThat(rows.keySet()).as("%s section 3.1: every class that schedules work, and no other", INVENTORY)
                .containsExactlyInAnyOrderElementsOf(inCode.keySet());
        assertThat(rows).hasSizeGreaterThanOrEqualTo(MIN_SCHEDULES);
        rows.forEach((name, cells) -> {
            String reaches = cells.get(2);
            String acting = cells.get(3);
            String classes = cells.get(5);
            String firstTick = cells.get(6);
            if (!CLASSES.matcher(classes).matches()) {
                problems.put(name, "its retry is not classified (a), (b) or (c): '" + classes + "'");
            }
            if (!Set.of("every instance", "one per window", "one at a time").contains(acting)) {
                problems.put(name, "acting instances '" + acting + "' is not every instance, one per window or one at a time");
            }
            boolean beyondTheDatabase = !reaches.startsWith("the database");
            boolean wanted = beyondTheDatabase && acting.equals("every instance");
            if (!Set.of("jittered", "one interval").contains(firstTick)) {
                problems.put(name, "first tick '" + firstTick + "' is neither jittered nor one interval");
            } else if (wanted != firstTick.equals("jittered")) {
                problems.put(name, wanted ? "reaches " + reaches + " from every instance and is not jittered"
                        : "jittered though it reaches only the database or acts once per window");
            }
            String code = inCode.get(name);
            if (code != null && firstTick.equals("jittered") != code.contains(TICK_PHASE)) {
                problems.put(name, "the inventory says '" + firstTick + "' where the code "
                        + (code.contains(TICK_PHASE) ? "draws its first tick" : "starts after exactly one interval"));
            }
        });
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every retry that is not a schedule's tick is classified too")
    void everyOtherRetryIsClassified() {
        int start = inventory.indexOf("\n### 3.2 ");
        assertThat(start).isPositive();
        int end = inventory.indexOf("\n#", start + 1);
        Map<String, String> problems = new TreeMap<>();
        int rows = 0;
        for (String line : inventory.substring(start, end).split("\n")) {
            if (!line.startsWith("| ") || line.startsWith("| Retry ") || line.startsWith("|---")) {
                continue;
            }
            rows++;
            String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
            String classes = cells[cells.length - 1].trim();
            if (!CLASS_FIRST.matcher(classes).find()) {
                problems.put(cells[0].trim(), "its class '" + classes + "' does not begin with (a), (b) or (c)");
            }
        }
        assertThat(rows).isGreaterThanOrEqualTo(MIN_OTHER_RETRIES);
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("the JDK client's retry of every method is never switched on: a POST is never resent by the client itself")
    void noPostIsResentByTheClient() throws IOException {
        List<Path> places = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            modules.filter(Files::isDirectory).map(module -> module.resolve("src/main")).filter(Files::isDirectory).forEach(places::add);
        }
        for (String other : List.of("deploy", "infra", "build-logic/src", "Dockerfile", "compose.yaml", "gradle.properties", "build.gradle.kts")) {
            Path place = root.resolve(other);
            if (Files.exists(place)) {
                places.add(place);
            }
        }
        Set<String> setting = new TreeSet<>();
        int read = 0;
        for (Path place : places) {
            try (Stream<Path> files = Files.walk(place)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text;
                    try {
                        text = Files.readString(file);
                    } catch (java.nio.charset.MalformedInputException binary) {
                        continue;
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException(unreadable);
                    }
                    read++;
                    if (text.contains(ALL_METHOD_RETRY)) {
                        setting.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertThat(read).as("files read").isGreaterThan(1000);
        assertThat(setting).as("where %s appears: the client would resend a POST on a closed connection", ALL_METHOD_RETRY).isEmpty();
    }
}
