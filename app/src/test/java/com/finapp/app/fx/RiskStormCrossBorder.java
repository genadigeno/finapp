package com.finapp.app.fx;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The risk storm's cross-border world (`P13-TST-001`) - a public bridge over this package's fixtures (the lending storm's
 * {@code LendingStormCredit} precedent): the simulated FX engine and the corridor engine both instances quote and pay
 * through, the pricing policy and the reference rates the FX suites use, the corridor policy's body, and a beneficiary's
 * single-use grant. The storm drives every door itself; this class owns only what the package keeps to itself.
 */
public final class RiskStormCrossBorder implements AutoCloseable {

    public static final String QUOTES = "/v1/me/cross-border/quotes";
    public static final String PAYMENTS = "/v1/me/cross-border/payments";
    public static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    public static final String CORRIDOR_POLICIES = "/v1/operator/cross-border/corridor-policies";

    private final SimulatedFxEngine fx;
    private final SimulatedCorridorEngine corridor;

    private RiskStormCrossBorder(SimulatedFxEngine fx, SimulatedCorridorEngine corridor) {
        this.fx = fx;
        this.corridor = corridor;
    }

    /** Both simulators started, every rate of the FX suites' mids set. */
    public static RiskStormCrossBorder start() throws IOException {
        SimulatedFxEngine fx = SimulatedFxEngine.start(new byte[32]);
        FxTestClient.rates(fx, Map.of());
        SimulatedCorridorEngine corridor = SimulatedCorridorEngine.start(
                "a-corridor-risk-storm-test-key-of-32by".getBytes(StandardCharsets.UTF_8));
        return new RiskStormCrossBorder(fx, corridor);
    }

    public String fxUrl() {
        return fx.baseUrl().toString();
    }

    public String corridorUrl() {
        return corridor.baseUrl().toString();
    }

    /** The FX suites' pricing policy active (four eyes in the domain) and a fresh reference rate for every pair. */
    public static void pricing(PricingPolicyAdministration administration, PricingPolicyStore store) throws Exception {
        FxTestPolicy.ensure(administration, store, FxTestPolicy.V1);
        FxTestClient.freshReferences();
    }

    /**
     * A fresh reference observation for every pair - the reference source's role, which no sweep plays in this tier
     * ({@code finapp.fx.rate.sweeper.enabled} off): a quote judged against a stale reference is {@code fx.RateUnavailable}.
     */
    public static void references() throws Exception {
        FxTestClient.freshReferences();
    }

    /** The corridor policy v1's proposal body. */
    public static String corridorPolicy(String reason) {
        return CorridorPolicyV1.json(reason);
    }

    /** A single-use grant for an individual beneficiary in {@code country}/{@code currency} whose payee check matches. */
    public String grant(String country, String currency) {
        return corridor.issueGrant(new SimulatedCorridorEngine.Beneficiary(country, currency, "individual", "match"));
    }

    @Override
    public void close() {
        fx.close();
        corridor.close();
    }
}
