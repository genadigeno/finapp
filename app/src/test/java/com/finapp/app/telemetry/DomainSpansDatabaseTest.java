package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.reconciliation.BreakResolutionDesk;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.telemetry.TraceAttributes;
import com.finapp.reconciliation.Matching;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The domain spans over the REAL composition (`P8-TSK-024`, `PHASE_8_PLAN.md` §15's tracing),
 * read back from the SDK the way {@code TraceAcrossDatabaseTest} reads the request's: a settlement
 * file pulled, parsed and accepted, a matching sweep, and a resolution door - and the spans those
 * legs actually exported, under their domain names, each carrying identifiers alone.
 *
 * <p>The identifier rule (ADR-0072, {@code INV-AUD-02}) is asserted over every domain span and
 * every database-connection span the window recorded: an attribute is one of the six identifier
 * keys with a UUID value, the failure's {@code error.type} (a class name), or the platform's own
 * correlation and causation stamps. And no span at all - whatever produced it - carries the
 * report's amounts or references.
 *
 * <p>{@code reconciliation.age} is NOT driven here: the ageing sweep acts on every source's
 * overdue expectations in the shared container, which would raise breaks under other suites'
 * fixtures. Its span is the module's {@code ReconciliationSweep} wiring alone.
 */
@Tag("database")
@SpringBootTest
@Import(RecordedSpans.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the domain spans over the composition (P8-TSK-024)")
class DomainSpansDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String SOURCE = "simulated-psp.settlement";
    /** The seeded register's literal id for {@link #SOURCE} (settlement V002). */
    private static final String SOURCE_ID = "01a0e2bc-8200-7001-8000-000000000001";

    /** The platform's own stamps, beside the identifiers a domain span may carry. */
    private static final Set<String> PLATFORM_KEYS =
            Set.of(TraceAttributes.CORRELATION_ID, TraceAttributes.CAUSATION_ID);

    private static final String ERROR_TYPE = "error.type";
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    @Autowired private InMemorySpanExporter spans;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private Matching matching;
    @Autowired private BreakResolutionDesk desk;
    @Autowired private io.micrometer.core.instrument.MeterRegistry registry;

    @BeforeEach
    void clearRecordedSpans() {
        spans.reset();
    }

    @Test
    @DisplayName("receive, parse, accept, a matching chunk and a resolution door each record"
            + " their span, and every domain span carries identifiers alone - and the"
            + " composition counts what those legs committed")
    void theLegsRecordIdentifierOnlySpans() {
        String marker = suffix();
        String batchRef = "PSPB-SPAN-" + marker;
        String captureRef = "PSP-CAP-" + marker;
        String remittanceRef = "PSP-REM-15" + digits(marker);
        String report =
                "H,SIM_PSP_CSV,1," + batchRef + ",EUR,2026-09-25\n"
                        + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,,"
                        + captureRef + ",,,ORD-" + marker + ",Desk sale\n"
                        + "T,1,98.25," + remittanceRef + "\n";

        double acceptedBefore = counted(SettlementMeters.BATCH_ACCEPTED);
        double itemsBefore = counted(ReconciliationOutcomeMeters.ITEM);
        long runsBefore = timed(ReconciliationOutcomeMeters.RUN_LATENCY);
        long acceptLatencyBefore = acceptStageTimed();

        // The intake legs: the door, the parse leg, the accept leg.
        String fileId = pulled(report).toString();
        parsing.sweep();
        acceptance.sweep();
        // The run leg: the accepted batch's run is this source's work.
        matching.sweep();
        // A resolution door, refused inside its span: no such resolution.
        String resolutionId = UUID.randomUUID().toString();
        try (SecurityContext.Scope operator =
                        SecurityContext.enter(
                                new Actor("span-operator-" + marker, ActorType.EMPLOYEE));
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> desk.withdraw(resolutionId))
                    .isInstanceOf(RuntimeException.class);
        }

        // The counters through the REAL composition: each leg's committed fact counted
        // after its commit, under this source's declared code (ADR-0072).
        assertThat(counted(SettlementMeters.BATCH_ACCEPTED) - acceptedBefore)
                .as("the accepted batch counted").isGreaterThanOrEqualTo(1.0d);
        assertThat(acceptStageTimed() - acceptLatencyBefore)
                .as("its door-to-accepted age recorded").isGreaterThanOrEqualTo(1L);
        assertThat(counted(ReconciliationOutcomeMeters.ITEM) - itemsBefore)
                .as("the run's items counted by outcome").isGreaterThanOrEqualTo(1.0d);
        assertThat(timed(ReconciliationOutcomeMeters.RUN_LATENCY) - runsBefore)
                .as("the run's age recorded").isGreaterThanOrEqualTo(1L);

        List<SpanData> recorded = spans.getFinishedSpanItems();

        assertThat(named(recorded, "settlement.receive"))
                .as("the door records settlement.receive")
                .isNotEmpty();
        assertThat(named(recorded, "settlement.parse"))
                .as("the parse leg records settlement.parse for this file")
                .anySatisfy(span -> assertThat(attributes(span)).containsEntry("file.id", fileId));
        assertThat(named(recorded, "settlement.accept"))
                .as("the accept leg records settlement.accept for this file")
                .anySatisfy(span -> assertThat(attributes(span)).containsEntry("file.id", fileId));
        assertThat(named(recorded, "reconciliation.chunk"))
                .as("the run leg records reconciliation.chunk for the file's source")
                .anySatisfy(span ->
                        assertThat(attributes(span)).containsEntry("source.id", SOURCE_ID));
        assertThat(named(recorded, "reconciliation.resolve"))
                .as("the resolution door records reconciliation.resolve, its refusal by class")
                .anySatisfy(span -> assertThat(attributes(span))
                        .containsEntry("resolution.id", resolutionId)
                        .containsKey(ERROR_TYPE));

        // The identifier rule over every domain span and every connection span.
        List<SpanData> judged =
                recorded.stream()
                        .filter(span -> span.getName().startsWith("settlement.")
                                || span.getName().startsWith("reconciliation.")
                                || span.getName().equals(TracedDataSource.SPAN_NAME))
                        .toList();
        assertThat(judged).as("the rule judges something").isNotEmpty();
        for (SpanData span : judged) {
            attributes(span).forEach((key, value) -> {
                if (PLATFORM_KEYS.contains(key)) {
                    return;
                }
                if (key.equals(ERROR_TYPE)) {
                    assertThat(value)
                            .as("span '%s': error.type is a class name, never a message",
                                    span.getName())
                            .matches(CLASS_NAME);
                    return;
                }
                assertThat(Spans.IDENTIFIER_KEYS)
                        .as("span '%s' carries '%s' - an identifier key or nothing",
                                span.getName(), key)
                        .contains(key);
                assertThat(Spans.admits(key, value))
                        .as("span '%s': %s=%s is an identifier's shape", span.getName(), key,
                                value)
                        .isTrue();
            });
        }

        // And no span at all - whatever produced it - carries the report's values.
        for (SpanData span : recorded) {
            for (String value : attributes(span).values()) {
                for (String leaked :
                        List.of("100.00", "98.25", "1.75", batchRef, captureRef,
                                remittanceRef, "ORD-" + marker, "Desk sale")) {
                    assertThat(value)
                            .as("span '%s' leaks %s", span.getName(), leaked)
                            .doesNotContain(leaked);
                }
            }
        }
    }

    // -----------------------------------------------------------------

    private UUID pulled(String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            FileReception.Result result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    reception.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    SOURCE,
                                                    DeliveryChannel.PULL,
                                                    content.getBytes(StandardCharsets.UTF_8),
                                                    Optional.empty(),
                                                    Actor.SYSTEM,
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private double counted(String name) {
        return registry.find(name).tag("source", SOURCE).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private long timed(String name) {
        return registry.find(name).tag("source", SOURCE).timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    private long acceptStageTimed() {
        return registry.find(SettlementMeters.INGESTION_LATENCY).tag("source", SOURCE)
                .tag("stage", SettlementMeters.ACCEPT_STAGE).timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    private static List<SpanData> named(List<SpanData> recorded, String name) {
        return recorded.stream().filter(span -> span.getName().equals(name)).toList();
    }

    private static Map<String, String> attributes(SpanData span) {
        Map<String, String> attributes = new TreeMap<>();
        span.getAttributes()
                .forEach((AttributeKey<?> key, Object value) ->
                        attributes.put(key.getKey(), String.valueOf(value)));
        return attributes;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                .replace('-', 'X');
    }

    /** The remittance shape wants digits; a marker's letters become their code points. */
    private static String digits(String marker) {
        StringBuilder digits = new StringBuilder();
        for (char c : marker.toCharArray()) {
            digits.append(Character.getNumericValue(c) % 10);
        }
        return digits.substring(0, Math.min(8, digits.length()));
    }
}
