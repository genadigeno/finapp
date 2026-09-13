package com.finapp.kyc;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The platform's first production consumer (`P2-TSK-007`): a registration opens a KYC case.
 *
 * <p>Reacts to {@code party.CustomerOpened} — whose <strong>aggregate is the Customer</strong>,
 * so the reactive key is {@link ReceivedEvent#aggregateId()} and the payload goes unread: the
 * envelope's metadata-only principle ({@code P0-TSK-018}) paying off at the first real consumer.
 * (The backlog named the event {@code party.CustomerRegistered}; that is the <em>audit
 * action's</em> code — the naming drift is corrected here rather than propagated.)
 *
 * <h2>Created announces; converged is silent</h2>
 *
 * <p>{@code openOrConverge} makes a duplicate delivery, a redelivery, and a race against the
 * customer's own {@code POST /v1/me/kyc} (when it exists) all land on the same answer: one case.
 * Only the call that actually <em>created</em> the case writes the {@code kyc.CaseOpened} audit
 * record and publishes {@code kyc.KycCaseOpened} — the fact happened once, so it is recorded and
 * announced once, by whoever won.
 *
 * <h2>Everything commits together</h2>
 *
 * <p>The case row, the audit record, the outbox event and the inbox dedupe record all ride the
 * unit of work the shell opened ({@code INV-EVT-01}, {@code INV-IDEM-04}): a crash anywhere
 * rolls back all four, and the redelivery retries into a clean slate.
 *
 * <h2>The actor is the platform, and that is the honest answer</h2>
 *
 * <p>A consumer has no authenticated caller: the person whose registration caused this is not
 * <em>present</em>, and the registration's own audit records already name that flow's actor.
 * Opening the case is the platform's own policy act — which makes this an enumerated
 * {@code enterSystem()} site ({@code SystemActorCallSitesAreEnumeratedTest}), with the
 * justification in {@code SECURITY_ARCHITECTURE.md} §Who is acting. What ties the record to the
 * person is the <strong>correlation</strong> — the producing flow's, entered by the shell — and
 * the target, which names the customer.
 *
 * <h2>The open is consent-gated, and a refusal is a skip, never a stall (`P2-TSK-019`)</h2>
 *
 * <p>Opening a case is the first consent-gated capability ({@code INV-CNS-01}), and this
 * consumer is one of its doors — a gate with an ungated second door is not a gate. A freshly
 * registered person <em>cannot</em> hold a grant yet (a grant needs a session, a session needs
 * the registration this event announces), so in the ordinary flow this consumer now
 * <strong>skips</strong>: the case opens when the consented person acts
 * ({@code POST /v1/me/kyc}, `P2-TSK-006`), or eagerly here when the party already holds a basis
 * — a re-onboarded party's grant survives, because consent is the party's fact and outlives any
 * one customer relationship.
 *
 * <p>The refusal is <strong>acknowledged, not thrown</strong>: it is the platform's own correct
 * decision, and stalling the partition over it would be the poison-record treatment applied to
 * a non-defect. It is logged with the flow's correlation and writes nothing — no case, no audit
 * record, no announcement — because nothing happened, and the trail of why is the registration's
 * own records plus the absence of a case.
 */
public final class CustomerOpenedOpensCase implements InboxEventHandler {

    /** {@code EVENT_ARCHITECTURE.md}: one topic per producing module. */
    private static final String TOPIC = "finapp.party";

    private static final String EVENT_TYPE = "party.CustomerOpened";

    private static final String PRODUCER = "kyc";

    private static final int EVENT_VERSION = 1;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CustomerOpenedOpensCase.class);

    private final KycCaseStore<Connection> cases;
    private final CaseKindResolver<Connection> kinds;
    private final CaseOpeningConsent<Connection> consent;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;
    private final OutboxWriter<Connection> outboxWriter;

    public CustomerOpenedOpensCase(
            KycCaseStore<Connection> cases,
            CaseKindResolver<Connection> kinds,
            CaseOpeningConsent<Connection> consent,
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        this.cases = Objects.requireNonNull(cases, "cases must not be null");
        this.kinds = Objects.requireNonNull(kinds, "kinds must not be null");
        this.consent = Objects.requireNonNull(consent, "consent must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must not be null");
    }

    @Override
    public String consumerName() {
        return "kyc.caseOpening";
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    @SuppressWarnings("try") // the actor scope is used for its close side effect
    public void handle(Connection unitOfWork, ReceivedEvent event) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            // The gate, before anything else (P2-TSK-019, INV-CNS-01): opening a case is
            // consent-gated, and this door asks like every other. The read shares this unit
            // of work, so the decision and the open it authorises are one snapshot - and a
            // withdrawal committed anywhere refuses the very next delivery on any instance
            // (INV-CNS-03). The refusal is a quiet domain outcome: acknowledged, logged,
            // nothing written - the case opens when the consented person acts.
            if (!consent.permitsOpening(unitOfWork, event.aggregateId())) {
                log.info(
                        "A customer's case was not opened: no current consent basis for the"
                                + " party behind customer {}. The case opens when the person"
                                + " grants and acts (INV-CNS-01).",
                        event.aggregateId());
                return;
            }
            // The kind is party's fact (an ORGANISATION opens a KYB case), asked through a
            // port on the same unit of work (P2-TSK-015) - the payload stays unread, and the
            // metadata-only stance holds.
            KycCaseKind kind = kinds.kindFor(unitOfWork, event.aggregateId());
            KycCaseStore.Opening opening =
                    cases.openOrConverge(
                            unitOfWork, KycCase.open(ids, clock, event.aggregateId(), kind));
            if (!opening.created()) {
                // The case already exists - opened by an earlier delivery, or by the customer
                // themselves. Nothing happened here, so nothing is recorded or announced:
                // a converged open that audited itself would put two opening records on one
                // case, which is the ambiguity INV-KYC-03 exists to prevent.
                return;
            }
            KycCase kycCase = opening.kycCase();
            audit(unitOfWork, kycCase);
            announce(unitOfWork, kycCase);
        }
    }

    private void audit(Connection unitOfWork, KycCase kycCase) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        KycAuditAction.KYC_CASE_OPENED,
                        "Customer",
                        kycCase.customerId().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        currentCorrelation().correlationId(),
                        Optional.of(
                                "case=" + kycCase.id() + " policyVersion=" + kycCase.policyVersion())));
    }

    private void announce(Connection unitOfWork, KycCase kycCase) {
        Correlation correlation = currentCorrelation();
        outboxWriter.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        "kyc.KycCaseOpened",
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        kycCase.id(),
                        "KycCase",
                        kycCase.openedAt(),
                        PRODUCER,
                        correlation.correlationId(),
                        // The consumed event is this one's cause, and the shell put exactly that
                        // into scope - inheriting the PARENT's causation instead would flatten
                        // the causal tree (Correlation.causing's documented trap).
                        correlation
                                .cause()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a consumed event always has a cause in"
                                                                + " scope; the shell entered it"))),
                EventPayload.of()
                        .with("caseId", kycCase.id().value().toString())
                        .with("customerId", kycCase.customerId().toString())
                        .with("policyVersion", kycCase.policyVersion().value())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static Correlation currentCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "no correlation in scope; the shell enters one from the"
                                                + " message envelope before consuming"));
    }
}
