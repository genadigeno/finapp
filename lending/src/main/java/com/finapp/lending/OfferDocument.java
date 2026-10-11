package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The offer document - the text a customer is shown, rendered from the offer's stored terms by its agreement template
 * (`P11-TSK-011`; {@code PHASE_11_PLAN.md} section 12.1, A5, A13).
 *
 * <p><strong>Deterministic, and therefore hashable.</strong> The same offer renders the same bytes on every instance,
 * years later: plain ASCII, fixed lines, amounts as exact decimals at the currency's scale, no locale, no clock, no
 * time zone. The offer stores the SHA-256 of the bytes at birth ({@code document_sha256}); the read re-renders and
 * refuses to show a document whose bytes are not the ones hashed ({@link LoanOffers}), and the acceptance records the
 * same hash as its evidence (`P11-TSK-012`). The document names the canonical terms' SHA-256, so the two hashes bind
 * each other.
 *
 * <p><strong>A template's wording is a version.</strong> The lines below are {@link AgreementTemplate}'s version 1 of
 * each product; changed wording is a new template member beside the old one, never an edit here - an edit would make
 * every stored document hash unrenderable, which {@link LoanOffers}' read would refuse loudly.
 */
public final class OfferDocument {

    private OfferDocument() {}

    /** The document for {@code offer}'s product and terms. */
    public static String render(LoanOfferId offer, LoanProduct product, LoanOffer.Terms terms) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(terms, "terms");
        AgreementTemplate template = terms.template();
        if (template.product() != product) {
            throw new IllegalArgumentException("an offer document is rendered by its own product's template");
        }
        StringBuilder out = new StringBuilder(1024);
        line(out, "LOAN OFFER " + offer.value());
        line(out, "Template: " + template.id() + " version " + template.version());
        line(out, "Product: " + product.name());
        line(out, (product.kind() == LoanKind.INSTALMENT ? "Principal: " : "Credit limit: ") + money(terms.amount()));
        terms.termMonths().ifPresent(months -> line(out, "Term: " + months + " monthly instalments"));
        line(out, "Nominal annual interest rate (fixed, simple, ACT/365): " + terms.nominalAnnualRate().toPlainString());
        line(out, (product.kind() == LoanKind.INSTALMENT ? "Repayment day: " : "Statement day: ") + terms.repaymentDay());
        terms.illustration().ifPresent(illustration -> {
            line(out, "ILLUSTRATIVE SCHEDULE - computed as if interest started on " + illustration.start()
                    + " (the offering day); the real schedule starts when the loan is paid out.");
            line(out, "First instalment due: " + illustration.firstDueDate());
            line(out, "Regular instalment: " + money(illustration.instalment()));
            line(out, "Final instalment: " + money(illustration.finalInstalment()));
            line(out, "Total interest: " + money(illustration.totalInterest()));
            line(out, "Total payable: " + money(illustration.totalPayable()));
        });
        terms.minimumPayment().ifPresent(rule -> {
            line(out, "Minimum payment each statement: interest and fees billed, plus the greater of "
                    + bps(rule.ratioBps()) + " of the drawn principal (rounded up) and what brings the payment to "
                    + money(rule.floor()) + " - never more than the balance.");
            line(out, "Payment due: " + rule.paymentDueDays() + " days after each statement date");
        });
        line(out, "Engines: " + terms.scheduleEngine().map(Enum::name).orElse("-") + " " + terms.accrualEngine().name()
                + " " + terms.allocationEngine().name() + " " + terms.statementEngine().map(Enum::name).orElse("-"));
        line(out, "Terms SHA-256: " + terms.termsSha256Hex());
        return out.toString();
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }

    private static String money(Money amount) {
        return amount.toBigDecimal().toPlainString() + " " + amount.currency().code();
    }

    private static String bps(int basisPoints) {
        return java.math.BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + " %";
    }
}
