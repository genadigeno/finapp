package com.finapp.app.credit;

import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataAnswer.UnavailableCause;
import com.finapp.credit.CreditDataPull;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated second credit bureau {@code bureau-sim-b} (`P10-TSK-021`; ADR-0085 sections 2 and 10, ADR-0008's
 * adapter shape) - a {@link CreditBureau} whose wire shares no word with {@code bureau-sim-a}'s, normalising to the same
 * attribute codes with the same meaning. Provider neutrality is this class and {@link SimulatedBureauAdapter} answering
 * one subject alike ({@code BureauSelectionDatabaseTest#bothProvidersNormaliseOneSubjectAlike}).
 *
 * <h2>The wire is ours, and this class is the only place it exists</h2>
 *
 * <p>{@code POST /v2/consumer-files}; our reference in {@code X-Request-Reference}, the credential in
 * {@code X-Api-Key}; the file's status {@code FILE_FULL} or {@code FILE_THIN}; snake_case fields; the retrieval
 * instant in epoch seconds; the insolvency marker {@code Y}/{@code N}; each amount an object of integer minor units and
 * its currency code. None of it crosses the port ({@code CreditProviderVocabularyIsConfinedTest}).
 *
 * <h2>Normalisation is total, and only a clean file is data</h2>
 *
 * <ul>
 *   <li>{@code FILE_FULL}: every field present and valid, or the whole answer is {@code MALFORMED}.
 *   <li>{@code FILE_THIN}: a field absent - its key not there at all - is {@link AttributeValue.Absent}; a field there
 *       in any unreadable form (a bare number, {@code null}, a broken amount object) is {@code MALFORMED}, never absent.
 *   <li>An amount in a currency other than the product's is {@code Absent} with the {@code CURRENCY_NOT_SUPPORTED}
 *       marker, never converted ({@code INV-CRD-12}).
 *   <li>Any other status is {@code UNKNOWN_STATUS}; a non-200 or a broken transport {@code PROVIDER_ERROR}; the
 *       client's wait expiring {@code TIMEOUT}. None carries an attribute ({@code INV-CRD-10}).
 * </ul>
 *
 * <p>Stateless: the fields are configuration. Not a production bean - the bureau order is refused in production until
 * party facts exist (unresolved question #13); the collection's selection is proven against the simulators.
 */
public final class SimulatedSecondBureauAdapter implements CreditBureau {

    /** The bureau's code: its declaration's, its evidence's and its meter's tag value. */
    public static final String CODE = "bureau-sim-b";

    /** The version of this adapter's normalisation - bump it when the mapping changes. */
    public static final int NORMALISER_VERSION = 1;

    /** The consumer-file path. */
    public static final String FILES_PATH = "/v2/consumer-files";

    /** The header our reference travels in - the bureau dedupes on it. */
    public static final String REFERENCE_HEADER = "X-Request-Reference";

    /** The header the API key travels in. */
    public static final String KEY_HEADER = "X-Api-Key";

    /** The retained body's bound - past it, the answer is malformed and nothing is retained. */
    public static final int MAX_EVIDENCE_BYTES = 64 * 1024;

    private static final String FULL = "FILE_FULL";
    private static final String THIN = "FILE_THIN";

    private static final Pattern OBJECT = Pattern.compile("(?s)^\\s*\\{.*\\}\\s*$");
    private static final Pattern STATUS = text("file_status");
    private static final Pattern GENERATED_AT = text("generated_at");

    /** Each bureau attribute's wire field and how it is written. */
    private enum Field {
        SCORE(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, "risk_grade_score", Form.COUNT),
        TRADELINES(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, "open_tradelines", Form.COUNT),
        LATE_PAYMENTS(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, "late_payments_24m", Form.COUNT),
        CHARGE_OFFS(CreditAttributeCode.BUREAU_DEFAULTS_72M, "charge_offs_72m", Form.COUNT),
        BANKRUPTCY(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, "bankruptcy_marker", Form.MARKER),
        PAYMENTS(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS, "monthly_payments", Form.AMOUNT),
        DEBT(CreditAttributeCode.BUREAU_TOTAL_BALANCE, "outstanding_debt", Form.AMOUNT);

        private final CreditAttributeCode code;
        private final Form form;
        private final Pattern key;
        private final Pattern value;

        Field(CreditAttributeCode code, String name, Form form) {
            this.code = code;
            this.form = form;
            this.key = Pattern.compile("\"" + name + "\"\\s*:");
            this.value = form == Form.AMOUNT
                    ? Pattern.compile("\"" + name + "\"\\s*:\\s*\\{\\s*\"amount_minor\"\\s*:\\s*\"([^\"\\\\]*)\"\\s*,"
                            + "\\s*\"currency_code\"\\s*:\\s*\"([^\"\\\\]*)\"\\s*\\}")
                    : text(name);
        }
    }

    /** How a field is written on this wire. */
    private enum Form { COUNT, MARKER, AMOUNT }

    private static final AttributeProvenance PROVENANCE =
            new AttributeProvenance.Provider(CreditSourceKind.BUREAU, CODE, NORMALISER_VERSION);

    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final CreditDataSubjectResolver subjects;
    private final HttpClient http;

    public SimulatedSecondBureauAdapter(
            URI baseUrl, Duration timeout, byte[] key, CreditDataSubjectResolver subjects) {
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
        String body = "{\"consumer\":{\"full_name\":\"" + escape(subject.fullName()) + "\",\"birth_date\":\""
                + subject.dateOfBirth() + "\",\"residence\":\"" + subject.residenceCountry().code() + "\"},"
                + "\"reporting_currency\":\"" + request.product().currency().code() + "\"}";
        HttpRequest post = HttpRequest.newBuilder(baseUrl.resolve(FILES_PATH))
                .timeout(timeout)
                .header(KEY_HEADER, Base64.getEncoder().encodeToString(key))
                .header(REFERENCE_HEADER, request.reference())
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
        return "SimulatedSecondBureauAdapter[" + CODE + "]";
    }

    /**
     * A 200 body, normalised totally onto the port's answer - the golden files' subject.
     *
     * @param received the body, verbatim
     * @param productCurrency the currency every money attribute must be in
     */
    static CreditDataAnswer normalise(byte[] received, CurrencyCode productCurrency) {
        String body = new String(received, StandardCharsets.UTF_8);
        if (!OBJECT.matcher(body).matches()) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        Optional<String> status = find(STATUS, body, 1);
        if (status.isEmpty()) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        boolean full = status.get().equals(FULL);
        if (!full && !status.get().equals(THIN)) {
            return unavailable(UnavailableCause.UNKNOWN_STATUS, received);
        }
        Optional<String> epochSeconds = find(GENERATED_AT, body, 1).filter(seconds -> seconds.matches("[0-9]{1,12}"));
        if (epochSeconds.isEmpty()) {
            return unavailable(UnavailableCause.MALFORMED, received);
        }
        Instant retrievedAt = Instant.ofEpochSecond(Long.parseLong(epochSeconds.get()));
        List<CreditAttribute> attributes = new ArrayList<>();
        boolean foreignCurrency = false;
        for (Field field : Field.values()) {
            Matcher value = field.value.matcher(body);
            if (!value.find()) {
                // Absent means the key is not there at all; there in an unreadable form, the file is malformed.
                if (full || field.key.matcher(body).find()) {
                    return unavailable(UnavailableCause.MALFORMED, received);
                }
                attributes.add(attribute(field.code, new AttributeValue.Absent()));
                continue;
            }
            Optional<AttributeValue> parsed = parse(field, value, productCurrency);
            if (parsed.isEmpty()) {
                // Present but unreadable: the whole answer is malformed - never a partial parse.
                return unavailable(UnavailableCause.MALFORMED, received);
            }
            if (parsed.get() instanceof AttributeValue.Absent) {
                foreignCurrency = true;
            }
            attributes.add(attribute(field.code, parsed.get()));
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

    /** A present field's value, {@code Absent} for an amount in a foreign currency, or empty if unreadable. */
    private static Optional<AttributeValue> parse(Field field, Matcher value, CurrencyCode productCurrency) {
        String raw = value.group(1);
        switch (field.form) {
            case COUNT:
                return raw.matches("[0-9]{1,9}")
                        ? Optional.of(new AttributeValue.IntegerValue(Long.parseLong(raw)))
                        : Optional.empty();
            case MARKER:
                if (raw.equals("Y") || raw.equals("N")) {
                    return Optional.of(new AttributeValue.BooleanValue(raw.equals("Y")));
                }
                return Optional.empty();
            case AMOUNT:
                if (!raw.matches("[0-9]{1,15}")) {
                    return Optional.empty();
                }
                CurrencyCode currency;
                try {
                    currency = CurrencyCode.of(value.group(2));
                } catch (IllegalArgumentException unknown) {
                    return Optional.empty();
                }
                if (!currency.equals(productCurrency)) {
                    // Never converted: absent, and the marker says why (INV-CRD-12).
                    return Optional.of(new AttributeValue.Absent());
                }
                try {
                    return Optional.of(new AttributeValue.MoneyValue(Money.ofMinorUnits(Long.parseLong(raw), currency)));
                } catch (MonetaryException unrepresentable) {
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

    private static Pattern text(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
    }

    private static Optional<String> find(Pattern pattern, String body, int group) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(group)) : Optional.empty();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
