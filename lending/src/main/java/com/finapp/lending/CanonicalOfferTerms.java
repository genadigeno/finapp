package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The canonical form of an offer's terms, format 2 (`P11-TSK-011`, `P11-TSK-031`; {@code PHASE_11_PLAN.md} section 12.1,
 * {@code INV-LND-05}, {@code INV-HIST-04}): the same offer always yields the same bytes, and so the same SHA-256 - the
 * {@code terms_sha256} the customer's acceptance echoes and the agreement copies (`P11-TSK-012`) and replay re-verifies
 * (`-029`).
 *
 * <p><strong>Self-contained.</strong> Everything the borrower agrees to is in it: the offer's identity and its decision,
 * the product, exactly the approved amount and term (A20), the repayment or statement day, the pinned terms version by
 * id and number with its WHOLE term set - rate, fees, late fee, grace, delinquency bounds, default threshold, allocation,
 * overpayment, collection, rounding, day count, zone and windows - the four engine versions, the agreement template, and
 * the loan's illustrative schedule figures with their first day (A5) or the line's minimum-payment rule. A later terms
 * version, a changed engine default or a re-worded template changes no stored hash.
 *
 * <p><strong>Hand-written, as credit's {@code CanonicalSnapshot} is, and for the same reason</strong>: a JSON library's
 * output depends on its configuration and version; this depends on the content alone. Keys in a fixed order, no
 * whitespace, every value a string token (amounts as minor units - the currency and scale stated once - rates as their
 * canonical decimal, dates ISO, absent values the token {@code NONE}), each token checked against a character set that
 * needs no escaping - so the form never holds an escape, and {@code lending V005}'s {@code CHECK} can hold the stored
 * hash to the stored bytes for every writer. A change to this form is a new format, with every earlier one kept for
 * replay.
 *
 * <p><strong>The formats.</strong> Format 1 (`P11-TSK-011`) admitted {@code [A-Za-z0-9_.:-]} in a token - and so no
 * servicing zone east of UTC: {@code +05:30} was refused at the offer although the terms door admitted it. Format 2
 * (`P11-TSK-031`, {@code lending V011}) is format 1 with {@code +} added to the token set and nothing else changed: the
 * same content renders the same bytes under both but for the leading format token, wherever format 1 renders at all.
 * Every format-1 row ever stored stays format 1 - its bytes and hash immutable - and {@link #render(Content, int)}
 * re-renders it byte for byte for replay (`-029`). The format is the bytes' own first token; {@link #formatOf} reads it
 * back, so a store writes the format its bytes declare and never a constant (an agreement copies its offer's).
 */
public final class CanonicalOfferTerms {

    /** The format this class writes - stored beside the terms. */
    public static final int FORMAT = 2;

    /** Format 1's token set: every format-1 row ever stored is in it ({@code lending V005}, {@code V006}). */
    private static final Pattern FORMAT_1_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    /** Format 2's: format 1's and {@code +}, so a servicing zone east of UTC is spelled ({@code lending V011}). */
    private static final Pattern FORMAT_2_VALUE = Pattern.compile("[A-Za-z0-9_.:+-]{1,64}");

    private static final Pattern FORMAT_TOKEN = Pattern.compile("\\{\"format\":\"([0-9]{1,4})\",.*", Pattern.DOTALL);

    private static final String NONE = "NONE";

    private CanonicalOfferTerms() {}

    /**
     * What the canonical terms are rendered from: the offer's own identity, the pinned version and its whole term set,
     * and the offer's figures.
     */
    public record Content(
            LoanOfferId offer,
            LoanApplicationId application,
            UUID decision,
            LoanTermsVersionId termsVersionId,
            int termsVersion,
            LoanTerms terms,
            Money amount,
            Optional<Integer> termMonths,
            int repaymentDay,
            Optional<LoanOffer.Illustration> illustration) {

        public Content {
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(application, "application must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(termMonths, "termMonths must not be null");
            Objects.requireNonNull(illustration, "illustration must not be null");
            if (!amount.currency().equals(terms.currency())) {
                throw new IllegalArgumentException("an offer's amount is in its terms' currency");
            }
        }

        @Override
        public String toString() {
            return "Content[" + offer.value() + ", redacted]";
        }
    }

    /** The canonical form of {@code content}, in the format this class writes ({@link #FORMAT}). */
    public static String render(Content content) {
        return render(content, FORMAT);
    }

    /**
     * The canonical form of {@code content} in {@code format} - 1 or 2: an earlier format for replay, byte for byte what
     * it wrote. Content a format cannot spell (a {@code +} under format 1) is refused, never re-spelled.
     */
    public static String render(Content content, int format) {
        Objects.requireNonNull(content, "content");
        LoanTerms terms = content.terms();
        Canonical out = new Canonical(switch (format) {
            case 1 -> FORMAT_1_VALUE;
            case 2 -> FORMAT_2_VALUE;
            default -> throw new IllegalArgumentException("the canonical offer terms are format 1 or 2");
        });
        out.append('{');
        field(out, "format", Integer.toString(format)).append(',');
        out.append("\"offer\":{");
        field(out, "offer", content.offer().value().toString()).append(',');
        field(out, "application", content.application().value().toString()).append(',');
        field(out, "decision", content.decision().toString()).append(',');
        field(out, "product", terms.product().name()).append(',');
        field(out, "kind", terms.product().kind().name()).append(',');
        field(out, "currency", terms.currency().code()).append(',');
        field(out, "scale", Integer.toString(terms.currency().minorUnits())).append(',');
        field(out, "amount", minor(content.amount())).append(',');
        field(out, "termMonths", content.termMonths().map(String::valueOf).orElse(NONE)).append(',');
        field(out, "repaymentDay", Integer.toString(content.repaymentDay()));
        out.append("},\"version\":{");
        field(out, "id", content.termsVersionId().value().toString()).append(',');
        field(out, "number", Integer.toString(content.termsVersion()));
        out.append("},\"terms\":{");
        field(out, "nominalAnnualRate", terms.nominalAnnualRate().toPlainString()).append(',');
        field(out, "rateType", terms.rateType().name()).append(',');
        field(out, "originationFeeBasis", terms.originationFee().basis().name()).append(',');
        field(out, "originationFee", terms.originationFee().amount().map(CanonicalOfferTerms::minor)
                .orElseGet(() -> terms.originationFee().basisPoints().orElseThrow().toString())).append(',');
        field(out, "originationFeeTreatment", terms.originationFeeTreatment().name()).append(',');
        field(out, "lateFee", minor(terms.lateFee())).append(',');
        field(out, "lateFeeDay", Integer.toString(terms.lateFeeDay())).append(',');
        field(out, "lateFeeCap", minor(terms.lateFeeCap())).append(',');
        field(out, "graceDays", Integer.toString(terms.graceDays())).append(',');
        list(out, "delinquencyBounds", terms.delinquencyBounds().stream().map(String::valueOf).toList()).append(',');
        field(out, "defaultThresholdDays", Integer.toString(terms.defaultThresholdDays())).append(',');
        field(out, "allocationSequence", terms.allocationSequence().name()).append(',');
        list(out, "allocationOrder", terms.allocationOrder().stream().map(Enum::name).toList()).append(',');
        field(out, "overpaymentTreatment", terms.overpaymentTreatment().name()).append(',');
        field(out, "autoCollection", Boolean.toString(terms.autoCollection())).append(',');
        field(out, "partialCollection", Boolean.toString(terms.partialCollection())).append(',');
        field(out, "instalmentRounding", terms.instalmentRounding().name()).append(',');
        field(out, "interestRounding", terms.interestRounding().name()).append(',');
        field(out, "dayCount", terms.dayCount().name()).append(',');
        field(out, "servicingZone", terms.servicingZone()).append(',');
        field(out, "offerValidityDays", Integer.toString(terms.offerValidityDays())).append(',');
        field(out, "payoffHorizonDays", Integer.toString(terms.payoffHorizonDays())).append(',');
        field(out, "minFirstPeriodDays", terms.instalment()
                .map(own -> Integer.toString(own.minFirstPeriodDays())).orElse(NONE)).append(',');
        field(out, "disbursementDeadlineHours", terms.instalment()
                .map(own -> Integer.toString(own.disbursementDeadlineHours())).orElse(NONE)).append(',');
        field(out, "minimumPaymentFloor", terms.revolving()
                .map(own -> minor(own.minimumPaymentFloor())).orElse(NONE)).append(',');
        field(out, "minimumPaymentRatioBps", terms.revolving()
                .map(own -> Integer.toString(own.minimumPaymentRatioBps())).orElse(NONE)).append(',');
        field(out, "paymentDueDays", terms.revolving()
                .map(own -> Integer.toString(own.paymentDueDays())).orElse(NONE));
        out.append("},\"engines\":{");
        field(out, "schedule", terms.instalment().map(own -> own.scheduleEngine().name()).orElse(NONE)).append(',');
        field(out, "accrual", terms.accrualEngine().name()).append(',');
        field(out, "allocation", terms.allocationEngine().name()).append(',');
        field(out, "statement", terms.revolving().map(own -> own.statementEngine().name()).orElse(NONE));
        out.append("},\"template\":{");
        field(out, "id", terms.agreementTemplate().id()).append(',');
        field(out, "version", Integer.toString(terms.agreementTemplate().version()));
        out.append("},\"illustration\":{");
        Optional<LoanOffer.Illustration> illustration = content.illustration();
        field(out, "basis", illustration.isPresent() ? "ILLUSTRATIVE_FROM_OFFERING_DAY" : NONE).append(',');
        field(out, "start", illustration.map(own -> own.start().toString()).orElse(NONE)).append(',');
        field(out, "firstDueDate", illustration.map(own -> own.firstDueDate().toString()).orElse(NONE)).append(',');
        field(out, "instalment", illustration.map(own -> minor(own.instalment())).orElse(NONE)).append(',');
        field(out, "finalInstalment", illustration.map(own -> minor(own.finalInstalment())).orElse(NONE)).append(',');
        field(out, "totalInterest", illustration.map(own -> minor(own.totalInterest())).orElse(NONE)).append(',');
        field(out, "totalPayable", illustration.map(own -> minor(own.totalPayable())).orElse(NONE));
        out.append("}}");
        return out.toString();
    }

    /**
     * The format {@code canonical} declares - its own first token. A store writes this beside the bytes, so the column
     * and the bytes cannot disagree ({@code lending V011}'s {@code CHECK} holds them together for every writer).
     */
    public static int formatOf(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        Matcher token = FORMAT_TOKEN.matcher(canonical);
        if (!token.matches()) {
            throw new IllegalArgumentException("canonical offer terms open with their format token");
        }
        int format = Integer.parseInt(token.group(1));
        if (format != 1 && format != 2) {
            throw new IllegalArgumentException("the canonical offer terms are format 1 or 2");
        }
        return format;
    }

    /** The SHA-256 of {@code text}'s UTF-8 bytes - the canonical terms', or the offer document's. */
    public static byte[] sha256(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java platform provides SHA-256", absent);
        }
    }

    // ------------------------------------------------------------------ the tokens

    /** The text being rendered and the token set of the format it is rendered in. */
    private static final class Canonical {

        private final StringBuilder text = new StringBuilder(2048);
        private final Pattern tokens;

        Canonical(Pattern tokens) {
            this.tokens = tokens;
        }

        Canonical append(char c) {
            text.append(c);
            return this;
        }

        Canonical append(String s) {
            text.append(s);
            return this;
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static Canonical field(Canonical out, String name, String value) {
        return out.append('"').append(name).append("\":").append(token(out.tokens, value));
    }

    private static Canonical list(Canonical out, String name, List<String> values) {
        out.append('"').append(name).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ",").append(token(out.tokens, values.get(i)));
        }
        return out.append(']');
    }

    private static String token(Pattern tokens, String value) {
        if (!tokens.matcher(value).matches()) {
            // Never the value: it may be a figure.
            throw new IllegalArgumentException("a canonical offer token is outside the escape-free character set");
        }
        return '"' + value + '"';
    }

    private static String minor(Money amount) {
        return Long.toString(amount.minorUnits());
    }
}
