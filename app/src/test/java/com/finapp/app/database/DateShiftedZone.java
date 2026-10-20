package com.finapp.app.database;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePreConstructCallback;

/**
 * {@link DateShifted}'s extension: the JVM's default zone moved to {@link #zone()} before the suite's Spring context starts
 * (its {@code beforeAll}, and again before an instance is constructed - whichever lifecycle the suite has), and put back
 * after the suite. The zone is chosen once per JVM, so every shifted suite of a run shares one context key.
 */
public final class DateShiftedZone implements BeforeAllCallback, TestInstancePreConstructCallback, AfterAllCallback {

    /** The system property naming the shifted zone - read by {@link DateShifted}'s {@code connection-init-sql}. */
    public static final String PROPERTY = "finapp.test.shifted-zone";

    private static final String ZONE = choose();
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(DateShiftedZone.class);

    /**
     * The shifted zone: {@code -Dfinapp.db.zone} when given (UTC turns the shift off for a diagnosis), else lending's
     * `P11-TSK-032` rule - UTC-12 before 11:00 UTC (its date a day behind UTC's until 12:00), UTC+14 from 11:00 (a day ahead
     * from 10:00). A run spanning that hour, or midnight UTC, loses the shift only for its tail.
     */
    public static String zone() {
        return ZONE;
    }

    private static String choose() {
        String pinned = System.getProperty("finapp.db.zone");
        if (pinned != null && !pinned.isBlank()) {
            return pinned.trim();
        }
        return Instant.now().atZone(ZoneOffset.UTC).getHour() < 11 ? "Etc/GMT+12" : "Etc/GMT-14";
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        shift(context);
    }

    @Override
    public void preConstructTestInstance(
            org.junit.jupiter.api.extension.TestInstanceFactoryContext factoryContext, ExtensionContext context) {
        shift(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Object original = context.getStore(NAMESPACE).remove("original");
        if (original instanceof TimeZone zone) {
            TimeZone.setDefault(zone);
            System.setProperty("user.timezone", zone.getID());
        }
    }

    private static void shift(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        if (store.get("original") == null) {
            store.put("original", TimeZone.getDefault());
            System.out.println(context.getRequiredTestClass().getSimpleName() + ": date-shifted - the test JVM runs in "
                    + ZONE + " (A6-04, the P11-TSK-032 rule)");
        }
        System.setProperty(PROPERTY, ZONE);
        System.setProperty("user.timezone", ZONE);
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }
}
