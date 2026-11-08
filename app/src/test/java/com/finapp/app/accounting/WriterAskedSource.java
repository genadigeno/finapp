package com.finapp.app.accounting;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * {@code ReportGenerationReadsAsTheReportingRoleTest}'s two sources: every {@link DataSource} bean of the context - the
 * application's, as {@code finapp_app} - and the reporting pool's port, each wrapped to record whether the test's own
 * thread asked it for a connection (`P15-TSK-021`). A top-level helper rather than a part of the suite, for {@code HeldConnectionSource}'s reason:
 * {@code TestTier}'s database signature reads {@code javax.sql.DataSource} in a test class's tree as "obtains a
 * database", which is false here, the data source pointing at a closed port.
 */
final class WriterAskedSource {

    private final List<String> askedByTheCaller = new CopyOnWriteArrayList<>();
    private volatile Thread caller;

    private final List<String> reportingAskedByTheCaller = new CopyOnWriteArrayList<>();
    private volatile com.finapp.app.database.ReportingPool reportingPool;

    /**
     * Wraps every {@link DataSource} bean, and the reporting pool behind its port: the pool itself is kept for the test
     * to read, and the port the generator is given records this thread's asking before it opens.
     */
    BeanPostProcessor postProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof com.finapp.app.database.ReportingPool pool) {
                    reportingPool = pool;
                    return recordingReporting(pool);
                }
                return bean instanceof DataSource dataSource ? recording(dataSource) : bean;
            }
        };
    }

    private Object recordingReporting(com.finapp.app.database.ReportingPool pool) {
        return Proxy.newProxyInstance(com.finapp.app.database.ReportingConnections.class.getClassLoader(),
                new Class<?>[] {com.finapp.app.database.ReportingConnections.class, AutoCloseable.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("open") && Thread.currentThread() == caller) {
                        reportingAskedByTheCaller.add(method.getName());
                    }
                    try {
                        return method.invoke(pool, args);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }

    /** The production reporting pool the context built. */
    com.finapp.app.database.ReportingPool reportingPool() {
        return reportingPool;
    }

    List<String> reportingAskedByTheCaller() {
        return reportingAskedByTheCaller;
    }

    private Object recording(DataSource dataSource) {
        return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection") && Thread.currentThread() == caller) {
                        askedByTheCaller.add(method.getName());
                    }
                    try {
                        return method.invoke(dataSource, args);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }

    /** From here on, this thread asking either source is recorded; what an earlier test recorded is forgotten. */
    void callerIs(Thread thread) {
        askedByTheCaller.clear();
        reportingAskedByTheCaller.clear();
        caller = thread;
    }

    List<String> askedByTheCaller() {
        return askedByTheCaller;
    }
}
