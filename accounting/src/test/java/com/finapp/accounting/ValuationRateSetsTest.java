package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Valuation rate sets - capture, approve, reject (`P14-TSK-014`; ADR-0116 decision 2; {@code PHASE_14_PLAN.md} sections
 * 6, 13.4; {@code INV-ACC-11}, {@code INV-FX-05}, {@code INV-AUD-04}): every pair EUR-based with its provenance, as of the
 * period's end; a stale, missing or foreign pair refusing the whole capture with nothing written; only a LOCKED period
 * valued; four eyes; one approved set per period; decisions terminal and converging. Hermetic: the period machine runs over
 * the in-memory stores, the reference is a scripted port; the live ranks are {@code ValuationRateSetDatabaseTest}'s, owed
 * (C5).
 */
@DisplayName("valuation rate sets: capture, approve, reject (P14-TSK-014)")
class ValuationRateSetsTest {

    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final Instant AS_OF = Instant.parse("2026-10-01T00:00:00Z");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final List<CurrencyCode> VALUED = List.of(CurrencyCode.of("GBP"), CurrencyCode.of("USD"),
            CurrencyCode.of("JPY"), CurrencyCode.of("BHD"));
    private static final String SOURCE = "simulated-reference";

    private final InMemoryAccountingPeriodStore periodStore = new InMemoryAccountingPeriodStore();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryValuationRateSetStore store = new InMemoryValuationRateSetStore().watching(periodStore);
    private final List<AuditRecord> audit = new ArrayList<>();
    private final ScriptedReference reference = new ScriptedReference();
    private Optional<LocalDate> closedThrough = Optional.empty();

    private final Actor accountant = new Actor("accountant-1", ActorType.CUSTOMER);
    private final Actor secondAccountant = new Actor("accountant-2", ActorType.CUSTOMER);
    private final Actor controller = new Actor("controller-1", ActorType.CUSTOMER);
    private final Actor secondController = new Actor("controller-2", ActorType.EMPLOYEE);

    ValuationRateSetsTest() {
        mappings.pinnedPeriods(periodStore::firstPinnedFrom);
        reference.observeAll(Instant.parse("2026-09-30T16:00:00Z"));
    }

    @Test
    @DisplayName("a capture keeps every pair EUR-based with its provenance - source, snapshot id, observed and received"
            + " instants, the rate exactly as fx stored it - as of the first instant after the period, PROPOSED, audited"
            + " without a rate")
    void captureKeepsEveryPairEurBasedWithItsProvenance() {
        AccountingPeriodId september = lockedSeptember();

        ValuationRateSets.Valued captured = sets().capture(UOW, september, accountant, null, correlation());

        assertThat(captured.status()).isEqualTo(ValuationRateSetStatus.PROPOSED);
        assertThat(captured.month()).isEqualTo(SEPTEMBER);
        assertThat(captured.asOf()).isEqualTo(AS_OF);
        assertThat(captured.maxAgeHours()).isEqualTo(ValuationStaleness.DEFAULT_HOURS);
        assertThat(reference.askedBefore).containsExactly(AS_OF);
        assertThat(captured.rates()).extracting(ValuationRate::pair)
                .containsExactly("EUR/GBP", "EUR/USD", "EUR/JPY", "EUR/BHD");
        for (ValuationRate rate : captured.rates()) {
            ValuationRates.Observation source = reference.observations.get(rate.quote()).orElseThrow();
            assertThat(rate.base()).isEqualTo(EUR);
            assertThat(rate.rate()).as("the rate exactly as stored, scale included").isEqualTo(source.rate());
            assertThat(rate.source()).isEqualTo(SOURCE);
            assertThat(rate.snapshotId()).isEqualTo(source.snapshotId());
            assertThat(rate.observedAt()).isEqualTo(source.observedAt());
            assertThat(rate.receivedAt()).isEqualTo(source.receivedAt());
        }
        assertThat(store.rates(UOW, captured.id())).isEqualTo(captured.rates());
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.ValuationSetCaptured");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetId()).isEqualTo(captured.id().value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("period=2026-09").contains("pairs=4")
                    .doesNotContain("0.86").doesNotContain("1.17");
        });
    }

    @Test
    @DisplayName("a stale pair refuses the whole capture and nothing is written - the bound judged against the period's"
            + " end: an observation exactly the bound before it is admitted, one a second older is not")
    void aStalePairIsRefused() {
        AccountingPeriodId september = lockedSeptember();
        reference.observe("JPY", AS_OF.minus(Duration.ofHours(ValuationStaleness.DEFAULT_HOURS)).minusSeconds(1));

        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOfSatisfying(ValuationRateSet.ValuationRateStale.class,
                        stale -> assertThat(stale.pairs()).containsExactly("EUR/JPY"))
                .hasMessageContaining("EUR/JPY").hasMessageContaining("96 hours");
        assertThat(store.all()).isEmpty();
        assertThat(audit).isEmpty();

        reference.observe("JPY", AS_OF.minus(Duration.ofHours(ValuationStaleness.DEFAULT_HOURS)));
        assertThat(sets().capture(UOW, september, accountant, null, correlation()).rates()).hasSize(4);
    }

    @Test
    @DisplayName("a missing pair refuses the whole capture: no observation before the end, or no answer at all")
    void aMissingPairIsRefused() {
        AccountingPeriodId september = lockedSeptember();
        reference.observations.put(CurrencyCode.of("BHD"), Optional.empty());

        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOfSatisfying(ValuationRateSet.ValuationRateStale.class,
                        stale -> assertThat(stale.pairs()).containsExactly("EUR/BHD"));

        reference.observations.remove(CurrencyCode.of("BHD"));
        reference.observeAll(Instant.parse("2026-09-30T16:00:00Z"));
        reference.unanswered = CurrencyCode.of("USD");
        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOfSatisfying(ValuationRateSet.ValuationRateStale.class,
                        stale -> assertThat(stale.pairs()).containsExactly("EUR/USD"));
        assertThat(store.all()).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("a port answering anything but the EUR-based reference pair before the end is refused, never stored -"
            + " an inverted pair, another pair, an observation at the end itself, a rate NUMERIC(20,10) cannot hold")
    void aPortAnsweringAnythingButTheReferencePairIsRefused() {
        AccountingPeriodId september = lockedSeptember();
        Instant before = Instant.parse("2026-09-30T16:00:00Z");

        reference.answer("GBP", observation(CurrencyCode.of("GBP"), EUR, new BigDecimal("1.1600000000"), before));
        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOf(ValuationRateSet.ValuationRateStale.class).hasMessageContaining("GBP/EUR");

        reference.observeAll(before);
        reference.answer("USD", observation(CurrencyCode.of("GBP"), CurrencyCode.of("USD"), new BigDecimal("1.3"), before));
        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOf(ValuationRateSet.ValuationRateStale.class).hasMessageContaining("GBP/USD");

        reference.observeAll(before);
        reference.observe("BHD", AS_OF);
        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOf(ValuationRateSet.ValuationRateStale.class).hasMessageContaining("not before");

        reference.observeAll(before);
        reference.answer("JPY", observation(EUR, CurrencyCode.of("JPY"), new BigDecimal("161.12345678901"), before));
        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOf(ValuationRateSet.ValuationRateStale.class).hasMessageContaining("NUMERIC(20,10)");
        assertThat(store.all()).isEmpty();
    }

    @Test
    @DisplayName("only a LOCKED period is valued: an OPEN one is stale, an unknown one not found; once a set is approved"
            + " the period takes no further capture")
    void onlyALockedPeriodIsValued() {
        AccountingPeriodId september = open(SEPTEMBER);
        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, null, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("OPEN");
        assertThatThrownBy(() -> sets().capture(UOW, AccountingPeriodId.next(IDS), accountant, null, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodNotFound.class);

        periods("2026-10-30").lock(UOW, september, accountant, null, correlation());
        ValuationRateSets.Valued captured = sets().capture(UOW, september, accountant, null, correlation());
        sets().approve(UOW, captured.id(), controller, "the reference's month-end rates", correlation());

        assertThatThrownBy(() -> sets().capture(UOW, september, secondAccountant, null, correlation()))
                .isInstanceOf(ValuationRateSets.ValuationSetStale.class).hasMessageContaining("already has an approved");
        assertThat(store.all()).hasSize(1);
    }

    @Test
    @DisplayName("four eyes: the capturer's approval is refused before anything moves, and the refusal is recorded FAILED")
    void theCapturerCannotApprove() {
        ValuationRateSets.Valued captured = sets().capture(UOW, lockedSeptember(), accountant, null, correlation());

        assertThatThrownBy(() -> sets().approve(UOW, captured.id(), accountant, "my own", correlation()))
                .isInstanceOfSatisfying(ValuationRateSets.SelfApprovalRefused.class, refused -> {
                    assertThat(refused.target()).isEqualTo(captured.id().value().toString());
                    assertThat(refused.summary()).isEqualTo("period=2026-09, act=valuation:approve");
                    sets().recordRefusal(UOW, refused, accountant, correlation());
                });
        assertThat(store.find(UOW, captured.id()).orElseThrow().status()).isEqualTo(ValuationRateSetStatus.PROPOSED);
        assertThat(audit).extracting(record -> record.operation().code())
                .containsExactly("accounting.ValuationSetCaptured", "accounting.SelfApprovalRefused");
        assertThat(audit.get(1).outcome()).isEqualTo(AuditOutcome.FAILED);
    }

    @Test
    @DisplayName("a second person approves once: the same approver's repeat converges writing nothing; another approver"
            + " finds it decided")
    void aSecondPersonApprovesOnce() {
        ValuationRateSets.Valued captured = sets().capture(UOW, lockedSeptember(), accountant, "month end", correlation());

        ValuationRateSets.Valued approved = sets().approve(UOW, captured.id(), controller, "checked", correlation());
        ValuationRateSets.Valued again = sets().approve(UOW, captured.id(), controller, "checked", correlation());

        assertThat(approved.status()).isEqualTo(ValuationRateSetStatus.APPROVED);
        assertThat(approved.replayed()).isFalse();
        assertThat(approved.rates()).isEqualTo(captured.rates());
        assertThat(again.replayed()).isTrue();
        assertThat(again.status()).isEqualTo(ValuationRateSetStatus.APPROVED);
        assertThatThrownBy(() -> sets().approve(UOW, captured.id(), secondController, "late", correlation()))
                .isInstanceOf(ValuationRateSets.ValuationSetStale.class).hasMessageContaining("APPROVED");
        assertThat(audit).extracting(record -> record.operation().code())
                .containsExactly("accounting.ValuationSetCaptured", "accounting.ValuationSetApproved");
        assertThat(audit.get(1).reason()).contains("checked");
        assertThat(sets().approvedFor(UOW, captured.period())).map(ValuationRateSets.Valued::id).contains(captured.id());
        assertThat(store.captureReason(captured.id())).contains("month end");
    }

    @Test
    @DisplayName("two captures, one approved set per period: the second set's approval is stale, its rejection still"
            + " possible")
    void oneApprovedPerPeriod() {
        AccountingPeriodId september = lockedSeptember();
        ValuationRateSets.Valued first = sets().capture(UOW, september, accountant, null, correlation());
        ValuationRateSets.Valued second = sets().capture(UOW, september, secondAccountant, null, correlation());

        sets().approve(UOW, first.id(), controller, "checked", correlation());
        assertThatThrownBy(() -> sets().approve(UOW, second.id(), secondController, "checked too", correlation()))
                .isInstanceOf(ValuationRateSets.ValuationSetStale.class).hasMessageContaining("already has an approved");

        assertThat(store.all()).filteredOn(row -> row.status() == ValuationRateSetStatus.APPROVED)
                .extracting(ValuationRateSetStore.SetRow::id).containsExactly(first.id());
        assertThat(sets().reject(UOW, second.id(), secondController, "superfluous", correlation()).status())
                .isEqualTo(ValuationRateSetStatus.REJECTED);
    }

    @Test
    @DisplayName("an approval needs the LOCKED period: after an unlock it is refused stale; the rejection is not; an"
            + " approved set survives an unlock")
    void approvalNeedsTheLockedPeriod() {
        AccountingPeriodId september = lockedSeptember();
        ValuationRateSets.Valued pending = sets().capture(UOW, september, accountant, null, correlation());
        ValuationRateSets.Valued kept = sets().capture(UOW, september, secondAccountant, null, correlation());
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "a late invoice", correlation());
        periods("2026-10-30").approveUnlock(UOW, september, controller, "agreed", correlation());

        assertThatThrownBy(() -> sets().approve(UOW, pending.id(), controller, "checked", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("OPEN");
        assertThat(sets().reject(UOW, pending.id(), controller, "the period reopened", correlation()).status())
                .isEqualTo(ValuationRateSetStatus.REJECTED);

        periods("2026-10-30").lock(UOW, september, accountant, null, correlation());
        sets().approve(UOW, kept.id(), controller, "checked", correlation());
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "another late invoice", correlation());
        periods("2026-10-30").approveUnlock(UOW, september, controller, "agreed", correlation());
        assertThat(sets().approvedFor(UOW, september)).map(ValuationRateSets.Valued::id).contains(kept.id());
    }

    @Test
    @DisplayName("a rejection is terminal and reasoned, its capturer may make it, its repeat converges; nothing decided"
            + " moves again")
    void aRejectionIsTerminal() {
        ValuationRateSets.Valued captured = sets().capture(UOW, lockedSeptember(), accountant, null, correlation());

        ValuationRateSets.Valued rejected = sets().reject(UOW, captured.id(), accountant, "wrong source", correlation());
        assertThat(rejected.status()).isEqualTo(ValuationRateSetStatus.REJECTED);
        assertThat(sets().reject(UOW, captured.id(), accountant, "wrong source", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> sets().approve(UOW, captured.id(), controller, "checked", correlation()))
                .isInstanceOf(ValuationRateSets.ValuationSetStale.class).hasMessageContaining("REJECTED");
        assertThatThrownBy(() -> sets().reject(UOW, captured.id(), controller, "again", correlation()))
                .isInstanceOf(ValuationRateSets.ValuationSetStale.class);
        assertThat(audit).extracting(record -> record.operation().code())
                .containsExactly("accounting.ValuationSetCaptured", "accounting.ValuationSetRejected");
        assertThat(audit.get(1).changeSummary()).contains("period=2026-09, byCapturer=true");
        assertThatThrownBy(() -> sets().approve(UOW, ValuationRateSetId.next(IDS), controller, "checked", correlation()))
                .isInstanceOf(ValuationRateSets.ValuationSetNotFound.class);
    }

    @Test
    @DisplayName("persons only, decisions reasoned, every reason screened")
    void personsOnlyAndReasoned() {
        AccountingPeriodId september = lockedSeptember();
        assertThatThrownBy(() -> sets().capture(UOW, september, Actor.SYSTEM, null, correlation()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sets().capture(UOW, september, accountant, "card 4111 1111 1111 1111", correlation()))
                .isInstanceOf(AccountingReasons.ReasonRequired.class);
        ValuationRateSets.Valued captured = sets().capture(UOW, september, accountant, " ", correlation());
        assertThat(store.captureReason(captured.id())).isEmpty();
        assertThatThrownBy(() -> sets().approve(UOW, captured.id(), controller, null, correlation()))
                .isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> sets().reject(UOW, captured.id(), controller, "", correlation()))
                .isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> sets().approve(UOW, captured.id(), new Actor("svc", ActorType.SERVICE), "x",
                correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThat(sets().read(UOW, captured.id()).set().status()).isEqualTo(ValuationRateSetStatus.PROPOSED);
    }

    // -----------------------------------------------------------------

    private ValuationRateSets sets() {
        return new ValuationRateSets(store, periodStore, reference, ValuationStaleness.standard(),
                (unitOfWork, record) -> audit.add(record), IDS,
                Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC));
    }

    private AccountingPeriods periods(String day) {
        Clock clock = Clock.fixed(Instant.parse(day + "T09:00:00Z"), ZoneOffset.UTC);
        PostingCutoff cutoff = new PostingCutoff() {
            @Override
            public Optional<LocalDate> closedThrough(Connection unitOfWork) {
                return closedThrough;
            }

            @Override
            public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
                closedThrough = Optional.of(through);
                return true;
            }

            @Override
            public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
                closedThrough = to;
                return true;
            }
        };
        return new AccountingPeriods(periodStore, mappings, cutoff, LockGrace.standard(), (unitOfWork, record) -> { },
                (unitOfWork, envelope, payload, mediaType) -> { }, IDS, clock);
    }

    private AccountingPeriodId open(YearMonth month) {
        periods(month.atDay(15).toString()).openNext(UOW, Actor.SYSTEM, correlation());
        return periodStore.month(month).orElseThrow().id();
    }

    private AccountingPeriodId lockedSeptember() {
        AccountingPeriodId september = open(SEPTEMBER);
        periods("2026-10-30").lock(UOW, september, accountant, null, correlation());
        return september;
    }

    private static ValuationRates.Observation observation(
            CurrencyCode base, CurrencyCode quote, BigDecimal rate, Instant observedAt) {
        return new ValuationRates.Observation(UUID.randomUUID(), SOURCE, base, quote, rate, observedAt,
                observedAt.plusSeconds(2));
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** The reference as the port answers it: one scripted observation per valued currency. */
    private static final class ScriptedReference implements ValuationRates {

        private static final Map<String, String> RATES = Map.of(
                "GBP", "0.8612000000", "USD", "1.1734000000", "JPY", "161.2500000000", "BHD", "0.4421000000");

        final Map<CurrencyCode, Optional<Observation>> observations = new LinkedHashMap<>();
        final List<Instant> askedBefore = new ArrayList<>();
        CurrencyCode unanswered;

        void observeAll(Instant observedAt) {
            for (CurrencyCode currency : VALUED) {
                observe(currency.code(), observedAt);
            }
        }

        void observe(String currency, Instant observedAt) {
            answer(currency, observation(EUR, CurrencyCode.of(currency), new BigDecimal(RATES.get(currency)), observedAt));
        }

        void answer(String currency, Observation observation) {
            observations.put(CurrencyCode.of(currency), Optional.of(observation));
        }

        @Override
        public List<CurrencyCode> valued() {
            return VALUED;
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            askedBefore.add(before);
            List<Observed> answered = new ArrayList<>();
            for (CurrencyCode currency : VALUED) {
                if (!currency.equals(unanswered)) {
                    answered.add(new Observed(currency, observations.getOrDefault(currency, Optional.empty())));
                }
            }
            return answered;
        }
    }
}
