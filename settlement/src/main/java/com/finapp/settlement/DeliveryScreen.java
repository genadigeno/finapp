package com.finapp.settlement;

import java.util.Objects;
import java.util.Optional;

/**
 * The door's in-memory screen (`P8-TSK-002`, ADR-0066 §3) — pure: no I/O, no clock, no
 * database, so a verdict is a function of the bytes alone.
 *
 * <p>This is the seam `P8-TSK-008`'s {@code SettlementFormat} SPI fills per format version —
 * bytes that parse structurally are screened field by field against each field's declared
 * class, and only declared free-text fields are tested for instrument shapes. Until a source's
 * format exists, {@link ConservativeScreen} screens all its bytes as one stream, which
 * over-refuses rather than under-refuses by design: a false positive is recovered by
 * re-presentation under a later format version; a stored PAN is PCI scope forever.
 */
public interface DeliveryScreen {

    Screening screen(byte[] content);

    /**
     * The screen's verdict: the record count its own walk saw (the 50,000-line bound's source
     * of truth), and the first finding, if any — one is enough, because the whole delivery is
     * refused and the counterparty corrects at the source.
     */
    record Screening(int lineCount, Optional<Finding> finding) {
        public Screening {
            Objects.requireNonNull(finding, "finding must not be null");
            if (lineCount < 0) {
                throw new IllegalArgumentException("a line count is never negative");
            }
        }
    }

    /**
     * What the screen found and where — the reason, the 1-based line and, for a field-class
     * screen, the field. Never the value: this record's contents reach the refusal row, the
     * audit record and the metric tag.
     */
    record Finding(RefusalReason reason, int lineNo, Optional<String> fieldName) {
        public Finding {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(fieldName, "fieldName must not be null");
            if (!reason.leavesARow()) {
                throw new IllegalArgumentException(
                        "a screen finding is a content refusal; the bounds are the door's");
            }
        }
    }
}
