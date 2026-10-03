package com.finapp.settlement.format.simpsp;

import com.finapp.settlement.ConservativeScreen;
import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.LineDirection;
import com.finapp.settlement.LineReferenceKind;
import com.finapp.settlement.ReferenceShape;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The simulated card PSP's CSV settlement report, version 1 (`P8-TSK-008`, ADR-0066 §§3, 8) —
 * frozen by its golden-file test: any change to this screen or parser is a NEW version.
 *
 * <h2>The shape v1 freezes</h2>
 *
 * <pre>
 * H,SIM_PSP_CSV,1,&lt;batchRef&gt;,&lt;currency&gt;,&lt;businessDate&gt;
 * D,&lt;seq&gt;,&lt;type&gt;,&lt;amount&gt;,&lt;fee&gt;,&lt;currency&gt;,&lt;businessDate&gt;,&lt;settlementDate&gt;,&lt;valueDate&gt;,&lt;pspRef&gt;,&lt;acquirerRef&gt;,&lt;disputeRef&gt;,&lt;ourRef&gt;,&lt;descriptor&gt;
 * T,&lt;count&gt;,&lt;net&gt;,&lt;remittanceRef&gt;
 * </pre>
 *
 * <p>UTF-8, an optional BOM, LF or CRLF, an optional trailing newline. Amounts are signed
 * decimals; the sign is the direction from the platform's view, and each known provider type
 * declares the sign it must carry. A non-empty {@code fee} splits the record into its
 * transaction line and a {@code PROCESSING_FEE} line carrying {@code ORIGINAL_REF} — the
 * gross-plus-fee split, so the trailer's net is Σ(signed amounts) − Σ(fees). The trailer's
 * count is the count of {@code D} records, before any split.
 *
 * <p><strong>This class is the provider vocabulary's whole world</strong>
 * ({@code INV-PAY-03}): {@code SALE} and its siblings appear here and nowhere else, and an
 * unknown type becomes {@code OTHER_IN}/{@code OTHER_OUT} by its sign — kept, never dropped,
 * never a success ({@code INV-REC-02}).
 *
 * <h2>The field-class screen (ADR-0066 §3, C6)</h2>
 *
 * <p>Reference fields are checked by their shape classes — the PSP's own references carry a
 * letter, colon, underscore or dash AND are a {@link ReferenceShape} (no digit run of card
 * length, single dashes collapsed; no account identifier, contiguous or printed), so a card
 * number fails the class however it is written; the acquirer reference is the 23-digit ARN, a
 * short id of 8–12 digits or the 15-digit network transaction id, and is <em>never</em> tested
 * as free text, which is exactly how a Luhn-valid 15-digit network transaction id survives.
 * Amounts and dates are checked by type. Only the descriptor is declared free text. <strong>A
 * field that fails its declared class is screened as free text</strong> before the file can be
 * stored as malformed — a PAN in a reference column is refused, never retained. Bytes that do
 * not parse structurally are screened as one conservative stream.
 *
 * <p><em>(Corrected 2026-10-02 by the Phase 8 → 9 transition, the audit's {@code SEC-02}: the
 * screen's reference classes were the parse's — {@code (?=.*[A-Za-z:_-])[A-Za-z0-9:_-]{2,100}}
 * admitted {@code 4111-1111-1111-1111} on its dashes and {@code PAN4111111111111111} on its
 * letters, and {@code [0-9]{8,23}} admitted a bare 16-digit card number in the acquirer column —
 * so a card number passed the door unscreened into {@code batch.external_batch_ref} and
 * {@code line_reference}, and back out of {@code GET /batches/{id}}. Refusing what v1 always
 * claimed to refuse corrects v1 rather than making a v2: the SCREEN's classes now exclude
 * instrument shapes as the three later formats' do, the golden file is unchanged and passes, and
 * the parse's classes — what a stored line is — are untouched.)</em>
 *
 * <p><em>(Corrected 2026-10-03 by the Phase 8 → 9 transition's re-gate, NEW-SEC-2:
 * {@code 4111:1111:1111:1111} and {@code 4111_1111_1111_1111} are the parse's reference class
 * AND were a {@link ReferenceShape} — the shape's run collapse read single dashes alone — so
 * both still passed the door into {@code batch.external_batch_ref} / {@code line_reference}
 * and out of {@code GET /batches/{id}}. {@link ReferenceShape} now collapses ':' and '_'
 * beside '-', and a reference-class value that fails its class is screened as free text
 * twice: as written, and with ':' and '_' read as the dashes they group like — the
 * conservative walk alone collapses only the printed separators. Refusing what the screen
 * always claimed to refuse corrects v1 again; the parse's classes and the golden file are
 * untouched.)</em>
 */
public final class SimPspCsvFormat implements SettlementFormat {

    public static final SimPspCsvFormat INSTANCE = new SimPspCsvFormat();

    /** Defect rows are bounded like their table (`V003`): the first hundred tell the story. */
    private static final int MAX_DEFECTS = 100;

    // The provider's own words - confined to this file (INV-PAY-03).
    private static final String P_SALE = "SALE";
    private static final String P_REFUND = "REFUND";
    private static final String P_CHARGEBACK = "CHARGEBACK";
    private static final String P_CB_REVERSAL = "CB_REVERSAL";
    private static final String P_DISPUTE_FEE = "DISPUTE_FEE";
    private static final String P_ADJUSTMENT = "ADJUSTMENT";

    /** A PSP-side reference, as the parse reads it: at least one letter, colon, underscore or
     * dash — never a bare digit run. A dash or a letter alone does not keep a card number out
     * ({@code 4111-1111-1111-1111}, {@code PAN4111111111111111}), so the SCREEN reads it with
     * {@link #screenedPspReference} (SEC-02, corrected 2026-10-02). */
    private static final Pattern PSP_REFERENCE =
            Pattern.compile("(?=.*[A-Za-z:_-])[A-Za-z0-9:_-]{2,100}");

    /** The acquirer reference: 8–23 digits, by design never tested as free text. */
    private static final Pattern ACQUIRER_REFERENCE = Pattern.compile("[0-9]{8,23}");

    /**
     * The acquirer reference as the SCREEN reads it: the 23-digit ARN, a short id of 8–12 digits
     * (below the door's 13–19 card band), or the 15-digit network transaction id that may be
     * Luhn-valid (ADR-0066 §3, {@code INV-PAY-02}'s one written exemption). Any other length is
     * of card length and is screened as free text, so a Luhn-valid one is refused (SEC-02).
     */
    private static final Pattern ACQUIRER_REFERENCE_SCREENED =
            Pattern.compile("[0-9]{8,12}|[0-9]{15}|[0-9]{20,23}");

    private static final Pattern CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");
    private static final Pattern AMOUNT_SHAPE = Pattern.compile("-?[0-9]{1,13}(\\.[0-9]{1,4})?");
    private static final Pattern FEE_SHAPE = Pattern.compile("[0-9]{1,13}(\\.[0-9]{1,4})?");
    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern SEQ_SHAPE = Pattern.compile("[0-9]{1,9}");
    private static final Pattern COUNT_SHAPE = Pattern.compile("[0-9]{1,9}");
    private static final Pattern REMITTANCE_REFERENCE = Pattern.compile("PSP-REM-[0-9]{4,12}");

    // Immutable lists, not arrays: a static array is mutable state the architecture rule
    // rightly refuses (ADR-0014), and a field-name table must be un-editable at runtime.
    private static final java.util.List<String> HEADER_FIELDS =
            java.util.List.of(
                    "recordType", "formatId", "formatVersion", "batchRef", "currency",
                    "businessDate");
    private static final java.util.List<String> DETAIL_FIELDS =
            java.util.List.of(
                    "recordType", "seq", "type", "amount", "fee", "currency", "businessDate",
                    "settlementDate", "valueDate", "pspRef", "acquirerRef", "disputeRef",
                    "ourRef", "descriptor");
    private static final java.util.List<String> TRAILER_FIELDS =
            java.util.List.of("recordType", "count", "net", "remittanceRef");

    private SimPspCsvFormat() {}

    @Override
    public SettlementFormatId id() {
        return SettlementFormatId.SIM_PSP_CSV;
    }

    @Override
    public int version() {
        return 1;
    }

    // ---------------------------------------------------------------- the field-class screen

    @Override
    public DeliveryScreen.Screening screen(byte[] content) {
        List<Record> records = records(content);
        if (!isStructurallyCsv(records)) {
            // Bytes that do not parse structurally are one conservative stream (ADR-0066 §3).
            return ConservativeScreen.INSTANCE.screen(content);
        }
        for (Record record : records) {
            Optional<DeliveryScreen.Finding> finding = screenRecord(record);
            if (finding.isPresent()) {
                return new DeliveryScreen.Screening(records.size(), finding);
            }
        }
        return new DeliveryScreen.Screening(records.size(), Optional.empty());
    }

    /** Structural enough to screen field by field: every record splits to a known shape. */
    private static boolean isStructurallyCsv(List<Record> records) {
        if (records.isEmpty()) {
            return false;
        }
        for (Record record : records) {
            String[] fields = record.fields();
            boolean known =
                    switch (fields[0]) {
                        case "H" -> fields.length == HEADER_FIELDS.size();
                        case "D" -> fields.length == DETAIL_FIELDS.size();
                        case "T" -> fields.length == TRAILER_FIELDS.size();
                        default -> false;
                    };
            if (!known) {
                return false;
            }
        }
        return true;
    }

    private static Optional<DeliveryScreen.Finding> screenRecord(Record record) {
        String[] fields = record.fields();
        return switch (fields[0]) {
            case "H" ->
                    firstFinding(
                            record,
                            referenceClassed(record, 3, HEADER_FIELDS.get(3),
                                    SimPspCsvFormat::screenedPspReference),
                            classed(record, 4, HEADER_FIELDS.get(4), CURRENCY_SHAPE),
                            classed(record, 5, HEADER_FIELDS.get(5), DATE_SHAPE));
            case "D" ->
                    firstFinding(
                            record,
                            classed(record, 1, DETAIL_FIELDS.get(1), SEQ_SHAPE),
                            referenceClassed(record, 2, DETAIL_FIELDS.get(2),
                                    SimPspCsvFormat::screenedPspReference),
                            classed(record, 3, DETAIL_FIELDS.get(3), AMOUNT_SHAPE),
                            optionalClassed(record, 4, DETAIL_FIELDS.get(4), FEE_SHAPE),
                            classed(record, 5, DETAIL_FIELDS.get(5), CURRENCY_SHAPE),
                            classed(record, 6, DETAIL_FIELDS.get(6), DATE_SHAPE),
                            optionalClassed(record, 7, DETAIL_FIELDS.get(7), DATE_SHAPE),
                            optionalClassed(record, 8, DETAIL_FIELDS.get(8), DATE_SHAPE),
                            referenceClassed(record, 9, DETAIL_FIELDS.get(9),
                                    SimPspCsvFormat::screenedPspReference),
                            optionalReferenceClassed(record, 10, DETAIL_FIELDS.get(10),
                                    ACQUIRER_REFERENCE_SCREENED.asMatchPredicate()),
                            optionalReferenceClassed(record, 11, DETAIL_FIELDS.get(11),
                                    SimPspCsvFormat::screenedPspReference),
                            optionalReferenceClassed(record, 12, DETAIL_FIELDS.get(12),
                                    SimPspCsvFormat::screenedPspReference),
                            // The one declared free-text field: always screened (ADR-0066 §3).
                            freeText(record, 13, DETAIL_FIELDS.get(13)));
            case "T" ->
                    firstFinding(
                            record,
                            classed(record, 1, TRAILER_FIELDS.get(1), COUNT_SHAPE),
                            classed(record, 2, TRAILER_FIELDS.get(2), AMOUNT_SHAPE),
                            classed(record, 3, TRAILER_FIELDS.get(3), REMITTANCE_REFERENCE));
            default -> Optional.empty();
        };
    }

    @SafeVarargs
    private static Optional<DeliveryScreen.Finding> firstFinding(
            Record record, Optional<DeliveryScreen.Finding>... findings) {
        for (Optional<DeliveryScreen.Finding> finding : findings) {
            if (finding.isPresent()) {
                return finding;
            }
        }
        return Optional.empty();
    }

    /**
     * A classed field: matches its shape and is never tested as free text — or fails its
     * class and IS, before the file can be stored as malformed (C6).
     */
    private static Optional<DeliveryScreen.Finding> classed(
            Record record, int index, String field, Pattern shape) {
        if (shape.matcher(record.fields()[index]).matches()) {
            return Optional.empty();
        }
        return freeText(record, index, field);
    }

    private static Optional<DeliveryScreen.Finding> optionalClassed(
            Record record, int index, String field, Pattern shape) {
        if (record.fields()[index].isEmpty()) {
            return Optional.empty();
        }
        return classed(record, index, field, shape);
    }

    /**
     * A REFERENCE class's field (SEC-02; NEW-SEC-2, corrected 2026-10-03): matches its shape —
     * or fails it and is screened as free text twice, as written and with the machine
     * separators ':' and '_' read as the dashes they group like. The conservative walk
     * collapses single spaces and dashes alone, so without the second walk a card number
     * written {@code 4111:1111:1111:1111} or {@code 4111_1111_1111_1111} — both the parse's
     * class — would pass the door into {@code batch.external_batch_ref} and
     * {@code line_reference}.
     */
    private static Optional<DeliveryScreen.Finding> referenceClassed(
            Record record, int index, String field, Predicate<String> shape) {
        String value = record.fields()[index];
        if (shape.test(value)) {
            return Optional.empty();
        }
        return freeText(record, index, field)
                .or(() -> screened(record, field, value.replace(':', '-').replace('_', '-')));
    }

    private static Optional<DeliveryScreen.Finding> optionalReferenceClassed(
            Record record, int index, String field, Predicate<String> shape) {
        if (record.fields()[index].isEmpty()) {
            return Optional.empty();
        }
        return referenceClassed(record, index, field, shape);
    }

    /**
     * A PSP-side reference as the SCREEN reads it: the parse's class AND a
     * {@link ReferenceShape} — a value of the class that carries a card number or an account
     * identifier however written fails it and meets the free-text screen (SEC-02).
     */
    private static boolean screenedPspReference(String value) {
        return PSP_REFERENCE.matcher(value).matches() && ReferenceShape.isReference(value);
    }

    /** One field's text as its own one-line stream through the conservative walker. */
    private static Optional<DeliveryScreen.Finding> freeText(
            Record record, int index, String field) {
        return screened(record, field, record.fields()[index]);
    }

    /** One value — the field's own text, or its translation — through the walker. */
    private static Optional<DeliveryScreen.Finding> screened(
            Record record, String field, String value) {
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
        List<Record> records = records(content);
        if (records.isEmpty()) {
            return new Result.Rejected(
                    RejectionCode.MALFORMED,
                    List.of(FormatDefect.wholeFile(RejectionCode.MALFORMED, "recordType")));
        }

        Record header = records.get(0);
        if (!"H".equals(header.fields()[0])
                || header.fields().length != HEADER_FIELDS.size()) {
            return new Result.Rejected(
                    RejectionCode.MALFORMED,
                    List.of(FormatDefect.at(RejectionCode.MALFORMED, header.physicalLine(),
                            "recordType")));
        }
        if (!id().name().equals(header.fields()[1])
                || !String.valueOf(version()).equals(header.fields()[2])) {
            return new Result.Rejected(
                    RejectionCode.UNSUPPORTED_FORMAT,
                    List.of(FormatDefect.at(RejectionCode.UNSUPPORTED_FORMAT,
                            header.physicalLine(), "formatId")));
        }

        List<FormatDefect> defects = new ArrayList<>();
        String batchRef = header.fields()[3];
        if (!PSP_REFERENCE.matcher(batchRef).matches()) {
            defects.add(FormatDefect.at(RejectionCode.MALFORMED, header.physicalLine(),
                    "batchRef"));
        }
        CurrencyCode currency = null;
        int scale = 0;
        String currencyText = header.fields()[4];
        if (!CURRENCY_SHAPE.matcher(currencyText).matches()) {
            defects.add(FormatDefect.at(RejectionCode.MALFORMED, header.physicalLine(),
                    "currency"));
        } else {
            try {
                currency = CurrencyCode.of(currencyText);
                scale = currency.minorUnits();
            } catch (RuntimeException unknownToTheplatform) {
                return new Result.Rejected(
                        RejectionCode.UNKNOWN_CURRENCY,
                        List.of(FormatDefect.at(RejectionCode.UNKNOWN_CURRENCY,
                                header.physicalLine(), "currency")));
            }
        }
        LocalDate businessDate =
                date(header.fields()[5], header.physicalLine(), "businessDate", defects)
                        .orElse(null);

        Record trailer = records.get(records.size() - 1);
        boolean trailerPresent =
                records.size() >= 2
                        && "T".equals(trailer.fields()[0])
                        && trailer.fields().length == TRAILER_FIELDS.size();
        if (!trailerPresent) {
            defects.add(FormatDefect.at(RejectionCode.MALFORMED, trailer.physicalLine(),
                    "recordType"));
        }

        List<ParsedLine> lines = new ArrayList<>();
        Set<Long> seenSeqs = new HashSet<>();
        Money runningNet = null;
        int detailRecords = 0;
        int canonicalLineNo = 0;
        for (int i = 1; i < records.size() - (trailerPresent ? 1 : 0); i++) {
            Record record = records.get(i);
            if (!"D".equals(record.fields()[0])
                    || record.fields().length != DETAIL_FIELDS.size()) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, record.physicalLine(),
                        "recordType"));
                continue;
            }
            detailRecords++;
            if (currency == null || businessDate == null) {
                continue; // The header's defects already reject the file.
            }
            Detail detail = detail(record, currency, scale, seenSeqs, defects);
            if (detail == null) {
                continue;
            }
            canonicalLineNo++;
            lines.add(detail.toLine(canonicalLineNo));
            runningNet = fold(runningNet, detail.signedAmount());
            if (detail.fee != null && detail.fee.minorUnits() > 0) {
                canonicalLineNo++;
                lines.add(detail.feeLine(canonicalLineNo));
                runningNet = fold(runningNet, detail.fee.negated());
            }
        }

        if (defects.isEmpty() && trailerPresent && currency != null && businessDate != null) {
            long declaredCount = number(trailer.fields()[1], trailer.physicalLine(), "count",
                    defects);
            Money declaredNet =
                    amount(trailer.fields()[2], currency, scale, true, trailer.physicalLine(),
                            "net", defects);
            String remittanceRef = trailer.fields()[3];
            if (!REMITTANCE_REFERENCE.matcher(remittanceRef).matches()) {
                defects.add(FormatDefect.at(RejectionCode.MALFORMED, trailer.physicalLine(),
                        "remittanceRef"));
            }
            if (defects.isEmpty()) {
                Money foldedNet =
                        runningNet == null ? Money.ofPersisted(0, currency, scale) : runningNet;
                if (declaredCount != detailRecords) {
                    return new Result.Rejected(
                            RejectionCode.CONTROL_TOTAL_MISMATCH,
                            List.of(FormatDefect.at(RejectionCode.CONTROL_TOTAL_MISMATCH,
                                    trailer.physicalLine(), "count")));
                }
                if (!foldedNet.equals(declaredNet)) {
                    return new Result.Rejected(
                            RejectionCode.CONTROL_TOTAL_MISMATCH,
                            List.of(FormatDefect.at(RejectionCode.CONTROL_TOTAL_MISMATCH,
                                    trailer.physicalLine(), "net")));
                }
                return new Result.Parsed(
                        new ParsedBatch(
                                batchRef,
                                businessDate,
                                remittanceRef,
                                detailRecords,
                                declaredNet,
                                lines));
            }
        }

        RejectionCode verdict =
                defects.stream()
                        .map(FormatDefect::code)
                        .filter(code -> code != RejectionCode.MALFORMED)
                        .findFirst()
                        .orElse(RejectionCode.MALFORMED);
        return new Result.Rejected(verdict, List.copyOf(defects));
    }

    // ------------------------------------------------------------------- the detail record

    /** One D record's parsed fields, before canonicalisation. */
    private static final class Detail {
        SettlementLineType type;
        LineDirection direction;
        Money magnitude;
        Money fee;
        LocalDate businessDate;
        Optional<LocalDate> settlementDate = Optional.empty();
        Optional<LocalDate> valueDate = Optional.empty();
        TreeMap<LineReferenceKind, String> references = new TreeMap<>();
        String primaryReference;
        byte[] rawSha256;

        Money signedAmount() {
            return direction == LineDirection.INBOUND ? magnitude : magnitude.negated();
        }

        ParsedLine toLine(int canonicalLineNo) {
            return new ParsedLine(
                    canonicalLineNo,
                    type,
                    direction,
                    magnitude,
                    businessDate,
                    settlementDate,
                    valueDate,
                    references,
                    rawSha256);
        }

        ParsedLine feeLine(int canonicalLineNo) {
            TreeMap<LineReferenceKind, String> feeReferences = new TreeMap<>();
            // The fee names the transaction it rode in on - the record's PRIMARY reference,
            // never whichever kind happens to sort first.
            feeReferences.put(LineReferenceKind.ORIGINAL_REF, primaryReference);
            return new ParsedLine(
                    canonicalLineNo,
                    SettlementLineType.PROCESSING_FEE,
                    LineDirection.OUTBOUND,
                    fee,
                    businessDate,
                    settlementDate,
                    valueDate,
                    feeReferences,
                    rawSha256);
        }
    }

    private static Detail detail(
            Record record,
            CurrencyCode currency,
            int scale,
            Set<Long> seenSeqs,
            List<FormatDefect> defects) {
        String[] f = record.fields();
        int line = record.physicalLine();
        // This record's own defects, gathered apart: whether it is read must never depend on
        // whether the file's bounded list still had room to record them.
        List<FormatDefect> found = new ArrayList<>();

        long seq = number(f[1], line, "seq", found);
        if (seq >= 1 && !seenSeqs.add(seq)) {
            defect(found, FormatDefect.at(RejectionCode.MALFORMED, line, "seq"));
        }
        Money magnitude = amount(f[3], currency, scale, true, line, "amount", found);
        Money fee =
                f[4].isEmpty()
                        ? null
                        : amount(f[4], currency, scale, false, line, "fee", found);
        if (!f[5].equals(currency.code())) {
            // One batch, one currency (INV-SET-07): a stray currency is the record's defect.
            defect(found, FormatDefect.at(RejectionCode.MALFORMED, line, "currency"));
        }

        Detail detail = new Detail();
        detail.businessDate = date(f[6], line, "businessDate", found).orElse(null);
        detail.settlementDate = date(f[7].isEmpty() ? null : f[7], line, "settlementDate",
                found);
        detail.valueDate = date(f[8].isEmpty() ? null : f[8], line, "valueDate", found);

        boolean inbound = magnitude != null && magnitude.minorUnits() > 0;
        if (magnitude != null && magnitude.minorUnits() == 0) {
            defect(found, FormatDefect.at(RejectionCode.MALFORMED, line, "amount"));
        }
        // The provider's vocabulary, mapped here and nowhere else (INV-PAY-03). An unknown
        // type is kept as OTHER_IN/OTHER_OUT by its sign - never dropped, never a success
        // (INV-REC-02).
        LineReferenceKind primaryKind;
        switch (f[2]) {
            case P_SALE -> {
                detail.type = SettlementLineType.CAPTURE;
                primaryKind = LineReferenceKind.PSP_CAPTURE_REF;
                requireSign(inbound, true, line, found);
            }
            case P_REFUND -> {
                detail.type = SettlementLineType.REFUND;
                primaryKind = LineReferenceKind.PSP_REFUND_REF;
                requireSign(inbound, false, line, found);
            }
            case P_CHARGEBACK -> {
                detail.type = SettlementLineType.CHARGEBACK;
                primaryKind = LineReferenceKind.DISPUTE_REF;
                requireSign(inbound, false, line, found);
            }
            case P_CB_REVERSAL -> {
                detail.type = SettlementLineType.CHARGEBACK_REVERSAL;
                primaryKind = LineReferenceKind.DISPUTE_REF;
                requireSign(inbound, true, line, found);
            }
            case P_DISPUTE_FEE -> {
                detail.type = SettlementLineType.DISPUTE_FEE;
                primaryKind = LineReferenceKind.DISPUTE_REF;
                requireSign(inbound, false, line, found);
            }
            case P_ADJUSTMENT -> {
                detail.type = SettlementLineType.COUNTERPARTY_ADJUSTMENT;
                primaryKind = LineReferenceKind.ORIGINAL_REF;
            }
            default -> {
                detail.type =
                        inbound ? SettlementLineType.OTHER_IN : SettlementLineType.OTHER_OUT;
                primaryKind = LineReferenceKind.ORIGINAL_REF;
            }
        }
        detail.direction = inbound ? LineDirection.INBOUND : LineDirection.OUTBOUND;

        reference(detail, primaryKind, f[9], true, line, "pspRef", PSP_REFERENCE, found);
        reference(detail, LineReferenceKind.ACQUIRER_REF, f[10], false, line, "acquirerRef",
                ACQUIRER_REFERENCE, found);
        if (primaryKind == LineReferenceKind.DISPUTE_REF && !f[11].isEmpty()) {
            // A dispute-stage record's primary reference IS its dispute reference; a second
            // one would make the typed map ambiguous.
            defect(found, FormatDefect.at(RejectionCode.MALFORMED, line, "disputeRef"));
        } else {
            reference(detail, LineReferenceKind.DISPUTE_REF, f[11], false, line, "disputeRef",
                    PSP_REFERENCE, found);
        }
        reference(detail, LineReferenceKind.OUR_REF, f[12], false, line, "ourRef",
                PSP_REFERENCE, found);
        if (f[13].length() > 200) {
            defect(found, FormatDefect.at(RejectionCode.MALFORMED, line, "descriptor"));
        }

        for (FormatDefect each : found) {
            defect(defects, each);
        }
        if (!found.isEmpty()) {
            return null;
        }
        detail.magnitude = magnitude.minorUnits() > 0 ? magnitude : magnitude.negated();
        detail.fee = fee;
        detail.rawSha256 = ParsedLine.sha256(record.raw().getBytes(StandardCharsets.UTF_8));
        return detail;
    }

    private static void requireSign(
            boolean inbound, boolean expectedInbound, int line, List<FormatDefect> defects) {
        if (inbound != expectedInbound) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, "amount"));
        }
    }

    private static void reference(
            Detail detail,
            LineReferenceKind kind,
            String value,
            boolean required,
            int line,
            String field,
            Pattern shape,
            List<FormatDefect> defects) {
        if (value.isEmpty()) {
            if (required) {
                defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            }
            return;
        }
        if (!shape.matcher(value).matches()) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return;
        }
        detail.references.put(kind, value);
        if (required) {
            detail.primaryReference = value;
        }
    }

    // ------------------------------------------------------------------- field primitives

    private static long number(
            String text, int line, String field, List<FormatDefect> defects) {
        if (!SEQ_SHAPE.matcher(text).matches()) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return -1;
        }
        long value = Long.parseLong(text);
        if (value < 1 && "seq".equals(field)) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return -1;
        }
        return value;
    }

    /** An exact decimal at the currency's scale — more decimals is `SCALE_MISMATCH`. */
    private static Money amount(
            String text,
            CurrencyCode currency,
            int scale,
            boolean signed,
            int line,
            String field,
            List<FormatDefect> defects) {
        Pattern shape = signed ? AMOUNT_SHAPE : FEE_SHAPE;
        if (!shape.matcher(text).matches()) {
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
        if (text == null) {
            return Optional.empty();
        }
        if (!DATE_SHAPE.matcher(text).matches()) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException impossibleMonth) {
            defect(defects, FormatDefect.at(RejectionCode.MALFORMED, line, field));
            return Optional.empty();
        }
    }

    private static Money fold(Money current, Money next) {
        return current == null ? next : current.plus(next);
    }

    private static void defect(List<FormatDefect> defects, FormatDefect defect) {
        if (defects.size() < MAX_DEFECTS) {
            defects.add(defect);
        }
    }

    // ------------------------------------------------------------------------ tokenising

    /** One physical record: its raw text, its fields, and the 1-based line it sits on. */
    private record Record(String raw, String[] fields, int physicalLine) {}

    private static List<Record> records(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<Record> records = new ArrayList<>();
        int line = 0;
        for (String raw : text.split("\r?\n", -1)) {
            line++;
            if (raw.isEmpty()) {
                continue; // The trailing newline's empty tail; a blank middle line is one too.
            }
            records.add(new Record(raw, raw.split(",", -1), line));
        }
        return records;
    }
}
