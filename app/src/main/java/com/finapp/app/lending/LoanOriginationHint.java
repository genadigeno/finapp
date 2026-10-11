package com.finapp.app.lending;

import com.finapp.lending.LoanOrigination;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import java.sql.Connection;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Credit's decision events as HINTS to the origination sweep (`P11-TSK-011`; PHASE_11_PLAN.md section 10, ADR-0087 as
 * amended; {@code INV-EVT-04}): {@code credit.CreditDecisionRecorded} and {@code credit.CreditDecisionRequestClosed},
 * one handler instance each. A hint makes the application due now - its permit, and nothing else - so the sweep takes its
 * step on the next tick instead of after its permit lapses. Lending consumes no event for correctness: the step reads
 * credit's request and decision authoritatively ({@code LoanCreditDecisions.decisionFor}), so a hint delivered twice,
 * late, out of order, forged or never changes nothing the read would not.
 *
 * <p><strong>The payload is never read</strong> - the envelope's metadata-only principle (`P2-TSK-007`): the aggregate
 * id is the decision (resolved to its request through credit's own read) or the request. So version 1 of
 * {@code CreditDecisionRecorded} - whose joined {@code reasonCodes} version 2 replaced - is HANDLED explicitly and
 * identically to version 2: no reason, figure or outcome of either is read (ADR-0087 point 9 as amended: refuse version 1
 * or handle it explicitly). A version this build does not know is refused - acknowledged with a log line and nothing
 * written, never a stalled partition: the sweep's own read still converges within one permit.
 *
 * <p>No actor is entered: a permit names no actor and records nothing; the step that follows is the sweep's, under the
 * system actor there.
 */
@Slf4j
public final class LoanOriginationHint implements InboxEventHandler {

    /** {@code EVENT_ARCHITECTURE.md}: one topic per producing module. */
    private static final String TOPIC = "finapp.credit";

    /** Which of credit's two events this instance takes. */
    public enum Kind {
        /** {@code credit.CreditDecisionRecorded} - the aggregate is the decision; versions 1 and 2. */
        DECISION_RECORDED("credit.CreditDecisionRecorded", "lending.originationDecisionHint", 2),
        /** {@code credit.CreditDecisionRequestClosed} - the aggregate is the request; version 1. */
        REQUEST_CLOSED("credit.CreditDecisionRequestClosed", "lending.originationClosureHint", 1);

        private final String eventType;
        private final String consumerName;
        private final int newestVersion;

        Kind(String eventType, String consumerName, int newestVersion) {
            this.eventType = eventType;
            this.consumerName = consumerName;
            this.newestVersion = newestVersion;
        }
    }

    private final LoanOrigination origination;
    private final Kind kind;

    public LoanOriginationHint(LoanOrigination origination, Kind kind) {
        this.origination = Objects.requireNonNull(origination, "origination");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    @Override
    public String consumerName() {
        return kind.consumerName;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String eventType() {
        return kind.eventType;
    }

    @Override
    public void handle(Connection unitOfWork, ReceivedEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.eventVersion() < 1 || event.eventVersion() > kind.newestVersion) {
            log.warn("A {} hint of an unknown event version was not acted on; the origination sweep converges"
                    + " without it", kind.eventType);
            return;
        }
        switch (kind) {
            case DECISION_RECORDED -> origination.hintDecision(unitOfWork, event.aggregateId());
            case REQUEST_CLOSED -> origination.hintRequest(unitOfWork, event.aggregateId());
        }
    }
}
