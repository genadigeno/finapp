package com.finapp.lending;

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
 * {@code lending.LoanOffered} says the scale of what is offered (`P15-TSK-027`'s review fix round,
 * F2): the value, where the catalogue reads only that the field exists and is an integer. A credit
 * line, because its offer needs no schedule.
 */
@DisplayName("lending's offer carries the scale of its amount (P15-TSK-027)")
class LoanOfferedCarriesItsScaleTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("the offered amount in minor units, beside its currency and its scale")
    void theScaleIsTheAmounts() {
        UUID party = IDS.next();
        LoanProduct product = LoanProduct.CREDIT_LINE;
        Money amount = Money.ofMinorUnits(12_345, LoanTermsFixtures.EUR);
        LoanTermsVersionId termsId = LoanTermsVersionId.of(IDS.next());
        LoanTerms terms = LoanTermsFixtures.creditLine("0.1899").build();
        LoanTermsStore.VersionRow row = new LoanTermsStore.VersionRow(termsId, product, 1, LoanTermsVersionStatus.ACTIVE,
                "proposer", NOW, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        LoanApplication application = new LoanApplication(LoanApplicationId.next(IDS), party, product,
                LoanApplicationStatus.AWAITING_DECISION, IDS.next(), termsId, 1, terms.allowedDays().get(0),
                RepaymentMethod.WALLET, PayoutPreference.WALLET, NOW, Optional.empty(),
                CorrelationId.of(IDS.next().toString()));
        List<Map<String, String>> written = new ArrayList<>();
        List<EventEnvelope> envelopes = new ArrayList<>();
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, bytes, mediaType) -> {
            envelopes.add(envelope);
            written.add(EventPayload.fields(bytes));
        };
        LoanTermsAdministration administration = new LoanTermsAdministration(
                stub(LoanTermsStore.class, Map.of(
                        "shareActive", args -> Optional.of(termsId),
                        "version", args -> Optional.of(new LoanTermsStore.LoanTermsVersion(row, terms)))),
                stub(AuditWriter.class, Map.of()),
                outbox,
                IDS,
                CLOCK,
                LoanTermsFixtures.BOUNDS);
        LoanOrigination origination = new LoanOrigination(
                stub(LoanApplicationStore.class, Map.of(
                        "lockClaimed", args -> Optional.of(application),
                        "transition", args -> true,
                        "appendEvent", args -> null)),
                stub(LoanOfferStore.class, Map.of(
                        "databaseNow", args -> NOW,
                        "insert", args -> new LoanOfferStore.Offered(NOW, NOW.plus(Duration.ofDays(14))),
                        "insertTerms", args -> null,
                        "appendEvent", args -> null)),
                administration,
                stub(LoanCreditDecisions.class, Map.of(
                        "decisionFor", args -> new LoanCreditDecisions.CreditOutcome.Approved(
                                IDS.next(), party, product, amount, Optional.empty(), NOW.plus(Duration.ofDays(30))))),
                outbox,
                IDS,
                CLOCK);

        LoanOrigination.Outcome outcome = origination.originate(
                stub(Connection.class, Map.of()), application.id(), new Actor("platform", ActorType.SYSTEM));

        assertThat(outcome.kind()).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(envelopes).singleElement().extracting(EventEnvelope::eventType).isEqualTo(LoanOrigination.OFFERED_EVENT);
        assertThat(written.get(0))
                .containsEntry("amountMinor", "12345")
                .containsEntry("currency", "EUR")
                .containsEntry("scale", Integer.toString(amount.scale()));
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
