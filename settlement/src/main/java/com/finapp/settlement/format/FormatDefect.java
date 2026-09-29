package com.finapp.settlement.format;

import com.finapp.settlement.RejectionCode;
import java.util.Objects;
import java.util.Optional;

/**
 * One thing wrong with a delivered file (`P8-TSK-008`, ADR-0066 §9): a code, the 1-based line
 * and, where one field is at fault, its NAME — never its value. These become
 * {@code ingestion_error} rows, bounded at 100, and the value columns simply do not exist.
 */
public record FormatDefect(RejectionCode code, Optional<Integer> lineNo, Optional<String> field) {

    /** The {@code refused_delivery} bound applied to a field NAME (`V002`'s rule). */
    private static final int MAX_FIELD_NAME = 200;

    public FormatDefect {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(lineNo, "lineNo must not be null");
        Objects.requireNonNull(field, "field must not be null");
        if (!code.leavesErrorRows()) {
            throw new IllegalArgumentException(code + " substantiates no error rows");
        }
        lineNo.ifPresent(
                line -> {
                    if (line < 1) {
                        throw new IllegalArgumentException("a defect's line is 1-based");
                    }
                });
        field.ifPresent(
                name -> {
                    if (name.isBlank() || name.length() > MAX_FIELD_NAME) {
                        throw new IllegalArgumentException(
                                "a defect names a field in at most "
                                        + MAX_FIELD_NAME
                                        + " characters");
                    }
                });
    }

    public static FormatDefect at(RejectionCode code, int lineNo, String field) {
        return new FormatDefect(code, Optional.of(lineNo), Optional.of(field));
    }

    public static FormatDefect atLine(RejectionCode code, int lineNo) {
        return new FormatDefect(code, Optional.of(lineNo), Optional.empty());
    }

    public static FormatDefect wholeFile(RejectionCode code, String field) {
        return new FormatDefect(code, Optional.empty(), Optional.of(field));
    }
}
