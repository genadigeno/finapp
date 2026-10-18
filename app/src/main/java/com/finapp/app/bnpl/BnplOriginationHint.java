package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplOrigination;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import java.sql.Connection;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Credit's decision events and checkout's session expiry as HINTS to the BNPL origination sweep (`P12-TSK-011`;
 * PHASE_12_PLAN.md section 10, ADR-0087 as amended; {@code INV-EVT-04}; lending's {@code LoanOriginationHint}):
 * {@code credit.CreditDecisionRecorded}, {@code credit.CreditDecisionRequestClosed} and
 * {@code checkout.CheckoutSessionExpired}, one handler instance each, consumer group {@code finapp.bnpl}. A hint makes the
 * application due now - its permit, and nothing else - so the sweep takes its step on the next tick instead of after its
 * permit lapses. bnpl consumes no event for correctness: the step reads credit's request and decision and checkout's
 * session authoritatively, so a hint delivered twice, late, out of order, forged or never changes nothing the reads would
 * not.
 *
 * <p><strong>The payload is never read</strong> - the envelope's metadata-only principle (`P2-TSK-007`): the aggregate id
 * is the decision (resolved to its request through credit's own read), the request, or the session. Version 1 of
 * {@code CreditDecisionRecorded} is handled explicitly and identically to version 2 (ADR-0087 point 9 as amended); a
 * version this build does not know is acknowledged with a log line and nothing written, never a stalled partition.
 *
 * <p>No actor is entered: a permit names no actor and records nothing; the step that follows is the sweep's.
 */
@Slf4j
public final class BnplOriginationHint implements InboxEventHandler {

    /** Which event this instance takes. */
    public enum Kind {
        /** {@code credit.CreditDecisionRecorded} - the aggregate is the decision; versions 1 and 2. */
        DECISION_RECORDED("finapp.credit", "credit.CreditDecisionRecorded", "bnpl.originationDecisionHint", 2),
        /** {@code credit.CreditDecisionRequestClosed} - the aggregate is the request; version 1. */
        REQUEST_CLOSED("finapp.credit", "credit.CreditDecisionRequestClosed", "bnpl.originationClosureHint", 1),
        /** {@code checkout.CheckoutSessionExpired} - the aggregate is the session; version 1. */
        SESSION_EXPIRED("finapp.checkout", "checkout.CheckoutSessionExpired", "bnpl.originationSessionHint", 1);

        private final String topic;
        private final String eventType;
        private final String consumerName;
        private final int newestVersion;

        Kind(String topic, String eventType, String consumerName, int newestVersion) {
            this.topic = topic;
            this.eventType = eventType;
            this.consumerName = consumerName;
            this.newestVersion = newestVersion;
        }
    }

    private final BnplOrigination origination;
    private final Kind kind;

    public BnplOriginationHint(BnplOrigination origination, Kind kind) {
        this.origination = Objects.requireNonNull(origination, "origination");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    @Override
    public String consumerName() {
        return kind.consumerName;
    }

    @Override
    public String topic() {
        return kind.topic;
    }

    @Override
    public String eventType() {
        return kind.eventType;
    }

    @Override
    public void handle(Connection unitOfWork, ReceivedEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.eventVersion() < 1 || event.eventVersion() > kind.newestVersion) {
            log.warn("A {} hint of an unknown event version was not acted on; the BNPL origination sweep converges"
                    + " without it", kind.eventType);
            return;
        }
        switch (kind) {
            case DECISION_RECORDED -> origination.hintDecision(unitOfWork, event.aggregateId());
            case REQUEST_CLOSED -> origination.hintRequest(unitOfWork, event.aggregateId());
            case SESSION_EXPIRED -> origination.hintSession(unitOfWork, event.aggregateId());
        }
    }
}
