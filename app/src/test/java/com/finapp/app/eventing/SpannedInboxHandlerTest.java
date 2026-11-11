package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The inbox leg of a flow, as a span (`P15-TSK-023`, ADR-0124 decision 8): every handled event recorded as {@code inbox.handle} in the
 * correlation the receiver entered - the envelope's, caused by the event - so the asynchronous leg is found by the flow's own id; the
 * handler's identity (consumer name, topic, type) its own, no idempotence claimed by the wrapper, and its failure its own.
 */
@DisplayName("each handled event is a span in the envelope's correlation, and the handler is otherwise untouched (P15-TSK-023)")
class SpannedInboxHandlerTest {

    @Test
    @DisplayName("the handler runs inside an inbox.handle span started in the receiver's correlation; its name, topic and type are the handler's")
    @SuppressWarnings("try")
    void aHandledEventIsASpanOfItsFlow() {
        Recording spans = new Recording();
        Handler handler = new Handler(false);
        InboxEventHandler spanned = SpannedInboxHandler.of(handler, spans);
        assertThat(SpannedInboxHandler.of(spanned, spans)).as("wrapped once").isSameAs(spanned);
        assertThat(spanned.consumerName()).isEqualTo("payments.planted");
        assertThat(spanned.topic()).isEqualTo("finapp.payments");
        assertThat(spanned.eventType()).isEqualTo("payments.PaymentCaptured");
        assertThat(spanned.naturallyIdempotent()).as("the wrapper claims nothing: the replay door reads the handler beans").isFalse();

        Correlation envelope = new Correlation(CorrelationId.of("payment-flow-1"), CausationId.of("event-1"));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(envelope)) {
            spanned.handle(null, null);
        }
        assertThat(handler.handled).isEqualTo(1);
        assertThat(spans.names).containsExactly(SpannedInboxHandler.SPAN);
        assertThat(spans.seen).containsExactly(Optional.of(envelope));
        assertThat(spans.attributes).containsExactly(Map.of());
    }

    @Test
    @DisplayName("the handler's failure propagates unchanged, so the receiver rolls back and redelivers exactly as before")
    void aFailureIsTheHandlersOwn() {
        InboxEventHandler spanned = SpannedInboxHandler.of(new Handler(true), new Recording());
        assertThatThrownBy(() -> spanned.handle(null, null)).isInstanceOf(IllegalStateException.class).hasMessage("planted");
    }

    private static final class Handler implements InboxEventHandler {
        private final boolean fails;
        int handled;

        Handler(boolean fails) {
            this.fails = fails;
        }

        @Override
        public String consumerName() {
            return "payments.planted";
        }

        @Override
        public String topic() {
            return "finapp.payments";
        }

        @Override
        public String eventType() {
            return "payments.PaymentCaptured";
        }

        @Override
        public boolean naturallyIdempotent() {
            return true;
        }

        @Override
        public void handle(Connection unitOfWork, ReceivedEvent event) {
            if (fails) {
                throw new IllegalStateException("planted");
            }
            handled++;
        }
    }

    private static final class Recording implements Spans {
        final List<String> names = new ArrayList<>();
        final List<Map<String, String>> attributes = new ArrayList<>();
        final List<Optional<Correlation>> seen = new ArrayList<>();

        @Override
        public <T> T within(String name, Map<String, String> identifiers, Supplier<T> work) {
            names.add(name);
            attributes.add(identifiers);
            seen.add(CorrelationContext.current());
            return work.get();
        }
    }
}
