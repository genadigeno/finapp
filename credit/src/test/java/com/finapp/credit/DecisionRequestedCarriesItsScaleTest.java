package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code credit.CreditDecisionRequested} says the scale of what is asked for (`P15-TSK-027`'s review
 * fix round, F2): the value, where the catalogue reads only that the field exists and is an integer.
 */
@DisplayName("credit's decision request carries the scale of its amount (P15-TSK-027)")
class DecisionRequestedCarriesItsScaleTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("the requested amount in minor units, beside its currency and its scale")
    void theScaleIsTheAmounts() {
        UUID party = IDS.next();
        CreditProduct product = CreditProduct.PERSONAL_LOAN;
        Money requested = Money.ofMinorUnits(1_000_000, product.currency());
        List<Map<String, String>> written = new ArrayList<>();
        List<EventEnvelope> envelopes = new ArrayList<>();
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, bytes, mediaType) -> {
            envelopes.add(envelope);
            written.add(EventPayload.fields(bytes));
        };
        CreditProfile profile = new CreditProfile(CreditProfileId.next(IDS), party, NOW);
        DecisionRequest.Application application = DecisionRequest.Application.submitted(
                product, requested, Optional.of(36), Optional.empty(), Optional.empty());
        DecisionRequests requests = new DecisionRequests(
                stub(DecisionRequestStore.class, Map.of(
                        "insert", args -> true,
                        "ownedBy", args -> Optional.of(new DecisionRequest((DecisionRequestId) args[1], party, profile.id(),
                                application, RequestOrigin.CUSTOMER_DOOR, DecisionRequestStatus.SUBMITTED, Optional.empty(),
                                Optional.empty(), NOW, NOW.plus(Duration.ofDays(30)), "correlation")))),
                stub(CreditProfiles.class, Map.of("ensure", args -> profile)),
                stub(CreditPolicyStore.class, Map.of(
                        "active", args -> Optional.of(CreditPolicyVersionId.next(IDS)),
                        "policy", args -> Optional.of(new CreditPolicyStore.PolicyVersion(
                                null, CreditPolicyV1.policy(product), Optional.empty(), Optional.empty())))),
                stub(CreditPartyStanding.class, Map.of("inGoodStanding", args -> true)),
                stub(CreditConsentGate.class, Map.of("permits", args -> true)),
                outbox,
                stub(AuditWriter.class, Map.of("append", args -> null)),
                IDS,
                CLOCK);

        requests.submit(
                stub(Connection.class, Map.of()),
                party,
                new DecisionRequests.Terms(product, requested, Optional.of(36), Optional.empty(), Optional.empty()),
                RequestOrigin.CUSTOMER_DOOR,
                new Actor(party.toString(), ActorType.CUSTOMER),
                CorrelationId.of(IDS.next().toString()));

        assertThat(envelopes).singleElement().extracting(EventEnvelope::eventType).isEqualTo(DecisionRequests.REQUESTED_EVENT);
        assertThat(written.get(0))
                .containsEntry("requestedMinor", "1000000")
                .containsEntry("currency", product.currency().code())
                .containsEntry("scale", Integer.toString(requested.scale()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<?> type, Map<String, Function<Object[], Object>> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Function<Object[], Object> answer = answers.get(method.getName());
            if (answer == null) {
                throw new AssertionError("this test did not expect " + method.getName());
            }
            return answer.apply(args);
        });
    }
}
