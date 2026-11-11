package com.finapp.app.telemetry;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Attaches {@link OtlpLogAppender} to the root logger beside the console, once the OpenTelemetry SDK exists, and detaches it when
 * the context closes (`P15-TSK-023`). Built only where a log endpoint is configured, so a developer's machine and every test log
 * to the console alone, as before.
 *
 * <p>The appender borrows the console appender's own encoder: the record's body is then the console's ECS line byte for byte.
 * With no console appender to borrow from (a logging configuration nobody here wrote), nothing is attached and a warning says so:
 * telemetry is never a reason for a replica not to start.
 */
@Slf4j
final class OtlpLogShipping implements InitializingBean, DisposableBean {

    /** The appender Spring Boot's own Logback configuration names for the console. */
    static final String CONSOLE = "CONSOLE";

    private final OpenTelemetry openTelemetry;
    private OtlpLogAppender appender;
    private ch.qos.logback.classic.Logger root;

    OtlpLogShipping(OpenTelemetry openTelemetry) {
        this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
    }

    @Override
    public void afterPropertiesSet() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            log.warn("Log records are not shipped: the logging backend is not Logback");
            return;
        }
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> console = root.getAppender(CONSOLE);
        if (!(console instanceof OutputStreamAppender<ILoggingEvent> stream) || stream.getEncoder() == null) {
            log.warn("Log records are not shipped: no console appender to take the ECS line from");
            return;
        }
        appender = new OtlpLogAppender(stream.getEncoder(), openTelemetry.getLogsBridge().get("finapp"));
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
    }

    @Override
    public void destroy() {
        if (appender != null) {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
