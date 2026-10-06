package com.finapp.app.telemetry;

/**
 * The FX and cross-border legs' span names (`P9-TSK-027`, PHASE_9_PLAN.md section 15's trace): each recorded through
 * the platform {@code Spans} port with identifier attributes only (ADR-0072, {@code INV-AUD-02}).
 */
public final class Phase9Spans {

    public static final String QUOTE_ISSUE = "fx.quote.issue";
    public static final String PROVIDER_QUOTE = "fx.provider.quote";
    public static final String CONVERT = "fx.convert";
    public static final String COVER_DISPATCH = "fx.cover.dispatch";
    public static final String COVER_RESOLVE = "fx.cover.resolve";
    public static final String AUTHORIZE = "crossborder.authorize";
    public static final String OUTBOUND_DISPATCH = "payments.outbound.dispatch";
    public static final String OUTBOUND_RESOLVE = "payments.outbound.resolve";
    public static final String OUTBOUND_RECALL = "payments.outbound.recall";
    public static final String OUTBOUND_RETURN_APPLY = "payments.outbound.return.apply";
    public static final String COUNTERPARTY_SCREEN = "kyc.counterparty.screen";

    private Phase9Spans() {}
}
