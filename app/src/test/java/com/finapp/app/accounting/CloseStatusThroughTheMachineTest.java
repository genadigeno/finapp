package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodCloseId;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.CloseStatusDefinition;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.InMemoryPeriodCloseStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code CLOSE_STATUS} read against the real machine (`P14-TSK-012`; {@code P14-TSK-017}'s routed note, read against the code rather than taken from it): the real {@link AccountingCloseStatusReads}, {@link CloseRuns} and {@link PeriodCloses} over the in-memory stores. It reads
 * correctly through {@code LOCKED}, {@code CLOSE_PROPOSED}, a rejection, {@code CLOSED}, the reopened state and a re-close; and it names the proposals {@code VALUATION_SET_APPROVED} waits for (`P14-TSK-011`'s review, M-6; `P14-TSK-014`'s, M-5).
 */
@DisplayName("CLOSE_STATUS through the machine (P14-TSK-012)")
class CloseStatusThroughTheMachineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore runStore = new InMemoryCloseRunStore(periods);
    private final InMemoryAccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private final InMemoryValuationRateSetStore setStore = new InMemoryValuationRateSetStore().watching(periods);
    private final InMemoryPeriodCloseStore closes = new InMemoryPeriodCloseStore(periods);
    private final CloseRuns runs = new CloseRuns(runStore, periods, breaks, CloseRuns.Leases.STANDARD, (uow, record) -> { }, IDS, CLOCK);
    private final ValuationRateSets sets = new ValuationRateSets(setStore, periods, new OneRate(), ValuationStaleness.standard(), (uow, record) -> { }, IDS, CLOCK);
    private final PeriodCloses machine = new PeriodCloses(periods, closes, runs, breaks, setStore, com.finapp.accounting.testing.MonthsThatRan.EVERY_MONTH, new NoCutoff(), (uow, period, actor, correlation) -> 0, new com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore(periods), (uow, record) -> { },
            (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
    private final CloseStatusDefinition definition = new CloseStatusDefinition(new AccountingCloseStatusReads(periods, runs, breaks, setStore), new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS));
    private final AccountingPeriodId march = AccountingPeriodId.next(IDS);

    @Test
    @DisplayName("LOCKED: awaiting its run, or stalled past the bound; RUNNING while the set is awaited - naming the proposals; CONCLUDED through CLOSE_PROPOSED, a rejection and CLOSED; NOT_LOCKED reopened, the old run current=false; the next lock its own")
    void itReadsTheMachine() throws Exception {
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        JsonNode open = read(Instant.parse("2097-04-05T09:00:00Z"));
        assertThat(open.get("runState").asText()).isEqualTo("NOT_LOCKED");
        assertThat(open.get("run").isNull()).isTrue();

        lock();
        Instant lockedAt = periods.find(UOW, march).orElseThrow().lockedAt().orElseThrow();
        assertThat(read(lockedAt).get("runState").asText()).as("the ordinary gap").isEqualTo("AWAITING_FIRST_POLL");
        assertThat(read(lockedAt.plus(Duration.ofDays(1))).get("runState").asText()).as("past the bound").isEqualTo("STALLED");

        runs.startMissing(UOW, Actor.SYSTEM);
        ValuationRateSets.Valued captured = sets.capture(UOW, march, ACCOUNTANT, null, CorrelationId.generate(IDS));
        concludeAllBut(CloseCheckCode.VALUATION_SET_APPROVED);
        JsonNode waiting = read(lockedAt);
        assertThat(waiting.get("runState").asText()).as("the set is awaited: the check waits, it does not fail").isEqualTo("RUNNING");
        assertThat(waiting.get("proposedValuationRateSets").get(0).asText()).as("M-5: which proposal it waits for").isEqualTo(captured.id().value().toString());
        assertThat(waiting.get("run").get("complete").asBoolean()).isFalse();

        sets.approve(UOW, captured.id(), CONTROLLER, "agreed", CorrelationId.generate(IDS));
        concludeAllBut(null);
        JsonNode ready = read(lockedAt);
        assertThat(ready.get("runState").asText()).isEqualTo("CONCLUDED");
        assertThat(ready.get("run").get("complete").asBoolean()).isTrue();
        assertThat(ready.get("proposedValuationRateSets")).isEmpty();

        PeriodCloseId proposal = machine.propose(UOW, march, runStore.runs().get(0).id(), List.of(), ACCOUNTANT, "read", CorrelationId.generate(IDS)).proposal().orElseThrow();
        JsonNode proposed = read(lockedAt);
        assertThat(proposed.get("status").asText()).isEqualTo("CLOSE_PROPOSED");
        assertThat(proposed.get("runState").asText()).isEqualTo("CONCLUDED");
        assertThat(proposed.get("run").get("current").asBoolean()).isTrue();

        machine.reject(UOW, march, proposal, CONTROLLER, "not yet", CorrelationId.generate(IDS));
        assertThat(read(lockedAt).get("status").asText()).isEqualTo("LOCKED");
        assertThat(read(lockedAt).get("runState").asText()).isEqualTo("CONCLUDED");

        PeriodCloseId again = machine.propose(UOW, march, runStore.runs().get(0).id(), List.of(), ACCOUNTANT, "read", CorrelationId.generate(IDS)).proposal().orElseThrow();
        machine.approve(UOW, march, again, CONTROLLER, "agreed", CorrelationId.generate(IDS));
        JsonNode closed = read(lockedAt.plus(Duration.ofDays(40)));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("runState").asText()).as("however long after").isEqualTo("CONCLUDED");
        assertThat(closed.get("revision").asInt()).isEqualTo(1);

        machine.proposeReopen(UOW, march, ACCOUNTANT, java.util.Set.of(), "a correction", CorrelationId.generate(IDS));
        machine.approveReopen(UOW, march, CONTROLLER, java.util.Set.of(), "agreed", CorrelationId.generate(IDS));
        JsonNode reopened = read(lockedAt);
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("revision").asInt()).isEqualTo(2);
        assertThat(reopened.get("runState").asText()).isEqualTo("NOT_LOCKED");
        assertThat(reopened.get("run").get("current").asBoolean()).as("the old run, served as what it was").isFalse();
        assertThat(reopened.get("lockedAt").isNull()).isTrue();

        lock();
        Instant again2 = periods.find(UOW, march).orElseThrow().lockedAt().orElseThrow();
        assertThat(read(again2).get("runState").asText()).as("the re-lock's own gap, the old run not answering").isEqualTo("AWAITING_FIRST_POLL");
        assertThat(read(again2).get("revision").asInt()).isEqualTo(2);
        runs.startMissing(UOW, Actor.SYSTEM);
        assertThat(read(again2).get("runState").asText()).isEqualTo("RUNNING");
    }

    private void lock() {
        int revision = periods.find(UOW, march).orElseThrow().revision();
        periods.markLocked(UOW, march, revision, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(GlMappingVersionId.next(IDS), 1)));
    }

    @Test
    @DisplayName("the independent review, M-5: past the bound the newest proposals are the ones named, the ones being awaited; the counts say how many there are")
    void theNewestProposalsAreNamed() throws Exception {
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        lock();
        List<String> captured = new ArrayList<>();
        for (int set = 0; set < AccountingCloseStatusReads.MAX_PROPOSED_NAMED + 5; set++) {
            captured.add(sets.capture(UOW, march, ACCOUNTANT, null, CorrelationId.generate(IDS)).id().value().toString());
        }
        java.util.Collections.reverse(captured);
        JsonNode read = read(Instant.parse("2097-04-05T09:00:00Z"));
        List<String> named = new ArrayList<>();
        read.get("proposedValuationRateSets").forEach(id -> named.add(id.asText()));
        assertThat(named).containsExactlyElementsOf(captured.subList(0, AccountingCloseStatusReads.MAX_PROPOSED_NAMED));
    }

    /** Every attempt concluded {@code PASSED} but {@code but}'s, left {@code PENDING} as a deferred one would be. */
    private void concludeAllBut(CloseCheckCode but) {
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            CloseRuns.ClaimedCheck claimed = next.get();
            if (claimed.check() == but) {
                runs.defer(UOW, claimed);
                continue;
            }
            if (claimed.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
                runs.concludeOpenBreaks(UOW, claimed);
            } else {
                runs.conclude(UOW, claimed, CheckVerdict.passed(claimed.check(), "judge:v1", CanonicalJson.object().put("clean", true)));
            }
        }
        runStore.advance(CloseRuns.Leases.STANDARD.deferral().plusSeconds(1));
    }

    private JsonNode read(Instant cutAt) throws Exception {
        ReportInputs inputs = new ReportInputs(march, MARCH, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.empty(), Optional.empty(), cutAt, RunParameters.none());
        return JSON.readTree(CanonicalJson.text(definition.generate(UOW, inputs)));
    }

    /** The cutoff is the ledger's: nothing here asks where it stands. */
    private static final class NoCutoff implements PostingCutoff {

        @Override
        public Optional<LocalDate> closedThrough(Connection unitOfWork) {
            return Optional.empty();
        }

        @Override
        public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
            return true;
        }

        @Override
        public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
            return true;
        }
    }

    /** The reference: one valued pair, observed the hour before the cutoff. */
    private static final class OneRate implements ValuationRates {

        private static final CurrencyCode USD = CurrencyCode.of("USD");

        @Override
        public List<CurrencyCode> valued() {
            return List.of(USD);
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            return List.of(new Observed(USD, Optional.of(new Observation(UUID.randomUUID(), "simulated-reference", CurrencyCode.of("EUR"), USD, new BigDecimal("1.1734000000"),
                    before.minusSeconds(3_600), before.minusSeconds(3_598)))));
        }
    }
}
