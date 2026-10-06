package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated corridor provider {@code corridor-sim-a} behind the {@link CorridorRail} port
 * (`P9-TSK-014`, ADR-0080 section 1, ADR-0008's no-real-connectivity rule) - its declaration and its
 * wire, both here.
 *
 * <h2>The declaration - only what is true (D17)</h2>
 *
 * <p>Push interaction; final on acceptance; reversals {@code {}} ({@code INV-REV-03}: a credit the
 * provider accepted cannot be reversed by us - a recall is a request, never a reversal);
 * {@code RefundMode.NONE} - the rail carries no pay-in, so no refund executes on it, and routing
 * refuses a {@code PAY_IN} here with {@code DIRECTION_UNSUPPORTED}; settled later through clearing on
 * {@code CORRIDOR_CLEARING(corridor-sim-a)}, the provider's own position (ADR-0078); a ten-minute
 * outcome deadline; no disputes; {@code {USD, JPY, BHD}} with the O7 maxima at each currency's scale.
 * The corridor facts - coverage US/USD, JP/JPY, BH/BHD, a 30-day return window, a 4-hour decision
 * deadline, a one-day delivery estimate and charges borne by the platform - are its
 * {@link #DECLARATION}.
 *
 * <h2>The wire is ours, and this class is the only place it exists (INV-PAY-03)</h2>
 *
 * <p>{@code POST /corridor/beneficiaries}, {@code POST /corridor/credits},
 * {@code GET /corridor/credits/{E}}, {@code POST /corridor/credits/{E}/recall}, the {@code status}
 * words, the reason codes and the field names appear here and nowhere else
 * ({@code RailVocabularyIsConfinedTest}). Amounts travel as JSON strings and are read by pattern,
 * never through a double. Our reference travels as the {@code Idempotency-Key}; the credential as
 * {@code Authorization: Bearer <base64(key)>}, never logged ({@link #toString()} names no key).
 *
 * <h2>Classification - totality, with one piece of knowledge</h2>
 *
 * <p>Only {@link ConnectException} is knowledge - nothing was transmitted. Everything else that is
 * not a parsed 200 body in so many words is {@code Indeterminate}: a timeout, a 5xx, a 404, an unknown
 * status or reason, a missing field, a body past the retention bound, an amount more precise than
 * its currency ({@code OVER_PRECISE} - refused, never rounded). The payee check is the one mapping
 * with a safe default: {@code match} is a match, {@code unavailable} unavailable, and every other
 * word - {@code close_match} included - is {@code NO_MATCH}.
 */
public final class SimulatedCorridorAdapter implements CorridorRail {

    /** The beneficiary exchange path. */
    public static final String BENEFICIARIES_PATH = "/corridor/beneficiaries";

    /** The credits path; an inquiry appends our reference, a recall {@code /recall} after it. */
    public static final String CREDITS_PATH = "/corridor/credits";

    /** The recall suffix. */
    public static final String RECALL_SUFFIX = "/recall";

    /** The idempotency header our reference travels in ({@code INV-PAY-04}). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** The provider's declared bound on a final answer to a send. */
    public static final Duration OUTCOME_DEADLINE = Duration.ofMinutes(10);

    /**
     * The corridor rail's declaration. Version 1; bump it when these capabilities change (ADR-0060
     * section 2 - routing steps record which declaration they judged).
     */
    public static final PaymentRail RAIL =
            new PaymentRail(
                    RailId.of("corridor-sim-a"),
                    1,
                    new RailCapabilities(
                            InteractionModel.PUSH,
                            RailCapabilities.Finality.FINAL_ON_ACCEPTANCE,
                            Set.of(),
                            RailCapabilities.RefundMode.NONE,
                            RailCapabilities.SettlementModel.DEFERRED_VIA_CLEARING,
                            Optional.of(OUTCOME_DEADLINE),
                            RailCapabilities.DisputeModel.NONE,
                            Optional.of(Set.of(
                                    CurrencyCode.of("USD"), CurrencyCode.of("JPY"), CurrencyCode.of("BHD"))),
                            Map.of(
                                    CurrencyCode.of("USD"), Money.of(new BigDecimal("10000.00"), CurrencyCode.of("USD")),
                                    CurrencyCode.of("JPY"), Money.of(new BigDecimal("1500000"), CurrencyCode.of("JPY")),
                                    CurrencyCode.of("BHD"), Money.of(new BigDecimal("4000.000"), CurrencyCode.of("BHD"))),
                            Optional.of(AccountPurpose.CORRIDOR_CLEARING)));

    /** The corridor facts of {@link #RAIL} (ADR-0080 section 1; O7's three corridors' destinations). */
    public static final CorridorDeclaration DECLARATION =
            new CorridorDeclaration(
                    RAIL.id(),
                    Set.of(
                            new CorridorDeclaration.Coverage(CountryCode.of("US"), CurrencyCode.of("USD")),
                            new CorridorDeclaration.Coverage(CountryCode.of("JP"), CurrencyCode.of("JPY")),
                            new CorridorDeclaration.Coverage(CountryCode.of("BH"), CurrencyCode.of("BHD"))),
                    Duration.ofDays(30),
                    Duration.ofHours(4),
                    Duration.ofDays(1),
                    CorridorDeclaration.ChargeBearer.OUR);

    /**
     * The second simulated corridor rail (`P9-TSK-026`, M9.8): the same wire, its own counterparty -
     * {@code corridor-sim-b} delivers USD in the US, overlapping {@code corridor-sim-a} on that corridor, and settles
     * on its OWN clearing position. Version 1.
     */
    public static final PaymentRail RAIL_B =
            new PaymentRail(
                    RailId.of("corridor-sim-b"),
                    1,
                    new RailCapabilities(
                            InteractionModel.PUSH,
                            RailCapabilities.Finality.FINAL_ON_ACCEPTANCE,
                            Set.of(),
                            RailCapabilities.RefundMode.NONE,
                            RailCapabilities.SettlementModel.DEFERRED_VIA_CLEARING,
                            Optional.of(OUTCOME_DEADLINE),
                            RailCapabilities.DisputeModel.NONE,
                            Optional.of(Set.of(CurrencyCode.of("USD"))),
                            Map.of(CurrencyCode.of("USD"), Money.of(new BigDecimal("10000.00"), CurrencyCode.of("USD"))),
                            Optional.of(AccountPurpose.CORRIDOR_CLEARING)));

    /** The corridor facts of {@link #RAIL_B}: US/USD only. */
    public static final CorridorDeclaration DECLARATION_B =
            new CorridorDeclaration(
                    RAIL_B.id(),
                    Set.of(new CorridorDeclaration.Coverage(CountryCode.of("US"), CurrencyCode.of("USD"))),
                    Duration.ofDays(30),
                    Duration.ofHours(4),
                    Duration.ofDays(1),
                    CorridorDeclaration.ChargeBearer.OUR);

    private static final Pattern STATUS = field("status");
    private static final Pattern REASON = field("reason");
    private static final Pattern DESTINATION_REF = field("destinationRef");
    private static final Pattern SUFFIX = field("suffix");
    private static final Pattern PAYEE_CHECK = field("payeeCheck");
    private static final Pattern COUNTRY = field("country");
    private static final Pattern CURRENCY = field("currency");
    private static final Pattern ENTITY_TYPE = field("entityType");
    private static final Pattern PROVIDER_REF = field("providerRef");
    private static final Pattern ACCEPTED_AT = field("acceptedAt");
    private static final Pattern DELIVERED_AT = field("deliveredAt");
    private static final Pattern RETURN_REF = field("returnRef");
    private static final Pattern RETURN_AMOUNT = field("returnAmount");
    private static final Pattern RETURNED_AT = field("returnedAt");

    private final PaymentRail rail;
    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final HttpClient http;

    /** {@code corridor-sim-a}. */
    public SimulatedCorridorAdapter(URI baseUrl, Duration timeout, byte[] key) {
        this(RAIL, baseUrl, timeout, key);
    }

    /** The simulated corridor {@code rail} - one of this build's declared simulated rails (`P9-TSK-026`). */
    public SimulatedCorridorAdapter(PaymentRail rail, URI baseUrl, Duration timeout, byte[] key) {
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
        if (!rail.equals(RAIL) && !rail.equals(RAIL_B)) {
            throw new IllegalArgumentException("not a simulated corridor rail this build declares: " + rail.id().value());
        }
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a corridor provider timeout must be positive");
        }
        this.key = key.clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public RailId id() {
        return rail.id();
    }

    @Override
    public BeneficiaryExchange exchangeBeneficiary(BeneficiaryGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");
        String body = "{\"reference\":\"" + grant.reference().value() + "\",\"grant\":\"" + grant.grant() + "\"}";
        Exchange exchange = post(BENEFICIARIES_PATH, grant.reference().value(), body);
        if (exchange.nothingSent()) {
            return new BeneficiaryExchange.NothingSent();
        }
        if (exchange.failure().isPresent()) {
            return new BeneficiaryExchange.Indeterminate(exchange.failure().get(), exchange.evidence());
        }
        Evidence evidence = exchange.evidence().orElseThrow();
        String text = exchange.text();
        return switch (find(STATUS, text).orElse("")) {
            case "exchanged" -> exchanged(text, evidence);
            case "refused" ->
                    exchangeRefusal(find(REASON, text).orElse(""))
                            .<BeneficiaryExchange>map(reason -> new BeneficiaryExchange.Refused(reason, evidence))
                            .orElseGet(() -> new BeneficiaryExchange.Indeterminate(
                                    Indeterminacy.UNKNOWN_STATE, Optional.of(evidence)));
            default -> new BeneficiaryExchange.Indeterminate(Indeterminacy.UNKNOWN_STATE, Optional.of(evidence));
        };
    }

    @Override
    public SendAnswer send(CreditInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        String body =
                "{\"reference\":\"" + instruction.reference().value() + "\",\"destinationRef\":\""
                        + instruction.destination().value() + "\",\"amount\":\""
                        + instruction.amount().toBigDecimal().toPlainString() + "\",\"currency\":\""
                        + instruction.amount().currency().code() + "\"}";
        Exchange exchange = post(CREDITS_PATH, instruction.reference().value(), body);
        if (exchange.nothingSent()) {
            return new SendAnswer.NothingSent();
        }
        if (exchange.failure().isPresent()) {
            return new SendAnswer.Indeterminate(exchange.failure().get(), exchange.evidence());
        }
        Evidence evidence = exchange.evidence().orElseThrow();
        String text = exchange.text();
        return switch (find(STATUS, text).orElse("")) {
            case "received" -> new SendAnswer.Received(evidence);
            case "accepted" -> accepted(text, evidence);
            case "rejected" ->
                    sendRejection(find(REASON, text).orElse(""))
                            .<SendAnswer>map(reason -> new SendAnswer.Rejected(reason, evidence))
                            .orElseGet(() -> new SendAnswer.Indeterminate(Indeterminacy.UNKNOWN_STATE, Optional.of(evidence)));
            default -> new SendAnswer.Indeterminate(Indeterminacy.UNKNOWN_STATE, Optional.of(evidence));
        };
    }

    @Override
    public InquiryAnswer inquire(EndToEndReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        HttpRequest get =
                HttpRequest.newBuilder(baseUrl.resolve(creditPath(reference)))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .GET()
                        .build();
        Exchange exchange = send(get);
        if (exchange.nothingSent()) {
            return new InquiryAnswer.NothingSent();
        }
        if (exchange.failure().isPresent()) {
            return new InquiryAnswer.Indeterminate(exchange.failure().get(), exchange.evidence());
        }
        Evidence evidence = exchange.evidence().orElseThrow();
        String text = exchange.text();
        return switch (find(STATUS, text).orElse("")) {
            case "unrecognised" -> new InquiryAnswer.Unrecognised(evidence);
            case "received" -> found(CreditState.RECEIVED, evidence);
            case "recalled" -> found(CreditState.RECALLED, evidence);
            case "rejected" ->
                    sendRejection(find(REASON, text).orElse(""))
                            .<InquiryAnswer>map(reason -> new InquiryAnswer.Found(
                                    CreditState.REJECTED, Optional.empty(), Optional.empty(), Optional.empty(),
                                    Optional.empty(), Optional.of(reason), evidence))
                            .orElseGet(() -> new InquiryAnswer.Indeterminate(
                                    Indeterminacy.UNKNOWN_STATE, Optional.of(evidence)));
            case "accepted" -> acceptedFound(text, evidence);
            default -> new InquiryAnswer.Indeterminate(Indeterminacy.UNKNOWN_STATE, Optional.of(evidence));
        };
    }

    @Override
    public RecallAnswer recall(EndToEndReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        Exchange exchange = post(creditPath(reference) + RECALL_SUFFIX, reference.value(), "{}");
        if (exchange.nothingSent()) {
            return new RecallAnswer.NothingSent();
        }
        if (exchange.failure().isPresent()) {
            return new RecallAnswer.Indeterminate(exchange.failure().get(), exchange.evidence());
        }
        Evidence evidence = exchange.evidence().orElseThrow();
        return switch (find(STATUS, exchange.text()).orElse("")) {
            case "recalled" -> new RecallAnswer.Recalled(evidence);
            case "too_late" -> new RecallAnswer.TooLate(evidence);
            case "unrecognised" -> new RecallAnswer.Unrecognised(evidence);
            default -> new RecallAnswer.Indeterminate(Indeterminacy.UNKNOWN_STATE, Optional.of(evidence));
        };
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "SimulatedCorridorAdapter[" + rail.id().value() + "]";
    }

    // -----------------------------------------------------------------
    // Mapping

    private static BeneficiaryExchange exchanged(String text, Evidence evidence) {
        Optional<String> destination = find(DESTINATION_REF, text);
        Optional<String> suffix = find(SUFFIX, text);
        Optional<String> country = find(COUNTRY, text);
        Optional<String> currency = find(CURRENCY, text);
        Optional<EntityType> entityType = find(ENTITY_TYPE, text).flatMap(SimulatedCorridorAdapter::entityType);
        if (destination.isEmpty() || suffix.isEmpty() || country.isEmpty() || currency.isEmpty()
                || entityType.isEmpty()) {
            return new BeneficiaryExchange.Indeterminate(Indeterminacy.MALFORMED, Optional.of(evidence));
        }
        try {
            return new BeneficiaryExchange.Exchanged(
                    new ProviderReference(destination.get()),
                    suffix.get(),
                    payeeCheck(find(PAYEE_CHECK, text).orElse("")),
                    CountryCode.of(country.get()),
                    CurrencyCode.of(currency.get()),
                    entityType.get(),
                    evidence);
        } catch (IllegalArgumentException unusable) {
            return new BeneficiaryExchange.Indeterminate(Indeterminacy.MALFORMED, Optional.of(evidence));
        }
    }

    private static SendAnswer accepted(String text, Evidence evidence) {
        Optional<String> providerRef = find(PROVIDER_REF, text);
        Optional<String> acceptedAt = find(ACCEPTED_AT, text);
        if (providerRef.isEmpty() || acceptedAt.isEmpty()) {
            // An acceptance with no provider reference cannot be reconciled: unactionable.
            return new SendAnswer.Indeterminate(Indeterminacy.MALFORMED, Optional.of(evidence));
        }
        try {
            return new SendAnswer.Accepted(
                    new ProviderReference(providerRef.get()), Instant.parse(acceptedAt.get()), evidence);
        } catch (IllegalArgumentException | DateTimeParseException unusable) {
            return new SendAnswer.Indeterminate(Indeterminacy.MALFORMED, Optional.of(evidence));
        }
    }

    private static InquiryAnswer found(CreditState state, Evidence evidence) {
        return new InquiryAnswer.Found(
                state, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                evidence);
    }

    private static InquiryAnswer acceptedFound(String text, Evidence evidence) {
        Optional<String> providerRef = find(PROVIDER_REF, text);
        Optional<String> acceptedAt = find(ACCEPTED_AT, text);
        Optional<String> currency = find(CURRENCY, text);
        if (providerRef.isEmpty() || acceptedAt.isEmpty()) {
            return new InquiryAnswer.Indeterminate(Indeterminacy.MALFORMED, Optional.of(evidence));
        }
        try {
            Optional<Instant> deliveredAt = find(DELIVERED_AT, text).map(Instant::parse);
            Optional<String> returnRef = find(RETURN_REF, text);
            Optional<String> returnAmount = find(RETURN_AMOUNT, text);
            Optional<String> returnedAt = find(RETURNED_AT, text);
            Optional<ReturnFact> returned = Optional.empty();
            if (returnRef.isPresent() || returnAmount.isPresent() || returnedAt.isPresent()) {
                if (returnRef.isEmpty() || returnAmount.isEmpty() || returnedAt.isEmpty() || currency.isEmpty()) {
                    return new InquiryAnswer.Indeterminate(Indeterminacy.MALFORMED, Optional.of(evidence));
                }
                Optional<Money> amount = exactMoney(returnAmount.get(), CurrencyCode.of(currency.get()));
                if (amount.isEmpty()) {
                    return new InquiryAnswer.Indeterminate(Indeterminacy.OVER_PRECISE, Optional.of(evidence));
                }
                returned = Optional.of(new ReturnFact(
                        new ProviderReference(returnRef.get()), amount.get(), Instant.parse(returnedAt.get())));
            }
            return new InquiryAnswer.Found(
                    CreditState.ACCEPTED,
                    Optional.of(new ProviderReference(providerRef.get())),
                    Optional.of(Instant.parse(acceptedAt.get())),
                    deliveredAt,
                    returned,
                    Optional.empty(),
                    evidence);
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException unusable) {
            return new InquiryAnswer.Indeterminate(Indeterminacy.MALFORMED, Optional.of(evidence));
        }
    }

    /** An amount held exactly in its currency's minor units, or empty when it is more precise. */
    private static Optional<Money> exactMoney(String raw, CurrencyCode currency) {
        if (!raw.matches("^[0-9]{1,19}(\\.[0-9]{1,30})?$")) {
            throw new IllegalArgumentException("not a plain decimal");
        }
        BigDecimal value = new BigDecimal(raw);
        if (value.scale() > currency.minorUnits()) {
            return Optional.empty();
        }
        return Optional.of(Money.of(value, currency));
    }

    /** Total, with the one safe default: whatever is not a plain match is not a match. */
    private static PayeeCheck payeeCheck(String word) {
        return switch (word) {
            case "match" -> PayeeCheck.MATCH;
            case "unavailable" -> PayeeCheck.UNAVAILABLE;
            default -> PayeeCheck.NO_MATCH;
        };
    }

    private static Optional<EntityType> entityType(String word) {
        return switch (word) {
            case "individual" -> Optional.of(EntityType.INDIVIDUAL);
            case "business" -> Optional.of(EntityType.BUSINESS);
            default -> Optional.empty();
        };
    }

    private static Optional<ExchangeRefusal> exchangeRefusal(String code) {
        return switch (code) {
            case "grant_invalid" -> Optional.of(ExchangeRefusal.GRANT_INVALID);
            case "grant_expired" -> Optional.of(ExchangeRefusal.GRANT_EXPIRED);
            case "grant_used" -> Optional.of(ExchangeRefusal.GRANT_USED);
            default -> Optional.empty();
        };
    }

    private static Optional<SendRejection> sendRejection(String code) {
        return switch (code) {
            case "beneficiary_closed" -> Optional.of(SendRejection.BENEFICIARY_CLOSED);
            case "limit" -> Optional.of(SendRejection.LIMIT);
            case "currency_not_carried" -> Optional.of(SendRejection.CURRENCY_NOT_CARRIED);
            default -> Optional.empty();
        };
    }

    // -----------------------------------------------------------------
    // Transport

    /** One exchange's classification, before any vocabulary is read. */
    private record Exchange(
            boolean nothingSent, Optional<Indeterminacy> failure, Optional<Evidence> evidence, String text) {}

    private static String creditPath(EndToEndReference reference) {
        return CREDITS_PATH + "/" + URLEncoder.encode(reference.value(), StandardCharsets.UTF_8);
    }

    private Exchange post(String path, String reference, String body) {
        HttpRequest post =
                HttpRequest.newBuilder(baseUrl.resolve(path))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .header(IDEMPOTENCY_KEY_HEADER, reference)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return send(post);
    }

    private Exchange send(HttpRequest request) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException refused) {
            return new Exchange(true, Optional.empty(), Optional.empty(), "");
        } catch (HttpTimeoutException slow) {
            return new Exchange(false, Optional.of(Indeterminacy.TIMEOUT), Optional.empty(), "");
        } catch (IOException broken) {
            return new Exchange(false, Optional.of(Indeterminacy.TRANSPORT), Optional.empty(), "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Exchange(false, Optional.of(Indeterminacy.TRANSPORT), Optional.empty(), "");
        }
        byte[] received = response.body();
        boolean retainable = received.length > 0 && received.length <= MAX_EVIDENCE_BYTES;
        if (response.statusCode() != 200) {
            return new Exchange(
                    false,
                    Optional.of(Indeterminacy.SERVER_ERROR),
                    retainable ? Optional.of(new Evidence(received)) : Optional.empty(),
                    "");
        }
        if (!retainable) {
            return new Exchange(false, Optional.of(Indeterminacy.MALFORMED), Optional.empty(), "");
        }
        return new Exchange(
                false, Optional.empty(), Optional.of(new Evidence(received)), new String(received, StandardCharsets.UTF_8));
    }

    private static Pattern field(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
    }

    private static Optional<String> find(Pattern field, String text) {
        Matcher matcher = field.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
