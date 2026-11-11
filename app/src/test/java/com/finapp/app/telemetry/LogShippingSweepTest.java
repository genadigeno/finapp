package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.DueItemId;
import com.finapp.creditmath.DueState;
import com.finapp.lending.LoanAmendment;
import com.finapp.lending.LoanAmendments;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What leaves a replica as a log record (`P15-TSK-023`, ADR-0124 decision 1): the console's own ECS line, byte for byte, with the
 * flow's correlation beside it; never the SDK's own complaints; and - the needle sweep over the lending records that printed money
 * (the residue A5-08) - no planted amount, in the console or in what was exported. Read from the bytes that really left the
 * process for a stand-in collector on loopback, never from a list appender (ADR-0019 decision 5).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("each replica ships its console's ECS lines, and no lending record ships an amount (P15-TSK-023)")
class LogShippingSweepTest {

    private static final Logger log = LoggerFactory.getLogger(LogShippingSweepTest.class);

    /** The planted amount: a figure nothing else in a test run prints. */
    static final String AMOUNT = "7319.47";

    private static final OtlpCapture.Recording COLLECTOR = new OtlpCapture.Recording();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final com.finapp.sharedkernel.id.IdGenerator IDS =
            new com.finapp.sharedkernel.id.IdGenerator(java.time.Clock.systemUTC(), new java.security.SecureRandom());

    @Autowired private SdkLoggerProvider loggers;

    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry registry) {
        registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> COLLECTOR.endpoint("/v1/logs"));
    }

    @AfterAll
    static void stop() {
        COLLECTOR.close();
    }

    @Test
    @DisplayName("the exported record's body is the console's ECS line for the same event, and the flow's correlation travels beside it")
    @SuppressWarnings("try")
    void theConsoleLineIsWhatShips(CapturedOutput output) {
        String marker = "shipped-" + UUID.randomUUID();
        String correlation = "correlation-" + UUID.randomUUID();
        try (CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(correlation)))) {
            log.info("a line to ship {}", marker);
        }
        flush();
        String line = output.getAll().lines().filter(each -> each.contains(marker)).findFirst().orElseThrow();
        assertThat(line).as("the console's line is ECS JSON").startsWith("{").contains(correlation);
        assertThat(COLLECTOR.received("/v1/logs")).as("the console's line, as printed").contains(line).contains(correlation);
    }

    @Test
    @DisplayName("the SDK's own loggers are never shipped: an export failure must not become a record to export")
    void theSdksOwnLinesAreNotShipped(CapturedOutput output) {
        String marker = "sdk-" + UUID.randomUUID();
        LoggerFactory.getLogger(OtlpLogAppender.SDK_LOGGERS + ".exporter.planted").warn("an export failed {}", marker);
        String control = "control-" + UUID.randomUUID();
        log.info("a line after it {}", control);
        flush();
        assertThat(output.getAll()).as("printed").contains(marker);
        assertThat(COLLECTOR.received("/v1/logs")).contains(control).doesNotContain(marker);
    }

    @Test
    @DisplayName("the needle sweep: each lending record that printed money, logged with a planted amount, ships no amount and prints none")
    void noLendingRecordShipsAnAmount(CapturedOutput output) throws Exception {
        Money planted = Money.of(new BigDecimal(AMOUNT), EUR);
        String marker = "records-" + UUID.randomUUID();
        List<Object> records = new ArrayList<>();
        records.add(new DueState.DueItem(DueItemId.of(IDS.next()), LocalDate.of(2026, 11, 11), planted, planted, planted));
        records.add(new LoanAmendments.Accepted(Mockito.mock(LoanAmendment.class), 2, 3, planted, false));
        records.add(privateRecord("com.finapp.lending.LoanAmendments$Plan", planted));
        records.add(privateRecord("com.finapp.lending.JdbcRepaymentStore$Head", planted));
        for (Object record : records) {
            log.info("{} {}", marker, record);
        }
        flush();
        String shipped = COLLECTOR.received("/v1/logs");
        assertThat(shipped).as("the control: every record's line was shipped")
                .contains("DueItem[", "Accepted[redacted]", "Plan[redacted]", "Head[redacted]");
        assertThat(shipped).as("no amount left the process").doesNotContain(AMOUNT);
        assertThat(output.getAll()).as("and none was printed").contains(marker).doesNotContain(AMOUNT);
    }

    /** A private record of lending's, built by its canonical constructor with the planted amount in every Money component. */
    private static Object privateRecord(String name, Money planted) throws Exception {
        Class<?> type = Class.forName(name);
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = types[i] == Money.class ? planted : types[i] == int.class ? 1 : types[i] == boolean.class ? Boolean.FALSE : null;
        }
        Constructor<?> constructor = type.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private void flush() {
        loggers.forceFlush().join(10, TimeUnit.SECONDS);
    }
}
