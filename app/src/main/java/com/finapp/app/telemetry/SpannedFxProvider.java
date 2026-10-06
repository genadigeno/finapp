package com.finapp.app.telemetry;

import com.finapp.fx.FxProvider;
import com.finapp.platform.telemetry.Spans;
import java.util.Map;
import java.util.Objects;

/**
 * An FX provider's three legs, each inside its span (`P9-TSK-027`): the firm quote ({@code fx.provider.quote}), the
 * cover's execution ({@code fx.cover.dispatch}) and its inquiry ({@code fx.cover.resolve}). No attribute carries a
 * reference, a rate or an amount; the adapter's own answer and failure pass through unchanged.
 */
public final class SpannedFxProvider implements FxProvider {

    private final FxProvider delegate;
    private final Spans spans;

    public SpannedFxProvider(FxProvider delegate, Spans spans) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
    }

    @Override
    public String code() {
        return delegate.code();
    }

    @Override
    public FirmQuoteAnswer firmQuote(FirmQuoteRequest request) {
        return spans.within(Phase9Spans.PROVIDER_QUOTE, Map.of(), () -> delegate.firmQuote(request));
    }

    @Override
    public ExecutionAnswer execute(ExecutionRequest request) {
        return spans.within(Phase9Spans.COVER_DISPATCH, Map.of(), () -> delegate.execute(request));
    }

    @Override
    public ExecutionAnswer inquire(String clientReference) {
        return spans.within(Phase9Spans.COVER_RESOLVE, Map.of(), () -> delegate.inquire(clientReference));
    }

    @Override
    public String toString() {
        return "SpannedFxProvider[" + delegate + "]";
    }
}
