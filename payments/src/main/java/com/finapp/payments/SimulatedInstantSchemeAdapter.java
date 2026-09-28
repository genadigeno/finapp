package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The simulated instant scheme behind the {@link PushRail} port (`P7-TSK-006`, ADR-0062 §1;
 * ADR-0008's no-real-connectivity rule). Everything scheme-specific lives here: the paths,
 * the JSON, the status words, the declared deadline — a second scheme is a sibling adapter
 * and routing rules, never a core change ({@code INV-RAIL-01}).
 *
 * <h2>The declaration (ADR-0059 §1, the card rail's discipline)</h2>
 *
 * <p>Push interaction; <strong>final on acceptance</strong> — no reversal of any kind, so
 * the machine's void vocabulary can never be asked of this rail; refunds execute as
 * {@code RETURN_PAYMENT}, a new forward push; settlement is scheme-reported on its cycle,
 * cleared through {@code INSTANT_CLEARING} (ADR-0062 §4); <strong>the scheme bounds its own
 * ambiguity</strong> with {@link #OUTCOME_DEADLINE} — past it, the status inquiry is
 * authoritative (executed, or never executed), which is the material difference from the
 * card rail, whose unknowns only a provider's answer ends. No dispute mechanism exists on
 * the rail.
 */
public final class SimulatedInstantSchemeAdapter implements PushRail {

    public static final String NAME = "simulated-instant-scheme";

    /**
     * The scheme's declared bound on a final answer (ADR-0059 §1's {@code outcomeDeadline},
     * ADR-0062 §3): once this has passed since the LATEST send, the inquiry's word is
     * authoritative. Declared data — identical on every instance, judged by `P7-TSK-009`'s
     * resolvers against the send permit plus the configured margin, never against a clock
     * alone.
     */
    public static final Duration OUTCOME_DEADLINE = Duration.ofSeconds(90);

    /**
     * The instant rail's declaration. Version 1; bump it when these capabilities change
     * (ADR-0060 §2 — routing steps record which declaration they judged).
     */
    public static final PaymentRail RAIL =
            new PaymentRail(
                    RailId.of("instant"),
                    1,
                    new RailCapabilities(
                            InteractionModel.PUSH,
                            RailCapabilities.Finality.FINAL_ON_ACCEPTANCE,
                            Set.of(),
                            RailCapabilities.RefundMode.RETURN_PAYMENT,
                            RailCapabilities.SettlementModel.SCHEME_REPORTED,
                            Optional.of(OUTCOME_DEADLINE),
                            RailCapabilities.DisputeModel.NONE,
                            Optional.empty(),
                            Map.of(),
                            Optional.of(AccountPurpose.INSTANT_CLEARING)));

    // The simulated wire paths - published for tests that stub the scheme.
    public static final String EXCHANGES_PATH = "/grant-exchanges";
    public static final String TRANSFERS_PATH = "/credit-transfers";
    public static final String INITIATIONS_PATH = "/initiations";
    /** The return's own resource (`P7-TSK-010`): a new transfer, never an edit of one. */
    public static final String RETURNS_PATH = "/returns";
    /** Inquiry prefixes; our end-to-end reference is the path segment (`INV-PAY-04`). */
    public static final String TRANSFER_STATUS_PATH = "/credit-transfers/";
    public static final String INITIATION_STATUS_PATH = "/initiations/";
    public static final String RETURN_STATUS_PATH = "/returns/";

    /** The dispatch idempotency header, published for the contract tests' wire oracle. */
    public static final String IDEMPOTENCY_KEY_HEADER = SchemeWireClient.IDEMPOTENCY_KEY_HEADER;

    private final SchemeWireClient client;

    public SimulatedInstantSchemeAdapter(URI baseUrl, Duration timeout, byte[] key) {
        this.client = new SchemeWireClient(baseUrl, timeout, key);
    }

    @Override
    public String schemeName() {
        return NAME;
    }

    @Override
    public ExchangeAnswer exchange(GrantExchange request) {
        // The grant's one legitimate destination: the rail provider's wire. Never logged.
        return client.exchange(
                EXCHANGES_PATH,
                request.reference(),
                "{\"endToEndReference\":"
                        + SchemeWireClient.jsonString(request.reference().value())
                        + ",\"grant\":"
                        + SchemeWireClient.jsonString(request.grant())
                        + "}");
    }

    @Override
    public PushAnswer send(CreditTransfer request) {
        return client.send(
                TRANSFERS_PATH,
                request.reference(),
                "{\"endToEndReference\":"
                        + SchemeWireClient.jsonString(request.reference().value())
                        + ",\"destination\":"
                        + SchemeWireClient.jsonString(request.destination().value())
                        + ","
                        + amountOf(request.amount())
                        + "}");
    }

    @Override
    public PushInquiryAnswer inquire(EndToEndReference ourReference) {
        return client.inquire(TRANSFER_STATUS_PATH + ourReference.value());
    }

    @Override
    public InitiationAnswer initiate(PayInInitiation request) {
        return client.initiate(
                INITIATIONS_PATH,
                request.reference(),
                "{\"endToEndReference\":"
                        + SchemeWireClient.jsonString(request.reference().value())
                        + ","
                        + amountOf(request.amount())
                        + "}");
    }

    @Override
    public PushInquiryAnswer inquireInitiation(EndToEndReference ourReference) {
        return client.inquireInitiation(INITIATION_STATUS_PATH + ourReference.value());
    }

    @Override
    public PushAnswer sendReturn(ReturnPayment request) {
        // The transfer's own wire discipline (P7-TSK-010): our reference as the
        // Idempotency-Key header AND the body field, the ORIGINAL's scheme reference as
        // the destination-by-reference, the total mapping shared with send().
        return client.send(
                RETURNS_PATH,
                request.reference(),
                "{\"endToEndReference\":"
                        + SchemeWireClient.jsonString(request.reference().value())
                        + ",\"originalReference\":"
                        + SchemeWireClient.jsonString(request.originalSchemeReference().value())
                        + ","
                        + amountOf(request.amount())
                        + "}");
    }

    @Override
    public PushInquiryAnswer inquireReturn(EndToEndReference ourReference) {
        return client.inquire(RETURN_STATUS_PATH + ourReference.value());
    }

    /** Minor units as a JSON string — the card wire's `INV-MON-01` stance, same reasons. */
    private static String amountOf(Money amount) {
        return "\"amountMinor\":\""
                + amount.minorUnits()
                + "\",\"currency\":\""
                + amount.currency().code()
                + "\",\"scale\":"
                + amount.scale();
    }
}
