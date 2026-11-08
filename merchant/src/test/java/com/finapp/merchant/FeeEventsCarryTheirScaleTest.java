package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fee events say the scale of what they carry (`P15-TSK-027`'s review fix round, F2).
 *
 * <p>The catalogue reads that the field exists and is an integer. This reads its value, at the
 * scale of a currency where a copied constant would be wrong. The two methods are private and run
 * deep inside a capture and a refund, so they are reached by name: a renamed or reshaped one fails
 * here, loudly.
 */
@DisplayName("merchant's fee events carry the scale of their amounts (P15-TSK-027)")
class FeeEventsCarryTheirScaleTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    private static final Correlation CORRELATION = new Correlation(CorrelationId.of(IDS.next().toString()), null);

    private record Written(EventEnvelope envelope, Map<String, String> fields) {}

    private final List<Written> written = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private MerchantSettlement settlement() {
        OutboxWriter<Connection> outbox =
                (unitOfWork, envelope, bytes, mediaType) -> written.add(new Written(envelope, EventPayload.fields(bytes)));
        return new MerchantSettlement(
                stub(PaymentFeePinStore.class), stub(FeeScheduleStore.class), stub(LedgerAccountStore.class),
                new ChartOfAccounts<>(stub(LedgerAccountStore.class)), outbox, IDS);
    }

    private static PaymentFeePin pin(Money gross) {
        return new PaymentFeePin(IDS.next(), MerchantId.of(IDS.next()),
                FeeScheduleVersionId.of(IDS.next()), gross, NOW, "pinner");
    }

    @Test
    @DisplayName("FeeAssessed at scale 0 and at scale 3, version 2")
    void feeAssessed() throws Exception {
        assessed("JPY", 10_000, 300, "0");
        assessed("BHD", 10_000, 300, "3");
    }

    private void assessed(String currency, long gross, long fee, String scale) throws Exception {
        CurrencyCode code = CurrencyCode.of(currency);
        Money total = Money.ofMinorUnits(gross, code);
        FeeAssessment assessment = new FeeAssessment(total, Money.ofMinorUnits(fee, code),
                Money.ofMinorUnits(gross - fee, code), FeeScheduleVersionId.of(IDS.next()));

        invoke("announce", new Class<?>[] {Connection.class, PaymentFeePin.class, FeeAssessment.class, Correlation.class, Instant.class},
                stub(Connection.class), pin(total), assessment, CORRELATION, NOW);

        Written last = written.get(written.size() - 1);
        assertThat(last.envelope().eventType()).isEqualTo(MerchantSettlement.EVENT_TYPE);
        assertThat(last.envelope().eventVersion()).isEqualTo(2);
        assertThat(last.fields())
                .containsEntry("grossMinor", Long.toString(gross))
                .containsEntry("feeMinor", Long.toString(fee))
                .containsEntry("netMinor", Long.toString(gross - fee))
                .containsEntry("currency", currency)
                .containsEntry("scale", scale);
    }

    @Test
    @DisplayName("FeeReturned at scale 0 and at scale 3, version 2")
    void feeReturned() throws Exception {
        returned("JPY", 5_000, 150, "0");
        returned("BHD", 5_000, 150, "3");
    }

    private void returned(String currency, long refunded, long fee, String scale) throws Exception {
        CurrencyCode code = CurrencyCode.of(currency);

        invoke("announceReturn",
                new Class<?>[] {Connection.class, PaymentFeePin.class, Money.class, Money.class, Correlation.class, Instant.class},
                stub(Connection.class), pin(Money.ofMinorUnits(10_000, code)), Money.ofMinorUnits(fee, code),
                Money.ofMinorUnits(refunded, code), CORRELATION, NOW);

        Written last = written.get(written.size() - 1);
        assertThat(last.envelope().eventType()).isEqualTo(MerchantSettlement.FEE_RETURNED_EVENT_TYPE);
        assertThat(last.envelope().eventVersion()).isEqualTo(2);
        assertThat(last.fields())
                .containsEntry("refundedMinor", Long.toString(refunded))
                .containsEntry("feeReturnedMinor", Long.toString(fee))
                .containsEntry("currency", currency)
                .containsEntry("scale", scale);
    }

    private void invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = MerchantSettlement.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try {
            method.invoke(settlement(), arguments);
        } catch (InvocationTargetException failed) {
            throw new AssertionError(name + " threw", failed.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<?> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new AssertionError("this test did not expect " + method.getName());
        });
    }
}
