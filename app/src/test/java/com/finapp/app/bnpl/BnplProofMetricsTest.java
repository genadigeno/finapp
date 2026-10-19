package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProofReads;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplRecordStore;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplThreeWayProof;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three BNPL proof gauges (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 15; DOD-OBS): currencies per three-way
 * verdict and obligations per subledger and replay verdict from ONE reading, every series registered eagerly with its
 * closed tag - and absent (NaN), never clean, when the reading fails; the CRITICAL lines count arms and kinds, never an
 * obligation.
 */
@DisplayName("finapp.bnpl.threeway.proof, .subledger.proof and .replay count per verdict from one reading (P12-TSK-025)")
class BnplProofMetricsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("one reading of three obligations - one failing its subledger, one diverged - and three currencies, one"
            + " failing: 2 HOLDS / 1 FAILS, 2 IDENTICAL / 1 DIVERGED, 2 HOLDS / 1 FAILS; unreadable is NaN everywhere")
    void countsPerVerdictAndAbsentWhenUnreadable() {
        BnplProofs.Reading reading = new BnplProofs.Reading(List.of(
                proven(true, BnplReplayProof.Verdict.IDENTICAL),
                proven(false, BnplReplayProof.Verdict.IDENTICAL),
                proven(true, BnplReplayProof.Verdict.DIVERGED)),
                List.of(currency(true), currency(true), currency(false)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplProofMetrics metrics = new BnplProofMetrics(proofs(), work -> reading, Clock.systemUTC(), registry);
        assertThat(metrics.readNow().failing()).isEqualTo(1);
        assertThat(gauge(registry, BnplProofMetrics.SUBLEDGER, "HOLDS")).isEqualTo(2);
        assertThat(gauge(registry, BnplProofMetrics.SUBLEDGER, "FAILS")).isEqualTo(1);
        assertThat(gauge(registry, BnplProofMetrics.REPLAY, "IDENTICAL")).isEqualTo(2);
        assertThat(gauge(registry, BnplProofMetrics.REPLAY, "DIVERGED")).isEqualTo(1);
        assertThat(gauge(registry, BnplProofMetrics.THREE_WAY, "HOLDS")).isEqualTo(2);
        assertThat(gauge(registry, BnplProofMetrics.THREE_WAY, "FAILS")).isEqualTo(1);
        assertThat(registry.getMeters()).as("every series eager, every tag closed: two verdicts each")
                .hasSize(6).allSatisfy(meter -> assertThat(meter.getId().getTags()).singleElement()
                        .satisfies(tag -> assertThat(tag.getKey()).isEqualTo("verdict")));

        SimpleMeterRegistry unreadable = new SimpleMeterRegistry();
        BnplProofMetrics down = new BnplProofMetrics(proofs(), work -> {
            throw new IllegalStateException("the database is down");
        }, Clock.systemUTC(), unreadable);
        for (String verdict : List.of("HOLDS", "FAILS")) {
            assertThat(gauge(unreadable, BnplProofMetrics.SUBLEDGER, verdict)).as(verdict).isNaN();
            assertThat(gauge(unreadable, BnplProofMetrics.THREE_WAY, verdict)).as(verdict).isNaN();
        }
        for (String verdict : List.of("IDENTICAL", "DIVERGED")) {
            assertThat(gauge(unreadable, BnplProofMetrics.REPLAY, verdict)).as(verdict).isNaN();
        }
        assertThatIllegalStateException().isThrownBy(down::readNow);
    }

    @Test
    @DisplayName("the CRITICAL lines count failing arms and diverged kinds - never an obligation id; the floor is never"
            + " below a minute")
    void theCriticalLinesNameNoObligation() {
        BnplProofs.Proven failing = proven(false, BnplReplayProof.Verdict.DIVERGED);
        BnplProofs.Reading reading = new BnplProofs.Reading(List.of(failing), List.of(currency(false)));
        BnplProofMetrics metrics = new BnplProofMetrics(proofs(), work -> reading, Clock.systemUTC(),
                new SimpleMeterRegistry(), BnplProofMetrics.DEFAULT_REFRESH);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BnplProofMetrics.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            metrics.readNow();
        } finally {
            logger.detachAppender(captured);
        }
        assertThat(captured.list).hasSize(3).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .startsWith("CRITICAL").doesNotContain(failing.subledger().obligation().value().toString()));
        assertThat(captured.list.get(0).getFormattedMessage()).contains("MERCHANT_LEG=1");
        assertThat(captured.list.get(1).getFormattedMessage()).contains("UNEXPLAINED_ENTRIES=1");
        assertThat(captured.list.get(2).getFormattedMessage()).contains("REFUND=1");
        assertThat(metrics.refresh()).isEqualTo(Duration.ofMinutes(15));
        assertThatIllegalArgumentException().isThrownBy(() -> new BnplProofMetrics(proofs(), work -> reading,
                Clock.systemUTC(), new SimpleMeterRegistry(), Duration.ofSeconds(10)));
    }

    // ------------------------------------------------------------------

    private static BnplProofs.Proven proven(boolean holds, BnplReplayProof.Verdict verdict) {
        BnplObligationId obligation = BnplObligationId.of(IDS.next());
        BnplSubledgerProof.Verdict subledger = new BnplSubledgerProof.Verdict(obligation, BnplProduct.BNPL_PAY_IN_4,
                BnplObligationStatus.ACTIVE, List.of(), true, holds ? 0 : 1, true, true, true);
        BnplReplayProof.Replay replay = new BnplReplayProof.Replay(obligation, verdict,
                verdict == BnplReplayProof.Verdict.IDENTICAL ? List.of()
                        : List.of(new BnplReplayProof.Finding(BnplReplayProof.Check.REFUND, IDS.next())), 5);
        return new BnplProofs.Proven(subledger, replay);
    }

    private static BnplThreeWayProof.CurrencyVerdict currency(boolean holds) {
        return new BnplThreeWayProof.CurrencyVerdict(EUR, List.of(new BnplThreeWayProof.MerchantLeg(IDS.next(), true,
                holds, 0, 0, true)), List.of(), List.of(), true, true, true);
    }

    /** A reading of nothing: the gauges' reading is the suite's {@code snapshots} answer, never the database. */
    private static BnplProofs proofs() {
        BnplRecordStore none = new BnplRecordStore() {
            @Override
            public List<BnplObligationId> obligationIds(java.sql.Connection unitOfWork) {
                return List.of();
            }

            @Override
            public Optional<com.finapp.bnpl.BnplRecord> read(java.sql.Connection unitOfWork, BnplObligationId id) {
                return Optional.empty();
            }
        };
        BnplProofReads reads = (BnplProofReads) Proxy.newProxyInstance(BnplProofMetricsTest.class.getClassLoader(),
                new Class<?>[] {BnplProofReads.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException("never read here");
                });
        BnplMerchants merchants = (BnplMerchants) Proxy.newProxyInstance(BnplProofMetricsTest.class.getClassLoader(),
                new Class<?>[] {BnplMerchants.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException("never read here");
                });
        return new BnplProofs(none, new BnplSubledgerProof(), new BnplReplayProof(),
                new BnplThreeWayProof(reads, merchants));
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String verdict) {
        Gauge gauge = registry.find(name).tag("verdict", verdict).gauge();
        assertThat(gauge).as(name + "{" + verdict + "}").isNotNull();
        return gauge.value();
    }
}
