package com.finapp.app.eventing;

import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.telemetry.Spans;
import java.sql.Connection;
import java.util.Map;
import java.util.Objects;

/**
 * An inbox handler recorded as the flow's asynchronous leg (`P15-TSK-023`, ADR-0124 decision 8): the receiver has already entered
 * the envelope's correlation and causation when it calls {@link #handle}, so the span the platform's processor stamps joins the
 * flow that published the event, across Kafka. No attribute: the span's name and the flow's ids are the whole of what it says.
 * Its consumer name, topic and type are the handler's own, unchanged: they decide its group and its dedupe key. It claims no
 * natural idempotency, and need not carry the handler's: only the receiver holds this wrapper, and the replay door reads the
 * handler beans themselves (`EventingBeans`), where a claim is a reviewed change (`DeadLetterWiringTest`).
 */
final class SpannedInboxHandler implements InboxEventHandler {

    /** The span every handled event is recorded under. */
    static final String SPAN = "inbox.handle";

    private final InboxEventHandler handler;
    private final Spans spans;

    private SpannedInboxHandler(InboxEventHandler handler, Spans spans) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.spans = Objects.requireNonNull(spans, "spans");
    }

    static InboxEventHandler of(InboxEventHandler handler, Spans spans) {
        return handler instanceof SpannedInboxHandler ? handler : new SpannedInboxHandler(handler, spans);
    }

    @Override
    public String consumerName() {
        return handler.consumerName();
    }

    @Override
    public String topic() {
        return handler.topic();
    }

    @Override
    public String eventType() {
        return handler.eventType();
    }

    @Override
    public void handle(Connection unitOfWork, ReceivedEvent event) {
        spans.within(SPAN, Map.of(), () -> {
            handler.handle(unitOfWork, event);
            return null;
        });
    }
}
