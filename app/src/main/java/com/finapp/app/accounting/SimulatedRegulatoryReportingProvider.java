package com.finapp.app.accounting;

import com.finapp.accounting.RegulatoryForm;
import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.reporting.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The simulated reference adapter behind {@link RegulatoryReportingProvider} (`P14-TSK-018`; {@code PHASE_14_PLAN.md} section 13.7; ADR-0118 decision 3; owner decision C4; {@code SimulatedFilingProvider}'s shape) - the platform's only channel in
 * Phase 14. <strong>Nothing here is real and everything it produces says so</strong>: {@value #STANDING} and {@value #CHANNEL} on its export and on every answer it gives. Its one jurisdiction is the placeholder {@value #JURISDICTION}, so no obligation can
 * name an authority: a proposal naming another is refused because no adapter files with it.
 *
 * <h2>Deterministic by a knob, and no state</h2>
 *
 * <p>The answer is the configured {@link Behaviour} ({@code finapp.accounting.regulatory.simulation}). Its references are <strong>derived from the submission's id</strong> (SHA-256), so the answer to a send and to every later inquiry is the same on every
 * instance. The simulator holds nothing in memory, which is what makes it correct with N instances: one that remembered what it was sent would answer a second instance "not received" and invite a resend it did not need. So {@link Behaviour#NEVER_RECEIVED} is a
 * rehearsal of the operator's door, not a memory: a stateful provider's truth is proven by {@code RegulatorySubmissionDriverTest}'s fake.
 *
 * <p>It refuses, as a provider would, a jurisdiction it does not serve and a content whose hash is not the one named. It logs nothing and keeps nothing.
 *
 * <h2>What a real adapter needs</h2>
 *
 * <p>A bean of its own beside this one, under its own name, with a timeout below {@code finapp.accounting.regulatory.lease}; a jurisdiction chosen by the owner and a format verified against that regulator's text; and a migration, because {@code accounting V010} admits
 * {@code REFERENCE} and nothing else. Nothing else changes: the machine, the driver and the doors do not know which adapter they hold.
 */
public final class SimulatedRegulatoryReportingProvider implements RegulatoryReportingProvider {

    /** The adapter's name, as an obligation names it. */
    public static final String NAME = "simulated-reference";

    /** The placeholder jurisdiction - no authority anywhere. */
    public static final String JURISDICTION = "REF";

    public static final String STANDING = RegulatoryForm.REFERENCE;
    public static final String CHANNEL = "SIMULATED";

    /** What every export says of itself. */
    public static final String NOTICE = "A reference document from a simulated adapter. No jurisdiction, channel or form it names is real, it was delivered to no one, and it asserts nothing about any legal obligation.";

    /** The rejection codes this adapter answers - closed. */
    public static final String REJECTED_BY_SIMULATION = "REJECTED_BY_SIMULATION";
    public static final String JURISDICTION_UNSUPPORTED = "JURISDICTION_UNSUPPORTED";
    public static final String CONTENT_HASH_MISMATCH = "CONTENT_HASH_MISMATCH";

    /** What the simulation answers - {@code finapp.accounting.regulatory.simulation}. */
    public enum Behaviour {
        /** Every send acknowledged at once (the default). */
        ACKNOWLEDGE,
        /** Every send rejected. */
        REJECT,
        /** A send answered unknown; every inquiry acknowledges it - the inquiry leg's path. */
        UNKNOWN_THEN_ACKNOWLEDGE,
        /** Every answer unknown: the row stays {@code SUBMITTED} and reads overdue past its horizon. */
        UNKNOWN,
        /** A send answered unknown; every inquiry "not received": the resends spend, and the person's door opens. */
        NEVER_RECEIVED,
        /** Every call throws. */
        UNAVAILABLE
    }

    /** The simulation is unreachable - nothing is known of the call. */
    public static final class Unavailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Unavailable() {
            super("the simulated regulatory adapter is unavailable");
        }
    }

    private final Behaviour behaviour;

    public SimulatedRegulatoryReportingProvider(Behaviour behaviour) {
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean declares(String jurisdiction) {
        return JURISDICTION.equals(jurisdiction);
    }

    /** The document, one {@code key: value} per line: what it is first, then what was attested, byte for byte. Deterministic: the same return renders the same bytes on every instance. */
    @Override
    public ReferenceExport export(Request request) {
        Objects.requireNonNull(request, "request");
        String document = String.join("\n", List.of(
                "standing: " + STANDING,
                "channel: " + CHANNEL,
                "notice: " + NOTICE,
                "submission: " + request.submission().value(),
                "jurisdiction: " + request.jurisdiction(),
                "form: " + request.form() + "/" + request.formVersion(),
                "period: " + request.period(),
                "contentSha256: " + request.contentSha256(),
                "content: " + request.content()));
        return new ReferenceExport(STANDING, CHANNEL, NOTICE, document, CanonicalJson.sha256(document));
    }

    @Override
    public Answer submit(Request request) {
        Objects.requireNonNull(request, "request");
        available();
        String reference = reference(request.submission());
        if (!declares(request.jurisdiction())) {
            return rejected(reference, JURISDICTION_UNSUPPORTED);
        }
        if (!CanonicalJson.sha256(request.content()).equals(request.contentSha256())) {
            return rejected(reference, CONTENT_HASH_MISMATCH);
        }
        return switch (behaviour) {
            case ACKNOWLEDGE -> Answer.acknowledged(reference, raw("ACKNOWLEDGED", reference, null));
            case REJECT -> rejected(reference, REJECTED_BY_SIMULATION);
            case UNKNOWN_THEN_ACKNOWLEDGE, UNKNOWN, NEVER_RECEIVED -> Answer.unknown(Optional.of(reference), raw("UNKNOWN", reference, null));
            case UNAVAILABLE -> throw new Unavailable();
        };
    }

    @Override
    public Answer inquire(RegulatorySubmissionId submission) {
        Objects.requireNonNull(submission, "submission");
        available();
        String reference = reference(submission);
        return switch (behaviour) {
            case ACKNOWLEDGE, UNKNOWN_THEN_ACKNOWLEDGE -> Answer.acknowledged(reference, raw("ACKNOWLEDGED", reference, null));
            case REJECT -> rejected(reference, REJECTED_BY_SIMULATION);
            case UNKNOWN -> Answer.unknown(Optional.of(reference), raw("UNKNOWN", reference, null));
            case NEVER_RECEIVED -> Answer.notReceived(raw("NOT_RECEIVED", null, null));
            case UNAVAILABLE -> throw new Unavailable();
        };
    }

    private void available() {
        if (behaviour == Behaviour.UNAVAILABLE) {
            throw new Unavailable();
        }
    }

    private static Answer rejected(String reference, String code) {
        return Answer.rejected(Optional.of(reference), code, raw("REJECTED", reference, code));
    }

    /** The answer's bytes, canonical: what it is first. Kept verbatim like any provider's. */
    private static byte[] raw(String outcome, String reference, String code) {
        CanonicalJson.Obj answer = CanonicalJson.object().put("channel", CHANNEL).put("standing", STANDING).put("outcome", outcome);
        if (reference != null) {
            answer.put("providerReference", reference);
        }
        if (code != null) {
            answer.put("rejectionCode", code);
        }
        return CanonicalJson.text(answer).getBytes(StandardCharsets.UTF_8);
    }

    /** The simulation's own reference for a submission: derived from its id, so every instance agrees. Letters only, so it can never read as an instrument and fail the screen every provider's reference meets. */
    static String reference(RegulatorySubmissionId submission) {
        StringBuilder letters = new StringBuilder("SIM-REG-");
        for (char nibble : CanonicalJson.sha256(submission.value().toString()).substring(0, 16).toCharArray()) {
            letters.append((char) ('A' + Character.digit(nibble, 16)));
        }
        return letters.toString();
    }
}
