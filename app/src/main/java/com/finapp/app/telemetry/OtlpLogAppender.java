package com.finapp.app.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.telemetry.TraceAttributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Ships every log event to the collector as an OTLP log record whose body is <strong>the ECS line the console printed</strong>
 * (`P15-TSK-023`, ADR-0124 decision 1): the same encoder's bytes, so the document an operator searches is the document the pod
 * wrote, with ADR-0019's redaction already applied by that encoder, and nothing a second formatter could get differently.
 *
 * <h2>Why the application ships its own lines</h2>
 *
 * <p>A node agent reading {@code /var/log/pods} needs a {@code hostPath} volume and root, and Pod Security {@code restricted} -
 * enforced on every namespace of this platform - refuses both. So each replica sends its lines through the one telemetry egress
 * it already has, and nothing privileged runs anywhere.
 *
 * <h2>What it never does</h2>
 *
 * <ul>
 *   <li><strong>Block.</strong> {@code emit} hands the record to the SDK's batch processor, whose bounded queue drops when full;
 *       the export runs on that processor's thread. A collector that is down costs records, never a logging call's time.
 *   <li><strong>Feed itself.</strong> The exporter reports its own failures through java.util.logging, which Boot bridges into
 *       this very logger tree. A failure to export would then be a record to export, and so on: {@code io.opentelemetry} loggers
 *       are skipped.
 *   <li><strong>Add a field.</strong> The flow's correlation and causation are copied from the MDC into the two attributes the
 *       collector's allow-list keeps, so a log store can index them; everything else is in the body, as printed.
 * </ul>
 */
final class OtlpLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** The SDK's own loggers: never exported, or an export failure would export itself. */
    static final String SDK_LOGGERS = "io.opentelemetry";

    private static final AttributeKey<String> CORRELATION = AttributeKey.stringKey(TraceAttributes.CORRELATION_ID);
    private static final AttributeKey<String> CAUSATION = AttributeKey.stringKey(TraceAttributes.CAUSATION_ID);

    private final Encoder<ILoggingEvent> encoder;
    private final Logger records;

    OtlpLogAppender(Encoder<ILoggingEvent> encoder, Logger records) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.records = Objects.requireNonNull(records, "records");
        setName("OTLP");
    }

    @Override
    protected void append(ILoggingEvent event) {
        String logger = event.getLoggerName();
        if (logger != null && logger.startsWith(SDK_LOGGERS)) {
            return;
        }
        byte[] encoded = encoder.encode(event);
        String line = encoded == null ? "" : new String(encoded, StandardCharsets.UTF_8).stripTrailing();
        LogRecordBuilder record = records.logRecordBuilder()
                .setTimestamp(event.getInstant())
                .setSeverity(severity(event.getLevel()))
                .setSeverityText(event.getLevel().toString())
                .setBody(line);
        Map<String, String> mdc = event.getMDCPropertyMap();
        String correlation = mdc.get(CorrelationContext.CORRELATION_ID_KEY);
        if (correlation != null) {
            record.setAttribute(CORRELATION, correlation);
        }
        String causation = mdc.get(CorrelationContext.CAUSATION_ID_KEY);
        if (causation != null) {
            record.setAttribute(CAUSATION, causation);
        }
        // The current span's context - the logging thread's own - joins the record to its trace.
        record.emit();
    }

    static Severity severity(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> Severity.ERROR;
            case Level.WARN_INT -> Severity.WARN;
            case Level.INFO_INT -> Severity.INFO;
            case Level.DEBUG_INT -> Severity.DEBUG;
            case Level.TRACE_INT -> Severity.TRACE;
            default -> Severity.UNDEFINED_SEVERITY_NUMBER;
        };
    }
}
