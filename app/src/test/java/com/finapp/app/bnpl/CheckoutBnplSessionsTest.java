package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.checkout.CheckoutSessions;
import com.finapp.app.telemetry.CheckoutMeters;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.checkout.CheckoutAuditAction;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.checkout.CheckoutStorageException;
import com.finapp.checkout.Order;
import com.finapp.checkout.OrderStore;
import com.finapp.ledger.JournalEntryId;
import com.finapp.merchant.FeeScheduleStore;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantStore;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * bnpl's checkout port over checkout's own judges, hermetic (`P12-TSK-007`; PHASE_12_PLAN.md sections 5.2a, 7.2 B3 and
 * 7.3 (c); {@code INV-BNPL-03}, {@code INV-MER-06}): {@link CheckoutBnplSessions} over the real {@link CheckoutSessions},
 * the real {@link CheckoutSession} aggregate and the real {@link CheckoutMeters}, the session and order stores in memory
 * - the row lock taken before the database clock is read, the deadline judged on that clock and never on the instance's,
 * a refusal writing nothing, one completion one order with the financing entry and {@code completedAs = FINANCED}, the
 * token kept wrapped, and an unknown session failing bnpl's transaction. The same properties against a real schema and
 * real races are {@code CheckoutFinancingDatabaseTest}'s.
 */
@DisplayName("bnpl's checkout port: B3 judged under the lock on the database clock, completed once (P12-TSK-007)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class CheckoutBnplSessionsTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    /** The instance's clock: fixed at NOW, so a judgement made on it is visible as such. */
    private static final Clock INSTANCE = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(INSTANCE, new SecureRandom());
    private static final Money GROSS = Money.ofMinorUnits(120_00L, CurrencyCode.of("EUR"));
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(30));

    private final List<String> events = new ArrayList<>();
    private final Map<CheckoutSessionId, CheckoutSession> rows = new HashMap<>();
    private final Map<CheckoutSessionId, Order> orders = new HashMap<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<String> published = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Connection bnplsConnection = connection("bnpl's transaction");

    /** The database's clock, as the store reports it; a test moves it to cross a deadline. */
    private Instant databaseClock = NOW;
    /** When set, the clock the conditional write judges at - a later instant than the read's. */
    private Instant writeClock;

    private CheckoutSessionToken token;
    private CheckoutSession session;
    private BnplCheckoutSessions port;
    private SecurityContext.Scope actor;

    @BeforeEach
    void anOpenSession() {
        actor = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
        token = CheckoutSessionToken.issue(new SecureRandom());
        session = CheckoutSession.open(
                IDS, INSTANCE, UUID.randomUUID(), GROSS, "A bicycle", UUID.randomUUID(), token, DEADLINE);
        rows.put(session.id(), session);
        port = new CheckoutBnplSessions(checkoutSessions());
    }

    @AfterEach
    void leave() {
        actor.close();
    }

    // ================================================================== B3

    @Test
    @DisplayName("the session row is locked FOR UPDATE on bnpl's connection BEFORE the database clock is read - the"
            + " judgement is the lock's, never a waiting statement's")
    void theRowIsLockedBeforeTheClockIsRead() {
        port.lockForPurchase(bnplsConnection, session.id().value());
        assertThat(events).containsExactly(
                "lock:" + session.id() + "@bnpl's transaction", "clock@bnpl's transaction");
    }

    @Test
    @DisplayName("an OPEN session before its deadline is Payable, and bnpl learns the merchant, the gross and the"
            + " deadline - nothing written")
    void anOpenSessionIsPayable() {
        BnplCheckoutSessions.Lock judged = port.lockForPurchase(bnplsConnection, session.id().value());
        assertThat(judged).isInstanceOfSatisfying(BnplCheckoutSessions.Payable.class, payable -> {
            assertThat(payable.session().sessionRef()).isEqualTo(session.id().value());
            assertThat(payable.session().merchantRef()).isEqualTo(session.merchantRef());
            assertThat(payable.session().gross()).isEqualTo(GROSS);
            assertThat(payable.session().expiresAt()).isEqualTo(DEADLINE);
            assertThat(payable.session().open()).isTrue();
            assertThat(payable.session().expired()).isFalse();
        });
        assertThat(events).noneMatch(event -> event.startsWith("write") || event.startsWith("order"));
    }

    @Test
    @DisplayName("a session NOT OPEN - a card or wallet confirmation won first - is NotPayable at once, nothing written")
    void aSessionNotOpenIsRefusedAtOnce() {
        rows.put(session.id(), session.confirm(INSTANCE, UUID.randomUUID()));
        assertThat(port.lockForPurchase(bnplsConnection, session.id().value()))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN));
        assertThat(port.completeByFinancing(bnplsConnection, session.id().value(), purchase(), entry(), correlation()))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN));
        assertThat(orders).isEmpty();
        assertThat(published).isEmpty();
        assertThat(audits).isEmpty();
        assertThat(rows.get(session.id()).status()).isEqualTo(CheckoutSessionStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("the deadline is judged on the DATABASE clock: OPEN by its row and early by the instance's clock, yet"
            + " past its deadline on the database's - NotPayable(EXPIRED); one microsecond before it, Payable")
    void theDeadlineIsTheDatabaseClocks() {
        databaseClock = DEADLINE;
        assertThat(port.lockForPurchase(bnplsConnection, session.id().value()))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.EXPIRED));
        assertThat(session.hasExpired(INSTANCE)).as("the instance's clock says the offer is alive").isFalse();

        databaseClock = DEADLINE.minusNanos(1_000);
        assertThat(port.lockForPurchase(bnplsConnection, session.id().value()))
                .isInstanceOf(BnplCheckoutSessions.Payable.class);
    }

    @Test
    @DisplayName("a session bnpl's own record names and checkout cannot find fails the transaction - never a refusal")
    void anUnknownSessionFailsTheTransaction() {
        UUID nobody = IDS.next();
        assertThatThrownBy(() -> port.lockForPurchase(bnplsConnection, nobody))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> port.completeByFinancing(bnplsConnection, nobody, purchase(), entry(), correlation()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ================================================================== the completion

    @Test
    @DisplayName("the completion: OPEN -> COMPLETED by financing, intent-less, the purchase its financing reference, ONE"
            + " order with the financing entry, announced FINANCED, audited and counted once")
    void theFinancingCompletesTheSessionAndBirthsOneOrder() {
        BnplPurchaseId purchase = purchase();
        JournalEntryId entry = entry();

        BnplCheckoutSessions.Completion completed =
                port.completeByFinancing(bnplsConnection, session.id().value(), purchase, entry, correlation());

        Order order = orders.get(session.id());
        assertThat(completed).isEqualTo(new BnplCheckoutSessions.Completed(order.id().value()));
        CheckoutSession after = rows.get(session.id());
        assertThat(after.status()).isEqualTo(CheckoutSessionStatus.COMPLETED);
        assertThat(after.financedByRef()).contains(purchase.value());
        assertThat(after.paymentIntentRef()).isEmpty();
        assertThat(order.capturedEntryRef()).as("the order references the financing entry").isEqualTo(entry.value());
        assertThat(order.amount()).isEqualTo(GROSS);
        assertThat(order.merchantRef()).isEqualTo(session.merchantRef());

        assertThat(published).singleElement().satisfies(payload -> assertThat(payload)
                .contains("checkout.OrderPaid|")
                .contains("\"completedAs\":\"FINANCED\"")
                .contains("\"capturedEntryId\":\"" + entry.value() + "\""));
        assertThat(audits).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(CheckoutAuditAction.ORDER_CREATED);
            assertThat(record.changeSummary().orElseThrow())
                    .contains("financedBy=" + purchase.value())
                    .contains("entry=" + entry.value())
                    .contains("completedAs=FINANCED")
                    // The gross never - as a decimal or in minor units. Not the bare digits "120": the UUIDv7
                    // identifiers in the summary can carry them (P12-TSK-011's gate found this order-dependent).
                    .doesNotContain("120.00").doesNotContain("12000");
        });
        assertThat(registry.get("finapp.checkout.session").tag("outcome", "financed").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("finapp.checkout.session").tag("outcome", "completed").counter().count())
                .as("a financing is not a capture").isZero();
        assertThat(registry.get("finapp.checkout.conversion.age").tag("outcome", "financed").timer().count())
                .isEqualTo(1L);
        assertThat(events).filteredOn(event -> event.startsWith("lock") || event.startsWith("clock"))
                .first().as("locked before anything is read or written").asString().startsWith("lock:");
        assertThat(events).allMatch(event -> event.endsWith("@bnpl's transaction"));
    }

    @Test
    @DisplayName("ten completions of one session: one order, one announcement - nine NotPayable, nothing more written")
    void tenCompletionsOneOrder() {
        List<BnplCheckoutSessions.Completion> answers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            answers.add(port.completeByFinancing(
                    bnplsConnection, session.id().value(), purchase(), entry(), correlation()));
        }
        assertThat(answers).filteredOn(BnplCheckoutSessions.Completed.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(BnplCheckoutSessions.NotPayable.class::isInstance)
                .hasSize(9)
                .allMatch(answer -> answer.equals(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN)));
        assertThat(orders).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(audits).hasSize(1);
    }

    @Test
    @DisplayName("the WRITE re-judges the deadline: a purchase whose own work crossed it is NotPayable(EXPIRED) and"
            + " bears no order - the caller rolls back")
    void theWriteRejudgesTheDeadline() {
        writeClock = DEADLINE;
        assertThat(port.completeByFinancing(bnplsConnection, session.id().value(), purchase(), entry(), correlation()))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.EXPIRED));
        assertThat(orders).isEmpty();
        assertThat(published).isEmpty();
        assertThat(rows.get(session.id()).status()).isEqualTo(CheckoutSessionStatus.OPEN);
    }

    // ================================================================== the read

    @Test
    @DisplayName("the read resolves the session by its token, which stays WRAPPED; an unknown token is empty")
    void theReadResolvesByTheWrappedToken() {
        Optional<BnplCheckoutSessions.Session> found = port.read(bnplsConnection, token.presentedOnce());
        assertThat(found).hasValueSatisfying(view -> {
            assertThat(view.sessionRef()).isEqualTo(session.id().value());
            assertThat(view.open()).isTrue();
            assertThat(view.toString()).doesNotContain("120");
        });
        assertThat(port.read(bnplsConnection, Sensitive.of("not-a-token"))).isEmpty();
    }

    @Test
    @DisplayName("THE GATE'S FINDING: the read reports the deadline on the DATABASE clock, read after the row - the"
            + " instance's clock calls the offer alive, the database's calls it expired, and bnpl is told the database's")
    void theReadJudgesTheDeadlineOnTheDatabaseClock() {
        assertThat(port.read(bnplsConnection, token.presentedOnce()).orElseThrow().expired()).isFalse();
        databaseClock = DEADLINE;
        BnplCheckoutSessions.Session read = port.read(bnplsConnection, token.presentedOnce()).orElseThrow();
        assertThat(read.expired()).isTrue();
        assertThat(read.open()).as("still OPEN by its row - the sweeper has not arrived").isTrue();
        assertThat(session.hasExpired(INSTANCE)).isFalse();
        assertThat(events).endsWith("clock@bnpl's transaction");
    }

    // ================================================================== fixtures

    private CheckoutSessions checkoutSessions() {
        return new CheckoutSessions(
                sessionStore(),
                orderStore(),
                unusable(MerchantStore.class),
                new FeeSchedules(
                        unusable(FeeScheduleStore.class), unusable(MerchantStore.class), auditWriter(), IDS, INSTANCE),
                new IdempotentExecutor(
                        unusable(IdempotencyRecordStore.class), INSTANCE, Duration.ofDays(1), Duration.ofMinutes(1)),
                auditWriter(),
                outboxWriter(),
                IDS,
                INSTANCE,
                new SecureRandom(),
                new CheckoutMeters(registry));
    }

    @SuppressWarnings("unchecked")
    private CheckoutSessionStore<Connection> sessionStore() {
        return (CheckoutSessionStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CheckoutSessionStore.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByIdForUpdate" -> {
                            CheckoutSessionId id = (CheckoutSessionId) args[1];
                            events.add("lock:" + id + "@" + args[0]);
                            return Optional.ofNullable(rows.get(id));
                        }
                        case "databaseNow" -> {
                            events.add("clock@" + args[0]);
                            return databaseClock;
                        }
                        case "findByToken" -> {
                            CheckoutSessionToken presented = (CheckoutSessionToken) args[1];
                            return rows.values().stream()
                                    .filter(row -> row.authenticates(presented))
                                    .findFirst();
                        }
                        case "completeByFinancing" -> {
                            CheckoutSession before = (CheckoutSession) args[1];
                            CheckoutSession financed = (CheckoutSession) args[2];
                            events.add("write:" + before.id() + "@" + args[0]);
                            CheckoutSession current = rows.get(before.id());
                            Instant at = writeClock == null ? databaseClock : writeClock;
                            // The statement's own predicate: OPEN, no intent, no financing yet, before the deadline.
                            if (current.status() != CheckoutSessionStatus.OPEN
                                    || current.paymentIntentRef().isPresent()
                                    || current.financedByRef().isPresent()
                                    || !at.isBefore(current.expiresAt())) {
                                return false;
                            }
                            rows.put(before.id(), financed);
                            return true;
                        }
                        case "toString" -> {
                            return "session store";
                        }
                        default -> throw new AssertionError("unexpected session store call: " + method.getName());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private OrderStore<Connection> orderStore() {
        return (OrderStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OrderStore.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "insert" -> {
                            Order order = (Order) args[1];
                            events.add("order:" + order.sessionRef() + "@" + args[0]);
                            if (orders.putIfAbsent(order.sessionRef(), order) != null) {
                                throw new CheckoutStorageException("UNIQUE (session_ref)");
                            }
                            return null;
                        }
                        case "findBySession" -> {
                            return Optional.ofNullable(orders.get((CheckoutSessionId) args[1]));
                        }
                        case "toString" -> {
                            return "order store";
                        }
                        default -> throw new AssertionError("unexpected order store call: " + method.getName());
                    }
                });
    }

    private AuditWriter<Connection> auditWriter() {
        return (unitOfWork, record) -> {
            events.add("audit@" + unitOfWork);
            audits.add(record);
        };
    }

    private OutboxWriter<Connection> outboxWriter() {
        return (Connection unitOfWork, EventEnvelope envelope, byte[] payload, String mediaType) -> {
            events.add("event@" + unitOfWork);
            published.add(envelope.eventType() + "|" + new String(payload, StandardCharsets.UTF_8));
        };
    }

    private static BnplPurchaseId purchase() {
        return BnplPurchaseId.next(IDS);
    }

    private static JournalEntryId entry() {
        return JournalEntryId.of(IDS.next());
    }

    private static Correlation correlation() {
        return Correlation.startingWith(CorrelationId.generate(IDS));
    }

    @SuppressWarnings("unchecked")
    private static <T> T unusable(Class<?> type) {
        return (T) Proxy.newProxyInstance(
                CheckoutBnplSessionsTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return "unusable " + type.getSimpleName();
                    }
                    throw new AssertionError("the financing path never calls " + type.getSimpleName() + "."
                            + method.getName());
                });
    }

    private static Connection connection(String name) {
        return (Connection) Proxy.newProxyInstance(
                CheckoutBnplSessionsTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return name;
                    }
                    throw new AssertionError("the stores are in memory; no statement on " + name);
                });
    }
}
