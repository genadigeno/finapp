package com.finapp.app.accounting;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * {@code AccountingGaugeWiringTest}'s data source: it holds one named thread on a latch, as a pool with no free connection would, and records whether the test's own thread asked; everyone else goes on to the source beneath it. A top-level helper rather than a part of the suite, deliberately: {@code TestTier}'s
 * database signature reads {@code javax.sql.DataSource} anywhere in a test class's tree as "obtains a database", which is false here, the datasource pointing at a closed port. {@code HermeticKycServices} and {@code ReportingWritablePathProbes} are the same.
 */
final class HeldConnectionSource {

    private final String heldThread;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch reached = new CountDownLatch(1);
    private final List<String> held = new CopyOnWriteArrayList<>();
    private final List<String> askedByTheCaller = new CopyOnWriteArrayList<>();
    private volatile Thread caller;

    HeldConnectionSource(String heldThread) {
        this.heldThread = heldThread;
    }

    /** Wraps every {@link DataSource} bean. */
    BeanPostProcessor postProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource dataSource ? holding(dataSource) : bean;
            }
        };
    }

    private Object holding(DataSource dataSource) {
        return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) {
                Thread thread = Thread.currentThread();
                if (thread.getName().equals(heldThread)) {
                    held.add(thread.getName());
                    reached.countDown();
                    if (!release.await(120, TimeUnit.SECONDS)) {
                        throw new SQLException("never released");
                    }
                } else if (thread == caller) {
                    askedByTheCaller.add(thread.getName());
                }
            }
            try {
                return method.invoke(dataSource, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        });
    }

    /** From here on, this thread asking is recorded. */
    void callerIs(Thread thread) {
        caller = thread;
    }

    boolean awaitHeld(long seconds) throws InterruptedException {
        return reached.await(seconds, TimeUnit.SECONDS);
    }

    void release() {
        release.countDown();
    }

    /** The name of each thread held, once per asking. */
    List<String> held() {
        return held;
    }

    List<String> askedByTheCaller() {
        return askedByTheCaller;
    }
}
