package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.fx.AvailabilityStore;
import com.finapp.fx.AvailabilitySubject;
import com.finapp.fx.FixedSide;
import com.finapp.fx.FxCollections;
import com.finapp.fx.FxTradeId;
import com.finapp.fx.PricingPurpose;
import com.finapp.fx.TradeStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FX operator reads' requests and views, without a database (`P17-TSK-037`): each filter the machine's own words - an enable
 * request's status {@code PROPOSED}, never the console's {@code PENDING} - a pair two postable currencies, each instant strict,
 * each collection's cursor its own; a trade's amounts at their stored scales. The reads, their order, the permissions and the
 * records are {@code FxCrossBorderReadsDatabaseTest}'s.
 */
@DisplayName("the FX operator reads' requests (P17-TSK-037)")
class FxReadDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("a status and a pair are the machine's own words; an instant is strict")
    void theFiltersAreTheDomainsWords() {
        assertThat(FxReadDesk.requestStatus("PROPOSED")).isEqualTo(AvailabilityStore.EnableRequestStatus.PROPOSED);
        assertThat(FxReadDesk.tradeStatus("REVERSED")).isEqualTo(TradeStatus.REVERSED);
        assertThat(FxReadDesk.pair("EUR-USD")).isEqualTo(AvailabilitySubject.pair("EUR-USD"));
        assertThat(FxReadDesk.instant("from", "2026-11-20T00:00:00Z")).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));

        assertRefused(() -> FxReadDesk.requestStatus("PENDING"));
        assertRefused(() -> FxReadDesk.requestStatus("proposed"));
        assertRefused(() -> FxReadDesk.tradeStatus("SETTLED"));
        assertRefused(() -> FxReadDesk.pair("EUR-EUR"));
        assertRefused(() -> FxReadDesk.pair("EUR/USD"));
        assertRefused(() -> FxReadDesk.pair("EUR-XAU"));
        assertRefused(() -> FxReadDesk.instant("from", null));
        assertRefused(() -> FxReadDesk.instant("from", "2026-11-20"));
    }

    @Test
    @DisplayName("each collection's cursor is its own, exact to the microsecond, and refused anywhere else")
    void eachCursorIsItsOwn() {
        Instant at = Instant.parse("2026-11-20T10:15:30.123456Z");
        UUID id = IDS.next();
        String requests = OperatorCollection.cursor(FxReadDesk.ENABLE_REQUESTS, at.toString(), id.toString());
        String trades = OperatorCollection.cursor(FxReadDesk.TRADES, at.toString(), id.toString());
        assertThat(FxReadDesk.requestCursor(requests)).contains(new FxCollections.EnableRequestCursor(at, id));
        assertThat(FxReadDesk.tradeCursor(trades)).contains(new FxCollections.TradeCursor(at, id));
        assertThat(FxReadDesk.requestCursor(null)).isEmpty();
        assertThat(FxReadDesk.tradeCursor(null)).isEmpty();

        assertRefused(() -> FxReadDesk.requestCursor(trades));
        assertRefused(() -> FxReadDesk.tradeCursor(requests));
        assertRefused(() -> FxReadDesk.tradeCursor("not-a-cursor"));
        assertRefused(() -> FxReadDesk.tradeCursor(OperatorCollection.cursor(FxReadDesk.TRADES, "today", id.toString())));
        assertRefused(() -> FxReadDesk.requestCursor(OperatorCollection.cursor(FxReadDesk.ENABLE_REQUESTS, at.toString(), "x")));
    }

    @Test
    @DisplayName("a trade reads its amounts at their stored scales and its pair as source-destination; a request its decision"
            + " only once taken")
    void theViewsAreTheRows() {
        UUID owner = IDS.next();
        FxTradeId id = FxTradeId.of(IDS.next());
        FxReadDesk.FxTradeItem trade = FxReadDesk.tradeItem(new FxCollections.TradeHead(id, owner, PricingPurpose.CONVERSION,
                FixedSide.FIXED_SOURCE, TradeStatus.BOOKED, Money.ofPersisted(10000, CurrencyCode.of("EUR"), 2),
                Money.ofPersisted(1085, CurrencyCode.of("JPY"), 0), new BigDecimal("108.5024"), "fx-sim-a",
                Instant.parse("2026-11-20T10:00:00Z"), LocalDate.parse("2026-11-20")));
        assertThat(trade.pair()).isEqualTo("EUR-JPY");
        assertThat(trade.sourceAmount()).isEqualTo("100.00");
        assertThat(trade.destinationAmount()).isEqualTo("1085");
        assertThat(trade.executedRate()).isEqualTo("108.5024");
        assertThat(trade.ownerPartyId()).isEqualTo(owner.toString());

        Instant proposed = Instant.parse("2026-11-20T09:00:00Z");
        FxReadDesk.FxEnableRequestItem open = FxReadDesk.requestItem(new FxCollections.EnableRequestEntry(IDS.next(),
                AvailabilitySubject.pair("GBP-JPY"), AvailabilityStore.EnableRequestStatus.PROPOSED, "op-a", proposed,
                "recovered", Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(open.subjectKind()).isEqualTo("PAIR");
        assertThat(open.subject()).isEqualTo("GBP-JPY");
        assertThat(open.decidedBy()).isNull();
        assertThat(open.decidedAt()).isNull();
        assertThat(open.proposedAt()).isEqualTo(proposed.toString());
    }

    @Test
    @DisplayName("a subject never switched reads available with no controller, as the gate reads it; a switched one its latest"
            + " fact")
    void aSubjectNeverSwitchedIsAvailable() {
        AvailabilitySubject pair = AvailabilitySubject.pair("EUR-GBP");
        FxReadDesk.FxAvailabilityItem never = FxReadDesk.availabilityItem(pair, Optional.empty());
        assertThat(never.subject()).isEqualTo("EUR-GBP");
        assertThat(never.available()).isTrue();
        assertThat(never.changedBy()).isNull();
        assertThat(never.changedAt()).isNull();
        assertThat(never.reason()).isNull();

        Instant at = Instant.parse("2026-11-20T08:00:00Z");
        FxReadDesk.FxAvailabilityItem stopped = FxReadDesk.availabilityItem(pair,
                Optional.of(new FxCollections.AvailabilityFact(pair, false, "op-a", "provider outage", at)));
        assertThat(stopped.available()).isFalse();
        assertThat(stopped.changedBy()).isEqualTo("op-a");
        assertThat(stopped.changedAt()).isEqualTo(at.toString());
        assertThat(stopped.reason()).isEqualTo("provider outage");
    }

    private static void assertRefused(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }
}
