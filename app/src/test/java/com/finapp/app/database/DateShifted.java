package com.finapp.app.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;

/**
 * A database suite DATE-SHIFTED from its first run (`P12-TST-001`, the Phase 11 -> 12 transition's debt A6-04; lending's
 * `P11-TSK-032` rule carried to {@code app}): the test JVM's default zone - and with it {@code LocalDate.now()}, the JDBC
 * driver's session zone and so a session's {@code current_date} - moved, before the Spring context starts, to a zone whose
 * date is not UTC's at the hour the suite runs ({@link DateShiftedZone#zone()}). Every platform date is judged on the
 * database clock in a pinned zone; a fixture that plants the session's or the JVM's date beside it is a day off whenever
 * the machine's date is not UTC's - on a UTC+4 machine only from 20:00 to 24:00 UTC, so the defect hides twenty hours a
 * day. Shifted, it shows every hour.
 *
 * <p>The suites so marked get Spring contexts of their own (the property below is part of the context's cache key), and
 * their pools' sessions take the zone by {@code connection-init-sql} - so a context another, unshifted suite created is
 * never reused, and a connection the pool opens later is shifted too. {@code DateShiftedSuitesTest} holds every
 * {@code app} BNPL and lending database suite to this annotation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(DateShiftedZone.class)
@TestPropertySource(properties = {
    "finapp.test.date-shifted=true",
    "spring.datasource.hikari.connection-init-sql=SET TIME ZONE '${" + DateShiftedZone.PROPERTY + "}'"
})
public @interface DateShifted {}
