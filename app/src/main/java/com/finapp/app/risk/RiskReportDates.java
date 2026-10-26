package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.risk.RiskDay;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The {@code date=} of the risk proofs' doors (`P13-TSK-023`; bnpl's {@code BnplReports#date}): a UTC calendar date,
 * {@code YYYY-MM-DD} - anything else, or none, {@code 422 api.ValidationFailed} before any read, nothing recorded.
 */
final class RiskReportDates {

    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private RiskReportDates() {}

    /** {@code raw} as a risk day, or the refusal of {@code act}. */
    static RiskDay day(String raw, String act) {
        if (raw != null && DATE.matcher(raw).matches()) {
            try {
                return RiskDay.of(LocalDate.parse(raw));
            } catch (DateTimeParseException unparseable) {
                // Falls through: 2026-02-30 has the shape and no meaning.
            }
        }
        throw new ApiException(PlatformErrorCode.VALIDATION_FAILED, act + " was asked for a date that is not a calendar date",
                "date must be YYYY-MM-DD, a UTC date.");
    }
}
