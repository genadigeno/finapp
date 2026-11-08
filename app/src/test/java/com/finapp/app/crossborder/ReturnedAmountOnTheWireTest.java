package com.finapp.app.crossborder;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.CrossBorderMetrics;
import com.finapp.crossborder.BeneficiaryId;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CorridorPolicyId;
import com.finapp.crossborder.OfferStore;
import com.finapp.crossborder.PaymentProgress;
import com.finapp.crossborder.PaymentStore;
import com.finapp.fx.ConversionParticipants;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.OutboundCreditId;
import com.finapp.payments.OutboundCreditReturnStore;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.SchemeExecutionClaimStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.reconciliation.ResolvedCorridorReturns;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.ObjenesisStd;

/**
 * {@code crossborder.CrossBorderPaymentReturned} says what came back (`P15-TSK-027`'s review fix round, F2).
 *
 * <p>The catalogue reads a field's name and kind and cannot read its quantity, so this holds the
 * quantity: the real resolution and the real {@code PaymentProgress} over stores that answer one
 * credit, the event caught at the outbox. A person's resolution is the path where the two amounts
 * can differ, because the automatic applier refuses a return that is not exactly the credit.
 */
@DisplayName("the returned amount on the wire is what came back, not what was sent (P15-TSK-027)")
class ReturnedAmountOnTheWireTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");
    private static final CorridorKey CORRIDOR = new CorridorKey(EUR, USD, CountryCode.of("US"));

    private record Written(EventEnvelope envelope, Map<String, String> fields) {}

    @Test
    @DisplayName("scale 0: the yen amount, in integer minor units, with scale 0")
    void scaleZero() {
        Written written = resolve(Money.ofMinorUnits(150_000, JPY), Money.ofMinorUnits(150_000, JPY));

        assertThat(written.fields())
                .containsEntry("returnedMinor", "150000")
                .containsEntry("returnedCurrency", "JPY")
                .containsEntry("returnedScale", "0");
    }

    @Test
    @DisplayName("scale 3: the dinar amount, in integer minor units, with scale 3")
    void scaleThree() {
        Written written = resolve(Money.ofMinorUnits(12_345, BHD), Money.ofMinorUnits(12_345, BHD));

        assertThat(written.fields())
                .containsEntry("returnedMinor", "12345")
                .containsEntry("returnedCurrency", "BHD")
                .containsEntry("returnedScale", "3");
    }

    @Test
    @DisplayName("a partial return carries what came back, never what was sent")
    void aPartialReturn() {
        Written written = resolve(Money.ofMinorUnits(107_960, USD), Money.ofMinorUnits(100_000, USD));

        assertThat(written.fields())
                .containsEntry("returnedMinor", "100000")
                .containsEntry("returnedCurrency", "USD")
                .containsEntry("returnedScale", "2");
    }

    @Test
    @DisplayName("a return in another currency carries that currency and its own scale")
    void anotherCurrency() {
        Written written = resolve(Money.ofMinorUnits(107_960, USD), Money.ofMinorUnits(14_000_000, JPY));

        assertThat(written.fields())
                .containsEntry("returnedMinor", "14000000")
                .containsEntry("returnedCurrency", "JPY")
                .containsEntry("returnedScale", "0");
    }

    @Test
    @DisplayName("it is version 2 of the event, and the fields version 1 carried are all still there")
    void theVersionAndTheOldFields() {
        Written written = resolve(Money.ofMinorUnits(107_960, USD), Money.ofMinorUnits(107_960, USD));

        assertThat(written.envelope().eventType()).isEqualTo(PaymentProgress.RETURNED_EVENT);
        assertThat(written.envelope().eventVersion()).isEqualTo(2);
        assertThat(written.fields()).containsEntry("basis", "RESOLVED").containsKeys("payment", "status");
    }

    /** The approval of a parked return of {@code came}, for a credit that sent {@code sent}. */
    private static Written resolve(Money sent, Money came) {
        UUID paymentId = IDS.next();
        UUID customer = IDS.next();
        UUID wallet = IDS.next();
        EndToEndReference reference = new EndToEndReference("return-reference-1");
        OutboundCreditStore.Row credit = new OutboundCreditStore.Row(
                OutboundCreditId.of(IDS.next()), customer, paymentId, "dispatch", null, null, sent, sent,
                IDS.next(), reference, OutboundCreditStore.Status.COMPLETED, Optional.empty(), NOW, NOW,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        PaymentStore.Row payment = new PaymentStore.Row(paymentId, IDS.next(), BeneficiaryId.of(IDS.next()),
                IDS.next(), IDS.next(), CORRIDOR, "dispatch", IDS.next(), IDS.next(),
                IDS.next(), PaymentStore.Status.IN_TRANSIT, NOW);
        // A zero fee: the fee refund is another entry, and nothing here is about it.
        OfferStore.OfferRow offer = new OfferStore.OfferRow(IDS.next(), IDS.next(), payment.quote(),
                payment.owner(), payment.beneficiary(), CorridorPolicyId.of(IDS.next()), CORRIDOR, Money.zero(EUR),
                Money.ofMinorUnits(100_000, EUR), Money.ofMinorUnits(100_000, EUR), sent, 24, NOW);

        List<Written> written = new ArrayList<>();
        OutboxWriter<Connection> outbox =
                (unitOfWork, envelope, bytes, mediaType) -> written.add(new Written(envelope, EventPayload.fields(bytes)));
        PaymentProgress progress = new PaymentProgress(
                stub(PaymentStore.class, Map.of(
                        "lock", args -> Optional.of(payment),
                        "move", args -> true,
                        "appendEvent", args -> null)),
                stub(OfferStore.class, Map.of("offerOwned", args -> Optional.of(offer))),
                outbox,
                IDS);
        CorridorReturnResolutions resolutions = new CorridorReturnResolutions(
                stub(OutboundCreditStore.class, Map.of(
                        "byReference", args -> Optional.of(credit),
                        "lock", args -> Optional.of(credit))),
                stub(OutboundCreditReturnStore.class, Map.of(
                        "findByCredit", args -> Optional.empty(),
                        "insert", args -> true)),
                progress,
                stub(ConversionParticipants.class, Map.of(
                        "activeCustomer", args -> Optional.of(customer),
                        "wallet", args -> Optional.of(LedgerAccountId.of(wallet)))),
                new ChartOfAccounts<>(stub(LedgerAccountStore.class, Map.of())),
                new ObjenesisStd().newInstance(PostingService.class),
                stub(AuditWriter.class, Map.of("append", args -> null)),
                IDS,
                CLOCK,
                new SimpleMeterRegistry(),
                new CrossBorderMetrics(List.of(), connection -> Map.of(), () -> null, CLOCK, new SimpleMeterRegistry()),
                stub(SchemeExecutionClaimStore.class, Map.of()),
                List.of());

        ResolvedCorridorReturns.Judgement judgement = resolutions.record(
                stub(Connection.class, Map.of()),
                new ResolvedCorridorReturns.ParkedReturn(
                        Optional.of(reference.value()), Optional.empty(), came, wallet, Set.of()),
                IDS.next(),
                new Actor("approver-1", ActorType.EMPLOYEE),
                CorrelationId.of(IDS.next().toString()));

        assertThat(judgement).isEqualTo(ResolvedCorridorReturns.Judgement.RECORDED);
        assertThat(written).hasSize(1);
        return written.get(0);
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
