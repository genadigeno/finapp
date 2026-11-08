package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.EvidenceKind;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.RefundId;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.InboxRecordStore;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The corridor door's Phase 9 residue (`P15-TSK-036`): an authenticated callback naming no credit was recorded by its
 * dedupe alone, so what the provider said was lost. It now rests as unattributed evidence, and still triggers no inquiry.
 */
@DisplayName("the corridor door keeps the evidence of a callback naming no credit (P15-TSK-036)")
class CorridorCallbackEvidenceTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private record Row(Optional<PaymentAttemptId> attempt, Optional<RefundId> refund, EvidenceKind kind, byte[] bytes) {}

    private final List<Row> retained = new ArrayList<>();
    private final WebhookSignature signature = new WebhookSignature(KEY, Duration.ofMinutes(5), CLOCK);
    private final CorrelationContext.Scope correlation =
            CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("corridor-" + UUID.randomUUID())));
    private final Connection connection = absent(Connection.class);

    @AfterEach
    void close() {
        correlation.close();
    }

    @SuppressWarnings("unchecked")
    private static <T> T absent(Class<?> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new AssertionError("this test must not reach " + type.getSimpleName() + "." + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private ProviderEvidenceStore<Connection> evidence() {
        return (ProviderEvidenceStore<Connection>) Proxy.newProxyInstance(
                ProviderEvidenceStore.class.getClassLoader(),
                new Class<?>[] {ProviderEvidenceStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("append")) {
                        retained.add(new Row(
                                (Optional<PaymentAttemptId>) args[1],
                                (Optional<RefundId>) args[2],
                                (EvidenceKind) args[3],
                                (byte[]) args[4]));
                        return null;
                    }
                    throw new AssertionError("only the subject-less append is expected, not " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private OutboundCreditStore credits(Optional<OutboundCreditStore.Row> row) {
        return (OutboundCreditStore) Proxy.newProxyInstance(
                OutboundCreditStore.class.getClassLoader(),
                new Class<?>[] {OutboundCreditStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("byReference")) {
                        return row;
                    }
                    throw new AssertionError("only the attribution read is expected, not " + method.getName());
                });
    }

    private CorridorCallbackService door(Optional<OutboundCreditStore.Row> row) {
        TransactionRunner runner = new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return work.apply(connection);
            }
        };
        InboxRecordStore<Connection> records = new InboxRecordStore<>() {
            @Override
            public RecordOutcome record(
                    Connection uow, InboxKey key, String messageType, CorrelationId correlationId, Instant processedAt,
                    Duration retention) {
                return RecordOutcome.RECORDED;
            }
        };
        return new CorridorCallbackService(
                signature, "corridor-a", credits(row), evidence(), new InboxConsumer<>(records, CLOCK, Duration.ofDays(1)),
                reference -> {
                    throw new AssertionError("an unattributed callback must trigger no inquiry");
                },
                runner, new ObjectMapper(), CLOCK);
    }

    private CorridorCallbackService.Delivered deliver(CorridorCallbackService door, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        return door.deliver(bytes, timestamp, signature.sign(timestamp, bytes));
    }

    @Test
    @DisplayName("an authenticated callback naming no credit is retained with no subject, and asks nothing")
    void aCallbackNamingNoCreditIsRetained() {
        CorridorCallbackService door = door(Optional.empty());

        CorridorCallbackService.Delivered delivered =
                deliver(door, "{\"eventId\":\"evt-1\",\"endToEndRef\":\"nosuchcredit1\",\"status\":\"accepted\"}");

        assertThat(delivered).isEqualTo(CorridorCallbackService.Delivered.UNMAPPABLE);
        assertThat(retained).singleElement().satisfies(row -> {
            assertThat(row.attempt()).isEmpty();
            assertThat(row.refund()).isEmpty();
            assertThat(row.kind()).isEqualTo(EvidenceKind.WEBHOOK);
        });
    }

    @Test
    @DisplayName("an authenticated callback with no usable event id is retained too: the bytes are the only record")
    void aCallbackWithNoUsableEventIdIsRetained() {
        CorridorCallbackService door = door(Optional.empty());

        CorridorCallbackService.Delivered delivered = deliver(door, "{\"endToEndRef\":\"whatever1\"}");

        assertThat(delivered).isEqualTo(CorridorCallbackService.Delivered.UNMAPPABLE);
        assertThat(retained).hasSize(1);
    }
}
