package com.finapp.settlement.format.simstatement;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The simulated settlement bank's tagged statement, version 1 (`P8-TSK-016`, ADR-0065 §3,
 * ADR-0066 §§3, 8) — MT940-shaped, and frozen by its golden-file test: any change to this screen
 * or parser is a NEW version, never an edit.
 *
 * <h2>The shape v1 freezes</h2>
 *
 * <pre>
 * :20:&lt;statementRef&gt;
 * :25:&lt;accountRef&gt;
 * :28C:&lt;sequence&gt;
 * :60F:&lt;C|D&gt;,&lt;yyyy-mm-dd&gt;,&lt;CCY&gt;,&lt;amount&gt;
 * ( :61:&lt;yyyy-mm-dd&gt;,&lt;C|D|F&gt;,&lt;amount&gt;[,&lt;remittanceRef&gt;]
 *   [:86:&lt;free text, at most 390 characters&gt;] )*
 * :62F:&lt;C|D&gt;,&lt;yyyy-mm-dd&gt;,&lt;CCY&gt;,&lt;amount&gt;
 * </pre>
 *
 * <p>UTF-8, an optional BOM, LF or CRLF, an optional trailing newline, one tagged record per
 * physical line and no blank line inside. Amounts are unsigned; the direction is the mark's —
 * a balance is a credit ({@code C}, positive) or a debit ({@code D}, negative) balance, a line is
 * money in ({@code C} → {@code BANK_CREDIT}), money out ({@code D} → {@code BANK_DEBIT}) or the
 * bank's own charge ({@code F} → {@code BANK_FEE}). The statement's own arithmetic is the control:
 * opening + Σ credits − Σ debits − Σ fees = closing, so a statement that does not add up is
 * rejected whole ({@code INV-SET-07}) rather than recognised as cash that cannot be explained.
 *
 * <p><strong>This class is the bank vocabulary's whole world</strong> ({@code INV-PAY-03}): the
 * record tags appear here and nowhere else — {@code SettlementVocabularyIsConfinedTest} holds the
 * line — and what leaves is the platform's canonical line, typed, positive and dated.
 *
 * <h2>What a statement carries on, and what it never does</h2>
 *
 * <ul>
 *   <li><strong>The continuity facts</strong> ({@code INV-SET-06}): the sequence and the SIGNED
 *       opening and closing balances ride out as {@link ParsedBatch.StatementFacts}, because
 *       {@code CASH_AT_BANK} is proven against the closing balance of an unbroken chain, and a gap
 *       or a moved opening is a break the accept leg must be able to see. The batch's declared net
 *       is closing − opening; it carries no remittance reference of its own — its lines do.
 *   <li><strong>The account reference is checked, then dropped</strong> ({@code INV-RAIL-03}).
 *       {@code :25:} is the bank's OPAQUE reference for the settlement account: it must have the
 *       reference's shape and equal the reference configured for the statement's currency, or the
 *       file is not our account's statement and is rejected. The value is never carried into a
 *       line, a batch, a defect, an exception message or a {@code toString} — the platform knows
 *       an external account only by the reference it configured, never by what arrives.
 *   <li><strong>Only the structured remittance reference is extracted</strong> (ADR-0065 §3:
 *       attribution is normalisation, never a name or an account identifier). It becomes
 *       {@code REMITTANCE_REF}; a credit or debit may omit it and then attributes to nobody
 *       downstream — kept, never dropped. A fee line takes none: the bank's own charge answers to
 *       no counterparty's remittance.
 *   <li><strong>The {@code :86:} narrative is never parsed.</strong> A bank's free text carries
 *       names and counterparty details; it rests only inside the stored, encrypted file, and no
 *       canonical field is ever read out of it.
 * </ul>
 *
 * <h2>The field-class screen (ADR-0066 §3, C6)</h2>
 *
 * <p>Tags make the stream structural, so it is screened field by field: every classed field is
 * checked by its shape and, <strong>failing it, is screened as free text</strong> before the file
 * can be stored as malformed — and {@code :86:} is declared free text, so it is always screened
 * ({@code INV-PAY-02}, {@code INV-RAIL-03}). For that discipline to refuse an account identifier
 * put where a reference belongs, the reference classes themselves must exclude instrument shapes:
 * the statement and remittance references admit no international account shape and no digit run
 * of card length, so an IBAN in {@code :25:}, {@code :20:} or a line's reference fails its class,
 * meets the free-text screen and is refused — never retained as a malformed file. Bytes that are
 * not tagged records are screened as one conservative stream.
 *
 * <p><strong>Not a singleton</strong>, unlike the PSP's report: the settlement account's
 * reference per currency is deployment configuration, fixed at construction. The adapter is still
 * pure — no I/O, no clock, no database — so a verdict and a parse remain functions of the bytes.
 */
public final class SimStatementTaggedFormat implements SettlementFormat {

    /** Defect rows are bounded like their table (`V003`): the first hundred tell the story. */
    private static final int MAX_DEFECTS = 100;

    /** The narrative's frozen bound, in characters. */
    private static final int MAX_NARRATIVE = 390;

    // The bank's own record tags - confined to this file (INV-PAY-03). Each one is a whole
    // literal: SettlementVocabularyIsConfinedTest scans for exactly these.
    private static final String TAG_STATEMENT_REF = ":20:";
    private static final String TAG_ACCOUNT = ":25:";
    private static final String TAG_SEQUENCE = ":28C:";
    private static final String TAG_OPENING = ":60F:";
    private static final String TAG_LINE = ":61:";
    private static final String TAG_NARRATIVE = ":86:";
    private static final String TAG_CLOSING = ":62F:";

    // The bank's marks.
    private static final String MARK_CREDIT = "C";
    private static final String MARK_DEBIT = "D";
    private static final String MARK_FEE = "F";

    /**
     * No instrument shape anywhere in a reference: neither the international account shape the
     * door refuses (bounded by non-alphanumerics, exactly as {@link ConservativeScreen} reads
     * it) nor a digit run of card length (single dashes between digits collapsed, as the
     * screen's walk collapses them). A reference that carries one fails its class and is
     * screened as free text (C6).
     */
    private static final String NO_INSTRUMENT_SHAPE =
            "(?!.*(?<![A-Za-z0-9])[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}(?![A-Za-z0-9]))"
                    + "(?!.*[0-9](?:-?[0-9]){12})";

    /** The statement's own reference: the batch identity. */
    private static final Pattern STATEMENT_REFERENCE =
            Pattern.compile(NO_INSTRUMENT_SHAPE + "[A-Za-z0-9-]{1,100}");

    /**
     * The bank's opaque reference for the settlement account — the only shape {@code :25:} may
     * take, and the shape every configured reference is validated against.
     */
    private static final Pattern ACCOUNT_REFERENCE = Pattern.compile("SIMBANK-[A-Z]{3}-[0-9]{2,8}");

    /** A line's structured remittance reference: at least one letter, never instrument-shaped. */
    private static final Pattern REMITTANCE_REFERENCE =
            Pattern.compile("(?=.*[A-Za-z])" + NO_INSTRUMENT_SHAPE + "[A-Za-z0-9-]{2,100}");

    private static final Pattern SEQUENCE_SHAPE = Pattern.compile("[0-9]{1,9}");
    private static final Pattern CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");
    private static final Pattern AMOUNT_SHAPE = Pattern.compile("[0-9]{1,13}(\\.[0-9]{1,4})?");
    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern BALANCE_MARK = Pattern.compile("[CD]");
    private static final Pattern LINE_MARK = Pattern.compile("[CDF]");

    // Immutable lists, not arrays: a static array is mutable state the architecture rule
    // rightly refuses (ADR-0014), and a field-name table must be un-editable at runtime.
    private static final List<String> TAGS =
            List.of(
                    TAG_STATEMENT_REF, TAG_ACCOUNT, TAG_SEQUENCE, TAG_OPENING, TAG_LINE,
                    TAG_NARRATIVE, TAG_CLOSING);

    /** The mandatory records before the body, in order — each named as a defect names it. */
    private static final List<String> HEADER_TAGS =
            List.of(TAG_STATEMENT_REF, TAG_ACCOUNT, TAG_SEQUENCE, TAG_OPENING);

    private static final List<String> HEADER_RECORD_NAMES =
            List.of("statementRef", "accountRef", "sequence", "openingBalance");

    private static final List<String> OPENING_FIELDS =
            List.of("openingMark", "openingDate", "openingCurrency", "openingAmount");
    private static final List<String> CLOSING_FIELDS =
            List.of("closingMark", "closingDate", "closingCurrency", "closingAmount");
    private static final List<String> LINE_FIELDS =
            List.of("valueDate", "mark", "amount", "remittanceRef");

    private static final String F_TAG = "tag";
    private static final String F_STATEMENT_REF = "statementRef";
    private static final String F_ACCOUNT_REF = "accountRef";
    private static final String F_SEQUENCE = "sequence";
    private static final String F_OPENING_BALANCE = "openingBalance";
    private static final String F_STATEMENT_LINE = "statementLine";
    private static final String F_NARRATIVE = "narrative";
    private static final String F_CLOSING_BALANCE = "closingBalance";
    private static final String F_CLOSING = "closing";

    /** A balance record's field count, and a line's with and without its reference. */
    private static final int BALANCE_FIELD_COUNT = 4;

    private static final int LINE_FIELDS_BARE = 3;
    private static final int LINE_FIELDS_REFERENCED = 4;

    /** The grammar's ranks: the four headers, then the body, then the closing balance. */
    private static final int RANK_BODY = 4;

    private static final int RANK_CLOSING = 5;

    /** The settlement account's opaque reference per currency — CONFIDENTIAL configuration. */
    private final Map<CurrencyCode, String> settlementAccountReferences;

    /**
     * @param settlementAccountReferences the bank's opaque reference of the platform's
     *     settlement account, per currency — a statement is ours only when its {@code :25:}
     *     equals the reference configured for its currency; each value must have the bank's
     *     reference shape, and none is ever echoed
     */
    public SimStatementTaggedFormat(Map<CurrencyCode, String> settlementAccountReferences) {
        Objects.requireNonNull(
                settlementAccountReferences, "settlementAccountReferences must not be null");
        Map<CurrencyCode, String> copy = Map.copyOf(settlementAccountReferences);
        copy.forEach(
                (currency, reference) -> {
                    if (!ACCOUNT_REFERENCE.matcher(reference).matches()) {
                        // The currency is an identifier; the reference is configuration and
                        // is never repeated back (INV-RAIL-03).
                        throw new IllegalArgumentException(
                                "the settlement account reference configured for "
                                        + currency.code()
                                        + " does not have the bank's opaque reference shape");
                    }
                });
        this.settlementAccountReferences = copy;
    }

    @Override
    public SettlementFormatId id() {
        return SettlementFormatId.SIM_STATEMENT_TAGGED;
    }

    @Override
    public int version() {
        return 1;
    }

    /** Identifiers only: the format, its version and the currencies configured — no reference. */
    @Override
    public String toString() {
        return "SimStatementTaggedFormat["
                + id()
                + " v"
                + version()
                + ", accounts for "
                + settlementAccountReferences.keySet().stream()
                        .map(CurrencyCode::code)
                        .sorted()
                        .toList()
                + "]";
    }

    // ---------------------------------------------------------------- the field-class screen

    @Override
    public DeliveryScreen.Screening screen(byte[] content) {
        List<TaggedRecord> records = records(content);
        if (!isStructurallyTagged(records)) {
            // Bytes that are not tagged records are one conservative stream (ADR-0066 §3).
            return ConservativeScreen.INSTANCE.screen(content);
        }
        for (TaggedRecord record : records) {
            Optional<DeliveryScreen.Finding> finding = screenRecord(record);
            if (finding.isPresent()) {
                return new DeliveryScreen.Screening(records.size(), finding);
            }
        }
        return new DeliveryScreen.Screening(records.size(), Optional.empty());
    }

    /** Structural enough to screen field by field: every physical line is a known tag's record. */
    private static boolean isStructurallyTagged(List<TaggedRecord> records) {
        if (records.isEmpty()) {
            return false;
        }
        for (TaggedRecord record : records) {
            if (record.tag() == null) {
                return false;
            }
        }
        return true;
    }

    private static Optional<DeliveryScreen.Finding> screenRecord(TaggedRecord record) {
        String value = record.value();
        return switch (record.tag()) {
            case TAG_STATEMENT_REF -> classed(record, value, F_STATEMENT_REF, STATEMENT_REFERENCE);
            case TAG_ACCOUNT -> classed(record, value, F_ACCOUNT_REF, ACCOUNT_REFERENCE);
            case TAG_SEQUENCE -> classed(record, value, F_SEQUENCE, SEQUENCE_SHAPE);
            case TAG_OPENING -> screenBalance(record, OPENING_FIELDS, F_OPENING_BALANCE);
            case TAG_CLOSING -> screenBalance(record, CLOSING_FIELDS, F_CLOSING_BALANCE);
            case TAG_LINE -> screenLine(record);
            // The one declared free-text field: always screened (ADR-0066 §3).
            case TAG_NARRATIVE -> freeText(record, value, F_NARRATIVE);
            // Unreachable for a structural stream; screened whole rather than trusted.
            default -> freeText(record, record.raw(), F_TAG);
        };
    }

    private static Optional<DeliveryScreen.Finding> screenBalance(
            TaggedRecord record, List<String> names, String recordName) {
        String[] fields = record.value().split(",", -1);
        if (fields.length != BALANCE_FIELD_COUNT) {
            // No declared classes apply to a record of the wrong shape: all of it is free text.
            return freeText(record, record.value(), recordName);
        }
        return firstFinding(
                classed(record, fields[0], names.get(0), BALANCE_MARK),
                classed(record, fields[1], names.get(1), DATE_SHAPE),
                classed(record, fields[2], names.get(2), CURRENCY_SHAPE),
                classed(record, fields[3], names.get(3), AMOUNT_SHAPE));
    }

    private static Optional<DeliveryScreen.Finding> screenLine(TaggedRecord record) {
        String[] fields = record.value().split(",", -1);
        if (fields.length != LINE_FIELDS_BARE && fields.length != LINE_FIELDS_REFERENCED) {
            return freeText(record, record.value(), F_STATEMENT_LINE);
        }
        Optional<DeliveryScreen.Finding> reference =
                fields.length == LINE_FIELDS_REFERENCED
                        ? classed(record, fields[3], LINE_FIELDS.get(3), REMITTANCE_REFERENCE)
                        : Optional.empty();
        return firstFinding(
                classed(record, fields[0], LINE_FIELDS.get(0), DATE_SHAPE),
                classed(record, fields[1], LINE_FIELDS.get(1), LINE_MARK),
                classed(record, fields[2], LINE_FIELDS.get(2), AMOUNT_SHAPE),
                reference);
    }

    @SafeVarargs
    private static Optional<DeliveryScreen.Finding> firstFinding(
            Optional<DeliveryScreen.Finding>... findings) {
        for (Optional<DeliveryScreen.Finding> finding : findings) {
            if (finding.isPresent()) {
                return finding;
            }
        }
        return Optional.empty();
    }

    /**
     * A classed field: matches its shape and is never tested as free text — or fails its class
     * and IS, before the file can be stored as malformed (C6).
     */
    private static Optional<DeliveryScreen.Finding> classed(
            TaggedRecord record, String value, String field, Pattern shape) {
        if (shape.matcher(value).matches()) {
            return Optional.empty();
        }
        return freeText(record, value, field);
    }

    /**
     * One field's text as its own one-line stream through the conservative walker, the finding
     * re-homed to the physical line and the field's NAME — never its value.
     */
    private static Optional<DeliveryScreen.Finding> freeText(
            TaggedRecord record, String value, String field) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return ConservativeScreen.INSTANCE
                .screen(value.getBytes(StandardCharsets.ISO_8859_1))
                .finding()
                .map(
                        found ->
                                new DeliveryScreen.Finding(
                                        found.reason(),
                                        record.physicalLine(),
                                        Optional.of(field)));
    }

    // ------------------------------------------------------------------------- the parse

    @Override
    public Result parse(byte[] content) {
        List<TaggedRecord> records = records(content);
        if (records.isEmpty()) {
            return new Result.Rejected(
                    RejectionCode.MALFORMED,
                    List.of(FormatDefect.wholeFile(RejectionCode.MALFORMED, F_TAG)));
        }

        List<FormatDefect> defects = new ArrayList<>();
        Layout layout = layout(records, defects);

        // The statement currency first: without it no amount has a scale, and a currency the
        // platform cannot represent rejects the file at once, as the PSP report's header does.
        CurrencyCode currency = null;
        int scale = 0;
        Optional<Balance> opening = Optional.empty();
        if (layout.opening != null) {
            TaggedRecord record = layout.opening;
            String[] fields = record.value().split(",", -1);
            if (fields.length != BALANCE_FIELD_COUNT) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, record.physicalLine(),
                        F_OPENING_BALANCE));
            } else {
                String currencyText = fields[2];
                if (!CURRENCY_SHAPE.matcher(currencyText).matches()) {
                    defect(defects, FormatDefect.at(RejectionCode.MALFORMED,
                            record.physicalLine(), OPENING_FIELDS.get(2)));
                } else {
                    try {
                        currency = CurrencyCode.of(currencyText);
                        scale = currency.minorUnits();
                    } catch (RuntimeException unknownToThePlatform) {
                        return new Result.Rejected(
                                RejectionCode.UNKNOWN_CURRENCY,
                                List.of(FormatDefect.at(RejectionCode.UNKNOWN_CURRENCY,
                                        record.physicalLine(), OPENING_FIELDS.get(2))));
                    }
                }
                opening = balance(record, fields, OPENING_FIELDS, currency, scale, defects);
            }
        }

        String statementRef = null;
        if (layout.statementRef != null) {
            String value = layout.statementRef.value();
            if (STATEMENT_REFERENCE.matcher(value).matches()) {
                statementRef = value;
            } else {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED,
                        layout.statementRef.physicalLine(), F_STATEMENT_REF));
            }
        }

        if (layout.account != null) {
            verifyAccount(layout.account, currency, defects);
        }

        long sequence = -1;
        if (layout.sequence != null) {
            sequence = sequence(layout.sequence, defects);
        }

        for (TaggedRecord narrative : layout.narratives) {
            String text = narrative.value();
            int length = text.codePointCount(0, text.length());
            if (length < 1 || length > MAX_NARRATIVE) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED,
                        narrative.physicalLine(), F_NARRATIVE));
            }
        }

        List<ParsedLine> lines = new ArrayList<>();
        if (currency != null) {
            int lineNo = 0;
            for (TaggedRecord record : layout.lines) {
                lineNo++;
                line(record, lineNo, currency, scale, defects).ifPresent(lines::add);
            }
        }

        Optional<Balance> closing = Optional.empty();
        if (layout.closing != null) {
            TaggedRecord record = layout.closing;
            String[] fields = record.value().split(",", -1);
            if (fields.length != BALANCE_FIELD_COUNT) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, record.physicalLine(),
                        F_CLOSING_BALANCE));
            } else {
                if (currency != null && !fields[2].equals(currency.code())) {
                    // One statement, one currency (INV-SET-07).
                    defect(defects, FormatDefect.at(RejectionCode.MALFORMED,
                            record.physicalLine(), CLOSING_FIELDS.get(2)));
                }
                closing = balance(record, fields, CLOSING_FIELDS, currency, scale, defects);
            }
        }

        if (defects.isEmpty()) {
            if (statementRef == null
                    || sequence < 1
                    || opening.isEmpty()
                    || closing.isEmpty()
                    || lines.size() != layout.lines.size()) {
                // Every failure above records a defect; reaching here is OUR defect, and a
                // RuntimeException leaves the file RECEIVED rather than half-accepted.
                throw new IllegalStateException(
                        "a statement read without defects left a component unread");
            }
            Money openingBalance = opening.get().signed();
            Money closingBalance = closing.get().signed();
            // The statement's own arithmetic: opening + credits - debits - fees = closing.
            Money folded = openingBalance;
            for (ParsedLine line : lines) {
                folded =
                        line.direction() == LineDirection.INBOUND
                                ? folded.plus(line.amount())
                                : folded.minus(line.amount());
            }
            if (!folded.equals(closingBalance)) {
                return new Result.Rejected(
                        RejectionCode.CONTROL_TOTAL_MISMATCH,
                        List.of(FormatDefect.at(RejectionCode.CONTROL_TOTAL_MISMATCH,
                                layout.closing.physicalLine(), F_CLOSING)));
            }
            return new Result.Parsed(
                    new ParsedBatch(
                            statementRef,
                            closing.get().date(),
                            Optional.empty(),
                            lines.size(),
                            closingBalance.minus(openingBalance),
                            lines,
                            Optional.of(
                                    new ParsedBatch.StatementFacts(
                                            sequence, openingBalance, closingBalance))));
        }

        RejectionCode verdict =
                defects.stream()
                        .map(FormatDefect::code)
                        .filter(code -> code != RejectionCode.MALFORMED)
                        .findFirst()
                        .orElse(RejectionCode.MALFORMED);
        return new Result.Rejected(verdict, List.copyOf(defects));
    }

    // -------------------------------------------------------------------- the grammar walk

    /** Where each record sits in the grammar, after the structural walk. */
    private static final class Layout {
        TaggedRecord statementRef;
        TaggedRecord account;
        TaggedRecord sequence;
        TaggedRecord opening;
        final List<TaggedRecord> lines = new ArrayList<>();
        final List<TaggedRecord> narratives = new ArrayList<>();
        TaggedRecord closing;
    }

    /**
     * The records placed in the grammar's order. A record the grammar does not admit where it
     * stands — a blank line, an unknown tag, a header already passed, a narrative not directly
     * after a statement line (so a second one in a row), anything after the closing balance — is
     * named {@code tag} at its line; a mandatory record that never came is named by the record.
     */
    private static Layout layout(List<TaggedRecord> records, List<FormatDefect> defects) {
        Layout layout = new Layout();
        int expected = 0;
        boolean narrativeAdmitted = false;
        for (TaggedRecord record : records) {
            String tag = record.tag();
            int line = record.physicalLine();
            if (tag == null || expected > RANK_CLOSING) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, F_TAG));
                narrativeAdmitted = false;
                continue;
            }
            if (TAG_NARRATIVE.equals(tag)) {
                if (narrativeAdmitted) {
                    layout.narratives.add(record);
                } else {
                    defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, F_TAG));
                }
                narrativeAdmitted = false;
                continue;
            }
            narrativeAdmitted = false;
            int rank = rankOf(tag);
            if (rank < expected) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, F_TAG));
                continue;
            }
            for (int missing = expected; missing < Math.min(rank, RANK_BODY); missing++) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line,
                        HEADER_RECORD_NAMES.get(missing)));
            }
            switch (rank) {
                case 0 -> layout.statementRef = record;
                case 1 -> layout.account = record;
                case 2 -> layout.sequence = record;
                case 3 -> layout.opening = record;
                case RANK_BODY -> {
                    layout.lines.add(record);
                    narrativeAdmitted = true;
                }
                default -> layout.closing = record;
            }
            expected = rank == RANK_BODY ? RANK_BODY : rank + 1;
        }
        for (int missing = expected; missing < RANK_BODY; missing++) {
            defect(defects, FormatDefect.wholeFile(RejectionCode.MALFORMED,
                    HEADER_RECORD_NAMES.get(missing)));
        }
        if (expected <= RANK_CLOSING) {
            defect(defects, FormatDefect.wholeFile(RejectionCode.MALFORMED, F_CLOSING_BALANCE));
        }
        return layout;
    }

    /** A known tag's place in the grammar; the narrative is placed by adjacency instead. */
    private static int rankOf(String tag) {
        int header = HEADER_TAGS.indexOf(tag);
        if (header >= 0) {
            return header;
        }
        return TAG_LINE.equals(tag) ? RANK_BODY : RANK_CLOSING;
    }

    // ------------------------------------------------------------------------ the records

    /**
     * {@code :25:} is ours only when it has the bank's reference shape AND equals the reference
     * configured for the statement's currency. Either failure names the field, never the value.
     */
    private void verifyAccount(
            TaggedRecord record, CurrencyCode currency, List<FormatDefect> defects) {
        String accountRef = record.value();
        if (!ACCOUNT_REFERENCE.matcher(accountRef).matches()) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, record.physicalLine(),
                    F_ACCOUNT_REF));
            return;
        }
        if (currency == null) {
            return; // The opening balance's own defect already rejects the file.
        }
        if (!accountRef.equals(settlementAccountReferences.get(currency))) {
            // Another account's statement, or a currency with no configured account: not ours.
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, record.physicalLine(),
                    F_ACCOUNT_REF));
        }
    }

    private static long sequence(TaggedRecord record, List<FormatDefect> defects) {
        String text = record.value();
        if (!SEQUENCE_SHAPE.matcher(text).matches()) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, record.physicalLine(),
                    F_SEQUENCE));
            return -1;
        }
        long value = Long.parseLong(text);
        if (value < 1) {
            // A statement chain starts at 1 (INV-SET-06).
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, record.physicalLine(),
                    F_SEQUENCE));
            return -1;
        }
        return value;
    }

    /** One balance, signed by its mark and dated. Its currency field is the caller's to judge. */
    private record Balance(Money signed, LocalDate date) {

        /** Identifiers only — an amount in a log line is `INV-AUD-02`'s to refuse. */
        @Override
        public String toString() {
            return "Balance[" + date + "]";
        }
    }

    private static Optional<Balance> balance(
            TaggedRecord record,
            String[] fields,
            List<String> names,
            CurrencyCode currency,
            int scale,
            List<FormatDefect> defects) {
        int line = record.physicalLine();
        String mark = fields[0];
        boolean credit = MARK_CREDIT.equals(mark);
        boolean marked = credit || MARK_DEBIT.equals(mark);
        if (!marked) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, names.get(0)));
        }
        Optional<LocalDate> date = date(fields[1], line, names.get(1), defects);
        Money magnitude =
                currency == null
                        ? null
                        : amount(fields[3], currency, scale, line, names.get(3), defects);
        if (!marked || date.isEmpty() || magnitude == null) {
            return Optional.empty();
        }
        return Optional.of(new Balance(credit ? magnitude : magnitude.negated(), date.get()));
    }

    /** One {@code :61:} record as a canonical line, or empty with its defects recorded. */
    private static Optional<ParsedLine> line(
            TaggedRecord record,
            int lineNo,
            CurrencyCode currency,
            int scale,
            List<FormatDefect> defects) {
        int physical = record.physicalLine();
        String[] f = record.value().split(",", -1);
        if (f.length != LINE_FIELDS_BARE && f.length != LINE_FIELDS_REFERENCED) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, physical,
                    F_STATEMENT_LINE));
            return Optional.empty();
        }
        boolean readable = true;

        Optional<LocalDate> valueDate = date(f[0], physical, LINE_FIELDS.get(0), defects);
        if (valueDate.isEmpty()) {
            readable = false;
        }

        // The bank's marks, mapped here and nowhere else (INV-PAY-03).
        SettlementLineType type = lineType(f[1]);
        if (type == null) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, physical,
                    LINE_FIELDS.get(1)));
            readable = false;
        }

        Money amount = amount(f[2], currency, scale, physical, LINE_FIELDS.get(2), defects);
        if (amount == null) {
            readable = false;
        } else if (amount.isZero()) {
            // The ADR-0003 triple: a line's amount is positive, its sign the mark's.
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, physical,
                    LINE_FIELDS.get(2)));
            readable = false;
        }

        TreeMap<LineReferenceKind, String> references = new TreeMap<>();
        if (f.length == LINE_FIELDS_REFERENCED) {
            String reference = f[3];
            if (type == SettlementLineType.BANK_FEE
                    || !REMITTANCE_REFERENCE.matcher(reference).matches()) {
                // The bank's own charge answers to no counterparty's remittance, and a
                // reference is present by its field, never by an empty one.
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, physical,
                        LINE_FIELDS.get(3)));
                readable = false;
            } else {
                references.put(LineReferenceKind.REMITTANCE_REF, reference);
            }
        }

        if (!readable) {
            return Optional.empty();
        }
        return Optional.of(
                new ParsedLine(
                        lineNo,
                        type,
                        type == SettlementLineType.BANK_CREDIT
                                ? LineDirection.INBOUND
                                : LineDirection.OUTBOUND,
                        amount,
                        valueDate.get(),
                        Optional.empty(),
                        valueDate,
                        references,
                        ParsedLine.sha256(record.raw().getBytes(StandardCharsets.UTF_8))));
    }

    private static SettlementLineType lineType(String mark) {
        return switch (mark) {
            case MARK_CREDIT -> SettlementLineType.BANK_CREDIT;
            case MARK_DEBIT -> SettlementLineType.BANK_DEBIT;
            case MARK_FEE -> SettlementLineType.BANK_FEE;
            default -> null;
        };
    }

    // ------------------------------------------------------------------- field primitives

    /** An exact, unsigned decimal at the currency's scale — more decimals is `SCALE_MISMATCH`. */
    private static Money amount(
            String text,
            CurrencyCode currency,
            int scale,
            int line,
            String field,
            List<FormatDefect> defects) {
        if (!AMOUNT_SHAPE.matcher(text).matches()) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return null;
        }
        BigDecimal decimal = new BigDecimal(text);
        if (decimal.scale() > scale) {
            defect(defects, FormatDefect.at(RejectionCode.SCALE_MISMATCH, line, field));
            return null;
        }
        return Money.ofPersisted(
                decimal.setScale(scale).unscaledValue().longValueExact(), currency, scale);
    }

    private static Optional<LocalDate> date(
            String text, int line, String field, List<FormatDefect> defects) {
        if (!DATE_SHAPE.matcher(text).matches()) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException impossibleDay) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return Optional.empty();
        }
    }

    private static void defect(List<FormatDefect> defects, FormatDefect defect) {
        if (defects.size() < MAX_DEFECTS) {
            defects.add(defect);
        }
    }

    // ------------------------------------------------------------------------ tokenising

    /**
     * One physical record: its raw text, the known tag it starts with ({@code null} for a blank
     * line or an unknown tag), the text after that tag, and the 1-based line it sits on.
     */
    private record TaggedRecord(String raw, String tag, String value, int physicalLine) {

        /** Identifiers only: a record's text may be a narrative or the account reference. */
        @Override
        public String toString() {
            return "TaggedRecord[" + physicalLine + "]";
        }
    }

    private static List<TaggedRecord> records(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] physical = text.split("\r?\n", -1);
        int count = physical.length;
        if (count > 0 && physical[count - 1].isEmpty()) {
            count--; // The trailing newline's empty tail - or the empty file's only "line".
        }
        List<TaggedRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String raw = physical[i];
            String tag = tagOf(raw);
            records.add(
                    new TaggedRecord(
                            raw, tag, tag == null ? raw : raw.substring(tag.length()), i + 1));
        }
        return records;
    }

    private static String tagOf(String raw) {
        for (String tag : TAGS) {
            if (raw.startsWith(tag)) {
                return tag;
            }
        }
        return null;
    }
}
