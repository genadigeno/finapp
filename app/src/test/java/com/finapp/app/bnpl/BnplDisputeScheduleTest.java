package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplActingParty;
import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplDisputeId;
import com.finapp.bnpl.BnplDisputeStore;
import com.finapp.bnpl.BnplDisputes;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRefundStore;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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
    @DisplayName("a stopping schedule takes no further step: the rest of the claimed page keeps the claim's cadence (P15-TSK-009)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aStoppingScheduleTakesNoFurtherStep() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        IdGenerator generator = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        List<BnplDisputeId> claimed = List.of(BnplDisputeId.next(generator), BnplDisputeId.next(generator),
                BnplDisputeId.next(generator));
        List<BnplDisputeId> stepped = new ArrayList<>();
        BnplDisputeStore store = (BnplDisputeStore) Proxy.newProxyInstance(BnplDisputeStore.class.getClassLoader(),
                new Class<?>[] {BnplDisputeStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "claimLapsed" -> claimed;
                    case "find" -> {
                        stepped.add((BnplDisputeId) args[1]);
                        executor.shutdown();
                        yield Optional.empty();
                    }
                    default -> throw new AssertionError(method.getName());
                });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.systemUTC();
        BnplDisputes disputes = new BnplDisputes(store, absent(BnplRefundStore.class), absent(BnplServicingStore.class),
                absent(BnplPurchaseStore.class), absent(BnplTermsStore.class), absent(BnplRefunds.class),
                absent(BnplMerchants.class), absent(BnplBook.class), absent(BnplActingParty.class), absent(OutboxWriter.class),
                absent(AuditWriter.class), new IdGenerator(clock, new SecureRandom()), clock);
        BnplDisputeSchedule schedule = new BnplDisputeSchedule(disputes, absent(BnplAccruals.class), noTransactions(),
                new BnplDisputeMetrics(registry), new BnplRefundMetrics(registry), new IdGenerator(clock, new SecureRandom()),
                Duration.ofSeconds(1), 20, Duration.ofSeconds(1));
        schedule.drain().started(executor);

        assertThat(schedule.sweepOnce()).as("what the one claim took").isEqualTo(3);
        assertThat(stepped).as("the step in flight, then no other").containsExactly(claimed.get(0));
    }

    /** A collaborator this path never calls: an interface answering nothing, or a class built without its constructor. */
    @SuppressWarnings("unchecked")
    private static <T> T absent(Class<T> type) {
        if (type.isInterface()) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                Class<?> answer = method.getReturnType();
                if (answer == boolean.class) {
                    return false;
                }
                if (answer == int.class) {
                    return 0;
                }
                return answer == Connection.class ? absent(Connection.class) : null;
            });
        }
        return new SpringObjenesis().newInstance(type);
    }

    /**
     * The real transaction wrapper over a manager with nothing to manage. Its connection source is reached through the
     * constructor's own parameter type, so this hermetic suite names no {@code DataSource}: the taxonomy's database signature.
     */
    private static BnplTransactions noTransactions() {
        try {
            Constructor<?> constructor = BnplTransactions.class.getDeclaredConstructors()[0];
            return (BnplTransactions) constructor.newInstance(
                    new TransactionTemplate(new NoTransactions()), absent(constructor.getParameterTypes()[1]));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class NoTransactions extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
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
