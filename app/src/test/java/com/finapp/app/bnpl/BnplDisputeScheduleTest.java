package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplDisputes;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The BNPL dispute deadline sweep's wiring, hermetic (`P12-TSK-023`; {@code PHASE_12_PLAN.md} section 7.5): off in test
 * contexts and on by default elsewhere, its gauge registered eagerly either way, its configuration refused when not
 * positive. The deadline's resolution and its race are {@code BnplDisputesTest}'s and {@code BnplDisputeDatabaseTest}'s.
 */
@DisplayName("the BNPL dispute deadline sweep: off in tests, its gauge and its configuration (P12-TSK-023)")
class BnplDisputeScheduleTest {

    @Test
    @DisplayName("off in test contexts, on by default: the bean is conditional on the property, missing meaning on, and the"
            + " test properties turn it off")
    void offInTestContexts() throws NoSuchMethodException, IOException {
        Method bean = BnplDisputeBeans.class.getDeclaredMethod("bnplDisputeSchedule", BnplDisputes.class,
                BnplAccruals.class, BnplTransactions.class, BnplDisputeMetrics.class, BnplRefundMetrics.class,
                IdGenerator.class, Duration.class, int.class, Duration.class, BnplFlowScope.class);
        ConditionalOnProperty condition = bean.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly("finapp.bnpl.dispute.sweeper.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
        assertThat(Files.readString(Path.of("src", "test", "resources", "application.properties")))
                .contains("finapp.bnpl.dispute.sweeper.enabled=false");
    }

    @Test
    @DisplayName("finapp.bnpl.dispute.sweeper.enabled is registered eagerly - 1 where the sweep runs, 0 where it does not")
    void theGauge() {
        SimpleMeterRegistry on = new SimpleMeterRegistry();
        new BnplDisputeBeans().bnplDisputeSweeperEnabled(true, on);
        assertThat(on.get("finapp.bnpl.dispute.sweeper.enabled").gauge().value()).isEqualTo(1.0);
        Gauge off = new BnplDisputeBeans().bnplDisputeSweeperEnabled(false, new SimpleMeterRegistry());
        assertThat(off.value()).isZero();
    }

    @Test
    @DisplayName("a poll, a retry cadence or a batch that is not positive is refused at construction")
    void theConfigurationIsPositive() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplDisputeMetrics metrics = new BnplDisputeMetrics(registry);
        BnplRefundMetrics refundMetrics = new BnplRefundMetrics(registry);
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        // No disputes or transactions handed: the configuration is judged before anything else (and no DataSource is
        // named, so the suite stays in the unit tier - TestTaxonomyTest).
        BnplDisputes disputes = null;
        BnplAccruals accruals = null;
        BnplTransactions transactions = null;
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new BnplDisputeSchedule(disputes, accruals, transactions, metrics, refundMetrics, ids,
                Duration.ZERO, 20, second)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplDisputeSchedule(disputes, accruals, transactions, metrics, refundMetrics, ids,
                second, 20, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplDisputeSchedule(disputes, accruals, transactions, metrics, refundMetrics, ids,
                second, 0, second)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplDisputeSchedule(disputes, accruals, transactions, metrics, refundMetrics, ids,
                second, 20, second)).as("a valid configuration still needs its collaborators")
                .isInstanceOf(NullPointerException.class);
    }
}
