package com.finapp.app.telemetry;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.CreditDataSource;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.CreditSpans;
import com.finapp.platform.telemetry.Spans;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A credit data source's pull inside its span (`P10-TSK-020`): {@code credit.data.collect}, with no attribute - never the
 * reference, the subject or any attribute the answer carries ({@code INV-CRD-02}). The code, kind and attribute set pass
 * through, so collection's selection and its kind check see the source unchanged; the answer and any failure pass
 * through unchanged.
 */
public final class SpannedCreditDataSource implements CreditDataSource {

    private final CreditDataSource delegate;
    private final Spans spans;

    public SpannedCreditDataSource(CreditDataSource delegate, Spans spans) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
    }

    @Override
    public String code() {
        return delegate.code();
    }

    @Override
    public CreditSourceKind kind() {
        return delegate.kind();
    }

    @Override
    public Set<CreditAttributeCode> attributes() {
        return delegate.attributes();
    }

    @Override
    public CreditDataAnswer pull(CreditDataPull request) {
        return spans.within(CreditSpans.COLLECT, Map.of(), () -> delegate.pull(request));
    }

    @Override
    public String toString() {
        return "SpannedCreditDataSource[" + delegate + "]";
    }
}
