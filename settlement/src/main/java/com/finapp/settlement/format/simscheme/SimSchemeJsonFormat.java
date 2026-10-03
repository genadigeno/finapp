package com.finapp.settlement.format.simscheme;

import com.finapp.settlement.ConservativeScreen;
import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.LineDirection;
import com.finapp.settlement.LineReferenceKind;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementLineType;
import com.finapp.settlement.format.FormatDefect;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.settlement.format.ParsedLine;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.Serial;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The simulated instant scheme's JSON cycle report, version 1 (`P8-TSK-017`, ADR-0065 §2,
 * ADR-0066 §§3, 8) — frozen by its golden-file test: any change to this screen, this reader or
 * this parser is a NEW version, never an edit.
 *
 * <h2>The shape v1 freezes</h2>
 *
 * <pre>
 * {"format": "SIM_SCHEME_JSON", "version": 1, "cycle": &lt;token&gt;, "currency": &lt;CCY&gt;,
 *  "businessDate": &lt;yyyy-mm-dd&gt;, "remittanceReference": &lt;SCH-REM-digits&gt;,
 *  "entries": [
 *    {"seq": &lt;n&gt;, "code": &lt;CT|RT&gt;, "dir": &lt;C|D&gt;, "amount": "&lt;decimal&gt;",
 *     ["fee": "&lt;decimal&gt;",] "schemeRef": &lt;ref&gt;, ["endToEndRef": &lt;ref&gt;,]
 *     ["ourRef": &lt;ref&gt;,] ["narrative": &lt;free text, 1..140 code points&gt;]}, ...],
 *  "net": "&lt;signed decimal&gt;", "entryCount": &lt;n&gt;}
 * </pre>
 *
 * <p>One JSON object (RFC 8259), UTF-8 with an optional BOM. Whitespace and member order are free;
 * every member appears EXACTLY once — a duplicate or an undeclared member is the file's defect,
 * never skipped. <strong>Amounts are JSON strings, never JSON numbers</strong>: a number invites
 * whoever reads the file to hold it as a binary float ({@code INV-MON-01}), so
 * {@code "amount": 10.00} is malformed; the counts ({@code version}, {@code seq},
 * {@code entryCount}) are JSON integers. Each entry's amount is positive and its direction is its
 * {@code dir}: {@code C} into the platform ({@code CREDIT_IN}, inbound), {@code D} out of it
 * ({@code DEBIT_OUT}, outbound), for a credit transfer and a return alike — the code is the
 * scheme's word and is checked, the direction decides the type. A non-zero {@code fee} splits the
 * entry into its transaction line and a {@code SCHEME_FEE} line carrying {@code ORIGINAL_REF} —
 * the PSP report's gross-plus-fee split — so the report's net is Σ credits − Σ debits − Σ fees; a
 * negative net is a net-payable cycle. {@code entryCount} counts entries, before any split.
 *
 * <p><strong>One cycle per file.</strong> The cycle token is the scheme's opaque identifier and
 * becomes the batch's {@code externalBatchRef}: the live batch identity keys on it, so a second
 * report of one cycle is a {@code CONFLICTING_BATCH} like any repeated batch. A file declaring
 * several cycles — {@code cycle} twice, or as a list — is the multi-part shape v1 does not deliver,
 * and is {@code UNSUPPORTED_FORMAT}. Every line is dated by the report's business date, which is
 * also the cycle's settlement date; a scheme report carries no value date.
 *
 * <p><strong>This class is the scheme vocabulary's whole world</strong> ({@code INV-PAY-03}):
 * {@code CT} and {@code RT} appear here and nowhere else —
 * {@code SettlementVocabularyIsConfinedTest} holds the line — and what leaves is the platform's
 * canonical line, typed, positive and dated. The narrative rests only inside the stored, encrypted
 * file; no canonical field is ever read out of it.
 *
 * <h2>The field-class screen (ADR-0066 §3, C6)</h2>
 *
 * <p>A single well-formed JSON object is screened value by value: every string — and every number,
 * which is text in the file too — is checked by its member's declared class and, <strong>failing
 * it, screened as free text</strong> before the file can be stored as malformed; the narrative is
 * declared free text and always screened, and an undeclared member's NAME is screened as free text
 * as well, since a name is the counterparty's text like any value ({@code INV-PAY-02},
 * {@code INV-RAIL-03}). The values screened are the DECODED strings, so a card number written in
 * JSON's unicode escapes is still a card number. For the discipline to refuse instrument data put
 * where a reference belongs, no class admits an instrument shape: the cycle and reference classes
 * refuse the international account shape and any digit run of card length, and no amount, count
 * or date class admits a run of thirteen digits. Bytes that are not one well-formed JSON object —
 * not UTF-8, not JSON, not an object, or anything after it — are screened as one conservative
 * stream.
 *
 * <p>The screen's record count is the entries' count, not the physical lines: a JSON document's
 * lines measure its whitespace — a minified report of a hundred thousand entries is one line, a
 * pretty-printed one of five thousand is fifty thousand — and the 50,000-record bound exists to
 * keep the parse and acceptance transactions finite, which is a matter of entries. Every array
 * element counts, so no member can hide records from the bound.
 *
 * <p>The JSON reader is this adapter's own and deliberately strict: objects, arrays, strings with
 * the standard escapes, numbers and the three literals ({@code true}, {@code false} and
 * {@code null} are read, then refused wherever v1 expects a value, since v1 uses none); no trailing
 * comma, no comment, no leading zero, no raw control character in a string, nothing after the
 * root. A singleton, like the PSP's report: v1 needs no configuration, and the adapter is pure —
 * no I/O, no clock, no database — so a verdict and a parse are functions of the bytes.
 */
public final class SimSchemeJsonFormat implements SettlementFormat {

    public static final SimSchemeJsonFormat INSTANCE = new SimSchemeJsonFormat();

    /**
     * The shape of the scheme's remittance references: the report's own
     * {@code remittanceReference}, and the pattern the composition root declares for the scheme's
     * source, by which the bank statement's lines are attributed to this report's remittance
     * (ADR-0065 hop 2). One constant, so the reference the parser admits and the reference the
     * attribution reaches cannot disagree. Never a digit run of card length.
     */
    public static final String REMITTANCE_REFERENCE = "SCH-REM-[0-9]{4,12}";

    /** Defect rows are bounded like their table (`V003`): the first hundred tell the story. */
    private static final int MAX_DEFECTS = 100;

    /**
     * The cycle token's bound: payments' {@code PushAnswer.MAX_CYCLE_LENGTH}, the bound the push
     * rail stores a cycle under — restated, since settlement never sees payments.
     */
    private static final int MAX_CYCLE_LENGTH = 64;

    /** A reference's bound: the canonical line's, and {@code line_reference}'s {@code CHECK}. */
    private static final int MAX_REFERENCE_LENGTH = 100;

    /** The narrative's frozen bound, in code points. */
    private static final int MAX_NARRATIVE = 140;

    /**
     * v1's deepest value sits at depth three (the report, its entries, an entry). A document
     * nested beyond eight is no v1 report by any reading, and the bound keeps a hostile nesting
     * from exhausting the reader's stack.
     */
    private static final int MAX_DEPTH = 8;

    /** A defect's or a reader failure's "line" for the file as a whole. */
    private static final int WHOLE_FILE = 0;

    /** U+FEFF, written as its code point: an invisible character has no place in source. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    // The scheme's own words - confined to this file (INV-PAY-03). Each one is a whole literal:
    // SettlementVocabularyIsConfinedTest scans for exactly these.
    private static final String P_CREDIT_TRANSFER = "CT";
    private static final String P_RETURN = "RT";

    // The scheme's directions, from the platform's view.
    private static final String DIR_IN = "C";
    private static final String DIR_OUT = "D";

    /**
     * No instrument shape anywhere in a value: neither the international account shape the door
     * refuses (bounded by non-alphanumerics, exactly as {@link ConservativeScreen} reads it) nor a
     * digit run of card length (single dashes between digits collapsed, as the screen's walk
     * collapses them). A value that carries one fails its class and is screened as free text (C6).
     */
    // ':' and '_' collapse like '-' since the re-gate's NEW-SEC-2 (2026-10-03): a
    // machine-grouped card run is no reference either - ReferenceShape and the shared
    // InstrumentShapes screen carry the same rule, so storage and serving agree.
    private static final String NO_INSTRUMENT_SHAPE =
            "(?!.*(?<![A-Za-z0-9])[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}(?![A-Za-z0-9]))"
                    + "(?!.*[0-9](?:[:_-]?[0-9]){12})";

    /** The characters of the scheme's opaque tokens: the push rail's own reference alphabet. */
    private static final String REFERENCE_ALPHABET = "[A-Za-z0-9._:-]";

    /** The scheme's opaque cycle token — the batch identity. */
    private static final Pattern CYCLE =
            Pattern.compile(
                    NO_INSTRUMENT_SHAPE + REFERENCE_ALPHABET + "{1," + MAX_CYCLE_LENGTH + "}");

    /** The scheme's reference for an execution — the line's first key. */
    private static final Pattern SCHEME_REFERENCE =
            Pattern.compile(
                    NO_INSTRUMENT_SHAPE + REFERENCE_ALPHABET + "{1," + MAX_REFERENCE_LENGTH + "}");

    /**
     * The platform's OWN minted reference, admitted exactly: a UUIDv7 without its dashes
     * (ADR-0013) — what payments gives a push as its end-to-end reference and a return as its
     * dispatch reference. Its hex holds a digit run of card length about once in sixty, so the
     * no-instrument class alone would refuse the platform's own references at random; the PSP
     * report's acquirer reference is the precedent for a class that admits its own legitimate
     * digit runs. Anything else the scheme echoes is held to the no-instrument class.
     */
    private static final String PLATFORM_REFERENCE =
            "[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15}";

    /** The end-to-end reference the platform gave a push, echoed by the scheme. */
    private static final Pattern END_TO_END_REFERENCE =
            Pattern.compile(
                    "(?:" + PLATFORM_REFERENCE + ")|(?:" + NO_INSTRUMENT_SHAPE + REFERENCE_ALPHABET
                            + "{1," + MAX_REFERENCE_LENGTH + "})");

    /** Our own reference, where the scheme echoes it back. */
    private static final Pattern OUR_REFERENCE =
            Pattern.compile(
                    "(?:" + PLATFORM_REFERENCE + ")|(?:" + NO_INSTRUMENT_SHAPE + REFERENCE_ALPHABET
                            + "{1," + MAX_REFERENCE_LENGTH + "})");

    private static final Pattern REMITTANCE_REFERENCE_SHAPE = Pattern.compile(REMITTANCE_REFERENCE);

    /** A format name: letters and underscores — no digit, so no digit run hides in it. */
    private static final Pattern FORMAT_NAME = Pattern.compile("[A-Z_]{1,64}");

    private static final Pattern CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");
    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /**
     * Amounts: at most twelve integer digits — a trillion units, and never a digit run of card
     * length, so an amount's class admits no instrument shape.
     */
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,4})?");

    private static final Pattern SIGNED_AMOUNT = Pattern.compile("-?[0-9]{1,12}(\\.[0-9]{1,4})?");

    /** A JSON integer as v1 admits one: no sign, no fraction, no exponent, at most nine digits. */
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]{0,8}");

    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z]{1,8}");
    private static final Pattern DIRECTION_SHAPE = Pattern.compile("[A-Z]");

    private static final String F_JSON = "json";
    private static final String F_MEMBER = "member";
    private static final String F_FORMAT = "format";
    private static final String F_VERSION = "version";
    private static final String F_CYCLE = "cycle";
    private static final String F_CURRENCY = "currency";
    private static final String F_BUSINESS_DATE = "businessDate";
    private static final String F_REMITTANCE_REFERENCE = "remittanceReference";
    private static final String F_ENTRIES = "entries";
    private static final String F_NET = "net";
    private static final String F_ENTRY_COUNT = "entryCount";
    private static final String F_SEQ = "seq";
    private static final String F_CODE = "code";
    private static final String F_DIR = "dir";
    private static final String F_AMOUNT = "amount";
    private static final String F_FEE = "fee";
    private static final String F_SCHEME_REF = "schemeRef";
    private static final String F_END_TO_END_REF = "endToEndRef";
    private static final String F_OUR_REF = "ourRef";
    private static final String F_NARRATIVE = "narrative";

    // Immutable sets and maps, not arrays: a static array is mutable state the architecture rule
    // rightly refuses (ADR-0014), and a field-class table must be un-editable at runtime.
    private static final Set<String> ROOT_MEMBERS =
            Set.of(
                    F_FORMAT, F_VERSION, F_CYCLE, F_CURRENCY, F_BUSINESS_DATE,
                    F_REMITTANCE_REFERENCE, F_ENTRIES, F_NET, F_ENTRY_COUNT);

    private static final Set<String> ENTRY_MEMBERS =
            Set.of(
                    F_SEQ, F_CODE, F_DIR, F_AMOUNT, F_FEE, F_SCHEME_REF, F_END_TO_END_REF,
                    F_OUR_REF, F_NARRATIVE);

    /** The report's own members' classes; {@code entries} is a list, screened entry by entry. */
    private static final Map<String, FieldClass> ROOT_CLASSES =
            Map.of(
                    F_FORMAT, FieldClass.text(FORMAT_NAME),
                    F_VERSION, FieldClass.integer(),
                    F_CYCLE, FieldClass.text(CYCLE),
                    F_CURRENCY, FieldClass.text(CURRENCY_SHAPE),
                    F_BUSINESS_DATE, FieldClass.text(DATE_SHAPE),
                    F_REMITTANCE_REFERENCE, FieldClass.text(REMITTANCE_REFERENCE_SHAPE),
                    F_NET, FieldClass.text(SIGNED_AMOUNT),
                    F_ENTRY_COUNT, FieldClass.integer());

    private static final Map<String, FieldClass> ENTRY_CLASSES =
            Map.of(
                    F_SEQ, FieldClass.integer(),
                    F_CODE, FieldClass.text(CODE_SHAPE),
                    F_DIR, FieldClass.text(DIRECTION_SHAPE),
                    F_AMOUNT, FieldClass.text(AMOUNT),
                    F_FEE, FieldClass.text(AMOUNT),
                    F_SCHEME_REF, FieldClass.text(SCHEME_REFERENCE),
                    F_END_TO_END_REF, FieldClass.text(END_TO_END_REFERENCE),
                    F_OUR_REF, FieldClass.text(OUR_REFERENCE),
                    // The one declared free-text field: always screened (ADR-0066 §3).
                    F_NARRATIVE, FieldClass.FREE_TEXT);

    private static final List<String> LITERALS = List.of("true", "false", "null");

    private SimSchemeJsonFormat() {}

    @Override
    public SettlementFormatId id() {
        return SettlementFormatId.SIM_SCHEME_JSON;
    }

    @Override
    public int version() {
        return 1;
    }

    // ---------------------------------------------------------------- the field-class screen

    @Override
    public DeliveryScreen.Screening screen(byte[] content) {
        Document document;
        try {
            document = read(content);
        } catch (NotJson notOneObject) {
            // Bytes that are not one well-formed JSON object are one conservative stream
            // (ADR-0066 §3).
            return ConservativeScreen.INSTANCE.screen(content);
        }
        return new DeliveryScreen.Screening(document.records(), screenReport(document.root()));
    }

    private static Optional<DeliveryScreen.Finding> screenReport(JsonObject report) {
        for (Member member : report.members()) {
            Optional<DeliveryScreen.Finding> finding =
                    F_ENTRIES.equals(member.name())
                            ? screenEntries(member.value())
                            : screenMember(member, ROOT_CLASSES);
            if (finding.isPresent()) {
                return finding;
            }
        }
        return Optional.empty();
    }

    private static Optional<DeliveryScreen.Finding> screenEntries(Node entries) {
        if (!(entries instanceof JsonArray list)) {
            // No entry classes apply to entries of the wrong shape: all of it is free text.
            return screenValue(entries, F_ENTRIES, FieldClass.FREE_TEXT);
        }
        for (Node element : list.elements()) {
            Optional<DeliveryScreen.Finding> finding =
                    element instanceof JsonObject entry
                            ? screenEntry(entry)
                            : screenValue(element, F_ENTRIES, FieldClass.FREE_TEXT);
            if (finding.isPresent()) {
                return finding;
            }
        }
        return Optional.empty();
    }

    private static Optional<DeliveryScreen.Finding> screenEntry(JsonObject entry) {
        for (Member member : entry.members()) {
            Optional<DeliveryScreen.Finding> finding = screenMember(member, ENTRY_CLASSES);
            if (finding.isPresent()) {
                return finding;
            }
        }
        return Optional.empty();
    }

    /**
     * A declared member's value by its class; an undeclared member's NAME and value both as free
     * text — the finding names it {@code member}, never by what it is called.
     */
    private static Optional<DeliveryScreen.Finding> screenMember(
            Member member, Map<String, FieldClass> classes) {
        FieldClass declared = classes.get(member.name());
        if (declared == null) {
            return freeText(member.name(), member.line(), F_MEMBER)
                    .or(() -> screenValue(member.value(), F_MEMBER, FieldClass.FREE_TEXT));
        }
        return screenValue(member.value(), member.name(), declared);
    }

    /**
     * A classed value: matches its class and is never tested as free text — or fails it and IS,
     * before the file can be stored as malformed (C6). A list or an object where the class
     * expects text is walked, every string and number in it held to the same test.
     */
    private static Optional<DeliveryScreen.Finding> screenValue(
            Node value, String field, FieldClass declared) {
        return switch (value) {
            case JsonString string ->
                    declared.admitsText(string.value())
                            ? Optional.empty()
                            : freeText(string.value(), string.line(), field);
            case JsonNumber number ->
                    declared.admitsNumber(number.text())
                            ? Optional.empty()
                            : freeText(number.text(), number.line(), field);
            // true, false and null carry no text of anyone's.
            case JsonLiteral literal -> Optional.empty();
            case JsonArray list -> {
                for (Node element : list.elements()) {
                    Optional<DeliveryScreen.Finding> finding =
                            screenValue(element, field, declared);
                    if (finding.isPresent()) {
                        yield finding;
                    }
                }
                yield Optional.empty();
            }
            case JsonObject object -> {
                for (Member member : object.members()) {
                    Optional<DeliveryScreen.Finding> finding =
                            freeText(member.name(), member.line(), F_MEMBER)
                                    .or(() -> screenValue(
                                            member.value(), field, FieldClass.FREE_TEXT));
                    if (finding.isPresent()) {
                        yield finding;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /**
     * One value's text as its own one-line stream through the conservative walker, the finding
     * re-homed to the physical line and the field's NAME — never its value.
     */
    private static Optional<DeliveryScreen.Finding> freeText(String value, int line, String field) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return ConservativeScreen.INSTANCE
                .screen(value.getBytes(StandardCharsets.ISO_8859_1))
                .finding()
                .map(found -> new DeliveryScreen.Finding(found.reason(), line, Optional.of(field)));
    }

    // ------------------------------------------------------------------------- the parse

    @Override
    public Result parse(byte[] content) {
        Document document;
        try {
            document = read(content);
        } catch (NotJson notOneObject) {
            return rejected(RejectionCode.MALFORMED, malformed(notOneObject.line(), F_JSON));
        }
        JsonObject report = document.root();

        // The format and its version first: another format's file, or a later version's, is
        // UNSUPPORTED - never a list of this version's complaints about it.
        Optional<Result.Rejected> unsupported = unsupported(report);
        if (unsupported.isPresent()) {
            return unsupported.get();
        }

        List<FormatDefect> defects = new ArrayList<>();
        Map<String, Member> members = members(report, ROOT_MEMBERS, defects);

        // The currency next: without it no amount has a scale, and a currency the platform
        // cannot represent rejects the file at once, as the PSP report's header does.
        CurrencyCode currency = null;
        int scale = 0;
        Optional<JsonString> currencyText = string(members, F_CURRENCY, WHOLE_FILE, defects);
        if (currencyText.isPresent()) {
            JsonString text = currencyText.get();
            if (!CURRENCY_SHAPE.matcher(text.value()).matches()) {
                defect(defects, malformed(text.line(), F_CURRENCY));
            } else {
                try {
                    currency = CurrencyCode.of(text.value());
                    scale = currency.minorUnits();
                } catch (RuntimeException unknownToThePlatform) {
                    return rejected(
                            RejectionCode.UNKNOWN_CURRENCY,
                            FormatDefect.at(
                                    RejectionCode.UNKNOWN_CURRENCY, text.line(), F_CURRENCY));
                }
            }
        }

        String cycle = classed(members, F_CYCLE, CYCLE, WHOLE_FILE, defects).orElse(null);
        LocalDate businessDate =
                string(members, F_BUSINESS_DATE, WHOLE_FILE, defects)
                        .flatMap(text -> date(text, F_BUSINESS_DATE, defects))
                        .orElse(null);
        String remittanceReference =
                classed(members, F_REMITTANCE_REFERENCE, REMITTANCE_REFERENCE_SHAPE, WHOLE_FILE,
                                defects)
                        .orElse(null);

        List<Entry> entries = new ArrayList<>();
        int entryRecords = -1;
        Member entriesMember = members.get(F_ENTRIES);
        if (entriesMember == null) {
            defect(defects, malformed(WHOLE_FILE, F_ENTRIES));
        } else if (!(entriesMember.value() instanceof JsonArray list)) {
            defect(defects, malformed(entriesMember.value().line(), F_ENTRIES));
        } else {
            entryRecords = list.elements().size();
            for (int i = 0; i < entryRecords; i++) {
                Node element = list.elements().get(i);
                if (!(element instanceof JsonObject object)) {
                    defect(defects, malformed(element.line(), F_ENTRIES));
                    continue;
                }
                if (currency == null) {
                    continue; // The currency's own defect already rejects the file.
                }
                entry(object, i + 1, currency, scale, document.text(), defects)
                        .ifPresent(entries::add);
            }
        }

        Money declaredNet = null;
        Optional<JsonString> netText = string(members, F_NET, WHOLE_FILE, defects);
        if (netText.isPresent() && currency != null) {
            declaredNet = amount(netText.get(), currency, scale, SIGNED_AMOUNT, F_NET, defects);
        }
        long declaredCount = integer(members, F_ENTRY_COUNT, WHOLE_FILE, defects);

        if (!defects.isEmpty()) {
            return verdictOf(defects);
        }
        if (currency == null
                || cycle == null
                || businessDate == null
                || remittanceReference == null
                || declaredNet == null
                || declaredCount < 0
                || entries.size() != entryRecords) {
            // Every failure above records a defect; reaching here is OUR defect, and a
            // RuntimeException leaves the file RECEIVED rather than half-accepted.
            throw new IllegalStateException(
                    "a report read without defects left a component unread");
        }

        if (declaredCount != entryRecords) {
            return rejected(
                    RejectionCode.CONTROL_TOTAL_MISMATCH,
                    FormatDefect.at(
                            RejectionCode.CONTROL_TOTAL_MISMATCH,
                            members.get(F_ENTRY_COUNT).value().line(),
                            F_ENTRY_COUNT));
        }
        List<ParsedLine> lines = lines(entries, businessDate);
        // The report's own arithmetic: credits - debits - fees = net.
        Money folded = Money.ofPersisted(0, currency, scale);
        for (ParsedLine line : lines) {
            folded =
                    line.direction() == LineDirection.INBOUND
                            ? folded.plus(line.amount())
                            : folded.minus(line.amount());
        }
        if (!folded.equals(declaredNet)) {
            return rejected(
                    RejectionCode.CONTROL_TOTAL_MISMATCH,
                    FormatDefect.at(
                            RejectionCode.CONTROL_TOTAL_MISMATCH,
                            netText.orElseThrow().line(),
                            F_NET));
        }
        return new Result.Parsed(
                new ParsedBatch(
                        cycle, businessDate, remittanceReference, entryRecords, declaredNet,
                        lines));
    }

    /**
     * {@code format} must name this format and {@code version} be the integer 1 — a missing one
     * is no better — and one file declares one cycle.
     */
    private Optional<Result.Rejected> unsupported(JsonObject report) {
        List<Member> formats = named(report, F_FORMAT);
        if (formats.isEmpty()) {
            return Optional.of(
                    rejected(
                            RejectionCode.UNSUPPORTED_FORMAT,
                            FormatDefect.wholeFile(RejectionCode.UNSUPPORTED_FORMAT, F_FORMAT)));
        }
        Node declaredFormat = formats.get(0).value();
        if (!(declaredFormat instanceof JsonString name) || !id().name().equals(name.value())) {
            return Optional.of(unsupportedAt(declaredFormat.line(), F_FORMAT));
        }
        List<Member> versions = named(report, F_VERSION);
        if (versions.isEmpty()) {
            return Optional.of(
                    rejected(
                            RejectionCode.UNSUPPORTED_FORMAT,
                            FormatDefect.wholeFile(RejectionCode.UNSUPPORTED_FORMAT, F_VERSION)));
        }
        Node declaredVersion = versions.get(0).value();
        if (!(declaredVersion instanceof JsonNumber number)
                || !String.valueOf(version()).equals(number.text())) {
            return Optional.of(unsupportedAt(declaredVersion.line(), F_VERSION));
        }
        // Several cycles in one file is the multi-part shape v1 does not deliver (P8-TSK-017).
        List<Member> cycles = named(report, F_CYCLE);
        if (cycles.size() > 1) {
            return Optional.of(unsupportedAt(cycles.get(1).line(), F_CYCLE));
        }
        if (cycles.size() == 1 && cycles.get(0).value() instanceof JsonArray list) {
            return Optional.of(unsupportedAt(list.line(), F_CYCLE));
        }
        return Optional.empty();
    }

    private static Result.Rejected unsupportedAt(int line, String field) {
        return rejected(
                RejectionCode.UNSUPPORTED_FORMAT,
                FormatDefect.at(RejectionCode.UNSUPPORTED_FORMAT, line, field));
    }

    // --------------------------------------------------------------------------- the entry

    /** One entry's validated facts, before canonicalisation. */
    private record Entry(
            SettlementLineType type,
            LineDirection direction,
            Money amount,
            Optional<Money> fee,
            SortedMap<LineReferenceKind, String> references,
            String schemeReference,
            byte[] rawSha256) {

        /** Identifiers only — an amount in a log line is `INV-AUD-02`'s to refuse. */
        @Override
        public String toString() {
            return "Entry[" + type + ", " + direction + "]";
        }
    }

    /** One entry object as its validated facts, or empty with its defects recorded. */
    private static Optional<Entry> entry(
            JsonObject object,
            int position,
            CurrencyCode currency,
            int scale,
            String text,
            List<FormatDefect> defects) {
        // This entry's own defects, gathered apart: whether it is read must never depend on
        // whether the file's bounded list still had room to record them.
        List<FormatDefect> found = new ArrayList<>();
        Map<String, Member> members = members(object, ENTRY_MEMBERS, found);
        int line = object.line();

        // 1..n, strictly in order: a gap, a repeat or a swap is the file's defect.
        long seq = integer(members, F_SEQ, line, found);
        if (seq >= 0 && seq != position) {
            defect(found, malformed(members.get(F_SEQ).value().line(), F_SEQ));
        }

        // The scheme's words, checked here and nowhere else (INV-PAY-03). The code is the
        // scheme's own classification; the direction decides the canonical type.
        Optional<JsonString> code = string(members, F_CODE, line, found);
        if (code.isPresent()
                && !P_CREDIT_TRANSFER.equals(code.get().value())
                && !P_RETURN.equals(code.get().value())) {
            defect(found, malformed(code.get().line(), F_CODE));
        }
        SettlementLineType type = null;
        LineDirection direction = null;
        Optional<JsonString> dir = string(members, F_DIR, line, found);
        if (dir.isPresent()) {
            switch (dir.get().value()) {
                case DIR_IN -> {
                    type = SettlementLineType.CREDIT_IN;
                    direction = LineDirection.INBOUND;
                }
                case DIR_OUT -> {
                    type = SettlementLineType.DEBIT_OUT;
                    direction = LineDirection.OUTBOUND;
                }
                default -> defect(found, malformed(dir.get().line(), F_DIR));
            }
        }

        Money amount = null;
        Optional<JsonString> amountText = string(members, F_AMOUNT, line, found);
        if (amountText.isPresent()) {
            amount = amount(amountText.get(), currency, scale, AMOUNT, F_AMOUNT, found);
            if (amount != null && amount.isZero()) {
                // The ADR-0003 triple: a line's amount is positive, its sign the direction's.
                defect(found, malformed(amountText.get().line(), F_AMOUNT));
            }
        }
        Money fee = null;
        Optional<JsonString> feeText = optional(members, F_FEE, found);
        if (feeText.isPresent()) {
            fee = amount(feeText.get(), currency, scale, AMOUNT, F_FEE, found);
        }

        TreeMap<LineReferenceKind, String> references = new TreeMap<>();
        String schemeReference =
                reference(
                        string(members, F_SCHEME_REF, line, found), SCHEME_REFERENCE,
                        F_SCHEME_REF, LineReferenceKind.SCHEME_REF, references, found);
        reference(
                optional(members, F_END_TO_END_REF, found), END_TO_END_REFERENCE,
                F_END_TO_END_REF, LineReferenceKind.END_TO_END_REF, references, found);
        reference(
                optional(members, F_OUR_REF, found), OUR_REFERENCE, F_OUR_REF,
                LineReferenceKind.OUR_REF, references, found);

        // Declared free text: never parsed into anything - only its bound is this format's.
        Optional<JsonString> narrative = optional(members, F_NARRATIVE, found);
        if (narrative.isPresent()) {
            String value = narrative.get().value();
            int length = value.codePointCount(0, value.length());
            if (length < 1 || length > MAX_NARRATIVE) {
                defect(found, malformed(narrative.get().line(), F_NARRATIVE));
            }
        }

        for (FormatDefect each : found) {
            defect(defects, each);
        }
        if (!found.isEmpty()) {
            return Optional.empty();
        }
        // The delivered record is the entry object's own bytes, braces included - what a split
        // fee line shares with its transaction.
        byte[] raw = text.substring(object.start(), object.end()).getBytes(StandardCharsets.UTF_8);
        return Optional.of(
                new Entry(
                        type,
                        direction,
                        amount,
                        fee == null || fee.isZero() ? Optional.empty() : Optional.of(fee),
                        references,
                        schemeReference,
                        ParsedLine.sha256(raw)));
    }

    /** The canonical lines: each entry, then its fee's line when it carries one. */
    private static List<ParsedLine> lines(List<Entry> entries, LocalDate businessDate) {
        // The cycle settles on the report's business date; a scheme report has no value date.
        Optional<LocalDate> settlementDate = Optional.of(businessDate);
        List<ParsedLine> lines = new ArrayList<>();
        for (Entry entry : entries) {
            lines.add(
                    new ParsedLine(
                            lines.size() + 1,
                            entry.type(),
                            entry.direction(),
                            entry.amount(),
                            businessDate,
                            settlementDate,
                            Optional.empty(),
                            entry.references(),
                            entry.rawSha256()));
            if (entry.fee().isPresent()) {
                // The fee names the execution it rode in on - the scheme's reference.
                TreeMap<LineReferenceKind, String> original = new TreeMap<>();
                original.put(LineReferenceKind.ORIGINAL_REF, entry.schemeReference());
                lines.add(
                        new ParsedLine(
                                lines.size() + 1,
                                SettlementLineType.SCHEME_FEE,
                                LineDirection.OUTBOUND,
                                entry.fee().get(),
                                businessDate,
                                settlementDate,
                                Optional.empty(),
                                original,
                                entry.rawSha256()));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------- member primitives

    /**
     * An object's declared members, the first of each name; an undeclared member is named
     * {@code member} (never by its name, which is the counterparty's text) and a repeat by the
     * member it repeats.
     */
    private static Map<String, Member> members(
            JsonObject object, Set<String> declared, List<FormatDefect> defects) {
        Map<String, Member> members = new HashMap<>();
        for (Member member : object.members()) {
            if (!declared.contains(member.name())) {
                defect(defects, malformed(member.line(), F_MEMBER));
            } else if (members.putIfAbsent(member.name(), member) != null) {
                defect(defects, malformed(member.line(), member.name()));
            }
        }
        return members;
    }

    private static List<Member> named(JsonObject object, String name) {
        return object.members().stream().filter(member -> member.name().equals(name)).toList();
    }

    /**
     * A required text member: a JSON string, or a defect — at the value for a number, a literal,
     * a list or an object where text belongs, and at the enclosing object (the whole file, for
     * the report's own members) when it is missing.
     */
    private static Optional<JsonString> string(
            Map<String, Member> members, String field, int containerLine,
            List<FormatDefect> defects) {
        if (!members.containsKey(field)) {
            defect(defects, malformed(containerLine, field));
            return Optional.empty();
        }
        return optional(members, field, defects);
    }

    /** An optional text member: absent is nothing, and present is a JSON string or a defect. */
    private static Optional<JsonString> optional(
            Map<String, Member> members, String field, List<FormatDefect> defects) {
        Member member = members.get(field);
        if (member == null) {
            return Optional.empty();
        }
        if (member.value() instanceof JsonString string) {
            return Optional.of(string);
        }
        defect(defects, malformed(member.value().line(), field));
        return Optional.empty();
    }

    /** A required text member of the declared shape. */
    private static Optional<String> classed(
            Map<String, Member> members, String field, Pattern shape, int containerLine,
            List<FormatDefect> defects) {
        Optional<JsonString> text = string(members, field, containerLine, defects);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (!shape.matcher(text.get().value()).matches()) {
            defect(defects, malformed(text.get().line(), field));
            return Optional.empty();
        }
        return Optional.of(text.get().value());
    }

    /** A required JSON integer, or -1 with its defect recorded. */
    private static long integer(
            Map<String, Member> members, String field, int containerLine,
            List<FormatDefect> defects) {
        Member member = members.get(field);
        if (member == null) {
            defect(defects, malformed(containerLine, field));
            return -1;
        }
        if (member.value() instanceof JsonNumber number
                && INTEGER.matcher(number.text()).matches()) {
            return Long.parseLong(number.text());
        }
        defect(defects, malformed(member.value().line(), field));
        return -1;
    }

    /** A reference of its declared class, put under its kind; present by its value, never by "". */
    private static String reference(
            Optional<JsonString> text,
            Pattern shape,
            String field,
            LineReferenceKind kind,
            Map<LineReferenceKind, String> references,
            List<FormatDefect> defects) {
        if (text.isEmpty()) {
            return null;
        }
        String value = text.get().value();
        if (!shape.matcher(value).matches()) {
            defect(defects, malformed(text.get().line(), field));
            return null;
        }
        references.put(kind, value);
        return value;
    }

    // -------------------------------------------------------------------- field primitives

    /** An exact decimal STRING at the currency's scale — more decimals is `SCALE_MISMATCH`. */
    private static Money amount(
            JsonString text,
            CurrencyCode currency,
            int scale,
            Pattern shape,
            String field,
            List<FormatDefect> defects) {
        if (!shape.matcher(text.value()).matches()) {
            defect(defects, malformed(text.line(), field));
            return null;
        }
        BigDecimal decimal = new BigDecimal(text.value());
        if (decimal.scale() > scale) {
            defect(defects, FormatDefect.at(RejectionCode.SCALE_MISMATCH, text.line(), field));
            return null;
        }
        return Money.ofPersisted(
                decimal.setScale(scale).unscaledValue().longValueExact(), currency, scale);
    }

    private static Optional<LocalDate> date(
            JsonString text, String field, List<FormatDefect> defects) {
        if (!DATE_SHAPE.matcher(text.value()).matches()) {
            defect(defects, malformed(text.line(), field));
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text.value()));
        } catch (DateTimeParseException impossibleDay) {
            defect(defects, malformed(text.line(), field));
            return Optional.empty();
        }
    }

    private static FormatDefect malformed(int line, String field) {
        return line == WHOLE_FILE
                ? FormatDefect.wholeFile(RejectionCode.MALFORMED, field)
                : FormatDefect.at(RejectionCode.MALFORMED, line, field);
    }

    private static Result.Rejected rejected(RejectionCode code, FormatDefect defect) {
        return new Result.Rejected(code, List.of(defect));
    }

    private static Result.Rejected verdictOf(List<FormatDefect> defects) {
        RejectionCode verdict =
                defects.stream()
                        .map(FormatDefect::code)
                        .filter(code -> code != RejectionCode.MALFORMED)
                        .findFirst()
                        .orElse(RejectionCode.MALFORMED);
        return new Result.Rejected(verdict, List.copyOf(defects));
    }

    private static void defect(List<FormatDefect> defects, FormatDefect defect) {
        if (defects.size() < MAX_DEFECTS) {
            defects.add(defect);
        }
    }

    // ------------------------------------------------------------------- the field classes

    /**
     * A member's declared class: the shape its text admits, or the shape its JSON integer does —
     * whichever it declares; a value of the other kind fails it. Neither is free text, which
     * admits nothing and so screens everything.
     */
    private record FieldClass(Optional<Pattern> textShape, Optional<Pattern> numberShape) {

        static final FieldClass FREE_TEXT = new FieldClass(Optional.empty(), Optional.empty());

        static FieldClass text(Pattern shape) {
            return new FieldClass(Optional.of(shape), Optional.empty());
        }

        static FieldClass integer() {
            return new FieldClass(Optional.empty(), Optional.of(INTEGER));
        }

        boolean admitsText(String value) {
            return textShape.isPresent() && textShape.get().matcher(value).matches();
        }

        boolean admitsNumber(String text) {
            return numberShape.isPresent() && numberShape.get().matcher(text).matches();
        }
    }

    // ------------------------------------------------------------------------ the reader

    /** One JSON value and the 1-based physical line its first character sits on. */
    private sealed interface Node
            permits JsonObject, JsonArray, JsonString, JsonNumber, JsonLiteral {
        int line();
    }

    /** A member: its name, the line its name sits on, and its value. */
    private record Member(String name, int line, Node value) {

        /** Identifiers only: a name or a value may be the counterparty's text. */
        @Override
        public String toString() {
            return "Member[" + line + "]";
        }
    }

    /** An object, with the character span it occupies — an entry's span is its raw record. */
    private record JsonObject(List<Member> members, int line, int start, int end)
            implements Node {

        @Override
        public String toString() {
            return "JsonObject[" + line + "]";
        }
    }

    private record JsonArray(List<Node> elements, int line) implements Node {

        @Override
        public String toString() {
            return "JsonArray[" + line + "]";
        }
    }

    private record JsonString(String value, int line) implements Node {

        @Override
        public String toString() {
            return "JsonString[" + line + "]";
        }
    }

    /** A number, kept as its token's text: it is never converted, only matched or refused. */
    private record JsonNumber(String text, int line) implements Node {

        @Override
        public String toString() {
            return "JsonNumber[" + line + "]";
        }
    }

    private record JsonLiteral(String text, int line) implements Node {

        @Override
        public String toString() {
            return "JsonLiteral[" + line + "]";
        }
    }

    /** The whole document: its root object, its record count and its decoded text. */
    private record Document(JsonObject root, int records, String text) {

        @Override
        public String toString() {
            return "Document[" + records + " records]";
        }
    }

    /** The bytes are not one well-formed JSON object — and where they stopped being one. */
    private static final class NotJson extends Exception {

        @Serial private static final long serialVersionUID = 1L;

        private final int line;

        NotJson(int line) {
            // No message and no stack: the reader's own control flow, caught in this class.
            super(null, null, false, false);
            this.line = line;
        }

        int line() {
            return line;
        }
    }

    /** Strict UTF-8, an optional BOM, then exactly one JSON object and nothing after it. */
    private static Document read(byte[] content) throws NotJson {
        String text = decode(content);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        return new JsonReader(text).document();
    }

    /** RFC 8259 is UTF-8: a malformed sequence is not JSON, never a replacement character. */
    private static String decode(byte[] content) throws NotJson {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(content);
        // UTF-8 never decodes to more chars than it has bytes, so the buffer cannot overflow.
        CharBuffer out = CharBuffer.allocate(content.length);
        CoderResult result = decoder.decode(in, out, true);
        if (!result.isError()) {
            result = decoder.flush(out);
        }
        if (result.isError() || result.isOverflow()) {
            throw new NotJson(lineAt(content, in.position()));
        }
        return out.flip().toString();
    }

    /** The 1-based physical line a byte offset sits on. */
    private static int lineAt(byte[] content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length; i++) {
            if (content[i] == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * A recursive-descent reader over the decoded text, counting physical lines as it goes — a
     * string cannot hold a raw newline, so every newline it meets is whitespace between tokens.
     */
    private static final class JsonReader {

        private final String text;
        private int pos;
        private int line = 1;
        private int depth;
        private int records;

        JsonReader(String text) {
            this.text = text;
        }

        Document document() throws NotJson {
            if (text.isEmpty()) {
                throw new NotJson(WHOLE_FILE);
            }
            skipWhitespace();
            Node root = value();
            skipWhitespace();
            if (pos < text.length()) {
                throw new NotJson(line); // Anything after the root: one object, nothing more.
            }
            if (!(root instanceof JsonObject object)) {
                throw new NotJson(root.line());
            }
            return new Document(object, records, text);
        }

        private Node value() throws NotJson {
            char c = peek();
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                int at = line;
                return new JsonString(string(), at);
            }
            if (c == '-' || isDigit(c)) {
                return number();
            }
            for (String literal : LITERALS) {
                if (text.startsWith(literal, pos)) {
                    int at = line;
                    pos += literal.length();
                    return new JsonLiteral(literal, at);
                }
            }
            throw new NotJson(line);
        }

        private JsonObject object() throws NotJson {
            int at = line;
            int start = pos;
            enter();
            pos++; // '{'
            List<Member> members = new ArrayList<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    if (peek() != '"') {
                        throw new NotJson(line); // A name is a string; a trailing comma is not.
                    }
                    int nameLine = line;
                    String name = string();
                    skipWhitespace();
                    if (peek() != ':') {
                        throw new NotJson(line);
                    }
                    pos++;
                    skipWhitespace();
                    members.add(new Member(name, nameLine, value()));
                    skipWhitespace();
                    char next = peek();
                    pos++;
                    if (next == '}') {
                        break;
                    }
                    if (next != ',') {
                        throw new NotJson(line);
                    }
                }
            }
            depth--;
            return new JsonObject(List.copyOf(members), at, start, pos);
        }

        private JsonArray array() throws NotJson {
            int at = line;
            enter();
            pos++; // '['
            List<Node> elements = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    elements.add(value());
                    records++;
                    skipWhitespace();
                    char next = peek();
                    pos++;
                    if (next == ']') {
                        break;
                    }
                    if (next != ',') {
                        throw new NotJson(line);
                    }
                }
            }
            depth--;
            return new JsonArray(List.copyOf(elements), at);
        }

        private String string() throws NotJson {
            pos++; // the opening quote
            StringBuilder value = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return value.toString();
                }
                if (c < 0x20) {
                    throw new NotJson(line); // A raw control character - a newline included.
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                char escape = peek();
                pos++;
                switch (escape) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(hexUnit());
                    default -> throw new NotJson(line);
                }
            }
        }

        /** The four hex digits of a unicode escape — ASCII hex only, and no sign. */
        private char hexUnit() throws NotJson {
            int unit = 0;
            for (int i = 0; i < 4; i++) {
                int digit = hexValue(peek());
                if (digit < 0) {
                    throw new NotJson(line);
                }
                unit = unit * 16 + digit;
                pos++;
            }
            return (char) unit;
        }

        /** {@code -? (0 | [1-9][0-9]*) (.[0-9]+)? ([eE][+-]?[0-9]+)?} — kept as its text. */
        private JsonNumber number() throws NotJson {
            int at = line;
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            char first = peek();
            if (first == '0') {
                pos++; // A leading zero is the whole integer part: "01" is not JSON.
            } else if (isDigit(first)) {
                digits();
            } else {
                throw new NotJson(line);
            }
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                requireDigits();
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                requireDigits();
            }
            return new JsonNumber(text.substring(start, pos), at);
        }

        private void requireDigits() throws NotJson {
            if (!isDigit(peek())) {
                throw new NotJson(line);
            }
            digits();
        }

        private void digits() {
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '\n') {
                    line++;
                } else if (c != ' ' && c != '\t' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private void enter() throws NotJson {
            depth++;
            if (depth > MAX_DEPTH) {
                throw new NotJson(line);
            }
        }

        /** The next character, or NotJson at the end — a document that stops mid-value. */
        private char peek() throws NotJson {
            if (pos >= text.length()) {
                throw new NotJson(line);
            }
            return text.charAt(pos);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static int hexValue(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }
    }
}
