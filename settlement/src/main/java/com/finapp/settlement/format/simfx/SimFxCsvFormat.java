package com.finapp.settlement.format.simfx;

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
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The simulated FX provider's trade report, version 1 (`P9-TSK-011`, ADR-0077, PHASE_9_PLAN.md
 * section 12.9.2) - frozen by its golden-file test: any change to this screen, this reader or this
 * parser is a NEW version, never an edit.
 *
 * <h2>The shape v1 freezes</h2>
 *
 * <pre>
 * H,SIM_FX_CSV,1,&lt;batchRef&gt;,&lt;currency&gt;,&lt;valueDate&gt;
 * D,&lt;seq&gt;,&lt;code&gt;,&lt;amount&gt;,&lt;fee&gt;,&lt;fxTradeRef&gt;,&lt;coverRef&gt;
 * T,&lt;count&gt;,&lt;net&gt;,&lt;remittanceRef&gt;
 * </pre>
 *
 * <p>One file per provider, currency and value date: every line is in the header's currency and
 * settles on the header's value date - the date the provider delivers or takes the currency - so
 * each line carries it as business, settlement AND value date (the timing detector compares it with
 * the cover leg's expected date, P9-TSK-013). The reader is the payout report's: UTF-8, an optional
 * BOM, LF or CRLF, one record per physical line split at every comma (no quoting), one header first,
 * one trailer last, the detail records between them numbered 1..n strictly in order; a day with no
 * trades is a header and a trailer declaring a count of 0 and a net of 0.
 *
 * <p>Amounts are SIGNED from the platform's view, and each known code declares the sign it must
 * carry: {@code SOLD} - the platform delivered this currency to the provider, money out - is
 * negative and becomes {@code FX_SOLD}, outbound; {@code BOUGHT} - the provider delivered this
 * currency to the platform, money in - is positive and becomes {@code FX_BOUGHT}, inbound. A cover's
 * two legs therefore arrive in two files, one per currency: reconciliation explains each currency
 * on its own and never converts ({@code INV-REC-08}). A non-zero {@code fee} splits the record into
 * its leg and an {@code FX_FEE} line carrying {@code ORIGINAL_REF} = the record's cover reference -
 * the trailer's net is Σ(signed amounts) − Σ(fees); the count is the detail records before any split.
 *
 * <p><strong>This class is the FX provider's report vocabulary's whole world</strong>
 * ({@code INV-PAY-03}): {@code SOLD} and {@code BOUGHT} appear here and nowhere else -
 * {@code SettlementVocabularyIsConfinedTest} holds the line - and an unknown well-formed code becomes
 * {@code OTHER_OUT}/{@code OTHER_IN} by its sign: kept, never dropped, never a success
 * ({@code INV-REC-02}). The provider's trade reference is the line's {@code FX_TRADE_REF} (an alias,
 * for the trace) and the platform's cover reference its {@code COVER_REF} - the KEY, admitted only in
 * its exact minted shape {@code T-} and 32 lower-case hex digits (the cover attempt's
 * {@code client_reference}, fx `V006`).
 *
 * <h2>The field-class screen (ADR-0066 §3, C6)</h2>
 *
 * <p>Every field is checked by its declared class and a field that fails its class is screened as
 * free text before the file can be stored as malformed. No class admits an instrument shape, with
 * one exact exception: the platform's own cover reference, whose hex can hold a digit run of card
 * length, is admitted as {@code coverRef} only in its minted shape. The format declares no free-text
 * field. Bytes that do not parse structurally are screened as one conservative stream.
 *
 * <p>A singleton: v1 needs no configuration, and the adapter is pure - no I/O, no clock, no
 * database - so a verdict and a parse are functions of the bytes.
 */
public final class SimFxCsvFormat implements SettlementFormat {

    public static final SimFxCsvFormat INSTANCE = new SimFxCsvFormat();

    /**
     * The shape of the FX provider's remittance references ({@code FXA-...}, PHASE_9_PLAN.md
     * section 12.9.2): the trailer's own {@code remittanceRef}, and the pattern the composition root
     * declares for {@code fx-sim-a.trade-report}, by which the bank statement's lines are attributed
     * to this report's remittance (ADR-0065 hop 2). One constant, so the reference the parser admits
     * and the reference the attribution reaches cannot disagree. Never a digit run of card length.
     */
    public static final String REMITTANCE_REFERENCE = "FXA-[0-9]{4,12}";

    /**
     * The shape of one counterparty's remittance references under this format (`P9-TSK-026`, M9.8): {@code FX<letter>-...},
     * the letter the counterparty's own ({@code A} is {@link #REMITTANCE_REFERENCE}). Each source declares its own, so
     * a bank statement's line is attributed to exactly one counterparty's report - two counterparties sharing one shape
     * would leave every such line unattributed ({@code SettlementSources} refuses it at composition).
     */
    public static String remittanceReference(char counterpartyLetter) {
        if (counterpartyLetter < 'A' || counterpartyLetter > 'Z') {
            throw new IllegalArgumentException("a counterparty's remittance letter is A-Z: " + counterpartyLetter);
        }
        return "FX" + counterpartyLetter + "-[0-9]{4,12}";
    }

    /**
     * What a report's trailer may carry: any counterparty's shape under this format - the format is the counterparties'
     * shared wire; which counterparty's reference it is, is the source's own pattern's to say at hop 2.
     */
    private static final String REMITTANCE_FAMILY = "FX[A-Z]-[0-9]{4,12}";

    /** Defect rows are bounded like their table (`V003`): the first hundred tell the story. */
    private static final int MAX_DEFECTS = 100;

    /** The provider's batch identifier's frozen bound. */
    private static final int MAX_BATCH_REFERENCE_LENGTH = 64;

    /** A reference's bound: the canonical line's, and {@code line_reference}'s {@code CHECK}. */
    private static final int MAX_REFERENCE_LENGTH = 100;

    /** U+FEFF, written as its code point: an invisible character has no place in source. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    // The record tags.
    private static final String RECORD_HEADER = "H";
    private static final String RECORD_DETAIL = "D";
    private static final String RECORD_TRAILER = "T";

    // The FX provider's own words - confined to this file (INV-PAY-03). Each one is a whole
    // literal: SettlementVocabularyIsConfinedTest scans for exactly these.
    private static final String P_SOLD = "SOLD";
    private static final String P_BOUGHT = "BOUGHT";

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

    /** The characters of the provider's opaque identifiers - the payout report's alphabet. */
    private static final String REFERENCE_ALPHABET = "[A-Za-z0-9._:-]";

    /** The provider's identifier for the day's batch — the batch identity. */
    private static final Pattern BATCH_REFERENCE =
            Pattern.compile(
                    NO_INSTRUMENT_SHAPE + REFERENCE_ALPHABET + "{1," + MAX_BATCH_REFERENCE_LENGTH
                            + "}");

    /** The provider's reference for the trade it executed - the line's alias, for the trace. */
    private static final Pattern TRADE_REFERENCE =
            Pattern.compile(
                    NO_INSTRUMENT_SHAPE + REFERENCE_ALPHABET + "{1," + MAX_REFERENCE_LENGTH + "}");

    /**
     * The platform's OWN cover reference, admitted exactly: {@code T-} and 32 lower-case hex digits,
     * the cover attempt's minted {@code client_reference} (fx `V006`'s {@code CHECK}). Its hex can
     * hold a digit run of card length, so the no-instrument class alone would refuse the platform's
     * own references at random; only this exact shape is admitted.
     */
    private static final Pattern COVER_REFERENCE = Pattern.compile("T-[0-9a-f]{32}");

    private static final Pattern REMITTANCE_REFERENCE_SHAPE = Pattern.compile(REMITTANCE_FAMILY);

    /** A format name: letters and underscores — no digit, so no digit run hides in it. */
    private static final Pattern FORMAT_NAME = Pattern.compile("[A-Z_]{1,64}");

    /**
     * A provider code: upper-case letters and underscores, a letter first — no digit, so no digit
     * run hides in it. A well-formed code no mapping knows is kept as {@code OTHER_*}.
     */
    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z][A-Z_]{0,31}");

    private static final Pattern CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");
    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /** The version, a sequence number and a count: at most nine digits. */
    private static final Pattern COUNT_SHAPE = Pattern.compile("[0-9]{1,9}");

    /**
     * Amounts: at most twelve integer digits — a trillion units, and never a digit run of card
     * length, so an amount's class admits no instrument shape.
     */
    private static final Pattern SIGNED_AMOUNT = Pattern.compile("-?[0-9]{1,12}(\\.[0-9]{1,4})?");

    private static final Pattern FEE_AMOUNT = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,4})?");

    // Field positions within their records.
    private static final int H_FORMAT = 1;
    private static final int H_VERSION = 2;
    private static final int H_BATCH_REF = 3;
    private static final int H_CURRENCY = 4;
    private static final int H_VALUE_DATE = 5;
    private static final int D_SEQ = 1;
    private static final int D_CODE = 2;
    private static final int D_AMOUNT = 3;
    private static final int D_FEE = 4;
    private static final int D_TRADE_REF = 5;
    private static final int D_COVER_REF = 6;
    private static final int T_COUNT = 1;
    private static final int T_NET = 2;
    private static final int T_REMITTANCE_REF = 3;

    // Immutable lists, not arrays: a static array is mutable state the architecture rule
    // rightly refuses (ADR-0014), and a field-name table must be un-editable at runtime.
    private static final List<String> HEADER_FIELDS =
            List.of(
                    "recordType", "formatId", "formatVersion", "batchRef", "currency",
                    "valueDate");
    private static final List<String> DETAIL_FIELDS =
            List.of(
                    "recordType", "seq", "code", "amount", "fee", "fxTradeRef", "coverRef");
    private static final List<String> TRAILER_FIELDS =
            List.of("recordType", "count", "net", "remittanceRef");

    private static final String F_RECORD_TYPE = "recordType";

    private SimFxCsvFormat() {}

    @Override
    public SettlementFormatId id() {
        return SettlementFormatId.SIM_FX_CSV;
    }

    @Override
    public int version() {
        return 1;
    }

    // ---------------------------------------------------------------- the field-class screen

    @Override
    public DeliveryScreen.Screening screen(byte[] content) {
        List<CsvRecord> records = records(content);
        if (!isStructurallyCsv(records)) {
            // Bytes that do not parse structurally are one conservative stream (ADR-0066 §3).
            return ConservativeScreen.INSTANCE.screen(content);
        }
        for (CsvRecord record : records) {
            Optional<DeliveryScreen.Finding> finding = screenRecord(record);
            if (finding.isPresent()) {
                return new DeliveryScreen.Screening(records.size(), finding);
            }
        }
        return new DeliveryScreen.Screening(records.size(), Optional.empty());
    }

    /** Structural enough to screen field by field: every record splits to a known shape. */
    private static boolean isStructurallyCsv(List<CsvRecord> records) {
        if (records.isEmpty()) {
            return false;
        }
        for (CsvRecord record : records) {
            if (!record.is(RECORD_HEADER, HEADER_FIELDS)
                    && !record.is(RECORD_DETAIL, DETAIL_FIELDS)
                    && !record.is(RECORD_TRAILER, TRAILER_FIELDS)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<DeliveryScreen.Finding> screenRecord(CsvRecord record) {
        return switch (record.field(0)) {
            case RECORD_HEADER ->
                    firstFinding(
                            classed(record, H_FORMAT, HEADER_FIELDS, FORMAT_NAME),
                            classed(record, H_VERSION, HEADER_FIELDS, COUNT_SHAPE),
                            classed(record, H_BATCH_REF, HEADER_FIELDS, BATCH_REFERENCE),
                            classed(record, H_CURRENCY, HEADER_FIELDS, CURRENCY_SHAPE),
                            classed(record, H_VALUE_DATE, HEADER_FIELDS, DATE_SHAPE));
            case RECORD_DETAIL ->
                    firstFinding(
                            classed(record, D_SEQ, DETAIL_FIELDS, COUNT_SHAPE),
                            classed(record, D_CODE, DETAIL_FIELDS, CODE_SHAPE),
                            classed(record, D_AMOUNT, DETAIL_FIELDS, SIGNED_AMOUNT),
                            optionalClassed(record, D_FEE, DETAIL_FIELDS, FEE_AMOUNT),
                            classed(record, D_TRADE_REF, DETAIL_FIELDS, TRADE_REFERENCE),
                            classed(record, D_COVER_REF, DETAIL_FIELDS, COVER_REFERENCE));
            case RECORD_TRAILER ->
                    firstFinding(
                            classed(record, T_COUNT, TRAILER_FIELDS, COUNT_SHAPE),
                            classed(record, T_NET, TRAILER_FIELDS, SIGNED_AMOUNT),
                            classed(record, T_REMITTANCE_REF, TRAILER_FIELDS,
                                    REMITTANCE_REFERENCE_SHAPE));
            default -> Optional.empty();
        };
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
            CsvRecord record, int index, List<String> names, Pattern shape) {
        if (shape.matcher(record.field(index)).matches()) {
            return Optional.empty();
        }
        return freeText(record, index, names.get(index));
    }

    private static Optional<DeliveryScreen.Finding> optionalClassed(
            CsvRecord record, int index, List<String> names, Pattern shape) {
        if (record.field(index).isEmpty()) {
            return Optional.empty();
        }
        return classed(record, index, names, shape);
    }

    /**
     * One field's text as its own one-line stream through the conservative walker, the finding
     * re-homed to the physical line and the field's NAME — never its value.
     */
    private static Optional<DeliveryScreen.Finding> freeText(
            CsvRecord record, int index, String field) {
        String value = record.field(index);
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
        List<CsvRecord> records = records(content);
        if (records.isEmpty()) {
            return rejected(
                    RejectionCode.MALFORMED,
                    FormatDefect.wholeFile(RejectionCode.MALFORMED, F_RECORD_TYPE));
        }

        CsvRecord header = records.get(0);
        int headerLine = header.physicalLine();
        if (!header.is(RECORD_HEADER, HEADER_FIELDS)) {
            return rejected(RejectionCode.MALFORMED, malformed(headerLine, F_RECORD_TYPE));
        }
        // The format and its version first: another format's file, or a later version's, is
        // UNSUPPORTED - never a list of this version's complaints about it.
        if (!id().name().equals(header.field(H_FORMAT))) {
            return unsupportedAt(headerLine, HEADER_FIELDS.get(H_FORMAT));
        }
        if (!String.valueOf(version()).equals(header.field(H_VERSION))) {
            return unsupportedAt(headerLine, HEADER_FIELDS.get(H_VERSION));
        }

        List<FormatDefect> defects = new ArrayList<>();
        String batchRef = header.field(H_BATCH_REF);
        if (!BATCH_REFERENCE.matcher(batchRef).matches()) {
            defect(defects, malformed(headerLine, HEADER_FIELDS.get(H_BATCH_REF)));
        }
        // The currency next: without it no amount has a scale, and a currency the platform
        // cannot represent rejects the file at once, as the PSP report's header does.
        CurrencyCode currency = null;
        int scale = 0;
        String currencyText = header.field(H_CURRENCY);
        if (!CURRENCY_SHAPE.matcher(currencyText).matches()) {
            defect(defects, malformed(headerLine, HEADER_FIELDS.get(H_CURRENCY)));
        } else {
            try {
                currency = CurrencyCode.of(currencyText);
                scale = currency.minorUnits();
            } catch (RuntimeException unknownToThePlatform) {
                return rejected(
                        RejectionCode.UNKNOWN_CURRENCY,
                        FormatDefect.at(
                                RejectionCode.UNKNOWN_CURRENCY,
                                headerLine,
                                HEADER_FIELDS.get(H_CURRENCY)));
            }
        }
        LocalDate businessDate =
                date(header.field(H_VALUE_DATE), headerLine,
                                HEADER_FIELDS.get(H_VALUE_DATE), defects)
                        .orElse(null);

        CsvRecord trailer = records.get(records.size() - 1);
        boolean trailerPresent = records.size() >= 2 && trailer.is(RECORD_TRAILER, TRAILER_FIELDS);
        if (!trailerPresent) {
            defect(defects, malformed(trailer.physicalLine(), F_RECORD_TYPE));
        }

        List<Detail> details = new ArrayList<>();
        int detailRecords = 0;
        for (int i = 1; i < records.size() - (trailerPresent ? 1 : 0); i++) {
            CsvRecord record = records.get(i);
            if (!record.is(RECORD_DETAIL, DETAIL_FIELDS)) {
                defect(defects, malformed(record.physicalLine(), F_RECORD_TYPE));
                continue;
            }
            detailRecords++;
            if (currency == null) {
                continue; // The currency's own defect already rejects the file.
            }
            // The record's place in the body is its expected sequence number: a record of the
            // wrong shape keeps its seat, so one fault is one defect, never a cascade.
            detail(record, i, currency, scale, defects).ifPresent(details::add);
        }

        if (!defects.isEmpty()) {
            return verdictOf(defects);
        }
        if (currency == null
                || businessDate == null
                || !trailerPresent
                || details.size() != detailRecords) {
            // Every failure above records a defect; reaching here is OUR defect, and a
            // RuntimeException leaves the file RECEIVED rather than half-accepted.
            throw new IllegalStateException(
                    "a report read without defects left a component unread");
        }

        int trailerLine = trailer.physicalLine();
        long declaredCount =
                count(trailer.field(T_COUNT), trailerLine, TRAILER_FIELDS.get(T_COUNT), defects);
        Money declaredNet =
                amount(trailer.field(T_NET), currency, scale, SIGNED_AMOUNT, trailerLine,
                        TRAILER_FIELDS.get(T_NET), defects);
        String remittanceRef = trailer.field(T_REMITTANCE_REF);
        if (!REMITTANCE_REFERENCE_SHAPE.matcher(remittanceRef).matches()) {
            defect(defects, malformed(trailerLine, TRAILER_FIELDS.get(T_REMITTANCE_REF)));
        }
        if (!defects.isEmpty() || declaredNet == null) {
            return verdictOf(defects);
        }

        if (declaredCount != detailRecords) {
            return rejected(
                    RejectionCode.CONTROL_TOTAL_MISMATCH,
                    FormatDefect.at(
                            RejectionCode.CONTROL_TOTAL_MISMATCH,
                            trailerLine,
                            TRAILER_FIELDS.get(T_COUNT)));
        }
        List<ParsedLine> lines = lines(details, businessDate);
        // The report's own arithmetic: Σ(signed amounts) - Σ(fees) = net.
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
                            trailerLine,
                            TRAILER_FIELDS.get(T_NET)));
        }
        return new Result.Parsed(
                new ParsedBatch(
                        batchRef, businessDate, remittanceRef, detailRecords, declaredNet, lines));
    }

    private static Result.Rejected unsupportedAt(int line, String field) {
        return rejected(
                RejectionCode.UNSUPPORTED_FORMAT,
                FormatDefect.at(RejectionCode.UNSUPPORTED_FORMAT, line, field));
    }

    // ------------------------------------------------------------------- the detail record

    /** One detail record's validated facts, before canonicalisation. */
    private record Detail(
            SettlementLineType type,
            LineDirection direction,
            Money amount,
            Optional<Money> fee,
            SortedMap<LineReferenceKind, String> references,
            String coverReference,
            byte[] rawSha256) {

        /** Identifiers only — an amount in a log line is `INV-AUD-02`'s to refuse. */
        @Override
        public String toString() {
            return "Detail[" + type + ", " + direction + "]";
        }
    }

    /** One detail record as its validated facts, or empty with its defects recorded. */
    private static Optional<Detail> detail(
            CsvRecord record,
            int position,
            CurrencyCode currency,
            int scale,
            List<FormatDefect> defects) {
        // This record's own defects, gathered apart: whether it is read must never depend on
        // whether the file's bounded list still had room to record them.
        List<FormatDefect> found = new ArrayList<>();
        int line = record.physicalLine();

        // 1..n, strictly in order: a gap, a repeat or a swap is the file's defect.
        long seq = count(record.field(D_SEQ), line, DETAIL_FIELDS.get(D_SEQ), found);
        if (seq >= 0 && seq != position) {
            found.add(malformed(line, DETAIL_FIELDS.get(D_SEQ)));
        }

        // The ADR-0003 triple: a line's amount is positive, its sign the direction's - so a
        // zero amount is no line, and its sign is judged against no code.
        Money signed =
                amount(record.field(D_AMOUNT), currency, scale, SIGNED_AMOUNT, line,
                        DETAIL_FIELDS.get(D_AMOUNT), found);
        boolean nonZero = signed != null && !signed.isZero();
        if (signed != null && signed.isZero()) {
            found.add(malformed(line, DETAIL_FIELDS.get(D_AMOUNT)));
        }
        boolean inbound = nonZero && signed.isPositive();

        Money fee = null;
        if (!record.field(D_FEE).isEmpty()) {
            fee = amount(record.field(D_FEE), currency, scale, FEE_AMOUNT, line,
                    DETAIL_FIELDS.get(D_FEE), found);
        }

        // The provider's vocabulary, mapped here and nowhere else (INV-PAY-03). An unknown
        // well-formed code is kept as OTHER_IN/OTHER_OUT by its sign - never dropped, never a
        // success (INV-REC-02).
        SettlementLineType type = null;
        String code = record.field(D_CODE);
        if (!CODE_SHAPE.matcher(code).matches()) {
            found.add(malformed(line, DETAIL_FIELDS.get(D_CODE)));
        } else {
            switch (code) {
                case P_SOLD -> {
                    type = SettlementLineType.FX_SOLD;
                    if (nonZero && inbound) {
                        found.add(malformed(line, DETAIL_FIELDS.get(D_AMOUNT)));
                    }
                }
                case P_BOUGHT -> {
                    type = SettlementLineType.FX_BOUGHT;
                    if (nonZero && !inbound) {
                        found.add(malformed(line, DETAIL_FIELDS.get(D_AMOUNT)));
                    }
                }
                default ->
                        type = inbound ? SettlementLineType.OTHER_IN : SettlementLineType.OTHER_OUT;
            }
        }

        TreeMap<LineReferenceKind, String> references = new TreeMap<>();
        String tradeReference = record.field(D_TRADE_REF);
        if (!TRADE_REFERENCE.matcher(tradeReference).matches()) {
            // Required: an empty one fails the class like any other.
            found.add(malformed(line, DETAIL_FIELDS.get(D_TRADE_REF)));
        } else {
            references.put(LineReferenceKind.FX_TRADE_REF, tradeReference);
        }
        String coverReference = record.field(D_COVER_REF);
        if (!COVER_REFERENCE.matcher(coverReference).matches()) {
            // Required: the cover reference is the key every leg is matched by.
            found.add(malformed(line, DETAIL_FIELDS.get(D_COVER_REF)));
        } else {
            references.put(LineReferenceKind.COVER_REF, coverReference);
        }

        for (FormatDefect each : found) {
            defect(defects, each);
        }
        if (!found.isEmpty() || signed == null || type == null) {
            return Optional.empty();
        }
        return Optional.of(
                new Detail(
                        type,
                        inbound ? LineDirection.INBOUND : LineDirection.OUTBOUND,
                        inbound ? signed : signed.negated(),
                        fee == null || fee.isZero() ? Optional.empty() : Optional.of(fee),
                        references,
                        coverReference,
                        ParsedLine.sha256(record.raw().getBytes(StandardCharsets.UTF_8))));
    }

    /** The canonical lines: each record, then its fee's line when it carries one. */
    private static List<ParsedLine> lines(List<Detail> details, LocalDate businessDate) {
        // The report's lines settle, and are valued, on its header's value date.
        Optional<LocalDate> settlementDate = Optional.of(businessDate);
        List<ParsedLine> lines = new ArrayList<>();
        for (Detail detail : details) {
            lines.add(
                    new ParsedLine(
                            lines.size() + 1,
                            detail.type(),
                            detail.direction(),
                            detail.amount(),
                            businessDate,
                            settlementDate,
                            settlementDate,
                            detail.references(),
                            detail.rawSha256()));
            if (detail.fee().isPresent()) {
                // The fee names the leg it rode in on - the cover reference, never whichever
                // kind happens to sort first (PHASE_9_PLAN.md section 12.9.1: ORIGINAL_REF = Tn).
                TreeMap<LineReferenceKind, String> original = new TreeMap<>();
                original.put(LineReferenceKind.ORIGINAL_REF, detail.coverReference());
                lines.add(
                        new ParsedLine(
                                lines.size() + 1,
                                SettlementLineType.FX_FEE,
                                LineDirection.OUTBOUND,
                                detail.fee().get(),
                                businessDate,
                                settlementDate,
                                settlementDate,
                                original,
                                detail.rawSha256()));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------- field primitives

    /** A sequence number or a count: at most nine digits, or -1 with its defect recorded. */
    private static long count(String text, int line, String field, List<FormatDefect> defects) {
        if (!COUNT_SHAPE.matcher(text).matches()) {
            defect(defects, malformed(line, field));
            return -1;
        }
        return Long.parseLong(text);
    }

    /** An exact decimal at the currency's scale — more decimals is `SCALE_MISMATCH`. */
    private static Money amount(
            String text,
            CurrencyCode currency,
            int scale,
            Pattern shape,
            int line,
            String field,
            List<FormatDefect> defects) {
        if (!shape.matcher(text).matches()) {
            defect(defects, malformed(line, field));
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
            defect(defects, malformed(line, field));
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException impossibleDay) {
            defect(defects, malformed(line, field));
            return Optional.empty();
        }
    }

    private static FormatDefect malformed(int line, String field) {
        return FormatDefect.at(RejectionCode.MALFORMED, line, field);
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

    // ------------------------------------------------------------------------ tokenising

    /** One physical record: its raw text, its fields, and the 1-based line it sits on. */
    private record CsvRecord(String raw, List<String> fields, int physicalLine) {

        String field(int index) {
            return fields.get(index);
        }

        /** Whether this record carries the tag and exactly the field count of a known shape. */
        boolean is(String tag, List<String> shape) {
            return tag.equals(fields.get(0)) && fields.size() == shape.size();
        }

        /** Identifiers only: a record's text is the provider's, logged by position alone. */
        @Override
        public String toString() {
            return "CsvRecord[" + physicalLine + "]";
        }
    }

    private static List<CsvRecord> records(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        List<CsvRecord> records = new ArrayList<>();
        int line = 0;
        for (String raw : text.split("\r?\n", -1)) {
            line++;
            if (raw.isEmpty()) {
                continue; // The trailing newline's empty tail; a blank middle line is one too.
            }
            records.add(new CsvRecord(raw, List.of(raw.split(",", -1)), line));
        }
        return records;
    }
}
