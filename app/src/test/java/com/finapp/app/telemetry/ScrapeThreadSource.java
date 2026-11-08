package com.finapp.app.telemetry;

import com.finapp.platform.metrics.GaugeRefresher;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * {@code NoGaugeReadsOnTheScrapeTest}'s data source: it records every connection asked for on the thread that is
 * scraping, and on the gauge refresher's, and refuses both at once - the database is absent anyway, and a refusal
 * now rather than after the pool's 5 s wait keeps the suite quick. Every other thread goes on to the source beneath it.
 * A top-level helper rather than a part of the suite, as {@code HeldConnectionSource} is: {@code TestTier}'s database
 * signature reads {@code javax.sql.DataSource} anywhere in a test class's tree as "obtains a database", which is false
 * here, the data source pointing at a closed port.
 */
final class ScrapeThreadSource {

    private final List<String> askedOnTheScrape = new CopyOnWriteArrayList<>();
    private final List<String> askedOnTheRefresher = new CopyOnWriteArrayList<>();
    private volatile Thread scraping;
    private volatile DataSource watched;

    /** Wraps every {@link DataSource} bean. */
    BeanPostProcessor postProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource dataSource ? watching(dataSource) : bean;
            }
        };
    }

    private Object watching(DataSource dataSource) {
        DataSource proxy = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (self, method, args) -> {
            if (method.getName().equals("getConnection")) {
                Thread thread = Thread.currentThread();
                if (thread == scraping) {
                    askedOnTheScrape.add(thread.getName() + ": " + stackHead());
                    throw new SQLException("asked on the scrape thread", "08001");
                }
                if (thread.getName().equals(GaugeRefresher.THREAD)) {
                    askedOnTheRefresher.add(thread.getName());
                    throw new SQLException("absent", "08001");
                }
            }
            try {
                return method.invoke(dataSource, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        });
        watched = proxy;
        return proxy;
    }

    /** From here on, a connection asked for on {@code thread} is a read on the scrape; {@code null} ends it. */
    void scrapingOn(Thread thread) {
        scraping = thread;
    }

    /** Each connection asked for on the scraping thread, with the first frames of who asked. */
    List<String> askedOnTheScrape() {
        return askedOnTheScrape;
    }

    List<String> askedOnTheRefresher() {
        return askedOnTheRefresher;
    }

    /** A planted gauge's read: one connection asked of the wrapped data source, as a gauge reading the database would. */
    double plantedRead() {
        try (java.sql.Connection connection = watched.getConnection()) {
            return connection.isValid(1) ? 1 : 0;
        } catch (SQLException refused) {
            return Double.NaN;
        }
    }

    void clear() {
        askedOnTheScrape.clear();
    }

    private static String stackHead() {
        StringBuilder head = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        int shown = 0;
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith("com.finapp") && !frame.getClassName().startsWith(ScrapeThreadSource.class.getName())) {
                head.append(frame.getClassName()).append('#').append(frame.getMethodName()).append(' ');
                if (++shown == 3) {
                    break;
                }
            }
        }
        return head.toString().strip();
    }
}
