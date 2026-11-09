package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentsAuditAction;
import com.finapp.payments.RailId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.SpringObjenesis;

/**
 * The declined-capture redirect writes the void dispatch's own record (`P15-TSK-039`; Phase 7 residue #35), as the
 * operator's and the customer's voids do. On PostgreSQL: {@code PaymentVoidDatabaseTest}, owed.
 */
@DisplayName("a redirected void writes its dispatch record, and a lost redirect writes none (P15-TSK-039)")
class RedirectVoidDispatchRecordTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("payments.outcomes", ActorType.SYSTEM);

    private final PaymentIntentId intent = PaymentIntentId.next(IDS);
    private final PaymentAttemptId attempt = PaymentAttemptId.next(IDS);
    private final List<AuditRecord> written = new ArrayList<>();

    @Test
    @DisplayName("the acting redirect records PaymentVoidDispatched beside its outcome, naming the void's reference")
    void theActingRedirectRecordsTheDispatch() throws Exception {
        redirect(true);

        assertThat(written.stream().filter(record -> record.operation() == PaymentsAuditAction.PAYMENT_VOID_DISPATCHED)).singleElement().satisfies(record -> {
            assertThat(record.actor()).isEqualTo(PLATFORM);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetId()).isEqualTo(intent.value().toString());
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> {
                assertThat(summary).contains("attempt=" + attempt);
                assertThat(summary).contains("reference=void-");
                assertThat(summary).contains("rail=card");
                assertThat(summary).contains("cause=declined-capture-redirect");
            });
        });
    }

    @Test
    @DisplayName("the redirect that lost the conditional dispatched nothing and records nothing")
    void theLoserRecordsNothing() throws Exception {
        redirect(false);

        assertThat(written).noneMatch(record -> record.operation() == PaymentsAuditAction.PAYMENT_VOID_DISPATCHED);
    }

    private void redirect(boolean acting) throws Exception {
        PaymentOutcomes outcomes = new SpringObjenesis().newInstance(PaymentOutcomes.class);
        set(outcomes, "audit", (AuditWriter<Connection>) (uow, record) -> written.add(record));
        set(outcomes, "ids", IDS);
        set(outcomes, "attempts", attempts(acting));
        set(outcomes, "intents", intents());
        set(outcomes, "observer", unanswering(PaymentOutcomes.class.getDeclaredField("observer").getType()));
        Method redirect = PaymentOutcomes.class.getDeclaredMethod("redirectToVoid", Connection.class, PaymentIntentId.class,
                PaymentAttemptId.class, PaymentAttemptStatus.class, String.class, Actor.class, Correlation.class, Instant.class);
        redirect.setAccessible(true);
        redirect.invoke(outcomes, null, intent, attempt, PaymentAttemptStatus.CAPTURE_DISPATCHED, "DECLINED", PLATFORM,
                Correlation.startingWith(CorrelationId.of("the-redirect")), Instant.now(CLOCK));
    }

    @SuppressWarnings("unchecked")
    private static PaymentIntentStore<Connection> intents() {
        com.finapp.payments.PaymentIntent row = new SpringObjenesis().newInstance(com.finapp.payments.PaymentIntent.class);
        return (PaymentIntentStore<Connection>) Proxy.newProxyInstance(PaymentIntentStore.class.getClassLoader(),
                new Class<?>[] {PaymentIntentStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.of(row);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = PaymentOutcomes.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private PaymentAttemptStore<Connection> attempts(boolean acting) {
        PaymentAttempt row = new SpringObjenesis().newInstance(PaymentAttempt.class);
        setRail(row);
        return (PaymentAttemptStore<Connection>) Proxy.newProxyInstance(PaymentAttemptStore.class.getClassLoader(),
                new Class<?>[] {PaymentAttemptStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "dispatchVoid" -> acting;
                    case "recordTransition" -> null;
                    case "findById" -> Optional.of(row);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void setRail(PaymentAttempt row) {
        try {
            Field rail = PaymentAttempt.class.getDeclaredField("rail");
            rail.setAccessible(true);
            rail.set(row, RailId.of("card"));
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError(unreachable);
        }
    }

    private static Object unanswering(Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Class<?> returned = method.getReturnType();
            if (returned == boolean.class) {
                return false;
            }
            return returned.isPrimitive() && returned != void.class ? 0 : null;
        });
    }
}
