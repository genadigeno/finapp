package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory FX provider for the quote suites (`P9-TSK-008`): quotes any pair it holds a rate
 * for, with a coherent stated counter, and COUNTS every firm-quote request - the RFQ count the cap
 * and same-key tests assert. A mode makes the next and every later answer declined, indeterminate,
 * incoherent or nothing-sent.
 */
final class FakeFxProvider implements FxProvider {

    enum Mode { QUOTE, DECLINE, INDETERMINATE, INCOHERENT, NOTHING_SENT }

    private final String code;
    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.QUOTE);
    private volatile Duration validFor = Duration.ofSeconds(60);

    FakeFxProvider(String code) {
        this.code = code;
    }

    static FxProviderDeclaration declaration(String code) {
        java.util.Set<FxProviderDeclaration.QuotedPair> pairs = new java.util.HashSet<>();
        for (String s : new String[] {"EUR", "GBP", "USD", "JPY", "BHD"}) {
            for (String d : new String[] {"EUR", "GBP", "USD", "JPY", "BHD"}) {
                if (!s.equals(d)) {
                    pairs.add(new FxProviderDeclaration.QuotedPair(CurrencyCode.of(s), CurrencyCode.of(d)));
                }
            }
        }
        return new FxProviderDeclaration(code, 1, pairs,
                java.util.Set.of(CurrencyCode.of("EUR"), CurrencyCode.of("GBP"), CurrencyCode.of("USD"),
                        CurrencyCode.of("JPY"), CurrencyCode.of("BHD")),
                Duration.ofMinutes(5));
    }

    FakeFxProvider rate(String source, String destination, String value) {
        rates.put(source + destination, new BigDecimal(value));
        return this;
    }

    FakeFxProvider mode(Mode next) {
        mode.set(next);
        return this;
    }

    FakeFxProvider validFor(Duration value) {
        validFor = value;
        return this;
    }

    int requests() {
        return requests.get();
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public FirmQuoteAnswer firmQuote(FirmQuoteRequest request) {
        requests.incrementAndGet();
        Evidence evidence = new Evidence(("answer to " + request.quoteRequestReference()).getBytes(StandardCharsets.UTF_8));
        return switch (mode.get()) {
            case DECLINE -> new FirmQuoteAnswer.Declined(DeclineReason.MARKET_CLOSED, evidence);
            case INDETERMINATE -> new FirmQuoteAnswer.Indeterminate(Indeterminacy.TIMEOUT, Optional.empty());
            case NOTHING_SENT -> new FirmQuoteAnswer.NothingSent();
            case QUOTE, INCOHERENT -> {
                BigDecimal rate = rates.get(request.source().code() + request.destination().code());
                if (rate == null) {
                    yield new FirmQuoteAnswer.Declined(DeclineReason.PAIR_NOT_QUOTED, evidence);
                }
                Money counter = counter(request, rate);
                if (mode.get() == Mode.INCOHERENT) {
                    counter = Money.ofMinorUnits(counter.minorUnits() + 5, counter.currency());
                }
                yield new FirmQuoteAnswer.Quoted(
                        "PQ-" + requests.get(),
                        new ProviderQuote(ExchangeRate.of(request.source(), request.destination(), rate), counter, validFor),
                        LocalDate.of(2026, 10, 6),
                        evidence);
            }
        };
    }

    private static Money counter(FirmQuoteRequest request, BigDecimal rate) {
        if (request.fixedSide() == FixedSide.FIXED_SOURCE) {
            BigDecimal bought = request.amount().toBigDecimal().multiply(rate)
                    .setScale(request.destination().minorUnits(), RoundingMode.HALF_EVEN);
            return Money.of(bought, request.destination());
        }
        BigDecimal sold = request.amount().toBigDecimal()
                .divide(rate, request.source().minorUnits(), RoundingMode.HALF_EVEN);
        return Money.of(sold, request.source());
    }

    @Override
    public ExecutionAnswer execute(ExecutionRequest request) {
        throw new UnsupportedOperationException("the quote suites never execute");
    }

    @Override
    public ExecutionAnswer inquire(String clientReference) {
        throw new UnsupportedOperationException("the quote suites never inquire");
    }
}
