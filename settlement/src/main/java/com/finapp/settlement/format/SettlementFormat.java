package com.finapp.settlement.format;

import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.SettlementFormatId;
import java.util.List;
import java.util.Objects;

/**
 * One frozen version of one counterparty's file format (`P8-TSK-008`, ADR-0066 §§3, 8) — the
 * SPI the door's screen seam and the parse leg are written against.
 *
 * <p><strong>Pure by contract</strong>: no I/O, no clock, no database. A screen verdict and a
 * parse are functions of the bytes alone, which is what makes a re-claim after a crash yield
 * byte-identical lines and fingerprints (the parse leg's idempotency), and what lets the
 * golden-file test freeze a version for good — any change to a screen or a parser is a NEW
 * version, never an edit (the {@code RailMoneySemanticsArePinnedTest} rule).
 *
 * <p><strong>The provider's vocabulary stays inside the adapter</strong>
 * ({@code INV-PAY-03}): implementations live in {@code com.finapp.settlement.format.<format>}
 * and are the only code that may know what the counterparty calls things —
 * {@code SettlementVocabularyIsConfinedTest} holds the line.
 */
public interface SettlementFormat {

    SettlementFormatId id();

    /** The frozen version (≥ 1) this instance screens and parses. */
    int version();

    /**
     * The field-class screen (ADR-0066 §3), run at the door before anything is stored: bytes
     * that parse structurally are checked field by field against each field's declared class —
     * a reference by its shape, an amount or date by its type, only declared free-text fields
     * for instrument shapes, and <strong>a field that fails its declared class is screened as
     * free text</strong> before the file can be stored as malformed (C6). Bytes that do not
     * parse structurally are screened as one conservative stream.
     */
    DeliveryScreen.Screening screen(byte[] content);

    /**
     * The whole file as one canonical batch, or the whole file rejected — never lines from
     * half of it ({@code INV-SET-07}). A {@link RuntimeException} out of here is OUR defect
     * and leaves the file {@code RECEIVED}; a {@link Result.Rejected} is the evidence's.
     */
    Result parse(byte[] content);

    /** The parse's whole answer. */
    sealed interface Result {

        /** Every record read, canonicalised and control-checked. */
        record Parsed(ParsedBatch batch) implements Result {
            public Parsed {
                Objects.requireNonNull(batch, "batch must not be null");
            }
        }

        /** The whole file refused, with what substantiates the verdict — never a value. */
        record Rejected(RejectionCode code, List<FormatDefect> defects) implements Result {
            public Rejected {
                Objects.requireNonNull(code, "code must not be null");
                Objects.requireNonNull(defects, "defects must not be null");
                if (!code.leavesErrorRows() && !defects.isEmpty()) {
                    throw new IllegalArgumentException(
                            code + " is a whole-file verdict and substantiates no error rows");
                }
                defects = List.copyOf(defects);
            }
        }
    }
}
