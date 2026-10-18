package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The canonical form of a BNPL offer's terms, format 1 (`P12-TSK-011`; {@code PHASE_12_PLAN.md} section 12.1,
 * {@code INV-HIST-04}; lending's {@code CanonicalOfferTerms} - the `P11-TSK-012` lesson: text, never {@code jsonb}): the
 * same offer always yields the same bytes, and so the same SHA-256 - the {@code terms_sha256} {@code bnpl V004}'s
 * {@code CHECK} holds to the stored bytes for every writer, the acceptance echoes and the agreement copies
 * (`P12-TSK-013`), and replay re-verifies (`P12-TSK-025`).
 *
 * <p><strong>Self-contained.</strong> Everything the customer agrees to: the offer's identity, its application and
 * decision, the product and plan, exactly the purchase amount, the pinned terms version by id and number with its whole
 * term set - rate, late fee, grace, delinquency bounds, default threshold, allocation, overpayment, collection, rounding,
 * day count, zone and windows - the engine versions, the agreement template, and the illustrative schedule line by line
 * with its start and totals. A later terms version, a changed engine default or a re-worded template changes no stored
 * hash.
 *
 * <p><strong>Hand-written, for the reason lending's is</strong>: a JSON library's output depends on its configuration and
 * version; this depends on the content alone. Keys in a fixed order, no whitespace, every value a string token (amounts
 * as minor units - the currency and scale stated once - the rate its canonical decimal, dates ISO, an absent value the
 * token {@code NONE}), each checked against a set that needs no escaping ({@code [A-Za-z0-9_.:+-]}), so the form holds no
 * escape and the database's {@code CHECK} can hash it as it stands. A change to this form is a new format beside this
 * one, kept for replay.
 */
public final class BnplOfferTerms {

    /** The format this class writes - stored beside the terms, and the bytes' own first token. */
    public static final int FORMAT = 1;

    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.:+-]{1,64}");

    private static final String NONE = "NONE";

    private BnplOfferTerms() {}

    /** What the canonical terms are rendered from. */
    public record Content(
            BnplOfferId offer,
            BnplApplicationId application,
            UUID decision,
            BnplTermsVersionId termsVersionId,
            int termsVersion,
            BnplTerms terms,
            BnplPlan plan,
            BnplIllustration illustration) {

        public Content {
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(application, "application must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(illustration, "illustration must not be null");
            if (plan.product() != terms.product()) {
                throw new IllegalArgumentException("an offer's plan is its terms' product's");
            }
            if (!illustration.amount().currency().equals(terms.currency())) {
                throw new IllegalArgumentException("an offer's amount is in its terms' currency");
            }
        }

        @Override
        public String toString() {
            return "Content[" + offer.value() + ", redacted]";
        }
    }

    /** The canonical terms of {@code content}, format {@link #FORMAT}. */
    public static String render(Content content) {
        Objects.requireNonNull(content, "content");
        BnplTerms terms = content.terms();
        BnplIllustration illustration = content.illustration();
        StringBuilder out = new StringBuilder(2048).append('{');
        field(out, "format", Integer.toString(FORMAT));
        field(out, "offer", content.offer().value().toString());
        field(out, "application", content.application().value().toString());
        field(out, "decision", content.decision().toString());
        field(out, "termsVersionId", content.termsVersionId().value().toString());
        field(out, "termsVersion", Integer.toString(content.termsVersion()));
        field(out, "product", terms.product().name());
        field(out, "plan", content.plan().name());
        field(out, "currency", terms.currency().code());
        field(out, "scale", Integer.toString(terms.currency().minorUnits()));
        field(out, "amountMinor", minor(illustration.amount()));
        field(out, "frequency", terms.frequency().name());
        field(out, "nominalAnnualRate", terms.nominalAnnualRate().toPlainString());
        field(out, "lateFeeMinor", minor(terms.lateFee()));
        field(out, "lateFeeDay", Integer.toString(terms.lateFeeDay()));
        field(out, "lateFeeCapMinor", minor(terms.lateFeeCap()));
        field(out, "graceDays", Integer.toString(terms.graceDays()));
        list(out, "delinquencyBounds", terms.delinquencyBounds().stream().map(String::valueOf).toList());
        field(out, "defaultThresholdDays", Integer.toString(terms.defaultThresholdDays()));
        field(out, "allocationSequence", terms.allocationSequence().name());
        list(out, "allocationOrder", terms.allocationOrder().stream().map(Enum::name).toList());
        field(out, "overpaymentTreatment", terms.overpaymentTreatment().name());
        field(out, "autoCollection", Boolean.toString(terms.autoCollection()));
        field(out, "partialCollection", Boolean.toString(terms.partialCollection()));
        field(out, "collectionRetryDays", Integer.toString(terms.collectionRetryDays()));
        field(out, "instalmentRounding", terms.instalmentRounding().name());
        field(out, "interestRounding", terms.payInN().map(own -> own.interestRounding().name()).orElse(NONE));
        field(out, "dayCount", terms.payInN().map(own -> own.dayCount().name()).orElse(NONE));
        field(out, "minFirstPeriodDays",
                terms.payInN().map(own -> Integer.toString(own.minFirstPeriodDays())).orElse(NONE));
        field(out, "servicingZone", terms.servicingZone());
        field(out, "offerValidityMinutes", Integer.toString(terms.offerValidityMinutes()));
        field(out, "disputeResponseDays", Integer.toString(terms.disputeResponseDays()));
        field(out, "disputeFourEyesThresholdMinor", minor(terms.disputeFourEyesThreshold()));
        field(out, "payoffHorizonDays", Integer.toString(terms.payoffHorizonDays()));
        field(out, "scheduleEngine", illustration.engine());
        field(out, "accrualEngine", terms.payInN().map(own -> own.accrualEngine().name()).orElse(NONE));
        field(out, "allocationEngine", terms.allocationEngine().name());
        field(out, "refundAllocationEngine", terms.refundAllocationEngine().name());
        field(out, "agreementTemplate", terms.agreementTemplate().id());
        field(out, "agreementTemplateVersion", Integer.toString(terms.agreementTemplate().version()));
        field(out, "illustrativeStart", illustration.start().toString());
        out.append("\"instalments\":[");
        for (int index = 0; index < illustration.lines().size(); index++) {
            BnplIllustration.Line line = illustration.lines().get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append('{');
            field(out, "sequence", Integer.toString(line.sequence()));
            field(out, "dueDate", line.dueDate().toString());
            field(out, "principalMinor", minor(line.principal()));
            field(out, "interestMinor", minor(line.interest()));
            last(out, "amountMinor", minor(line.amount()));
            out.append('}');
        }
        out.append("],");
        field(out, "totalInterestMinor", minor(illustration.totalInterest()));
        last(out, "totalPayableMinor", minor(illustration.totalPayable()));
        return out.append('}').toString();
    }

    /** The SHA-256 of {@code text}'s UTF-8 bytes - what {@code bnpl V004}'s {@code CHECK} computes. */
    public static byte[] sha256(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java platform provides SHA-256", absent);
        }
    }

    private static String minor(Money amount) {
        return Long.toString(amount.minorUnits());
    }

    private static void field(StringBuilder out, String key, String value) {
        last(out, key, value);
        out.append(',');
    }

    private static void last(StringBuilder out, String key, String value) {
        out.append('"').append(key).append("\":\"").append(token(value)).append('"');
    }

    private static void list(StringBuilder out, String key, List<String> values) {
        out.append('"').append(key).append("\":[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append('"').append(token(values.get(index))).append('"');
        }
        out.append("],");
    }

    private static String token(String value) {
        if (value == null || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("a canonical token is 1 to 64 of [A-Za-z0-9_.:+-]");
        }
        return value;
    }
}
