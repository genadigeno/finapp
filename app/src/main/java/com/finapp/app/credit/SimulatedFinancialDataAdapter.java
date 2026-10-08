package com.finapp.app.credit;

import com.finapp.app.api.DecimalText;
import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataAnswer.UnavailableCause;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.credit.CreditEvidence;
import com.finapp.credit.CreditSourceKind;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryException;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated financial-data provider {@code findata-sim-a} (`P10-TSK-007`; ADR-0085 section 2, ADR-0008's adapter
 * shape) - the platform's first {@link FinancialDataProvider}: the applicant's verified monthly income and committed
 * expenditure, read from their accounts.
 *
 * <h2>The wire is ours, and this class is the only place it exists</h2>
 *
 * <p>{@code POST /findata/summaries}, the {@code status} words {@code summary_complete} and {@code summary_partial},
 * and the summary's field names - confined here ({@code CreditProviderVocabularyIsConfinedTest}). Amounts travel as
 * JSON strings, read through {@code DecimalText}.
 *
 * <h2>Normalisation is total, as the bureau's</h2>
 *
 * <p>A complete summary with any unreadable field is {@code MALFORMED} - its surviving fields never become data; a
 * partial summary's missing field is {@link AttributeValue.Absent}; money in a currency other than the product's is
 * {@code Absent} with the {@code CURRENCY_NOT_SUPPORTED} marker naming {@code FINANCIAL_DATA}, never converted; any
 * other status is {@code UNKNOWN_STATUS}; a non-200 or a broken transport {@code PROVIDER_ERROR}; the wait expiring
 * {@code TIMEOUT}.
 *
 * <p>Our reference travels as the {@code Idempotency-Key}; the provider dedupes on it. Stateless; wired fail-safe
 * until the account connection it reads exists (unresolved question #14).
 */
public final class SimulatedFinancialDataAdapter implements FinancialDataProvider {

    /** The provider's code: its declaration's, its evidence's and its meter's tag value. */
    public static final String CODE = "findata-sim-a";

    /**
     * The version of this adapter's normalisation - bump it when the mapping changes. Version 2 (`P10-TSK-021`): a
     * field present in any form but a readable string - a bare number, {@code null}, an object - is malformed, never
     * absent; version 1 read such a field of a partial answer as absent.
     */
    public static final int NORMALISER_VERSION = 2;

    /** The summary path. */
    public static final String SUMMARIES_PATH = "/findata/summaries";

    /** The idempotency header our reference travels in. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** The retained body's bound - past it, the answer is malformed and nothing is retained. */
    public static final int MAX_EVIDENCE_BYTES = 64 * 1024;

    private static final String COMPLETE = "summary_complete";
    private static final String PARTIAL = "summary_partial";

    private static final Pattern OBJECT = Pattern.compile("(?s)^\\s*\\{.*\\}\\s*$");
    private static final Pattern STATUS = field("status");
    private static final Pattern RETRIEVED_AT = field("retrievedAt");

    /** Each financial-data attribute's wire field, and its currency field. */
    private enum Field {
        INCOME(CreditAttributeCode.FINDATA_MONTHLY_INCOME, "verifiedMonthlyIncome", "verifiedMonthlyIncomeCurrency"),
        EXPENDITURE(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE, "committedMonthlyExpenditure",
                "committedMonthlyExpenditureCurrency");

        private final CreditAttributeCode code;
        private final Pattern value;
        private final Pattern key;
        private final Pattern currency;

        Field(CreditAttributeCode code, String name, String currencyName) {
            this.code = code;
            this.value = field(name);
            this.key = Pattern.compile("\"" + name + "\"\\s*:");
            this.currency = currencyName == null ? null : field(currencyName);
        }
    }

    private static final AttributeProvenance PROVENANCE =
            new AttributeProvenance.Provider(CreditSourceKind.FINANCIAL_DATA, CODE, NORMALISER_VERSION);

    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final CreditDataSubjectResolver subjects;
    private final HttpClient http;

    public SimulatedFinancialDataAdapter(URI baseUrl, Duration timeout, byte[] key, CreditDataSubjectResolver subjects) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(key, "key");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a financial-data timeout must be positive");
        }
        this.key = key.clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public CreditDataAnswer pull(CreditDataPull request) {
        Objects.requireNonNull(request, "request");
        // Naming nobody is the caller's defect, not a provider fault: refused loudly, never sent.
        CreditDataSubject subject = subjects.resolve(request.subjectReference())
                .orElseThrow(() -> new IllegalStateException("the financial-data pull names no resolvable subject"));
        String body = "{\"subject\":{\"name\":\"" + escape(subject.fullName()) + "\",\"dateOfBirth\":\""
                + subject.dateOfBirth() + "\",\"country\":\"" + subject.residenceCountry().code() + "\"},"
                + "\"currency\":\"" + request.product().currency().code() + "\"}";
        HttpRequest post = HttpRequest.newBuilder(baseUrl.resolve(SUMMARIES_PATH))
                .timeout(timeout)
                .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                .header(IDEMPOTENCY_KEY_HEADER, request.reference())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(post, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException slow) {
            return unavailable(UnavailableCause.TIMEOUT, null);
        } catch (IOException broken) {
            return unavailable(UnavailableCause.PROVIDER_ERROR, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable(UnavailableCause.PROVIDER_ERROR, null);
        }
        byte[] received = response.body();
        boolean retainable = received.length > 0 && received.length <= MAX_EVIDENCE_BYTES;
        if (response.statusCode() != 200) {
            return unavailable(UnavailableCause.PROVIDER_ERROR, retainable ? received : null);
        }
        if (!retainable) {
            return unavailable(UnavailableCause.MALFORMED, null);
        }
        return normalise(received, request.product().currency());
    }

    /** Never the key, never a subject. */
    @Override
    public String toString() {
        return "SimulatedFinancialDataAdapter[" + CODE + "]";
    }

    /**
     * A 200 body, normalised totally onto the port's answer - the golden files' subject.
     *
     * @param received the body, verbatim
     * @param productCurrency the currency every money attribute must be in
     */
    static CreditDataAnswer normalise(byte[] received, CurrencyCode productCurrency) {
        String text = new String(received, StandardCharsets.UTF_8);
        if (!OBJECT.matcher(text).matches()) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        Optional<String> status = find(STATUS, text);
        if (status.isEmpty()) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        boolean complete = status.get().equals(COMPLETE);
        if (!complete && !status.get().equals(PARTIAL)) {
            return unavailable(UnavailableCause.UNKNOWN_STATUS, received);
        }
        Instant retrievedAt;
        try {
            retrievedAt = Instant.parse(find(RETRIEVED_AT, text).orElse(""));
        } catch (DateTimeParseException unreadable) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        List<CreditAttribute> attributes = new ArrayList<>();
        boolean foreignCurrency = false;
        for (Field field : Field.values()) {
            Optional<String> raw = find(field.value, text);
            if (raw.isEmpty()) {
                // Absent means the field is not there at all; there in an unreadable form, it is malformed (version 2).
                if (complete || field.key.matcher(text).find()) {
                    return unavailable(UnavailableCause.MALFORMED, received);
                }
                attributes.add(attribute(field.code, new AttributeValue.Absent()));
                continue;
            }
            Optional<AttributeValue> value = parse(field, raw.get(), text, productCurrency);
            if (value.isEmpty()) {
                // Present but unreadable: the whole answer is malformed - never a partial parse.
                return unavailable(UnavailableCause.MALFORMED, received);
            }
            if (value.get() instanceof AttributeValue.Absent) {
                foreignCurrency = true;
            }
            attributes.add(attribute(field.code, value.get()));
        }
        if (foreignCurrency) {
            attributes.add(attribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED,
                    new AttributeValue.CodeValue(CreditSourceKind.FINANCIAL_DATA.name())));
        }
        CreditEvidence evidence = new CreditEvidence(received);
        boolean anyAbsent = attributes.stream().anyMatch(CreditAttribute::absent);
        return anyAbsent
                ? new CreditDataAnswer.Partial(CODE, NORMALISER_VERSION, retrievedAt, attributes, evidence)
                : new CreditDataAnswer.Received(CODE, NORMALISER_VERSION, retrievedAt, attributes, evidence);
    }

    /** A present field's value, {@code Absent} for money in a foreign currency, or empty if unreadable. */
    private static Optional<AttributeValue> parse(Field field, String raw, String text, CurrencyCode productCurrency) {
        switch (field.code.valueType()) {
            case INTEGER:
                if (!raw.matches("[0-9]{1,9}")) {
                    return Optional.empty();
                }
                return Optional.of(new AttributeValue.IntegerValue(Long.parseLong(raw)));
            case BOOLEAN:
                if (raw.equals("true") || raw.equals("false")) {
                    return Optional.of(new AttributeValue.BooleanValue(Boolean.parseBoolean(raw)));
                }
                return Optional.empty();
            case MONEY:
                Optional<String> currencyText = find(field.currency, text);
                if (currencyText.isEmpty() || !DecimalText.plain(raw)) {
                    return Optional.empty();
                }
                CurrencyCode currency;
                try {
                    currency = CurrencyCode.of(currencyText.get());
                } catch (IllegalArgumentException unknown) {
                    return Optional.empty();
                }
                if (!currency.equals(productCurrency)) {
                    // Never converted: absent, and the marker says why (INV-CRD-12).
                    return Optional.of(new AttributeValue.Absent());
                }
                try {
                    return Optional.of(new AttributeValue.MoneyValue(Money.of(DecimalText.parse(raw), currency)));
                } catch (MonetaryException overPrecise) {
                    return Optional.empty();
                }
            default:
                return Optional.empty();
        }
    }

    private static CreditAttribute attribute(CreditAttributeCode code, AttributeValue value) {
        return new CreditAttribute(code, value, PROVENANCE);
    }

    private static CreditDataAnswer unavailable(UnavailableCause cause, byte[] received) {
        return new CreditDataAnswer.Unavailable(
                cause, received == null ? Optional.empty() : Optional.of(new CreditEvidence(received)));
    }

    private static Pattern field(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
    }

    private static Optional<String> find(Pattern field, String text) {
        Matcher matcher = field.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
