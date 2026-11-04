package com.finapp.app.accounting;

import static com.finapp.accounting.testing.RegulatoryWorld.JANUARY;
import static com.finapp.accounting.testing.RegulatoryWorld.REASON;
import static com.finapp.accounting.testing.RegulatoryWorld.SENDER;
import static com.finapp.accounting.testing.RegulatoryWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatoryReportingProvider.Outcome;
import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The regulatory drive, over a provider that remembers (`P14-TSK-018`; {@code PHASE_14_PLAN.md} sections 8.3 row 13, 8.4 (d); {@code INV-ACC-13}, {@code INV-HIST-02}): the provider on no transaction; a crash at every point recovered by asking, never by sending blind; a definite "not received" the only permission to
 * send again, bounded; a failure counted and ending only itself; ten instances really racing one claim. In memory: the {@code SKIP LOCKED} itself is {@code RegulatorySubmissionDatabaseTest}'s (owed, C5).
 */
@DisplayName("the regulatory drive (P14-TSK-018)")
class RegulatorySubmissionDriverTest {

    /** A clock a suite moves. */
    private static final class Moving extends Clock {
        private volatile Instant now = Instant.parse("2096-03-10T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration INTERVAL = Duration.ofMinutes(15);

    private final Moving clock = new Moving();
    private final RegulatoryWorld world = new RegulatoryWorld(clock);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RegulatorySubmissionMetrics metrics = new RegulatorySubmissionMetrics(registry);
    private final CloseRunMetrics legs = new CloseRunMetrics(registry);
    private volatile boolean inTransaction;
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            inTransaction = true;
            try {
                return work.apply(UOW);
            } finally {
                inTransaction = false;
            }
        }
    };
    private final RegulatorySubmissionDriver driver = new RegulatorySubmissionDriver(world.submissions, transactions, metrics, legs, AccountingFlowScope.NONE, LEASE, INTERVAL, 10);
    private RegulatoryObligationId obligation;
    private RegulatorySubmissions.Acted sent;

    @BeforeEach
    void aReturnCommittedSubmitted() {
        world.closed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00));
        obligation = world.active();
        sent = world.sent(obligation, JANUARY);
    }

    private RegulatorySubmissionStore.Row row() {
        return world.store.find(UOW, sent.id()).orElseThrow();
    }

    private double count(String outcome) {
        return registry.get(RegulatorySubmissionMetrics.SUBMISSION).tag("outcome", outcome).counter().count();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the door's drive: the first attempt sends once under the submission's id, the answer is recorded verbatim, the return concluded")
    void theDoorsDriveSendsOnceAndRecords() {
        assertThat(driver.submitIfDue(sent.id())).isTrue();

        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.inquiries()).isZero();
        assertThat(world.provider.returnsHeld(sent.id())).isEqualTo(1);
        assertThat(row().status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(world.store.answers(UOW, sent.id(), 100)).hasSize(1);
        assertThat(count("acknowledged")).isEqualTo(1);
        assertThat(new String(world.store.answers(UOW, sent.id(), 100).get(0).raw(), StandardCharsets.UTF_8)).as("verbatim").contains("ACKNOWLEDGED").contains(sent.id().value().toString());
    }

    @Test
    @DisplayName("a retried submit submits once: the door's drive again finds nothing due, and the leg finds nothing due - ten retries, one send")
    void aRetriedSubmitSubmitsOnce() {
        for (int i = 0; i < 10; i++) {
            driver.submitIfDue(sent.id());
            driver.inquireDue();
        }
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.sendsReceived(sent.id())).isEqualTo(1);
        assertThat(driver.submitIfDue(sent.id())).as("nothing due").isFalse();
        assertThat(driver.inquireDue()).as("nothing due").isZero();
        assertThat(world.provider.inquiries()).isZero();
    }

    @Test
    @DisplayName("the provider is called on no transaction, the platform acting: no connection crosses a call")
    void theProviderIsCalledOnNoTransaction() {
        List<Boolean> during = new ArrayList<>();
        List<Actor> acting = new ArrayList<>();
        world.provider.nextSubmit(() -> {
            during.add(inTransaction);
            acting.add(SecurityContext.require());
            return Answer.unknown(Optional.empty(), bytes("{}"));
        });
        driver.submitIfDue(sent.id());
        assertThat(during).containsExactly(false);
        assertThat(acting.get(0).type()).isEqualTo(ActorType.SYSTEM);

        // The inquiry and the resend, the same.
        world.provider.nextInquiry(() -> {
            during.add(inTransaction);
            return Answer.notReceived(bytes("{}"));
        });
        world.provider.nextSubmit(() -> {
            during.add(inTransaction);
            return Answer.unknown(Optional.empty(), bytes("{}"));
        });
        clock.advance(INTERVAL.plusSeconds(1));
        driver.inquireDue();
        assertThat(during).containsExactly(false, false, false);
    }

    @Test
    @DisplayName("a call that throws records nothing, counted, the permit left: the next claim asks, never sends blind")
    void aCallThatThrowsRecordsNothing() {
        world.provider.nextSubmit(ScriptedRegulatoryProvider.unreachable());
        assertThat(driver.submitIfDue(sent.id())).isFalse();

        assertThat(world.store.answers(UOW, sent.id(), 100)).isEmpty();
        assertThat(row().status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(count("call_failed")).isEqualTo(1);

        clock.advance(LEASE.plusSeconds(1));
        driver.inquireDue();
        assertThat(world.provider.inquiries()).as("asked first").isEqualTo(1);
        assertThat(world.provider.returnsHeld(sent.id())).as("nothing had arrived, so the send is owed").isEqualTo(1);
        assertThat(row().status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(row().resends()).isEqualTo(1);
        assertThat(count("not_received")).isEqualTo(1);
        assertThat(count("resent")).isEqualTo(1);
    }

    @Test
    @DisplayName("sent and the answer lost: the next claim asks and finds it, and nothing is sent again")
    void aLostAnswerIsAskedFor() {
        world.provider.nextSubmit(() -> {
            world.provider.delivered(new com.finapp.accounting.RegulatoryReportingProvider.Request(sent.id(), "REF", RegulatoryWorld.FORM, 1, JANUARY.toString(), "the content", "h"));
            throw new IllegalStateException("the answer was lost on the wire");
        });
        driver.submitIfDue(sent.id());
        assertThat(row().status()).isEqualTo(SubmissionStatus.SUBMITTED);

        clock.advance(LEASE.plusSeconds(1));
        driver.inquireDue();
        assertThat(row().status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.inquiries()).isEqualTo(1);
        assertThat(world.provider.sendsReceived(sent.id())).isEqualTo(1);
        assertThat(row().resends()).isZero();
    }

    @Test
    @DisplayName("a crash between the commit and the call: a row the provider never received, found by asking, sent under the same id")
    void aCrashBeforeTheCallIsRecovered() {
        transactions.inTransaction(uow -> world.submissions.claim(uow, sent.id(), LEASE)); // A worker claimed and died.
        assertThat(world.provider.submits()).isZero();

        clock.advance(LEASE.plusSeconds(1));
        assertThat(driver.inquireDue()).isEqualTo(1);
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.contents()).hasSize(1);
        assertThat(row().status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(row().resends()).isEqualTo(1);
        assertThat(row().attempts()).isEqualTo(2);
        assertThat(world.provider.sendsReceived(sent.id())).as("under the same id").isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown answer is an inquiry and never a send: the leg asks each interval; past the horizon it reads overdue and it goes on asking; no resend counted")
    void anUnknownAnswerIsAnInquiry() {
        world.provider.nextSubmit(() -> Answer.unknown(Optional.empty(), bytes("{}")));
        driver.submitIfDue(sent.id());
        assertThat(row().lastAnswer()).contains("UNKNOWN");
        for (int i = 0; i < 3; i++) {
            world.provider.nextInquiry(() -> Answer.unknown(Optional.empty(), bytes("{}")));
            clock.advance(INTERVAL.plusSeconds(1));
            driver.inquireDue();
        }
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.inquiries()).isEqualTo(3);
        assertThat(row().resends()).isZero();
        assertThat(count("unknown")).isEqualTo(4);

        clock.advance(Duration.ofDays(2));
        assertThat(row().pastHorizon()).isTrue();
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overdue()).isEqualTo(1);
        world.provider.nextInquiry(() -> Answer.unknown(Optional.empty(), bytes("{}")));
        assertThat(driver.inquireDue()).isEqualTo(1);
        assertThat(row().status()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    @Test
    @DisplayName("never received: the resends are bounded; once spent nothing more is sent, it goes on asking, and a person's door is open")
    void theResendsRunOut() {
        for (int i = 0; i <= RegulatorySubmissions.MAX_RESENDS + 1; i++) {
            world.provider.nextSubmit(() -> Answer.unknown(Optional.empty(), bytes("{}")));
        }
        driver.submitIfDue(sent.id());
        for (int i = 0; i < RegulatorySubmissions.MAX_RESENDS + 3; i++) {
            clock.advance(INTERVAL.plusSeconds(1));
            driver.inquireDue();
        }
        assertThat(row().resends()).isEqualTo(RegulatorySubmissions.MAX_RESENDS);
        assertThat(world.provider.submits()).as("the first, and the bound").isEqualTo(1 + RegulatorySubmissions.MAX_RESENDS);
        assertThat(count("resends_exhausted")).isGreaterThanOrEqualTo(1);
        assertThat(row().lastAnswer()).contains("NOT_RECEIVED");
        assertThat(count("resends_exhausted")).as("it goes on asking").isEqualTo(3);
        assertThat(world.provider.inquiries()).isEqualTo(RegulatorySubmissions.MAX_RESENDS + 3);
        assertThat(count("not_resent_month_moved")).isZero();
        assertThat(count("not_resent_overtaken")).isZero();
        assertThat(world.submissions.declareUndelivered(UOW, sent.id(), driver.askBeforeNonDelivery(row(), world.correlation()), REASON, SENDER, world.correlation()).status()).isEqualTo(SubmissionStatus.UNDELIVERED);
    }

    @Test
    @DisplayName("SUBMITTED with nothing sent, the month reopened and re-closed before the first attempt: it asks and never sends, the leg never sends either, each withheld send counted for what it is, and a person's door is open")
    void aMonthMovedBeforeTheFirstAttemptIsNeverSent() {
        world.reopened(JANUARY);
        world.reclosed(JANUARY);

        assertThat(driver.submitIfDue(sent.id())).isTrue();
        assertThat(world.provider.submits()).as("the regulator is handed nothing").isZero();
        assertThat(world.provider.inquiries()).isEqualTo(1);
        assertThat(world.provider.contents()).isEmpty();
        assertThat(row().status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(row().resends()).isZero();
        assertThat(row().lastAnswer()).contains("NOT_RECEIVED");
        assertThat(count("send_withheld_month_moved")).isEqualTo(1);
        assertThat(count("not_resent_month_moved")).isEqualTo(1);
        assertThat(count("resent")).isZero();

        clock.advance(INTERVAL.plusSeconds(1));
        driver.inquireDue();
        assertThat(world.provider.submits()).isZero();
        assertThat(count("not_resent_month_moved")).isEqualTo(2);
        assertThat(world.submissions.view(UOW, sent.id()).nonDeliveryOpen()).isTrue();
    }

    @Test
    @DisplayName("a withheld send is counted before the call: the month moved and the provider down, the first attempt's inquiry throws, and it is still counted, once")
    void aWithheldSendIsCountedThoughTheProviderIsDown() {
        world.reopened(JANUARY);
        world.reclosed(JANUARY);
        world.provider.nextInquiry(ScriptedRegulatoryProvider.unreachable());

        assertThat(driver.submitIfDue(sent.id())).isFalse();
        assertThat(count("send_withheld_month_moved")).isEqualTo(1);
        assertThat(count("call_failed")).isEqualTo(1);
        assertThat(world.provider.submits()).isZero();

        clock.advance(LEASE.plusSeconds(1));
        driver.inquireDue();
        assertThat(count("send_withheld_month_moved")).as("that was the first attempt's; this is the second").isEqualTo(1);
        assertThat(world.provider.submits()).isZero();
    }

    @Test
    @DisplayName("the first send failed on the wire, then the month reopened: the next attempt asks, is told not received, and does not send again")
    void aResendAfterAReopenIsRefused() {
        world.provider.nextSubmit(ScriptedRegulatoryProvider.unreachable());
        driver.submitIfDue(sent.id());
        assertThat(world.provider.submits()).isEqualTo(1);
        world.reopened(JANUARY);

        clock.advance(LEASE.plusSeconds(1));
        driver.inquireDue();
        assertThat(world.provider.inquiries()).isEqualTo(1);
        assertThat(world.provider.submits()).as("no second send").isEqualTo(1);
        assertThat(row().resends()).isZero();
        assertThat(count("not_resent_month_moved")).isEqualTo(1);
        assertThat(count("resent")).isZero();
        assertThat(count("send_withheld_month_moved")).as("that is the first attempt's, and this was the second").isZero();
        assertThat(world.audit).extracting(record -> record.operation().code()).doesNotContain("regulatory.SubmissionResent");
    }

    @Test
    @DisplayName("a permit refused because another worker holds the row is named for that, never as resends spent")
    void aPermitOvertakenIsNamedForIt() {
        transactions.inTransaction(uow -> world.submissions.claim(uow, sent.id(), LEASE)); // A worker claimed and died.
        clock.advance(LEASE.plusSeconds(1));
        int[] calls = {0};
        AccountingTransactions racing = new AccountingTransactions() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                calls[0]++;
                if (calls[0] == 3) { // The permit's: another instance claims first.
                    clock.advance(INTERVAL.plusSeconds(1));
                    world.submissions.claim(UOW, sent.id(), LEASE);
                }
                return work.apply(UOW);
            }
        };
        new RegulatorySubmissionDriver(world.submissions, racing, metrics, legs, AccountingFlowScope.NONE, LEASE, INTERVAL, 1).inquireDue();

        assertThat(calls[0]).isGreaterThanOrEqualTo(3);
        assertThat(count("not_resent_overtaken")).isEqualTo(1);
        assertThat(count("resends_exhausted")).isZero();
        assertThat(world.provider.submits()).isZero();
    }

    @Test
    @DisplayName("the non-delivery door's one question: on no transaction, as the platform; its answer kept; a call that fails keeps nothing and answers empty, counted")
    void theDoorsQuestion() {
        transactions.inTransaction(uow -> world.submissions.claim(uow, sent.id(), LEASE));
        world.store.await(UOW, sent.id(), 1, INTERVAL, Duration.ofDays(1), "NOT_RECEIVED", Optional.empty());
        int kept = world.store.keptAnswers().size();

        world.provider.nextInquiry(ScriptedRegulatoryProvider.unreachable());
        assertThat(driver.askBeforeNonDelivery(row(), world.correlation())).isEmpty();
        assertThat(world.store.keptAnswers()).hasSize(kept);
        assertThat(count("call_failed")).isEqualTo(1);

        List<Boolean> during = new ArrayList<>();
        List<Actor> acting = new ArrayList<>();
        world.provider.nextInquiry(() -> {
            during.add(inTransaction);
            acting.add(SecurityContext.require());
            return Answer.notReceived(bytes("now"));
        });
        assertThat(driver.askBeforeNonDelivery(row(), world.correlation()).map(RegulatorySubmissions.Recorded::disposition)).contains(RegulatorySubmissions.Disposition.NOT_RECEIVED);
        assertThat(during).containsExactly(false);
        assertThat(acting.get(0).type()).isEqualTo(ActorType.SYSTEM);
        assertThat(world.store.keptAnswers()).hasSize(kept + 1);
        assertThat(world.provider.inquiries()).as("one question each").isEqualTo(2);
    }

    @Test
    @DisplayName("a record that fails to commit: counted, and the next claim asks and records it")
    void aRecordThatFailsIsAskedAgain() {
        AccountingTransactions failing = new AccountingTransactions() {
            private int calls;

            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                calls++;
                if (calls == 2) {
                    throw new IllegalStateException("the commit failed");
                }
                return work.apply(UOW);
            }
        };
        RegulatorySubmissionDriver flaky = new RegulatorySubmissionDriver(world.submissions, failing, metrics, legs, AccountingFlowScope.NONE, LEASE, INTERVAL, 10);
        assertThat(flaky.submitIfDue(sent.id())).isFalse();
        assertThat(count("record_failed")).isEqualTo(1);
        assertThat(row().status()).isEqualTo(SubmissionStatus.SUBMITTED);

        clock.advance(LEASE.plusSeconds(1));
        assertThat(driver.inquireDue()).isEqualTo(1);
        assertThat(row().status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(world.provider.sendsReceived(sent.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("a claim that fails is counted leg.failed{leg=inquiry} from the first run, ends the tick, and throws nothing; the door's drive likewise")
    void aFailedClaimIsMeteredAndEndsTheTick() {
        AccountingTransactions down = new AccountingTransactions() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                throw new IllegalStateException("the database is down");
            }
        };
        RegulatorySubmissionDriver failing = new RegulatorySubmissionDriver(world.submissions, down, metrics, legs, AccountingFlowScope.NONE, LEASE, INTERVAL, 10);

        assertThat(failing.inquireDue()).isZero();
        assertThat(failing.submitIfDue(sent.id())).isFalse();
        assertThat(registry.get(CloseRunMetrics.LEG_FAILED).tag("leg", AccountingCloseSchedule.INQUIRY_LEG).counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the tick is bounded; each return is its own: one's failed call does not stop the next")
    void theTickIsBoundedAndEachReturnIsItsOwn() {
        world.closed(RegulatoryWorld.FEBRUARY);
        world.issued(RegulatoryWorld.FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        RegulatorySubmissions.Acted second = world.sent(obligation, RegulatoryWorld.FEBRUARY);
        world.provider.nextSubmit(ScriptedRegulatoryProvider.unreachable());

        RegulatorySubmissionDriver onePerTick = new RegulatorySubmissionDriver(world.submissions, transactions, metrics, legs, AccountingFlowScope.NONE, LEASE, INTERVAL, 1);
        assertThat(onePerTick.inquireDue()).isZero();
        assertThat(world.provider.submits()).isEqualTo(1);

        assertThat(driver.inquireDue()).as("the other was due; the failed one is under its lease").isEqualTo(1);
        assertThat(world.store.find(UOW, second.id()).orElseThrow().attempts() + row().attempts()).isEqualTo(2);

        // Each its own: two due in one tick, the first call failing, the second still goes.
        java.time.YearMonth march = java.time.YearMonth.of(2096, 3);
        java.time.YearMonth april = java.time.YearMonth.of(2096, 4);
        world.closed(march);
        world.closed(april);
        world.issued(march, RegulatoryWorld.body(1, 1, 1, 1));
        world.issued(april, RegulatoryWorld.body(1, 1, 1, 1));
        world.sent(obligation, march);
        world.sent(obligation, april);
        int sends = world.provider.submits();
        world.provider.nextSubmit(ScriptedRegulatoryProvider.unreachable());
        assertThat(driver.inquireDue()).isEqualTo(1);
        assertThat(world.provider.submits()).isEqualTo(sends + 2);
    }

    @Test
    @DisplayName("an answer that cannot be believed decides nothing and is metered: kept, the row SUBMITTED, its permit moved")
    void anAnswerThatCannotBeBelievedDecidesNothing() {
        world.provider.nextSubmit(() -> ScriptedRegulatoryProvider.answering(Outcome.ACKNOWLEDGED, Optional.of("<script>"), Optional.empty(), "<script>alert(1)</script>"));
        driver.submitIfDue(sent.id());

        assertThat(row().status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(row().lastAnswer()).contains("UNKNOWN");
        assertThat(row().providerReference()).isEmpty();
        assertThat(count("answer_anomaly")).isEqualTo(1);
        assertThat(new String(world.store.answers(UOW, sent.id(), 100).get(0).raw(), StandardCharsets.UTF_8)).isEqualTo("<script>alert(1)</script>");
        assertThat(row().nextInquiryAt()).as("its permit moved").contains(clock.instant().plus(INTERVAL));
    }

    @Test
    @DisplayName("configuration refused at start-up unless positive")
    void configurationIsPositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RegulatorySubmissionDriver(world.submissions, transactions, metrics, legs, AccountingFlowScope.NONE, Duration.ZERO, INTERVAL, 10)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RegulatorySubmissionDriver(world.submissions, transactions, metrics, legs, AccountingFlowScope.NONE, LEASE, Duration.ofSeconds(-1), 10)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RegulatorySubmissionDriver(world.submissions, transactions, metrics, legs, AccountingFlowScope.NONE, LEASE, INTERVAL, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ten instances race one due return: one claim, one call; the other nine find nothing due. The claim's atomicity is the store's: the SKIP LOCKED itself is the owed database suite's")
    void tenInstancesOneCall() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            outcomes.add(pool.submit(() -> {
                start.await();
                return new RegulatorySubmissionDriver(world.submissions, transactions, metrics, legs, AccountingFlowScope.NONE, LEASE, INTERVAL, 10).inquireDue();
            }));
        }
        start.countDown();
        int recorded = 0;
        for (Future<Integer> outcome : outcomes) {
            recorded += outcome.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(recorded).isEqualTo(1);
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.returnsHeld(sent.id())).isEqualTo(1);
        assertThat(row().attempts()).isEqualTo(1);
    }
}
