package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * The canonical form of a decision snapshot, format 2 - format 1 kept for replay (`P10-TSK-008`, format 2
 * `P13-TSK-007`; PHASE_10_PLAN.md section 12.2, {@code INV-CRD-07}): the same content always yields the same bytes, and
 * so the same SHA-256.
 *
 * <h2>Why hand-written</h2>
 *
 * <p>A JSON library's output depends on its configuration, its version and the map it is given; the snapshot's hash
 * must depend on nothing but the content. So the form is written here, key by key in a fixed order, with no whitespace:
 * every value a string token (integers by {@code Long.toString}, money as minor units with its currency and scale,
 * booleans, codes, and {@code ABSENT} explicit), attributes sorted by code ({@link SnapshotContent} sorts them), UTF-8,
 * and nothing read from a locale, a time zone or a map's iteration order. Every token is checked against a character set
 * that needs no escaping, so the form never contains an escape at all.
 *
 * <p>{@link #parse} reads a snapshot back strictly - and refuses any text it would not itself have written in the format
 * the text declares (it re-renders in that format and compares), so a stored snapshot is re-readable and re-verifiable.
 *
 * <p><strong>Format 2</strong> (`P13-TSK-007`; {@code INV-RSK-10}, {@code INV-HIST-04}) differs from format 1 in one
 * place: a port's provenance may name the version of the record the port answered from -
 * {@code "recordVersion":"n"} after its {@code "version"} - which the risk seam's version 2 does with the customer risk
 * profile version it read. Everything else is format 1's, byte for byte. Format 1 is kept, unchanged, for replay: every
 * stored format-1 text parses and re-renders exactly as it did, and a format-1 text that names a record version is
 * refused - format 1 never wrote one. A further change to this form is format 3, with formats 1 and 2 kept.
 */
public final class CanonicalSnapshot {

    /** The format this class writes. */
    public static final int FORMAT = 2;

    /** Every format this class reads - each one it ever wrote, for replay. */
    public static final Set<Integer> FORMATS = Set.of(1, 2);

    private static final String CANONICAL_CHARACTERS = "[A-Za-z0-9_.:-]{0,128}";

    private CanonicalSnapshot() {}

    // ------------------------------------------------------------------ rendering

    /** The canonical form of {@code content}, in the format this class writes. */
    public static String render(SnapshotContent content) {
        return render(content, FORMAT);
    }

    /**
     * The canonical form of {@code content} in {@code format} - a format this class reads; format 1 refuses content
     * naming a record version, which it never wrote.
     */
    public static String render(SnapshotContent content, int format) {
        Objects.requireNonNull(content, "content");
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("not a snapshot format this class writes: " + format);
        }
        StringBuilder out = new StringBuilder(2048);
        out.append("{\"format\":").append(string(Integer.toString(format)));
        out.append(",\"request\":{\"decisionRequest\":").append(string(content.decisionRequest().toString()))
                .append(",\"party\":").append(string(content.party().toString()))
                .append(",\"product\":").append(string(content.product().name()))
                .append(",\"amount\":").append(money(content.requestedAmount()))
                .append(",\"termMonths\":").append(string(content.termMonths().map(Object::toString).orElse("NONE")))
                .append('}');
        out.append(",\"versions\":{\"policy\":").append(string(content.versions().policyVersion().toString()))
                .append(",\"model\":").append(string(content.versions().modelVersion().toString()))
                .append(",\"engine\":").append(string(Integer.toString(content.versions().engineVersion())))
                .append('}');
        out.append(",\"attributes\":[");
        boolean first = true;
        for (CreditAttribute attribute : content.attributes()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"code\":").append(string(attribute.code().name()))
                    .append(",\"value\":").append(value(attribute.value()))
                    .append(",\"provenance\":").append(provenance(attribute.provenance(), format))
                    .append('}');
        }
        return out.append("]}").toString();
    }

    /** The SHA-256 of the canonical form's UTF-8 bytes. */
    public static byte[] sha256(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String value(AttributeValue value) {
        return switch (value) {
            case AttributeValue.IntegerValue integer -> "{\"integer\":" + string(Long.toString(integer.value())) + "}";
            case AttributeValue.MoneyValue money -> money(money.value());
            case AttributeValue.BooleanValue bool -> "{\"boolean\":" + string(Boolean.toString(bool.value())) + "}";
            case AttributeValue.CodeValue code -> "{\"code\":" + string(code.value()) + "}";
            case AttributeValue.Absent absent -> "{\"absent\":\"true\"}";
        };
    }

    private static String money(Money money) {
        return "{\"minor\":" + string(Long.toString(money.minorUnits())) + ",\"currency\":"
                + string(money.currency().code()) + ",\"scale\":" + string(Integer.toString(money.scale())) + "}";
    }

    private static String provenance(AttributeProvenance provenance, int format) {
        return switch (provenance) {
            case AttributeProvenance.Provider p -> "{\"kind\":\"provider\",\"source\":" + string(p.kind().name())
                    + ",\"provider\":" + string(p.providerCode()) + ",\"normaliser\":"
                    + string(Integer.toString(p.normaliserVersion())) + "}";
            case AttributeProvenance.Record r -> "{\"kind\":\"record\",\"record\":" + string(r.record().value().toString())
                    + ",\"source\":" + string(r.kind().name()) + ",\"provider\":" + string(r.providerCode())
                    + ",\"normaliser\":" + string(Integer.toString(r.normaliserVersion())) + "}";
            case AttributeProvenance.Unavailable u -> "{\"kind\":\"unavailable\",\"source\":" + string(u.kind().name())
                    + ",\"dataRequest\":" + string(u.dataRequest().value().toString()) + "}";
            case AttributeProvenance.NotRead n -> "{\"kind\":\"notRead\",\"source\":" + string(n.kind().name()) + "}";
            case AttributeProvenance.Declared d -> "{\"kind\":\"declared\"}";
            case AttributeProvenance.Port p -> "{\"kind\":\"port\",\"port\":" + string(p.port()) + ",\"version\":"
                    + string(Integer.toString(p.version())) + recordVersion(p.recordVersion(), format) + "}";
        };
    }

    /** Format 2's one addition: the record a port answered from. Format 1 never wrote one, so it refuses one. */
    private static String recordVersion(OptionalLong recordVersion, int format) {
        if (recordVersion.isEmpty()) {
            return "";
        }
        if (format == 1) {
            throw new IllegalArgumentException("a format-1 snapshot names no record version");
        }
        return ",\"recordVersion\":" + string(Long.toString(recordVersion.getAsLong()));
    }

    private static String string(String token) {
        if (!token.matches(CANONICAL_CHARACTERS)) {
            throw new IllegalArgumentException("a canonical snapshot token holds no character needing an escape");
        }
        return "\"" + token + "\"";
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Reads a snapshot of any format in {@link #FORMATS} back - strictly: the text must be exactly what
     * {@link #render(SnapshotContent, int)} would write in the format it declares for the content it says, or it is
     * refused.
     */
    public static SnapshotContent parse(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        Map<String, Object> root = object(new Reader(canonical).value());
        int format = FORMATS.stream()
                .filter(known -> String.valueOf(root.get("format")).equals(Integer.toString(known)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a snapshot format this class reads"));
        Map<String, Object> request = object(root.get("request"));
        CreditProduct product = CreditProduct.valueOf(text(request.get("product")));
        String term = text(request.get("termMonths"));
        Map<String, Object> versions = object(root.get("versions"));
        List<CreditAttribute> attributes = new ArrayList<>();
        for (Object entry : list(root.get("attributes"))) {
            Map<String, Object> attribute = object(entry);
            attributes.add(new CreditAttribute(
                    CreditAttributeCode.valueOf(text(attribute.get("code"))),
                    parseValue(object(attribute.get("value"))),
                    parseProvenance(object(attribute.get("provenance")))));
        }
        SnapshotContent content = new SnapshotContent(
                UUID.fromString(text(request.get("decisionRequest"))),
                UUID.fromString(text(request.get("party"))),
                product,
                parseMoney(object(request.get("amount"))),
                term.equals("NONE") ? Optional.empty() : Optional.of(Integer.parseInt(term)),
                new PinnedVersions(UUID.fromString(text(versions.get("policy"))),
                        UUID.fromString(text(versions.get("model"))), Integer.parseInt(text(versions.get("engine")))),
                attributes);
        if (!render(content, format).equals(canonical)) {
            throw new IllegalArgumentException("not a canonical format-" + format + " snapshot");
        }
        return content;
    }

    private static AttributeValue parseValue(Map<String, Object> value) {
        if (value.containsKey("absent")) {
            return new AttributeValue.Absent();
        }
        if (value.containsKey("integer")) {
            return new AttributeValue.IntegerValue(Long.parseLong(text(value.get("integer"))));
        }
        if (value.containsKey("minor")) {
            return new AttributeValue.MoneyValue(parseMoney(value));
        }
        if (value.containsKey("boolean")) {
            return new AttributeValue.BooleanValue(Boolean.parseBoolean(text(value.get("boolean"))));
        }
        return new AttributeValue.CodeValue(text(value.get("code")));
    }

    private static Money parseMoney(Map<String, Object> money) {
        return Money.ofPersisted(Long.parseLong(text(money.get("minor"))), CurrencyCode.of(text(money.get("currency"))),
                Integer.parseInt(text(money.get("scale"))));
    }

    private static AttributeProvenance parseProvenance(Map<String, Object> p) {
        return switch (text(p.get("kind"))) {
            case "provider" -> new AttributeProvenance.Provider(CreditSourceKind.valueOf(text(p.get("source"))),
                    text(p.get("provider")), Integer.parseInt(text(p.get("normaliser"))));
            case "record" -> new AttributeProvenance.Record(CreditRecordId.of(UUID.fromString(text(p.get("record")))),
                    CreditSourceKind.valueOf(text(p.get("source"))), text(p.get("provider")),
                    Integer.parseInt(text(p.get("normaliser"))));
            case "unavailable" -> new AttributeProvenance.Unavailable(CreditSourceKind.valueOf(text(p.get("source"))),
                    CreditDataRequestId.of(UUID.fromString(text(p.get("dataRequest")))));
            case "notRead" -> new AttributeProvenance.NotRead(CreditSourceKind.valueOf(text(p.get("source"))));
            case "declared" -> new AttributeProvenance.Declared();
            case "port" -> new AttributeProvenance.Port(text(p.get("port")), Integer.parseInt(text(p.get("version"))),
                    p.containsKey("recordVersion")
                            ? OptionalLong.of(Long.parseLong(text(p.get("recordVersion"))))
                            : OptionalLong.empty());
            default -> throw new IllegalArgumentException("unknown provenance kind");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("expected an array");
        }
        return (List<Object>) value;
    }

    private static String text(Object value) {
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("expected a string");
        }
        return string;
    }

    /** A strict reader of the subset the renderer writes: objects, arrays and unescaped strings, no whitespace. */
    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            Object value = next();
            if (at != text.length()) {
                throw new IllegalArgumentException("trailing characters after the snapshot");
            }
            return value;
        }

        private Object next() {
            char c = peek();
            if (c == '{') {
                at++;
                Map<String, Object> object = new LinkedHashMap<>();
                if (peek() == '}') {
                    at++;
                    return object;
                }
                do {
                    String key = string();
                    expect(':');
                    if (object.put(key, next()) != null) {
                        throw new IllegalArgumentException("a repeated key");
                    }
                } while (accept(','));
                expect('}');
                return object;
            }
            if (c == '[') {
                at++;
                List<Object> array = new ArrayList<>();
                if (peek() == ']') {
                    at++;
                    return array;
                }
                do {
                    array.add(next());
                } while (accept(','));
                expect(']');
                return array;
            }
            return string();
        }

        private String string() {
            expect('"');
            int end = text.indexOf('"', at);
            if (end < 0) {
                throw new IllegalArgumentException("an unterminated string");
            }
            String value = text.substring(at, end);
            if (value.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("a canonical snapshot holds no escape");
            }
            at = end + 1;
            return value;
        }

        private char peek() {
            if (at >= text.length()) {
                throw new IllegalArgumentException("the snapshot ends early");
            }
            return text.charAt(at);
        }

        private boolean accept(char c) {
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!accept(c)) {
                throw new IllegalArgumentException("expected '" + c + "' at " + at);
            }
        }
    }
}
