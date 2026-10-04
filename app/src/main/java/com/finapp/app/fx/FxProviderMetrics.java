package com.finapp.app.fx;

import com.finapp.fx.FxProvider;
import com.finapp.fx.FxProviderDeclaration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * {@code finapp.fx.provider.quote.latency{provider, outcome}} (`P9-TSK-006`, ADR-0075's
 * operational impact): how long each declared provider takes to answer a firm quote, and with
 * what verdict - provider quoting health and the failover signal. Registered eagerly for every
 * declared provider and every outcome, configured or not, so a freshly started instance publishes
 * the whole series (`P1-TSK-029`'s rule); durations through a {@code Timer.Sample} on the
 * registry's clock, never an amount or a rate (ADR-0072).
 */
public final class FxProviderMetrics {

    public static final String QUOTE_LATENCY = "finapp.fx.provider.quote.latency";

    /** The outcome tag's closed set - one per {@code FirmQuoteAnswer} kind. */
    static final List<String> OUTCOMES =
            List.of("quoted", "declined", "nothing_sent", "indeterminate");

    private final MeterRegistry registry;

    public FxProviderMetrics(Collection<FxProviderDeclaration> declared, MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        for (FxProviderDeclaration declaration : declared) {
            for (String outcome : OUTCOMES) {
                timer(declaration.code(), outcome);
            }
        }
    }

    /** Wraps {@code adapter} so every firm quote is timed under its verdict. */
    public FxProvider timed(FxProvider adapter) {
        Objects.requireNonNull(adapter, "adapter must not be null");
        return new FxProvider() {
            @Override
            public String code() {
                return adapter.code();
            }

            @Override
            public FirmQuoteAnswer firmQuote(FirmQuoteRequest request) {
                // The registry's clock, never an ambient read (NoAmbientTimeRulesTest).
                Timer.Sample sample = Timer.start(registry);
                FirmQuoteAnswer answer = adapter.firmQuote(request);
                sample.stop(timer(adapter.code(), outcomeOf(answer)));
                return answer;
            }

            @Override
            public ExecutionAnswer execute(ExecutionRequest request) {
                return adapter.execute(request);
            }

            @Override
            public ExecutionAnswer inquire(String clientReference) {
                return adapter.inquire(clientReference);
            }

            @Override
            public String toString() {
                return "Timed" + adapter;
            }
        };
    }

    static String outcomeOf(FxProvider.FirmQuoteAnswer answer) {
        return switch (answer) {
            case FxProvider.FirmQuoteAnswer.Quoted quoted -> "quoted";
            case FxProvider.FirmQuoteAnswer.Declined declined -> "declined";
            case FxProvider.FirmQuoteAnswer.NothingSent nothing -> "nothing_sent";
            case FxProvider.FirmQuoteAnswer.Indeterminate indeterminate -> "indeterminate";
        };
    }

    private Timer timer(String provider, String outcome) {
        return Timer.builder(QUOTE_LATENCY)
                .tag("provider", provider)
                .tag("outcome", outcome)
                .description(
                        "Time an FX provider takes to answer a firm quote, by provider and verdict:"
                                + " quoted, declined, nothing sent, or indeterminate - quoting"
                                + " health and the failover signal. Durations only")
                .register(registry);
    }
}
