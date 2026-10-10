package com.finapp.app.credit;

import com.finapp.app.api.DecimalText;
import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataAnswer.UnavailableCause;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditBureau;
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
import tools.jackson.databind.JsonNode;

/**
 * The simulated credit bureau {@code bureau-sim-a} (`P10-TSK-005`; ADR-0085 section 2, ADR-0008's
 * adapter shape) - the platform's first {@link CreditBureau}.
 *
 * <h2>The wire is ours, and this class is the only place it exists</h2>
 *
 * <p>ADR-0008 simulates providers, so the vocabulary confined here is the one defined here:
 * {@code POST /bureau/reports}, the {@code status} words {@code report_complete} and
 * {@code report_partial}, and the report's field names. None of it crosses the port
 * ({@code CreditProviderVocabularyIsConfinedTest}). Amounts travel as JSON strings, read through
 * {@code DecimalText} - never a double, never an unbounded parse.
 *
 * <h2>Normalisation is total, and only a clean report is data</h2>
 *
 * <ul>
 *   <li>The body is parsed as strict JSON ({@link CreditProviderJson}, version 3): anything but exactly one object - two
 *       concatenated reports, a duplicate key, a token after the object - is {@code MALFORMED}, and only the object's
 *       top-level fields are read.
 *   <li>{@code report_complete}: every field must be present and valid, or the whole answer is
 *       {@code MALFORMED} - a surviving field of a broken report is never parsed into an attribute.
 *   <li>{@code report_partial}: the fields present must be valid (else {@code MALFORMED}); a field
 *       absent is {@link AttributeValue.Absent}, never a default.
 *   <li>Money in a currency other than the product's is {@code Absent}, with the
 *       {@code CURRENCY_NOT_SUPPORTED} marker naming the source kind - never converted
 *       ({@code INV-CRD-12}). Money more precise than its currency is {@code MALFORMED}.
 *   <li>Any other status is {@code UNKNOWN_STATUS}; a non-200 or a broken transport is
 *       {@code PROVIDER_ERROR}; the client's wait expiring is {@code TIMEOUT}. None carries an
 *       attribute.
 * </ul>
 *
 * <h2>What every request carries</h2>
 *
 * <p>Our reference as the {@code Idempotency-Key} header - the bureau dedupes on it, so a repeat
 * answers the first pull and is never counted twice - the API credential as a bearer token, and the
 * subject's identifying facts, resolved here from the opaque reference and sent nowhere else.
 *
 * <p>Stateless: the fields are configuration. Not yet a bean - bureau collection (`P10-TSK-006`)
 * wires it, binding the client timeout from configuration.
 */
public final class SimulatedBureauAdapter implements CreditBureau {

    /** The bureau's code: its declaration's, its evidence's and its meter's tag value. */
    public static final String CODE = "bureau-sim-a";

    /**
     * The version of this adapter's normalisation - bump it when the mapping changes. Version 2 (`P10-TSK-021`): a
     * field present in any form but a readable string - a bare number, {@code null}, an object - is malformed, never
     * absent; version 1 read such a field of a partial answer as absent. Version 3 (the Phase 10 -> 11 transition): the
     * body is parsed as strict JSON ({@link CreditProviderJson}) - two concatenated reports, a duplicate key, a token
     * after the object are malformed, and only top-level fields are read; version 2 matched patterns, so it took the
     * first report's fields, the first of two keys, and a field nested inside another object.
     */
    public static final int NORMALISER_VERSION = 3;

    /** The report path. */
    public static final String REPORTS_PATH = "/bureau/reports";

    /** The idempotency header our reference travels in. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** The retained body's bound - past it, the answer is malformed and nothing is retained. */
    public static final int MAX_EVIDENCE_BYTES = 64 * 1024;

    private static final String COMPLETE = "report_complete";
    private static final String PARTIAL = "report_partial";

    private static final String STATUS = "status";
    private static final String RETRIEVED_AT = "retrievedAt";

    /** Each bureau attribute's wire field, and the currency field of the money ones. */
    private enum Field {
        EXTERNAL_SCORE(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, "externalScore", null),
        ACTIVE_ACCOUNTS(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, "activeAccounts", null),
        DELINQUENCIES(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, "delinquencies24m", null),
        DEFAULTS(CreditAttributeCode.BUREAU_DEFAULTS_72M, "defaults72m", null),
        INSOLVENCY(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, "insolvencyFlag", null),
        OBLIGATIONS(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS, "monthlyObligations", "monthlyObligationsCurrency"),
        BALANCE(CreditAttributeCode.BUREAU_TOTAL_BALANCE, "totalBalance", "totalBalanceCurrency");

        private final CreditAttributeCode code;
        private final String name;
        private final String currency;

        Field(CreditAttributeCode code, String name, String currencyName) {
            this.code = code;
            this.name = name;
            this.currency = currencyName;
        }
    }

    private static final AttributeProvenance PROVENANCE =
            new AttributeProvenance.Provider(CreditSourceKind.BUREAU, CODE, NORMALISER_VERSION);

    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final CreditDataSubjectResolver subjects;
    private final HttpClient http;

    public SimulatedBureauAdapter(URI baseUrl, Duration timeout, byte[] key, CreditDataSubjectResolver subjects) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(key, "key");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a bureau timeout must be positive");
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
                .orElseThrow(() -> new IllegalStateException("the bureau pull names no resolvable subject"));
        String body = "{\"subject\":{\"name\":\"" + escape(subject.fullName()) + "\",\"dateOfBirth\":\""
                + subject.dateOfBirth() + "\",\"country\":\"" + subject.residenceCountry().code() + "\"},"
                + "\"currency\":\"" + request.product().currency().code() + "\"}";
        HttpRequest post = HttpRequest.newBuilder(baseUrl.resolve(REPORTS_PATH))
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
        return "SimulatedBureauAdapter[" + CODE + "]";
    }

    /**
     * A 200 body, normalised totally onto the port's answer - the golden files' subject.
     *
     * @param received the body, verbatim
     * @param productCurrency the currency every money attribute must be in
     */
    static CreditDataAnswer normalise(byte[] received, CurrencyCode productCurrency) {
        Optional<JsonNode> parsed = CreditProviderJson.object(received);
        if (parsed.isEmpty()) {
            // Not exactly one well-formed JSON object - wholly malformed, nothing of it read (version 3).
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        JsonNode report = parsed.get();
        Optional<String> status = CreditProviderJson.text(report, STATUS);
        if (status.isEmpty()) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        boolean complete = status.get().equals(COMPLETE);
        if (!complete && !status.get().equals(PARTIAL)) {
            return unavailable(UnavailableCause.UNKNOWN_STATUS, received);
        }
        Instant retrievedAt;
        try {
            retrievedAt = Instant.parse(CreditProviderJson.text(report, RETRIEVED_AT).orElse(""));
        } catch (DateTimeParseException unreadable) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        List<CreditAttribute> attributes = new ArrayList<>();
        boolean foreignCurrency = false;
        for (Field field : Field.values()) {
            Optional<String> raw = CreditProviderJson.text(report, field.name);
            if (raw.isEmpty()) {
                // Absent means the field is not there at all; there in an unreadable form, it is malformed (version 2).
                if (complete || CreditProviderJson.has(report, field.name)) {
                    return unavailable(UnavailableCause.MALFORMED, received);
                }
                attributes.add(attribute(field.code, new AttributeValue.Absent()));
                continue;
            }
            Optional<AttributeValue> value = parse(field, raw.get(), report, productCurrency);
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
                    new AttributeValue.CodeValue(CreditSourceKind.BUREAU.name())));
        }
        CreditEvidence evidence = new CreditEvidence(received);
        boolean anyAbsent = attributes.stream().anyMatch(CreditAttribute::absent);
        return anyAbsent
                ? new CreditDataAnswer.Partial(CODE, NORMALISER_VERSION, retrievedAt, attributes, evidence)
                : new CreditDataAnswer.Received(CODE, NORMALISER_VERSION, retrievedAt, attributes, evidence);
    }

    /** A present field's value, {@code Absent} for money in a foreign currency, or empty if unreadable. */
    private static Optional<AttributeValue> parse(Field field, String raw, JsonNode report, CurrencyCode productCurrency) {
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
                Optional<String> currencyText = CreditProviderJson.text(report, field.currency);
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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
