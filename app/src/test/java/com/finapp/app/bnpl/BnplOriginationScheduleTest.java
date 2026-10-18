package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplApplicationStore;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplOfferStore;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The BNPL origination sweep's wiring, hermetic (`P12-TSK-011`; PHASE_12_PLAN.md section 7.5): off in test contexts and
 * on by default elsewhere, its gauge registered eagerly either way, its configuration refused when not positive; the
 * meters registered eagerly and counting only a step that moved the application; and the three hints routing each event
 * to its permit - and a version this build does not know to nothing. The ten-sweeper race is
 * {@code BnplOriginationDatabaseTest}'s.
 */
@DisplayName("the BNPL origination sweep: off in tests, its gauge, its meters and its hints (P12-TSK-011)")
class BnplOriginationScheduleTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("off in test contexts, on by default: the bean is conditional on the property, missing meaning on, and the"
            + " test properties turn it off")
    void offInTestContexts() throws NoSuchMethodException, IOException {
        Method bean = BnplOfferBeans.class.getDeclaredMethod("bnplOriginationSchedule", BnplOrigination.class,
                BnplTransactions.class, BnplOriginationMetrics.class, Duration.class, int.class, Duration.class);
        ConditionalOnProperty condition = bean.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly("finapp.bnpl.origination.sweeper.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
        assertThat(Files.readString(Path.of("src", "test", "resources", "application.properties")))
                .contains("finapp.bnpl.origination.sweeper.enabled=false");
    }

    @Test
    @DisplayName("finapp.bnpl.origination.sweeper.enabled is registered eagerly - 1 where the sweep runs, 0 where it does not")
    void theGauge() {
        SimpleMeterRegistry on = new SimpleMeterRegistry();
        new BnplOfferBeans().bnplOriginationSweeperEnabled(true, on);
        assertThat(on.get("finapp.bnpl.origination.sweeper.enabled").gauge().value()).isEqualTo(1.0);
        SimpleMeterRegistry off = new SimpleMeterRegistry();
        Gauge gauge = new BnplOfferBeans().bnplOriginationSweeperEnabled(false, off);
        assertThat(gauge.value()).isZero();
    }

    @Test
    @DisplayName("a poll, a permit or a batch that is not positive is refused at construction")
    void theConfigurationIsPositive() {
        BnplOrigination origination = origination(new ArrayList<>());
        BnplOriginationMetrics metrics = new BnplOriginationMetrics(new SimpleMeterRegistry());
        // No transactions handed: the configuration is judged before anything else (and no DataSource is named, so the
        // suite stays in the unit tier - TestTaxonomyTest).
        BnplTransactions transactions = null;
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new BnplOriginationSchedule(origination, transactions, metrics, Duration.ZERO, 20,
                second)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplOriginationSchedule(origination, transactions, metrics, second, 20,
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplOriginationSchedule(origination, transactions, metrics, second, 0, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplOriginationSchedule(origination, transactions, metrics, second, 20, second))
                .as("a valid configuration still needs its collaborators").isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("every meter series is registered at zero; only a step that moved the application counts - and its"
            + " eligibility signal beside it")
    void theMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplOriginationMetrics metrics = new BnplOriginationMetrics(registry);
        for (BnplProduct product : BnplProduct.values()) {
            for (BnplOriginationMetrics.Outcome outcome : BnplOriginationMetrics.Outcome.values()) {
                assertThat(offer(registry, product, outcome.name().toLowerCase(java.util.Locale.ROOT))).isZero();
            }
            assertThat(eligibility(registry, product, "eligible")).isZero();
            assertThat(eligibility(registry, product, "not_eligible")).isZero();
        }
        assertThat(registry.get(BnplOriginationMetrics.ORIGINATION).tags("kind", "application", "outcome", "failed")
                .counter().count()).isZero();
        metrics.record(new BnplOrigination.Outcome(BnplOrigination.Outcome.Kind.NOTHING, Optional.empty(),
                Optional.empty(), Optional.empty()));
        metrics.record(new BnplOrigination.Outcome(BnplOrigination.Outcome.Kind.WAITING,
                Optional.of(BnplProduct.BNPL_PAY_IN_4), Optional.empty(), Optional.empty()));
        metrics.record(new BnplOrigination.Outcome(BnplOrigination.Outcome.Kind.OFFERED,
                Optional.of(BnplProduct.BNPL_PAY_IN_4), Optional.empty(),
                Optional.of(BnplOrigination.Eligibility.ELIGIBLE)));
        metrics.record(new BnplOrigination.Outcome(BnplOrigination.Outcome.Kind.DECLINED,
                Optional.of(BnplProduct.BNPL_PAY_IN_N), Optional.empty(),
                Optional.of(BnplOrigination.Eligibility.NOT_ELIGIBLE)));
        metrics.failed(BnplOriginationMetrics.Step.OFFER);
        assertThat(offer(registry, BnplProduct.BNPL_PAY_IN_4, "offered")).isEqualTo(1.0);
        assertThat(offer(registry, BnplProduct.BNPL_PAY_IN_N, "application_declined")).isEqualTo(1.0);
        assertThat(eligibility(registry, BnplProduct.BNPL_PAY_IN_4, "eligible")).isEqualTo(1.0);
        assertThat(eligibility(registry, BnplProduct.BNPL_PAY_IN_N, "not_eligible")).isEqualTo(1.0);
        assertThat(registry.get(BnplOriginationMetrics.ORIGINATION).tags("kind", "offer", "outcome", "failed")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("each hint pulls its own permit - the decision through credit's read, the request, the session - and an"
            + " unknown version does nothing; each names its topic, event type and the bnpl consumer group")
    void theHints() {
        List<String> calls = new ArrayList<>();
        BnplOrigination origination = origination(calls);
        UUID aggregate = UUID.randomUUID();
        for (BnplOriginationHint.Kind kind : BnplOriginationHint.Kind.values()) {
            BnplOriginationHint hint = new BnplOriginationHint(origination, kind);
            assertThat(hint.consumerName()).startsWith("bnpl.");
            hint.handle(unsupported(Connection.class), event(hint.eventType(), 1, aggregate));
            hint.handle(unsupported(Connection.class), event(hint.eventType(), 9, aggregate));
        }
        assertThat(calls).containsExactly("requestOf " + aggregate, "hintRequest " + aggregate,
                "hintRequest " + aggregate, "hintSession " + aggregate);
        assertThat(new BnplOriginationHint(origination, BnplOriginationHint.Kind.SESSION_EXPIRED).topic())
                .isEqualTo("finapp.checkout");
        assertThat(new BnplOriginationHint(origination, BnplOriginationHint.Kind.DECISION_RECORDED).topic())
                .isEqualTo("finapp.credit");
    }

    // ------------------------------------------------------------------ plumbing

    /** An origination whose stores record the hint calls; credit resolves any decision to the same id as its request. */
    @SuppressWarnings("unchecked")
    private static BnplOrigination origination(List<String> calls) {
        BnplApplicationStore applications = recording(BnplApplicationStore.class, calls);
        BnplCreditDecisions credit = (BnplCreditDecisions) Proxy.newProxyInstance(
                BnplOriginationScheduleTest.class.getClassLoader(), new Class<?>[] {BnplCreditDecisions.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("requestOf")) {
                        calls.add("requestOf " + args[1]);
                        return Optional.of((UUID) args[1]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new BnplOrigination(applications, unsupported(BnplOfferStore.class),
                new BnplTermsAdministration(unsupported(BnplTermsStore.class), unsupported(AuditWriter.class),
                        unsupported(OutboxWriter.class), IDS, CLOCK, product -> {
                            throw new UnsupportedOperationException();
                        }),
                credit, unsupported(BnplCheckoutSessions.class), unsupported(OutboxWriter.class), IDS, CLOCK);
    }

    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<T> type, List<String> calls) {
        return (T) Proxy.newProxyInstance(BnplOriginationScheduleTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("hint")) {
                        calls.add(method.getName() + " " + args[1]);
                        return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        if (!type.isInterface()) {
            return null;
        }
        return (T) Proxy.newProxyInstance(BnplOriginationScheduleTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ReceivedEvent event(String type, int version, UUID aggregate) {
        return new ReceivedEvent(EventId.next(IDS), type, version, 1, aggregate, "aggregate", Instant.now(), "credit",
                CorrelationId.of("p12-tsk-011-hint"), CausationId.of("p12-tsk-011-hint"), "application/json",
                new byte[0]);
    }

    private static double offer(SimpleMeterRegistry registry, BnplProduct product, String outcome) {
        return registry.get(BnplOriginationMetrics.OFFER).tags("product", product.name(), "outcome", outcome).counter()
                .count();
    }

    private static double eligibility(SimpleMeterRegistry registry, BnplProduct product, String outcome) {
        return registry.get(BnplOriginationMetrics.ELIGIBILITY).tags("product", product.name(), "outcome", outcome)
                .counter().count();
    }
}
