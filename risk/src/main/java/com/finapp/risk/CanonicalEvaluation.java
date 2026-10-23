package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The canonical form of the sealed inputs and of a verdict, format 1 (`P13-TSK-003`; ADR-0109 decisions 3-4;
 * {@code INV-RSK-01}; credit's {@code CanonicalSnapshot} precedent): the same value always yields the same bytes,
 * and so the same SHA-256 - what `-004` seals beside the assessment and the battery (`P13-TST-002`) fingerprints,
 * and what {@code RiskEngineDigestTest} compares across two JVMs.
 *
 * <p><strong>Why hand-written.</strong> A JSON library's output depends on its configuration, its version and the
 * map it is given; this hash must depend on nothing but the content. So the form is written here key by key in a
 * fixed order, with no whitespace, every value a string token - integers by {@code Long.toString}, money as minor
 * units with currency and scale, booleans, enum names, {@code NEVER} and {@code NONE} spelled out, an absence as
 * {@code {"absent":"true"}} - every map walked in its declaration order ({@link EvaluationInputs} holds them so),
 * nothing read from a locale, a time zone or a salted iteration order. Every token is checked against a character
 * set that needs no escaping, so the form never contains an escape at all.
 *
 * <p>{@link #parseInputs} reads format 1 back strictly - it re-renders and compares, so a text this class would not
 * have written is refused - which lets a sealed inputs text be replayed from the text alone. A verdict is compared
 * by rendering, never parsed. A change to this form is format 2 with format 1 kept here, for replay.
 */
public final class CanonicalEvaluation {

    /** The format this class writes. */
    public static final int FORMAT = 1;

    private static final Pattern CANONICAL_CHARACTERS = Pattern.compile("[A-Za-z0-9_.:,=-]{0,128}");
    private static final String ABSENT = "{\"absent\":\"true\"}";
    private static final String NONE = "NONE";

    private CanonicalEvaluation() {}

    // ------------------------------------------------------------------ rendering

    /** The canonical form of {@code inputs}. */
    public static String render(EvaluationInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        StringBuilder out = new StringBuilder(2048);
        out.append("{\"format\":").append(string(Integer.toString(FORMAT)))
                .append(",\"kind\":\"inputs\",\"operation\":").append(string(inputs.operation().name()));
        out.append(",\"subjects\":[");
        boolean first = true;
        for (SignalSubject subject : inputs.subjects().values()) {
            out.append(first ? "" : ",").append("{\"kind\":").append(string(subject.kind().name()))
                    .append(",\"ref\":").append(string(subject.ref())).append('}');
            first = false;
        }
        out.append("],\"amount\":").append(inputs.amount().value().map(CanonicalEvaluation::money).orElse(ABSENT));
        out.append(",\"velocity\":[");
        first = true;
        for (Map.Entry<VelocityKey, VelocityReading> entry : inputs.velocity().entrySet()) {
            out.append(first ? "" : ",").append("{\"dimension\":").append(string(entry.getKey().dimension().name()))
                    .append(",\"window\":").append(string(entry.getKey().window().name()))
                    .append(",\"count\":").append(string(Long.toString(entry.getValue().count())))
                    .append(",\"sum\":").append(string(Long.toString(entry.getValue().sumMinor()))).append('}');
            first = false;
        }
        out.append("],\"payeeFirstSeen\":").append(firstSeen(inputs.payeeFirstSeen()))
                .append(",\"deviceFirstSeen\":").append(firstSeen(inputs.deviceFirstSeen()))
                .append(",\"sessionAssurance\":").append(value(inputs.sessionAssurance().value().map(Enum::name)));
        out.append(",\"authFailures\":").append(windowed(inputs.authFailures(), "count", v -> Long.toString(v)));
        out.append(",\"credentialChange\":").append(lastSeen(inputs.credentialChange()))
                .append(",\"recovery\":").append(lastSeen(inputs.recovery()))
                .append(",\"accountAgeDays\":").append(value(inputs.accountAgeDays().value().map(Object::toString)))
                .append(",\"kycApproved\":").append(value(inputs.kycApproved().value().map(Object::toString)))
                .append(",\"screeningHit\":").append(value(inputs.screeningHit().value().map(Object::toString)))
                .append(",\"profileRating\":").append(value(inputs.profileRating().value().map(Enum::name)))
                .append(",\"merchantSuspended\":").append(value(inputs.merchantSuspended().value().map(Object::toString)))
                .append(",\"corridor\":").append(value(inputs.corridor().value()));
        MonitoringReadings monitoring = inputs.monitoring();
        out.append(",\"monitoring\":{\"dormantDays\":").append(value(monitoring.dormantDays().value().map(Object::toString)));
        out.append(",\"flows\":[");
        first = true;
        for (Map.Entry<WindowKind, Flow> entry : monitoring.flows().entrySet()) {
            out.append(first ? "" : ",").append("{\"window\":").append(string(entry.getKey().name()))
                    .append(",\"in\":").append(string(Long.toString(entry.getValue().inMinor())))
                    .append(",\"out\":").append(string(Long.toString(entry.getValue().outMinor()))).append('}');
            first = false;
        }
        out.append("],\"amounts\":[");
        first = true;
        for (Map.Entry<WindowKind, Amounts> entry : monitoring.amounts().entrySet()) {
            out.append(first ? "" : ",").append("{\"window\":").append(string(entry.getKey().name()))
                    .append(",\"minor\":[");
            boolean firstAmount = true;
            for (long amount : entry.getValue().minor()) {
                out.append(firstAmount ? "" : ",").append(string(Long.toString(amount)));
                firstAmount = false;
            }
            out.append("]}");
            first = false;
        }
        out.append("],\"refunds\":").append(ratios(monitoring.refunds()))
                .append(",\"chargebacks\":").append(ratios(monitoring.chargebacks()))
                .append(",\"bnplApplications\":").append(windowed(monitoring.bnplApplications(), "count", v -> Long.toString(v)))
                .append("}}");
        return out.toString();
    }

    /** The canonical form of {@code verdict}. */
    public static String render(Verdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        StringBuilder out = new StringBuilder(1024);
        out.append("{\"format\":").append(string(Integer.toString(FORMAT)))
                .append(",\"kind\":\"verdict\",\"engine\":").append(string(verdict.engine().name()))
                .append(",\"operation\":").append(string(verdict.operation().name()));
        out.append(",\"hits\":[");
        boolean first = true;
        for (RuleHit hit : verdict.hits()) {
            out.append(first ? "" : ",").append("{\"ordinal\":").append(string(Integer.toString(hit.ordinal())))
                    .append(",\"kind\":").append(string(hit.kind().name()))
                    .append(",\"outcome\":").append(string(hit.outcome().name()))
                    .append(",\"contribution\":").append(string(Integer.toString(hit.contribution())))
                    .append(",\"hardDecision\":").append(string(hit.hardDecision().map(Enum::name).orElse(NONE)))
                    .append(",\"judged\":").append(string(hit.judged())).append('}');
            first = false;
        }
        out.append("],\"score\":").append(string(Integer.toString(verdict.score().value())))
                .append(",\"decision\":").append(string(verdict.decision().map(Enum::name).orElse(NONE)))
                .append(",\"rating\":").append(string(verdict.rating().map(Enum::name).orElse(NONE)));
        out.append(",\"reasons\":[");
        first = true;
        for (ReasonCode reason : verdict.reasons()) {
            out.append(first ? "" : ",").append(string(reason.code()));
            first = false;
        }
        out.append("],\"failSafeApplied\":").append(string(Boolean.toString(verdict.failSafeApplied()))).append('}');
        return out.toString();
    }

    /** The SHA-256 of the canonical form's UTF-8 bytes, as 64 lowercase hex characters. */
    public static String sha256(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String money(Money money) {
        return "{\"minor\":" + string(Long.toString(money.minorUnits())) + ",\"currency\":"
                + string(money.currency().code()) + ",\"scale\":" + string(Integer.toString(money.scale())) + "}";
    }

    private static String firstSeen(Reading<FirstSeen> reading) {
        return reading.value().map(seen -> "{\"days\":" + string(seen.canonical()) + "}").orElse(ABSENT);
    }

    private static String lastSeen(Reading<LastSeen> reading) {
        return reading.value().map(seen -> "{\"hours\":" + string(seen.canonical()) + "}").orElse(ABSENT);
    }

    private static String value(java.util.Optional<String> token) {
        return token.map(t -> "{\"value\":" + string(t) + "}").orElse(ABSENT);
    }

    private static <V> String windowed(Map<WindowKind, V> map, String field, java.util.function.Function<V, String> token) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<WindowKind, V> entry : map.entrySet()) {
            out.append(first ? "" : ",").append("{\"window\":").append(string(entry.getKey().name()))
                    .append(",\"").append(field).append("\":").append(string(token.apply(entry.getValue()))).append('}');
            first = false;
        }
        return out.append(']').toString();
    }

    private static String ratios(Map<WindowKind, Ratio> map) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<WindowKind, Ratio> entry : map.entrySet()) {
            out.append(first ? "" : ",").append("{\"window\":").append(string(entry.getKey().name()))
                    .append(",\"numerator\":").append(string(Long.toString(entry.getValue().numerator())))
                    .append(",\"denominator\":").append(string(Long.toString(entry.getValue().denominator()))).append('}');
            first = false;
        }
        return out.append(']').toString();
    }

    private static String string(String token) {
        if (!CANONICAL_CHARACTERS.matcher(token).matches()) {
            throw new IllegalArgumentException("a canonical token holds no character needing an escape");
        }
        return "\"" + token + "\"";
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Reads a format-1 inputs form back - strictly: the text must be exactly what {@link #render(EvaluationInputs)}
     * writes for the value it describes, or it is refused.
     *
     * @throws IllegalArgumentException for any text this class would not have written
     */
    public static EvaluationInputs parseInputs(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        EvaluationInputs inputs;
        try {
            inputs = read(canonical);
        } catch (RuntimeException refused) {
            throw new IllegalArgumentException("not a format-" + FORMAT + " inputs form: " + refused.getMessage(), refused);
        }
        if (!render(inputs).equals(canonical)) {
            throw new IllegalArgumentException("not a format-" + FORMAT + " inputs form: the text is not canonical");
        }
        return inputs;
    }

    private static EvaluationInputs read(String canonical) {
        Map<String, Object> root = object(new Reader(canonical).document());
        if (!text(root, "format").equals(Integer.toString(FORMAT)) || !text(root, "kind").equals("inputs")) {
            throw new IllegalArgumentException("not an inputs form of format " + FORMAT);
        }
        RiskOperation operation = RiskOperation.valueOf(text(root, "operation"));
        Map<SubjectKind, SignalSubject> subjects = new EnumMap<>(SubjectKind.class);
        for (Object element : list(root, "subjects")) {
            Map<String, Object> subject = object(element);
            SubjectKind kind = SubjectKind.valueOf(text(subject, "kind"));
            if (subjects.put(kind, new SignalSubject(kind, text(subject, "ref"))) != null) {
                throw new IllegalArgumentException("a subject kind is held once");
            }
        }
        Map<VelocityKey, VelocityReading> velocity = new TreeMap<>();
        for (Object element : list(root, "velocity")) {
            Map<String, Object> reading = object(element);
            VelocityKey key = new VelocityKey(SubjectKind.valueOf(text(reading, "dimension")),
                    WindowKind.valueOf(text(reading, "window")));
            if (velocity.put(key, new VelocityReading(number(reading, "count"), number(reading, "sum"))) != null) {
                throw new IllegalArgumentException("a velocity key is held once");
            }
        }
        Map<WindowKind, Long> authFailures = windowed(list(root, "authFailures"), "count");
        Map<String, Object> monitoring = object(root.get("monitoring"));
        Map<WindowKind, Flow> flows = new EnumMap<>(WindowKind.class);
        for (Object element : list(monitoring, "flows")) {
            Map<String, Object> flow = object(element);
            put(flows, WindowKind.valueOf(text(flow, "window")), new Flow(number(flow, "in"), number(flow, "out")));
        }
        Map<WindowKind, Amounts> amounts = new EnumMap<>(WindowKind.class);
        for (Object element : list(monitoring, "amounts")) {
            Map<String, Object> window = object(element);
            List<Long> minor = new ArrayList<>();
            for (Object amount : list(window, "minor")) {
                minor.add(Long.parseLong(text(amount)));
            }
            put(amounts, WindowKind.valueOf(text(window, "window")), new Amounts(minor));
        }
        MonitoringReadings readings = new MonitoringReadings(
                reading(monitoring, "dormantDays").map(Integer::parseInt),
                flows, amounts, ratios(list(monitoring, "refunds")), ratios(list(monitoring, "chargebacks")),
                windowed(list(monitoring, "bnplApplications"), "count"));
        return new EvaluationInputs(operation, subjects, money(root.get("amount")), velocity,
                firstSeen(root.get("payeeFirstSeen")), firstSeen(root.get("deviceFirstSeen")),
                reading(root, "sessionAssurance").map(RiskAssurance::valueOf), authFailures,
                lastSeen(root.get("credentialChange")), lastSeen(root.get("recovery")),
                reading(root, "accountAgeDays").map(Integer::parseInt),
                reading(root, "kycApproved").map(CanonicalEvaluation::bool),
                reading(root, "screeningHit").map(CanonicalEvaluation::bool),
                reading(root, "profileRating").map(RiskRating::valueOf),
                reading(root, "merchantSuspended").map(CanonicalEvaluation::bool),
                reading(root, "corridor"), readings);
    }

    private static Reading<FirstSeen> firstSeen(Object node) {
        Map<String, Object> object = object(node);
        if (isAbsent(object)) {
            return Reading.absent();
        }
        String days = text(object, "days");
        return Reading.of(days.equals("NEVER") ? FirstSeen.never() : FirstSeen.daysAgo(Integer.parseInt(days)));
    }

    private static Reading<LastSeen> lastSeen(Object node) {
        Map<String, Object> object = object(node);
        if (isAbsent(object)) {
            return Reading.absent();
        }
        String hours = text(object, "hours");
        return Reading.of(hours.equals("NEVER") ? LastSeen.never() : LastSeen.hoursAgo(Long.parseLong(hours)));
    }

    private static Reading<Money> money(Object node) {
        Map<String, Object> object = object(node);
        if (isAbsent(object)) {
            return Reading.absent();
        }
        return Reading.of(Money.ofPersisted(number(object, "minor"), CurrencyCode.of(text(object, "currency")),
                Integer.parseInt(text(object, "scale"))));
    }

    /** A {@code {"value":...}} or absent reading, as the token. */
    private static Reading<String> reading(Map<String, Object> parent, String name) {
        Map<String, Object> object = object(parent.get(name));
        return isAbsent(object) ? Reading.absent() : Reading.of(text(object, "value"));
    }

    private static Map<WindowKind, Long> windowed(List<Object> elements, String field) {
        Map<WindowKind, Long> map = new EnumMap<>(WindowKind.class);
        for (Object element : elements) {
            Map<String, Object> entry = object(element);
            put(map, WindowKind.valueOf(text(entry, "window")), number(entry, field));
        }
        return map;
    }

    private static Map<WindowKind, Ratio> ratios(List<Object> elements) {
        Map<WindowKind, Ratio> map = new EnumMap<>(WindowKind.class);
        for (Object element : elements) {
            Map<String, Object> entry = object(element);
            put(map, WindowKind.valueOf(text(entry, "window")),
                    new Ratio(number(entry, "numerator"), number(entry, "denominator")));
        }
        return map;
    }

    private static <V> void put(Map<WindowKind, V> map, WindowKind window, V value) {
        if (map.put(window, value) != null) {
            throw new IllegalArgumentException("a window is held once");
        }
    }

    private static boolean isAbsent(Map<String, Object> object) {
        return object.size() == 1 && "true".equals(object.get("absent"));
    }

    private static boolean bool(String token) {
        return switch (token) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("not a boolean token");
        };
    }

    private static long number(Map<String, Object> object, String name) {
        return Long.parseLong(text(object, name));
    }

    private static String text(Map<String, Object> object, String name) {
        return text(object.get(name));
    }

    private static String text(Object node) {
        if (!(node instanceof String s)) {
            throw new IllegalArgumentException("a string token was expected");
        }
        return s;
    }

    private static List<Object> list(Map<String, Object> object, String name) {
        Object node = object.get(name);
        if (!(node instanceof List<?> l)) {
            throw new IllegalArgumentException("an array was expected at " + name);
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) l;
        return list;
    }

    private static Map<String, Object> object(Object node) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("an object was expected");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) m;
        return map;
    }

    /** The subset this class writes: objects, arrays and quoted tokens, no whitespace, no escapes. */
    private static final class Reader {

        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Object document() {
            Object value = value();
            if (at != text.length()) {
                throw new IllegalArgumentException("trailing text");
            }
            return value;
        }

        private Object value() {
            char c = peek();
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            throw new IllegalArgumentException("unexpected character at " + at);
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            if (peek() == '}') {
                at++;
                return object;
            }
            while (true) {
                String key = string();
                expect(':');
                if (object.put(key, value()) != null) {
                    throw new IllegalArgumentException("a key is held once");
                }
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect('}');
                return object;
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> array = new ArrayList<>();
            if (peek() == ']') {
                at++;
                return array;
            }
            while (true) {
                array.add(value());
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect(']');
                return array;
            }
        }

        private String string() {
            expect('"');
            int start = at;
            while (at < text.length() && text.charAt(at) != '"') {
                at++;
            }
            if (at >= text.length()) {
                throw new IllegalArgumentException("an unterminated string");
            }
            String token = text.substring(start, at);
            at++;
            if (!CANONICAL_CHARACTERS.matcher(token).matches()) {
                throw new IllegalArgumentException("a token outside the canonical character set");
            }
            return token;
        }

        private char peek() {
            if (at >= text.length()) {
                throw new IllegalArgumentException("unexpected end of text");
            }
            return text.charAt(at);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + at);
            }
            at++;
        }
    }
}
