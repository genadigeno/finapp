package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.ServicingOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The BNPL servicing sweep's wiring, hermetic (`P12-TSK-015`; PHASE_12_PLAN.md section 7.5): off in test contexts and on
 * by default elsewhere, its gauge registered eagerly either way, its configuration refused when not positive; and the
 * servicing meters registered eagerly, counting each committed step's billings, applications and catch-up. The ten-sweeper
 * race and the failing obligation that never holds the page are {@code BnplBillingDatabaseTest}'s.
 */
@DisplayName("the BNPL servicing sweep: off in tests, its gauge and its meters (P12-TSK-015)")
class BnplServicingScheduleTest {

    @Test
    @DisplayName("off in test contexts, on by default: the bean is conditional on the property, missing meaning on, and the"
            + " test properties turn it off")
    void offInTestContexts() throws NoSuchMethodException, IOException {
        Method bean = BnplServicingBeans.class.getDeclaredMethod("bnplServicingSchedule", BnplServicing.class,
                BnplAccruals.class, BnplTransactions.class, BnplServicingMetrics.class, BnplAccrualMetrics.class,
                Duration.class, int.class, Duration.class);
        ConditionalOnProperty condition = bean.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly("finapp.bnpl.servicing.sweeper.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
        assertThat(Files.readString(Path.of("src", "test", "resources", "application.properties")))
                .contains("finapp.bnpl.servicing.sweeper.enabled=false");
    }

    @Test
    @DisplayName("finapp.bnpl.servicing.sweeper.enabled is registered eagerly - 1 where the sweep runs, 0 where it does not")
    void theGauge() {
        SimpleMeterRegistry on = new SimpleMeterRegistry();
        new BnplServicingBeans().bnplServicingSweeperEnabled(true, on);
        assertThat(on.get("finapp.bnpl.servicing.sweeper.enabled").gauge().value()).isEqualTo(1.0);
        SimpleMeterRegistry off = new SimpleMeterRegistry();
        Gauge gauge = new BnplServicingBeans().bnplServicingSweeperEnabled(false, off);
        assertThat(gauge.value()).isZero();
    }

    @Test
    @DisplayName("a poll, a retry cadence or a batch that is not positive is refused at construction")
    void theConfigurationIsPositive() {
        BnplServicingMetrics metrics = new BnplServicingMetrics(new SimpleMeterRegistry());
        // No servicing or transactions handed: the configuration is judged before anything else (and no DataSource is
        // named, so the suite stays in the unit tier - TestTaxonomyTest).
        BnplServicing servicing = null;
        BnplAccruals accruals = null;
        BnplTransactions transactions = null;
        BnplAccrualMetrics accrualMetrics = BnplAccrualMetricsTest.metrics(new SimpleMeterRegistry());
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> schedule(servicing, accruals, transactions, metrics, accrualMetrics,
                Duration.ZERO, 20, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule(servicing, accruals, transactions, metrics, accrualMetrics,
                second, 20, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule(servicing, accruals, transactions, metrics, accrualMetrics,
                second, 0, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule(servicing, accruals, transactions, metrics, accrualMetrics,
                second, 20, second))
                .as("a valid configuration still needs its collaborators").isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("every servicing series is registered at zero; a committed step counts its outcome, its billings, its"
            + " applications at them and its catch-up - never a figure")
    void theMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplServicingMetrics metrics = new BnplServicingMetrics(registry);
        assertThat(registry.find(BnplServicingMetrics.BILLING).counters()).hasSize(2 * 2);
        assertThat(registry.find(BnplServicingMetrics.SERVICING).counters())
                .as("P12-TSK-018 adds closure/closed").hasSize(6);
        metrics.record(new ServicingOutcome(ServicingOutcome.Kind.SERVICED, Optional.of(BnplProduct.BNPL_PAY_IN_4), 3, 1,
                true));
        metrics.record(new ServicingOutcome(ServicingOutcome.Kind.SERVICED, Optional.of(BnplProduct.BNPL_PAY_IN_4), 0, 0,
                false, true));
        metrics.record(new ServicingOutcome(ServicingOutcome.Kind.BEHIND_ACCRUAL, Optional.of(BnplProduct.BNPL_PAY_IN_N),
                0, 0, false));
        metrics.record(new ServicingOutcome(ServicingOutcome.Kind.NOT_SERVICED, Optional.empty(), 0, 0, false));
        metrics.failed();
        assertThat(billing(registry, "BNPL_PAY_IN_4", "billed")).isEqualTo(3.0);
        assertThat(billing(registry, "BNPL_PAY_IN_4", "credit_applied")).isEqualTo(1.0);
        assertThat(billing(registry, "BNPL_PAY_IN_N", "billed")).isZero();
        assertThat(servicing(registry, "service", "serviced")).isEqualTo(2.0);
        assertThat(servicing(registry, "service", "behind_accrual")).isEqualTo(1.0);
        assertThat(servicing(registry, "service", "not_serviced")).isEqualTo(1.0);
        assertThat(servicing(registry, "service", "failed")).isEqualTo(1.0);
        assertThat(servicing(registry, "catch_up", "applied")).isEqualTo(1.0);
        assertThat(servicing(registry, "closure", "closed")).as("one closure, P12-TSK-018").isEqualTo(1.0);
    }

    private static BnplServicingSchedule schedule(BnplServicing servicing, BnplAccruals accruals,
            BnplTransactions transactions, BnplServicingMetrics metrics, BnplAccrualMetrics accrualMetrics, Duration poll,
            int batch, Duration retry) {
        return new BnplServicingSchedule(servicing, accruals, transactions, metrics, accrualMetrics, poll, batch, retry);
    }

    private static double billing(SimpleMeterRegistry registry, String product, String outcome) {
        return registry.get(BnplServicingMetrics.BILLING).tags("product", product, "outcome", outcome).counter().count();
    }

    private static double servicing(SimpleMeterRegistry registry, String step, String outcome) {
        return registry.get(BnplServicingMetrics.SERVICING).tags("step", step, "outcome", outcome).counter().count();
    }
}
