package com.finapp.app.fx;

import com.finapp.fx.FixedSide;
import com.finapp.fx.FxProvider;
import com.finapp.fx.FxProviderDeclaration;
import com.finapp.fx.FxProviderEvidenceStore;
import com.finapp.fx.ProviderQuote;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated FX provider {@code fx-sim-a} (`P9-TSK-006`, ADR-0075, ADR-0077 §4, ADR-0008's
 * adapter shape) - the platform's first {@link FxProvider}.
 *
 * <h2>The wire is ours, and this class is the only place it exists</h2>
 *
 * <p>ADR-0008 simulates providers, so the vocabulary {@code INV-PAY-03} confines is the one
 * defined here: {@code POST /fx/quotes}, {@code POST /fx/executions},
 * {@code GET /fx/executions/{T}}, the {@code status} field with {@code quoted}/{@code declined}/
 * {@code executed}/{@code rejected}/{@code unrecognised}, the reason codes, and the field names.
 * None of it crosses the port ({@code FxProviderVocabularyIsConfinedTest}). Amounts and rates
 * travel as JSON <strong>strings</strong> and are read by pattern, never through a double.
 *
 * <h2>Classification - totality, with one piece of knowledge</h2>
 *
 * <p><strong>Only {@link ConnectException} is knowledge</strong>: a refused connection means an
 * RST arrived and nothing was transmitted - {@code NothingSent}. Every other transport failure
 * is {@code Indeterminate}, and so is every answer that is not a parsed 200 body in so many words:
 * a 5xx, a 404, an unknown status, an unknown reason, a missing field, a body past the retention
 * bound, and a rate or an amount more precise than its type admits ({@code OVER_PRECISE} - refused,
 * never rounded). No default anywhere is a success: erring toward indeterminate costs one inquiry;
 * erring toward success books an execution nobody confirmed.
 *
 * <h2>What every request carries</h2>
 *
 * <p>Our reference - the quote request's {@code QR} or the execution's {@code T} - as the
 * {@code Idempotency-Key} header ({@code INV-PAY-04}, asserted at the wire by the contract
 * battery), and the API credential as {@code Authorization: Bearer <base64(key)>}. The provider's
 * side of the contract - dedupe on {@code T} before judging the quote's validity - is the
 * battery's subject against {@code SimulatedFxEngine}.
 */
public final class SimulatedFxProviderAdapter implements FxProvider {

    /** The provider's code: its declaration's, its evidence's and its meter's tag value. */
    public static final String CODE = "fx-sim-a";

    /** The firm-quote path. */
    public static final String QUOTES_PATH = "/fx/quotes";

    /** The execution path; an inquiry appends our reference. */
    public static final String EXECUTIONS_PATH = "/fx/executions";

    /** The idempotency header our reference travels in ({@code INV-PAY-04}). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** The retained body's bound - past it, the answer is indeterminate and nothing is retained. */
    public static final int MAX_EVIDENCE_BYTES = FxProviderEvidenceStore.MAX_PAYLOAD_BYTES;

    /**
     * What this build declares about {@code fx-sim-a}: every ordered pair of the postable
     * currencies, all five settled (`PHASE_9_PLAN.md`: "all five for fx-sim-a"), and firm quotes
     * held at most two minutes. Declaration version 1; bump it when these capabilities change.
     */
    public static final FxProviderDeclaration DECLARATION =
            new FxProviderDeclaration(
                    CODE, 1, everyOrderedPair(), Set.copyOf(SupportedCurrencies.ALL), Duration.ofMinutes(2));

    private static final Pattern STATUS = field("status");
    private static final Pattern REASON = field("reason");
    private static final Pattern QUOTE_REF = field("quoteRef");
    private static final Pattern TRADE_REF = field("tradeRef");
    private static final Pattern RATE = field("rate");
    private static final Pattern COUNTER = field("counter");
    private static final Pattern COUNTER_CURRENCY = field("counterCurrency");
    private static final Pattern VALID_FOR_SECONDS = field("validForSeconds");
    private static final Pattern VALUE_DATE = field("valueDate");
    private static final Pattern SOLD = field("sold");
    private static final Pattern SOLD_CURRENCY = field("soldCurrency");
    private static final Pattern BOUGHT = field("bought");
    private static final Pattern BOUGHT_CURRENCY = field("boughtCurrency");

    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final HttpClient http;

    public SimulatedFxProviderAdapter(URI baseUrl, Duration timeout, byte[] key) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("an FX provider timeout must be positive");
        }
        this.key = key.clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public FirmQuoteAnswer firmQuote(FirmQuoteRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String body =
                "{\"reference\":\"" + request.quoteRequestReference() + "\",\"source\":\""
                        + request.source().code() + "\",\"destination\":\""
                        + request.destination().code() + "\",\"fixedSide\":\""
                        + request.fixedSide().name() + "\",\"amount\":\""
                        + request.amount().toBigDecimal().toPlainString() + "\"}";
        Exchange exchange = post(QUOTES_PATH, request.quoteRequestReference(), body);
        if (exchange.nothingSent()) {
            return new FirmQuoteAnswer.NothingSent();
        }
        if (exchange.failure().isPresent()) {
            return new FirmQuoteAnswer.Indeterminate(exchange.failure().get(), exchange.evidence());
        }
        Evidence evidence = exchange.evidence().orElseThrow();
        String text = exchange.text();
        return switch (find(STATUS, text).orElse("")) {
            case "quoted" -> quoted(request, text, evidence);
            case "declined" ->
                    declineReason(find(REASON, text).orElse(""))
                            .<FirmQuoteAnswer>map(reason -> new FirmQuoteAnswer.Declined(reason, evidence))
                            .orElseGet(() -> indeterminateQuote(Indeterminacy.UNKNOWN_STATE, evidence));
            default -> indeterminateQuote(Indeterminacy.UNKNOWN_STATE, evidence);
        };
    }

    @Override
    public ExecutionAnswer execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String body =
                "{\"reference\":\"" + request.clientReference() + "\",\"quoteRef\":\""
                        + escape(request.providerQuoteReference()) + "\",\"source\":\""
                        + request.source().code() + "\",\"destination\":\""
                        + request.destination().code() + "\",\"fixedSide\":\""
                        + request.fixedSide().name() + "\",\"amount\":\""
                        + request.amount().toBigDecimal().toPlainString() + "\"}";
        Exchange exchange = post(EXECUTIONS_PATH, request.clientReference(), body);
        // "unrecognised" means nothing on the execute path: only an inquiry can say so.
        return executionAnswer(exchange, false);
    }

    @Override
    public ExecutionAnswer inquire(String clientReference) {
        FxProvider.reference(clientReference);
        HttpRequest get =
                HttpRequest.newBuilder(
                                baseUrl.resolve(
                                        EXECUTIONS_PATH + "/"
                                                + URLEncoder.encode(
                                                        clientReference, StandardCharsets.UTF_8)))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .GET()
                        .build();
        return executionAnswer(send(get), true);
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "SimulatedFxProviderAdapter[" + CODE + "]";
    }

    // -----------------------------------------------------------------
    // Mapping

    private static FirmQuoteAnswer quoted(FirmQuoteRequest request, String text, Evidence evidence) {
        Optional<String> quoteRef = find(QUOTE_REF, text);
        Optional<String> rate = find(RATE, text);
        Optional<String> counter = find(COUNTER, text);
        Optional<String> counterCurrency = find(COUNTER_CURRENCY, text);
        Optional<String> validFor = find(VALID_FOR_SECONDS, text);
        Optional<String> valueDate = find(VALUE_DATE, text);
        if (quoteRef.isEmpty() || rate.isEmpty() || counter.isEmpty() || counterCurrency.isEmpty()
                || validFor.isEmpty() || valueDate.isEmpty()) {
            return indeterminateQuote(Indeterminacy.MALFORMED, evidence);
        }
        CurrencyCode expectedCounter =
                request.fixedSide() == FixedSide.FIXED_SOURCE
                        ? request.destination()
                        : request.source();
        try {
            CurrencyCode stated = CurrencyCode.of(counterCurrency.get());
            if (!stated.equals(expectedCounter)) {
                return indeterminateQuote(Indeterminacy.MALFORMED, evidence);
            }
            Optional<ExchangeRate> exact =
                    exactRate(request.source(), request.destination(), rate.get());
            Optional<Money> statedCounter = exactMoney(counter.get(), stated);
            if (exact.isEmpty() || statedCounter.isEmpty()) {
                return indeterminateQuote(Indeterminacy.OVER_PRECISE, evidence);
            }
            long seconds = Long.parseLong(validFor.get());
            if (seconds <= 0) {
                return indeterminateQuote(Indeterminacy.MALFORMED, evidence);
            }
            return new FirmQuoteAnswer.Quoted(
                    quoteRef.get(),
                    new ProviderQuote(exact.get(), statedCounter.get(), Duration.ofSeconds(seconds)),
                    LocalDate.parse(valueDate.get()),
                    evidence);
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException unusable) {
            return indeterminateQuote(Indeterminacy.MALFORMED, evidence);
        }
    }

    private static ExecutionAnswer executionAnswer(Exchange exchange, boolean inquiry) {
        if (exchange.nothingSent()) {
            return new ExecutionAnswer.NothingSent();
        }
        if (exchange.failure().isPresent()) {
            return new ExecutionAnswer.Indeterminate(exchange.failure().get(), exchange.evidence());
        }
        Evidence evidence = exchange.evidence().orElseThrow();
        String text = exchange.text();
        return switch (find(STATUS, text).orElse("")) {
            case "executed" -> executed(text, evidence);
            case "rejected" ->
                    rejectReason(find(REASON, text).orElse(""))
                            .<ExecutionAnswer>map(reason -> new ExecutionAnswer.Rejected(reason, evidence))
                            .orElseGet(() -> indeterminateExecution(Indeterminacy.UNKNOWN_STATE, evidence));
            // Explicit and parsed - the only licence to say "never seen" - and only on inquiry.
            case "unrecognised" ->
                    inquiry
                            ? new ExecutionAnswer.Unrecognised(evidence)
                            : indeterminateExecution(Indeterminacy.UNKNOWN_STATE, evidence);
            default -> indeterminateExecution(Indeterminacy.UNKNOWN_STATE, evidence);
        };
    }

    private static ExecutionAnswer executed(String text, Evidence evidence) {
        Optional<String> tradeRef = find(TRADE_REF, text);
        Optional<String> sold = find(SOLD, text);
        Optional<String> soldCurrency = find(SOLD_CURRENCY, text);
        Optional<String> bought = find(BOUGHT, text);
        Optional<String> boughtCurrency = find(BOUGHT_CURRENCY, text);
        Optional<String> rate = find(RATE, text);
        Optional<String> valueDate = find(VALUE_DATE, text);
        if (tradeRef.isEmpty() || tradeRef.get().isBlank() || sold.isEmpty()
                || soldCurrency.isEmpty() || bought.isEmpty() || boughtCurrency.isEmpty()
                || rate.isEmpty() || valueDate.isEmpty()) {
            // An execution with no trade reference cannot be referred to again: unactionable.
            return indeterminateExecution(Indeterminacy.MALFORMED, evidence);
        }
        try {
            CurrencyCode soldIn = CurrencyCode.of(soldCurrency.get());
            CurrencyCode boughtIn = CurrencyCode.of(boughtCurrency.get());
            Optional<Money> soldAmount = exactMoney(sold.get(), soldIn);
            Optional<Money> boughtAmount = exactMoney(bought.get(), boughtIn);
            Optional<ExchangeRate> executedRate = exactRate(soldIn, boughtIn, rate.get());
            if (soldAmount.isEmpty() || boughtAmount.isEmpty() || executedRate.isEmpty()) {
                return indeterminateExecution(Indeterminacy.OVER_PRECISE, evidence);
            }
            return new ExecutionAnswer.Executed(
                    tradeRef.get(),
                    soldAmount.get(),
                    boughtAmount.get(),
                    executedRate.get(),
                    LocalDate.parse(valueDate.get()),
                    evidence);
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException unusable) {
            return indeterminateExecution(Indeterminacy.MALFORMED, evidence);
        }
    }

    /** A rate held exactly, or empty when it is more precise than {@link ExchangeRate} admits. */
    private static Optional<ExchangeRate> exactRate(
            CurrencyCode source, CurrencyCode destination, String raw) {
        BigDecimal value = new BigDecimal(requireDecimal(raw));
        if (value.scale() > ExchangeRate.MAX_SCALE || value.precision() > ExchangeRate.MAX_PRECISION) {
            return Optional.empty();
        }
        return Optional.of(ExchangeRate.of(source, destination, value));
    }

    /** An amount held exactly in its currency's minor units, or empty when it is more precise. */
    private static Optional<Money> exactMoney(String raw, CurrencyCode currency) {
        BigDecimal value = new BigDecimal(requireDecimal(raw));
        if (value.scale() > currency.minorUnits()) {
            return Optional.empty();
        }
        return Optional.of(Money.of(value, currency));
    }

    /** A plain non-negative decimal - no sign, no exponent, nothing a double would accept loosely. */
    private static String requireDecimal(String raw) {
        if (!raw.matches("^[0-9]{1,19}(\\.[0-9]{1,30})?$")) {
            throw new IllegalArgumentException("not a plain decimal");
        }
        return raw;
    }

    private static Optional<DeclineReason> declineReason(String code) {
        return switch (code) {
            case "pair_not_quoted" -> Optional.of(DeclineReason.PAIR_NOT_QUOTED);
            case "amount_out_of_range" -> Optional.of(DeclineReason.AMOUNT_OUT_OF_RANGE);
            case "market_closed" -> Optional.of(DeclineReason.MARKET_CLOSED);
            default -> Optional.empty();
        };
    }

    private static Optional<RejectReason> rejectReason(String code) {
        return switch (code) {
            case "quote_expired" -> Optional.of(RejectReason.QUOTE_EXPIRED);
            case "price_changed" -> Optional.of(RejectReason.PRICE_CHANGED);
            case "limit" -> Optional.of(RejectReason.LIMIT);
            default -> Optional.empty();
        };
    }

    private static FirmQuoteAnswer indeterminateQuote(Indeterminacy cause, Evidence evidence) {
        return new FirmQuoteAnswer.Indeterminate(cause, Optional.of(evidence));
    }

    private static ExecutionAnswer indeterminateExecution(Indeterminacy cause, Evidence evidence) {
        return new ExecutionAnswer.Indeterminate(cause, Optional.of(evidence));
    }

    // -----------------------------------------------------------------
    // Transport

    /** One exchange's classification, before any vocabulary is read. */
    private record Exchange(
            boolean nothingSent,
            Optional<Indeterminacy> failure,
            Optional<Evidence> evidence,
            String text) {}

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
            // A 404 included: a status code is not an answer. Retained when there is a body.
            return new Exchange(
                    false,
                    Optional.of(Indeterminacy.SERVER_ERROR),
                    retainable ? Optional.of(new Evidence(received)) : Optional.empty(),
                    "");
        }
        if (!retainable) {
            // Nothing to retain: an empty body is absence, an oversized one unretainable.
            return new Exchange(false, Optional.of(Indeterminacy.MALFORMED), Optional.empty(), "");
        }
        Evidence evidence = new Evidence(received);
        return new Exchange(
                false, Optional.empty(), Optional.of(evidence), new String(received, StandardCharsets.UTF_8));
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

    private static Set<FxProviderDeclaration.QuotedPair> everyOrderedPair() {
        Set<FxProviderDeclaration.QuotedPair> pairs = new HashSet<>();
        for (CurrencyCode source : SupportedCurrencies.ALL) {
            for (CurrencyCode destination : SupportedCurrencies.ALL) {
                if (!source.equals(destination)) {
                    pairs.add(new FxProviderDeclaration.QuotedPair(source, destination));
                }
            }
        }
        return pairs;
    }
}
