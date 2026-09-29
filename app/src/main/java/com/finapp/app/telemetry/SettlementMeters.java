package com.finapp.app.telemetry;

import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * The settlement ingestion series (`P8-TSK-002`, `PHASE_8_PLAN.md` §15) — a second,
 * independent tally, never truth: the rows are the record.
 *
 * <p><strong>Eager per declared source</strong>, the `P7-TSK-015` rule: every series a
 * dashboard or alert will read exists from the first scrape at zero, because a counter that
 * appears only on its first increment reads as "no data" exactly when "zero" is the signal —
 * and a refusal must be alertable from the FIRST file (ADR-0066 §4). The {@code source} tag is
 * bounded by the compiled register; {@code outcome} by two closed enums.
 */
public final class SettlementMeters {

    /** Deliveries that landed or converged, by source and outcome (new, duplicate). */
    public static final String RECEIVED = "finapp.settlement.file.received";

    /** Deliveries the door refused, by source and reason — alertable from the first file. */
    public static final String REFUSED = "finapp.settlement.delivery.refused";

    /** Files rejected whole, by source and rejection code (`P8-TSK-008`, ADR-0066 §9). */
    public static final String REJECTED = "finapp.settlement.file.rejected";

    /** Door-to-verdict age per intake stage — {@code stage=parse} today (`P8-TSK-008`). */
    public static final String INGESTION_LATENCY = "finapp.settlement.ingestion.latency";

    /** The one intake stage that exists; acceptance brings its own (`P8-TSK-009`). */
    public static final String PARSE_STAGE = "parse";

    private final MeterRegistry registry;

    public SettlementMeters(MeterRegistry registry, SettlementSources sources) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(sources, "sources must not be null");
        for (SettlementSourceDescriptor source : sources.declared()) {
            for (SettlementFileStore.ReceiptOutcome outcome :
                    SettlementFileStore.ReceiptOutcome.values()) {
                // register() alone creates the series at zero; Counter.count() is a double
                // and INV-MON-01's rule bites even a meter (the gauges' exemptions are for
                // readings a test asserts, and nothing here reads one).
                received(source.code(), outcome);
            }
            for (RefusalReason reason : RefusalReason.values()) {
                refused(source.code(), reason);
            }
            for (RejectionCode code : RejectionCode.values()) {
                rejected(source.code(), code);
            }
            latency(source.code());
        }
    }

    public void countReceived(String sourceCode, SettlementFileStore.ReceiptOutcome outcome) {
        received(sourceCode, outcome).increment();
    }

    public void countRefused(String sourceCode, RefusalReason reason) {
        refused(sourceCode, reason).increment();
    }

    public void countRejected(String sourceCode, RejectionCode code) {
        rejected(sourceCode, code).increment();
    }

    public void recordParseLatency(String sourceCode, Duration sinceReceipt) {
        latency(sourceCode).record(sinceReceipt);
    }

    private Counter received(String sourceCode, SettlementFileStore.ReceiptOutcome outcome) {
        return Counter.builder(RECEIVED)
                .tag("source", sourceCode)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .description("Settlement deliveries that landed (new) or converged (duplicate)")
                .register(registry);
    }

    private Counter refused(String sourceCode, RefusalReason reason) {
        return Counter.builder(REFUSED)
                .tag("source", sourceCode)
                .tag("outcome", reason.name().toLowerCase(Locale.ROOT))
                .description("Settlement deliveries the door refused, by reason (ADR-0066)")
                .register(registry);
    }

    private Counter rejected(String sourceCode, RejectionCode code) {
        return Counter.builder(REJECTED)
                .tag("source", sourceCode)
                .tag("outcome", code.name().toLowerCase(Locale.ROOT))
                .description(
                        "Settlement files rejected whole - the parse leg's verdicts and the"
                                + " decline (ADR-0066 §9)")
                .register(registry);
    }

    private Timer latency(String sourceCode) {
        return Timer.builder(INGESTION_LATENCY)
                .tag("source", sourceCode)
                .tag("stage", PARSE_STAGE)
                .description(
                        "Door-to-verdict age of settlement files per intake stage - a slow"
                                + " parse leg ages evidence")
                .register(registry);
    }
}
