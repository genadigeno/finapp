package com.finapp.app.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small PromQL evaluator for alert rules over a Prometheus text scrape (`P11-TSK-030`): enough to prove that
 * a rule FIRES when one sample breaks its condition - which no other guard here can, since none runs Prometheus.
 *
 * <p>The grammar is closed and every token outside it is refused, so a rules file that grows an expression this class
 * cannot evaluate fails its test rather than going unchecked:
 *
 * <pre>
 * expr     := cmp ('or' cmp)*
 * cmp      := vector (OP number)?                         OP: &gt; &gt;= &lt; &lt;= == !=
 * vector   := AGG ('by' '(' label (',' label)* ')')? '(' inner ')'  |  selector ('!=' selector)?
 * inner    := 'increase' '(' selector '[' duration ']' ')'  |  selector ('!=' selector)?
 * selector := name ('{' label ('=' | '!=') "value" (',' ...)* '}')?          AGG: max | min | sum
 * </pre>
 *
 * <p>Semantics follow Prometheus for what the rules use: a comparison with a number FILTERS (NaN compares false, so a
 * threshold never sees an unreadable gauge); {@code x != x} on one selector keeps exactly the NaN samples; {@code max} and
 * {@code min} skip NaN unless every input is NaN; {@code increase} is the counter's difference between the two scrapes it
 * is given (a reset reads the later value); {@code or} adds the right side's series whose labels the left lacks. Target
 * labels a scrape configuration attaches - {@code job}, {@code instance}, static labels - are added to every sample by the
 * caller, as Prometheus does. A rule FIRES when its result is non-empty.
 */
final class RuleEvaluator {

    /** One sample: its series name, its labels (target labels included) and its value. */
    record Sample(String name, Map<String, String> labels, double value) {

        Sample {
            Objects.requireNonNull(name, "name");
            labels = Map.copyOf(labels);
        }

        Sample withValue(double replaced) {
            return new Sample(name, labels, replaced);
        }
    }

    /** A result element: labels (the series name dropped) and a value. */
    private record Element(Map<String, String> labels, double value) {}

    private static final Pattern LINE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{(.*)})?\\s+(\\S+)(\\s+\\S+)?$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[a-zA-Z_:][a-zA-Z0-9_:]*|[0-9]+(?:\\.[0-9]+)?[smhd]?|>=|<=|==|!=|[(){}\\[\\],<>=])");
    private static final Set<String> AGGREGATIONS = Set.of("max", "min", "sum");

    private final List<String> tokens;
    private final List<Sample> before;
    private final List<Sample> after;
    private int at;

    private RuleEvaluator(String expr, List<Sample> before, List<Sample> after) {
        this.tokens = tokenize(expr);
        this.before = before;
        this.after = after;
    }

    /** Whether {@code expr} fires on {@code after} - {@code before} the scrape a window earlier, for {@code increase}. */
    static boolean fires(String expr, List<Sample> before, List<Sample> after) {
        RuleEvaluator evaluator = new RuleEvaluator(expr, before, after);
        List<Element> result = evaluator.expression();
        if (evaluator.at != evaluator.tokens.size()) {
            throw new IllegalArgumentException("unparsed tail in " + expr + " at " + evaluator.tokens.subList(evaluator.at,
                    evaluator.tokens.size()));
        }
        return !result.isEmpty();
    }

    /** A Prometheus text scrape's samples, each given {@code targetLabels}. */
    static List<Sample> samples(String scrape, Map<String, String> targetLabels) {
        List<Sample> samples = new ArrayList<>();
        for (String line : scrape.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher sample = LINE.matcher(line.strip());
            if (!sample.matches()) {
                throw new IllegalArgumentException("not a sample line: " + line);
            }
            Map<String, String> labels = new TreeMap<>(targetLabels);
            if (sample.group(3) != null) {
                Matcher label = LABEL.matcher(sample.group(3));
                while (label.find()) {
                    labels.put(label.group(1), label.group(2));
                }
            }
            samples.add(new Sample(sample.group(1), labels, value(sample.group(4))));
        }
        return samples;
    }

    private static double value(String text) {
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(text);
        };
    }

    // ------------------------------------------------------------------ the grammar

    private List<Element> expression() {
        List<Element> left = comparison();
        while (peek("or")) {
            take("or");
            List<Element> right = comparison();
            Set<Map<String, String>> present = new java.util.HashSet<>();
            left.forEach(element -> present.add(element.labels()));
            List<Element> union = new ArrayList<>(left);
            right.stream().filter(element -> !present.contains(element.labels())).forEach(union::add);
            left = union;
        }
        return left;
    }

    private List<Element> comparison() {
        List<Element> vector = vector();
        if (peek(">") || peek(">=") || peek("<") || peek("<=") || peek("==") || peek("!=")) {
            String op = tokens.get(at++);
            double threshold = number(tokens.get(at++));
            return vector.stream().filter(element -> compare(element.value(), op, threshold)).toList();
        }
        return vector;
    }

    private List<Element> vector() {
        String head = tokens.get(at);
        if (AGGREGATIONS.contains(head)) {
            at++;
            List<String> by = List.of();
            if (peek("by")) {
                take("by");
                by = labelList();
            }
            take("(");
            List<Element> inner = inner();
            take(")");
            return aggregate(head, by, inner);
        }
        return selfCompared();
    }

    private List<Element> inner() {
        if (peek("increase")) {
            take("increase");
            take("(");
            String[] selector = new String[1];
            List<Sample> later = selector(after, selector);
            take("[");
            String window = tokens.get(at++);
            if (!window.matches("[0-9]+[smhd]")) {
                throw new IllegalArgumentException("not a range: " + window);
            }
            take("]");
            take(")");
            Map<Map<String, String>, Double> earlier = new LinkedHashMap<>();
            for (Sample sample : matching(before, selector[0])) {
                earlier.put(sample.labels(), sample.value());
            }
            List<Element> increases = new ArrayList<>();
            for (Sample sample : later) {
                Double was = earlier.get(sample.labels());
                if (was != null) {
                    increases.add(new Element(sample.labels(), sample.value() >= was ? sample.value() - was : sample.value()));
                }
            }
            return increases;
        }
        return selfCompared();
    }

    /** {@code selector} or {@code selector != selector} on the SAME selector - the NaN test. */
    private List<Element> selfCompared() {
        int start = at;
        String[] spelled = new String[1];
        List<Sample> left = selector(after, spelled);
        String leftText = String.join("", tokens.subList(start, at));
        if (peek("!=") && at + 1 < tokens.size() && Character.isLetter(tokens.get(at + 1).charAt(0))) {
            take("!=");
            int rightStart = at;
            selector(after, new String[1]);
            String rightText = String.join("", tokens.subList(rightStart, at));
            if (!leftText.equals(rightText)) {
                throw new IllegalArgumentException("only x != x (the NaN test) is evaluated: " + leftText + " != " + rightText);
            }
            return left.stream().filter(sample -> sample.value() != sample.value())
                    .map(sample -> new Element(sample.labels(), sample.value())).toList();
        }
        return left.stream().map(sample -> new Element(sample.labels(), sample.value())).toList();
    }

    /** A selector's samples in {@code scrape}; its canonical spelling into {@code spelled[0]}. */
    private List<Sample> selector(List<Sample> scrape, String[] spelled) {
        String name = tokens.get(at++);
        if (!name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*") || AGGREGATIONS.contains(name) || name.equals("increase")
                || name.equals("or") || name.equals("by")) {
            throw new IllegalArgumentException("not a series name: " + name);
        }
        StringBuilder spelling = new StringBuilder(name);
        if (peek("{")) {
            take("{");
            spelling.append('{');
            while (true) {
                String label = tokens.get(at++);
                String op = tokens.get(at++);
                if (!op.equals("=") && !op.equals("!=")) {
                    throw new IllegalArgumentException("only = and != matchers are evaluated: " + op);
                }
                String value = tokens.get(at++);
                if (!value.startsWith("\"")) {
                    throw new IllegalArgumentException("a matcher's value is a string: " + value);
                }
                spelling.append(label).append(op).append(value);
                if (peek(",")) {
                    take(",");
                    spelling.append(',');
                    continue;
                }
                take("}");
                spelling.append('}');
                break;
            }
        }
        spelled[0] = spelling.toString();
        return matching(scrape, spelled[0]);
    }

    private static List<Sample> matching(List<Sample> scrape, String spelling) {
        int brace = spelling.indexOf('{');
        String name = brace < 0 ? spelling : spelling.substring(0, brace);
        Map<String, String[]> matchers = new TreeMap<>();
        if (brace >= 0) {
            Matcher matcher = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)(!?=)\"([^\"]*)\"")
                    .matcher(spelling.substring(brace + 1, spelling.length() - 1));
            while (matcher.find()) {
                matchers.put(matcher.group(1), new String[] {matcher.group(2), matcher.group(3)});
            }
        }
        return scrape.stream()
                .filter(sample -> sample.name().equals(name))
                .filter(sample -> matchers.entrySet().stream().allMatch(entry -> {
                    String actual = sample.labels().getOrDefault(entry.getKey(), "");
                    boolean equal = actual.equals(entry.getValue()[1]);
                    return entry.getValue()[0].equals("=") == equal;
                }))
                .toList();
    }

    private List<String> labelList() {
        take("(");
        List<String> labels = new ArrayList<>();
        labels.add(tokens.get(at++));
        while (peek(",")) {
            take(",");
            labels.add(tokens.get(at++));
        }
        take(")");
        return labels;
    }

    private static List<Element> aggregate(String operation, List<String> by, List<Element> inputs) {
        Map<Map<String, String>, List<Double>> groups = new LinkedHashMap<>();
        for (Element input : inputs) {
            Map<String, String> key = new TreeMap<>();
            by.forEach(label -> key.put(label, input.labels().getOrDefault(label, "")));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(input.value());
        }
        List<Element> out = new ArrayList<>();
        groups.forEach((key, values) -> {
            List<Double> numbers = values.stream().filter(value -> !Double.isNaN(value)).toList();
            double result;
            if (operation.equals("sum")) {
                result = values.stream().mapToDouble(Double::doubleValue).sum();
            } else if (numbers.isEmpty()) {
                result = Double.NaN;
            } else if (operation.equals("max")) {
                result = numbers.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            } else {
                result = numbers.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            }
            out.add(new Element(key, result));
        });
        return out;
    }

    private static boolean compare(double value, String op, double threshold) {
        return switch (op) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            case "!=" -> value != threshold;
            default -> throw new IllegalArgumentException(op);
        };
    }

    private static double number(String token) {
        if (!token.matches("[0-9]+(\\.[0-9]+)?")) {
            throw new IllegalArgumentException("a comparison is with a number: " + token);
        }
        return Double.parseDouble(token);
    }

    private boolean peek(String token) {
        return at < tokens.size() && tokens.get(at).equals(token);
    }

    private void take(String token) {
        if (!peek(token)) {
            throw new IllegalArgumentException("expected " + token + " at " + (at < tokens.size() ? tokens.get(at) : "end")
                    + " in " + String.join(" ", tokens));
        }
        at++;
    }

    private static List<String> tokenize(String expr) {
        List<String> tokens = new ArrayList<>();
        Matcher token = TOKEN.matcher(expr);
        int position = 0;
        while (position < expr.length()) {
            if (expr.substring(position).isBlank()) {
                break;
            }
            if (!token.find(position) || token.start() != position) {
                throw new IllegalArgumentException("a token outside the evaluated grammar at " + position + " in " + expr);
            }
            tokens.add(token.group(1));
            position = token.end();
        }
        return tokens;
    }

    /** The series names an expression selects - for coverage checks. */
    static Set<String> seriesOf(String expr) {
        Set<String> names = new TreeSet<>();
        List<String> tokens = tokenize(expr);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean afterBrace = i > 0 && (tokens.get(i - 1).equals("{") || tokens.get(i - 1).equals(","));
            boolean label = afterBrace || (i > 1 && tokens.get(i - 2).equals("by")) || (i > 0 && tokens.get(i - 1).equals("by"));
            if (token.matches("[a-zA-Z_:][a-zA-Z0-9_:]*") && !AGGREGATIONS.contains(token) && !token.equals("increase")
                    && !token.equals("or") && !token.equals("by") && !label && !(i + 1 < tokens.size()
                    && (tokens.get(i + 1).equals("=") || tokens.get(i + 1).equals("!=") && i + 2 < tokens.size()
                            && tokens.get(i + 2).startsWith("\"")))) {
                names.add(token);
            }
        }
        return names;
    }
}
