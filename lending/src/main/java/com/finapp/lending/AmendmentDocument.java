package com.finapp.lending;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The amendment document - the text a borrower is shown before accepting a restructuring (`P11-TSK-027`; ADR-0099 point 5,
 * A13 - the acceptance evidence as {@link OfferDocument}'s).
 *
 * <p><strong>Deterministic, and therefore hashable.</strong> Rendered from the proposal's own immutable facts - the loan,
 * the amendment, the agreement version and rate it amends, the amended rate, the remaining instalments, the arrears flag
 * and the pinned offer validity - in plain ASCII, fixed lines, no locale, no clock. The proposal stores the SHA-256 of the
 * bytes at birth ({@code loan_amendment.document_sha256}); the customer's read re-renders and refuses to show a document
 * whose bytes are not the ones hashed, the acceptance echoes the hash and its evidence records it. The schedule is NOT in
 * the document: it is fixed on the day of acceptance from the principal then outstanding, and the agreement version it
 * makes names it (canonical terms format 3).
 */
public final class AmendmentDocument {

    private AmendmentDocument() {}

    /** The document of amendment {@code amendment} of {@code loan}, amending version {@code amendsVersion}. */
    public static String render(UUID amendment, LoanId loan, int amendsVersion, BigDecimal currentRate,
            BigDecimal amendedRate, int remainingInstalments, boolean rescheduleArrears, int offerValidityDays) {
        Objects.requireNonNull(amendment, "amendment");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(currentRate, "currentRate");
        Objects.requireNonNull(amendedRate, "amendedRate");
        StringBuilder out = new StringBuilder(1024);
        line(out, "LOAN AMENDMENT " + amendment);
        line(out, "Loan: " + loan.value());
        line(out, "Amends agreement version: " + amendsVersion);
        line(out, "Nominal annual interest rate (fixed, simple, ACT/365): " + currentRate.toPlainString() + " -> "
                + amendedRate.toPlainString() + ", from the day you accept");
        line(out, "Remaining instalments from the day you accept: " + remainingInstalments + " monthly, on your repayment"
                + " day");
        line(out, rescheduleArrears
                ? "Overdue principal: re-scheduled into the new instalments"
                : "Overdue principal: stays due now");
        line(out, "Overdue interest and fees: stay due now - never added to the principal");
        line(out, "The principal is never increased. The new schedule is calculated on the day you accept from the"
                + " principal then outstanding; instalments already billed are kept.");
        line(out, "This offer may be accepted within " + offerValidityDays + " days of its approval, while the agreement"
                + " it amends is in force.");
        return out.toString();
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
