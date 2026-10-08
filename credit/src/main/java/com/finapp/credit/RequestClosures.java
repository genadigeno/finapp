package com.finapp.credit;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The platform's closure of an open decision request (`P10-TSK-015`, `-016`): {@code EXPIRED}, or {@code ABANDONED} with
 * its reason - the edge under the caller's row lock, its history row and {@code credit.CreditDecisionRequestClosed}, in
 * the caller's transaction. Shared by the progress step and the deciding transaction, so both close a request alike.
 */
@RequiredArgsConstructor
final class RequestClosures {

    @NonNull private final DecisionRequestStore requests;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    void close(
            Connection uow,
            DecisionRequest request,
            DecisionRequestStatus to,
            Optional<ClosureReason> reason,
            Actor platform,
            CorrelationId correlation) {
        if (!requests.transition(uow, request.id(), EnumSet.copyOf(DecisionRequestStatus.OPEN), to, reason, platform,
                Optional.empty())) {
            throw new IllegalStateException("the locked request moved under its own lock");
        }
        EventPayload payload = EventPayload.of()
                .with("decisionRequestId", request.id().value().toString())
                .with("status", to.name());
        if (reason.isPresent()) {
            payload = payload.with("closureReason", reason.get().name());
        }
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        DecisionRequests.CLOSED_EVENT,
                        DecisionRequests.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        request.id(),
                        DecisionRequests.AGGREGATE_TYPE,
                        clock.instant(),
                        CreditDataCollection.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
