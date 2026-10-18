package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The BNPL offer document - the text a customer is shown, rendered from the offer's illustration by its product's
 * agreement template (`P12-TSK-011`; {@code PHASE_12_PLAN.md} section 12.1; lending's {@code OfferDocument}).
 *
 * <p><strong>Deterministic, and therefore hashable.</strong> The same offer renders the same bytes on every instance,
 * years later: plain ASCII, fixed lines, amounts as exact decimals at the currency's scale, no locale, no clock, no time
 * zone. The offer stores the SHA-256 of the bytes at birth ({@code document_sha256}); the read re-renders and refuses to
 * show a document whose bytes are not the ones hashed ({@link BnplOffers}), and the acceptance records the same hash as
 * its evidence (`P12-TSK-013`). The document names the canonical terms' SHA-256, so the two hashes bind each other.
 *
 * <p><strong>A template's wording is a version.</strong> These lines are {@link BnplAgreementTemplate}'s version 1 of each
 * product; changed wording is a new template member beside the old one, never an edit here.
 */
public final class BnplOfferDocument {

    private BnplOfferDocument() {}

    /** The document of offer {@code offer} on {@code plan}, its illustration and its terms' hash. */
    public static String render(BnplOfferId offer, BnplTerms terms, BnplPlan plan, BnplIllustration illustration,
            String termsSha256Hex) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(illustration, "illustration");
        Objects.requireNonNull(termsSha256Hex, "termsSha256Hex");
        BnplAgreementTemplate template = terms.agreementTemplate();
        if (template.product() != plan.product() || terms.product() != plan.product()) {
            throw new IllegalArgumentException("a BNPL offer document is rendered by its own product's template");
        }
        StringBuilder out = new StringBuilder(1024);
        line(out, "BNPL OFFER " + offer.value());
        line(out, "Template: " + template.id() + " version " + template.version());
        line(out, "Product: " + plan.product().name());
        line(out, "Plan: " + plan.name() + " - " + illustration.instalmentCount() + " instalments");
        line(out, "Purchase amount: " + money(illustration.amount()));
        line(out, "Nominal annual interest rate (fixed): " + terms.nominalAnnualRate().toPlainString());
        line(out, "ILLUSTRATIVE SCHEDULE - as if purchased on " + illustration.start()
                + " (the offering day); the real schedule starts on the purchase's own day.");
        for (BnplIllustration.Line instalment : illustration.lines()) {
            line(out, "Instalment " + instalment.sequence() + " due " + instalment.dueDate() + ": "
                    + money(instalment.amount()) + " (interest " + money(instalment.interest()) + ")");
        }
        line(out, "Total interest: " + money(illustration.totalInterest()));
        line(out, "Total payable: " + money(illustration.totalPayable()));
        if (terms.lateFee().isPositive()) {
            line(out, "Late fee: " + money(terms.lateFee()) + " once per instalment " + terms.lateFeeDay()
                    + " days past due, at most " + money(terms.lateFeeCap()) + " per agreement");
        } else {
            line(out, "Late fee: none");
        }
        line(out, "Instalments are collected from your wallet on their due dates.");
        line(out, "Engine: " + illustration.engine());
        line(out, "Terms SHA-256: " + termsSha256Hex);
        return out.toString();
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }

    private static String money(Money amount) {
        return amount.toBigDecimal().toPlainString() + " " + amount.currency().code();
    }
}
