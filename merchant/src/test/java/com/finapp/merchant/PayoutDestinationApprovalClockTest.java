package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A payout destination's approval and its audit record read one clock (`P15-TSK-038`, the review's finding): the deadline is
 * pinned from the database's reading, so the record beside it is the row's instant, or that reading on a refusal.
 *
 * <p>The instance's clock here is a year away from every reading, so a record stamped from it cannot pass by agreeing.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("a payout destination's approval is recorded on the database's clock (P15-TSK-038)")
class PayoutDestinationApprovalClockTest {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");
    private static final Clock A_YEAR_AWAY = Clock.fixed(T0.plus(Duration.ofDays(365)), ZoneOffset.UTC);
    private static final Duration COOLING_OFF = Duration.ofHours(72);
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(T0, ZoneOffset.UTC), new SecureRandom());
    private static final MerchantId MERCHANT = MerchantId.next(IDS);
    private static final String PROPOSER = UUID.randomUUID().toString();
    private static final String APPROVER = UUID.randomUUID().toString();

    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<PayoutDestination> written = new ArrayList<>();
    private PayoutDestination stored;
    private Instant databaseClock;

    @Test
    @DisplayName("the approved record is the row's approved_at, the reading the deadline ran from, never this instance's clock")
    void theApprovalsRecordIsTheRowsInstant() {
        stored = proposedAt(T0.minusSeconds(60));
        databaseClock = T0;

        PayoutDestinations.Approval outcome = approveAs(APPROVER);

        assertThat(outcome).isInstanceOf(PayoutDestinations.Approved.class);
        PayoutDestination approved = written.get(0);
        assertThat(approved.approvedAt()).contains(T0);
        assertThat(approved.coolingOffUntil()).contains(T0.plus(COOLING_OFF));
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(MerchantAuditAction.PAYOUT_DESTINATION_APPROVED);
            assertThat(record.occurredAt()).as("no second clock in one operation").isEqualTo(T0);
        });
    }

    @Test
    @DisplayName("under a proposer's fast clock the record is the floored approved_at, the row's own, and the deadline still the reading's")
    void aFlooredApprovalIsRecordedAsTheRowHoldsIt() {
        Instant aheadOfTheDatabase = T0.plusSeconds(5);
        stored = proposedAt(aheadOfTheDatabase);
        databaseClock = T0;

        approveAs(APPROVER);

        PayoutDestination approved = written.get(0);
        assertThat(approved.approvedAt()).as("floored at the proposal").contains(aheadOfTheDatabase);
        assertThat(approved.coolingOffUntil()).as("the control runs from the reading").contains(T0.plus(COOLING_OFF));
        assertThat(audited).singleElement().satisfies(
                record -> assertThat(record.occurredAt()).as("the record is what the row holds").isEqualTo(aheadOfTheDatabase));
    }

    @Test
    @DisplayName("a refused self-approval is recorded at the reading its decision used, not this instance's clock")
    void aRefusedSelfApprovalIsRecordedAtTheReading() {
        stored = proposedAt(T0.minusSeconds(60));
        databaseClock = T0;

        PayoutDestinations.Approval outcome = approveAs(PROPOSER);

        assertThat(outcome).isInstanceOf(PayoutDestinations.SelfApprovalRefused.class);
        assertThat(written).as("nothing moved").isEmpty();
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(MerchantAuditAction.PAYOUT_DESTINATION_APPROVAL_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(record.occurredAt()).isEqualTo(T0);
        });
    }

    // -----------------------------------------------------------------

    private PayoutDestinations.Approval approveAs(String actorId) {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
                SecurityContext.Scope actor = SecurityContext.enter(new Actor(actorId, ActorType.CUSTOMER))) {
            return destinations().approve(proxy(Connection.class, (method, arguments) -> null), MERCHANT, stored.id(), "verified");
        }
    }

    private PayoutDestinations destinations() {
        return new PayoutDestinations(
                store(),
                proxy(MerchantStore.class, (method, arguments) -> {
                    throw new AssertionError("an approval reads no merchant: " + method);
                }),
                new IdempotentExecutor(
                        proxy(IdempotencyRecordStore.class, (method, arguments) -> {
                            throw new AssertionError("an approval claims no key: " + method);
                        }),
                        A_YEAR_AWAY,
                        Duration.ofDays(1),
                        Duration.ofMinutes(1)),
                audit(),
                IDS,
                A_YEAR_AWAY,
                COOLING_OFF);
    }

    private static PayoutDestination proposedAt(Instant instant) {
        return PayoutDestination.propose(
                IDS, Clock.fixed(instant, ZoneOffset.UTC), MERCHANT,
                new PayoutDestinationReference(Sensitive.of("pdr_clock")), "3000", PROPOSER, "the merchant's new account");
    }

    private PayoutDestinationStore<Connection> store() {
        return proxy(PayoutDestinationStore.class, (method, arguments) -> switch (method) {
            case "findForUpdate" -> Optional.of(stored);
            case "databaseNow" -> databaseClock;
            case "transition" -> {
                written.add((PayoutDestination) arguments[2]);
                yield true;
            }
            default -> throw new AssertionError("an approval has no business with " + method);
        });
    }

    private AuditWriter<Connection> audit() {
        return proxy(AuditWriter.class, (method, arguments) -> {
            audited.add((AuditRecord) arguments[1]);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?> type, BiFunction<String, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (self, method, arguments) -> answer.apply(method.getName(), arguments));
    }
}
