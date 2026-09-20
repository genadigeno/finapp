package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.time.Duration;

/**
 * The simulated card-style PSP — the platform's first {@link PaymentProvider} (`P5-TSK-003`,
 * ADR-0049).
 *
 * <p>A thin binding of {@link PspWireClient} to the four operations and their paths — the whole
 * adapter, because the port's contract (misbehaviour is a result, never an exception) lives in
 * the client and the vocabulary mapping is its one job (the `P2-TSK-009` adapter shape). The
 * wire it speaks is Stripe-shaped — request idempotency in the {@code Idempotency-Key} header, a
 * bearer credential, capture and refund naming their predecessor's provider reference — and it
 * is <em>ours</em>: the only endpoints that exist are the `P0-TSK-037` harness in tests and
 * whatever a demo stands up (ADR-0049; the Phase 2 verification-provider shape).
 *
 * <h2>Wiring — deliberately none yet</h2>
 *
 * <p>No bean until the first composition-root consumer, `P5-TSK-009` (the `P1-TSK-007`
 * unconsumed-wiring licence; two beans were once deleted precisely because nothing consumed
 * them). What is fixed now so that task wires without a naming decision:
 * {@code finapp.payments.provider.url} (<strong>no default</strong> — ADR-0008 simulates
 * providers, so an unconfigured deployment carries no adapter aimed at nothing, the
 * {@code KycBeans} precedent), {@code finapp.payments.provider.timeout} (default {@code PT2S}),
 * and the key decoded by {@code com.finapp.app.payments.ProviderApiKey} — the confinement
 * mechanism's fifth credential — passed in as bytes.
 */
public final class SimulatedCardPspAdapter implements PaymentProvider {

    /** The stable provider name: the evidence scope and, from `P5-TSK-017`, the meter tag value. */
    public static final String NAME = "simulated-card";

    // The simulated wire paths - published for tests that stub the provider.
    public static final String AUTHORIZATIONS_PATH = "/authorizations";
    public static final String CAPTURES_PATH = "/captures";
    public static final String REFUNDS_PATH = "/refunds";
    /** Query prefix; the platform-minted reference is the path segment (`INV-PAY-04`). */
    public static final String OPERATIONS_PATH = "/operations/";

    /** The dispatch idempotency header (`INV-PAY-04` at the wire), published for tests. */
    public static final String IDEMPOTENCY_KEY_HEADER = PspWireClient.IDEMPOTENCY_KEY_HEADER;

    private final PspWireClient client;

    public SimulatedCardPspAdapter(URI baseUrl, Duration timeout, byte[] key) {
        this.client = new PspWireClient(baseUrl, timeout, key);
    }

    @Override
    public String providerName() {
        return NAME;
    }

    @Override
    public ProviderAnswer authorize(AuthorizationRequest request) {
        return client.dispatch(
                AUTHORIZATIONS_PATH,
                request.reference(),
                "{\"token\":\""
                        // The one place the token legitimately goes: onto the provider wire.
                        // The expose site is named in SecretsAreUnwrappedInOnePlaceTest.
                        + request.instrumentToken().expose()
                        + "\","
                        + amountOf(request.amount())
                        + "}");
    }

    @Override
    public ProviderAnswer capture(CaptureRequest request) {
        return client.dispatch(
                CAPTURES_PATH,
                request.reference(),
                "{\"authorization\":\""
                        + request.authorization().value()
                        + "\","
                        + amountOf(request.amount())
                        + "}");
    }

    @Override
    public ProviderAnswer refund(RefundRequest request) {
        return client.dispatch(
                REFUNDS_PATH,
                request.reference(),
                "{\"capture\":\""
                        + request.capture().value()
                        + "\","
                        + amountOf(request.amount())
                        + "}");
    }

    @Override
    public QueryAnswer query(ProviderIdempotencyReference ourReference) {
        // The reference's charset makes it a legal path segment with no escaping machinery -
        // its recorded design property.
        return client.query(OPERATIONS_PATH + ourReference.value());
    }

    /**
     * Minor units travel as a JSON <em>string</em>: a JSON number is a {@code double} in every
     * careless consumer, and {@code INV-MON-01}'s reasoning does not stop at our own boundary
     * (the `P3-TSK-013` published-amounts stance, applied to a wire we also define). Every
     * value is from validated types, so the body needs no escaping machinery.
     */
    private static String amountOf(Money amount) {
        return "\"amountMinor\":\""
                + amount.minorUnits()
                + "\",\"currency\":\""
                + amount.currency().code()
                + "\",\"scale\":"
                + amount.scale();
    }
}
