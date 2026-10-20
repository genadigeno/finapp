package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Every {@code app} BNPL and lending database suite is date-shifted from its first run (`P12-TST-001`, debt A6-04): each
 * {@code @Tag("database")} class of {@code com.finapp.app.bnpl} and {@code com.finapp.app.lending}, and the BNPL suites
 * other packages hold, carries {@link DateShifted} - so a suite added later is shifted the day it is written, never after
 * a red run at 20:00 UTC tells someone it should have been.
 *
 * <p>One exemption, recorded on the debt row: {@code LendingStormDatabaseTest} reads every date on the database clock in
 * an explicit zone (its cohorts' fixed offsets, {@code lending.business_date}, {@code AT TIME ZONE 'UTC'}), and its run
 * is not the BNPL storm's to repeat.
 */
@DisplayName("every app BNPL and lending database suite is date-shifted (A6-04)")
class DateShiftedSuitesTest {

    private static final List<String> PACKAGES = List.of("com.finapp.app.bnpl", "com.finapp.app.lending");

    /** The BNPL suites outside the two packages - each a BNPL path's own suite. */
    private static final List<String> ALSO = List.of(
            "com.finapp.app.checkout.CheckoutFinancingDatabaseTest",
            "com.finapp.app.credit.BnplProductDecisionDatabaseTest");

    private static final Set<String> EXEMPT = Set.of("com.finapp.app.lending.LendingStormDatabaseTest");

    @Test
    @DisplayName("each one carries @DateShifted - bar the recorded exemption")
    void everyDatabaseSuiteIsDateShifted() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter((reader, factory) -> true);
        Set<String> suites = new TreeSet<>(ALSO);
        for (String pack : PACKAGES) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(pack)) {
                suites.add(candidate.getBeanClassName());
            }
        }
        Set<String> database = new TreeSet<>();
        Set<String> unshifted = new TreeSet<>();
        for (String name : suites) {
            Class<?> suite = Class.forName(name);
            boolean tagged = Arrays.stream(suite.getAnnotationsByType(Tag.class))
                    .anyMatch(tag -> "database".equals(tag.value()));
            if (!tagged || EXEMPT.contains(name)) {
                continue;
            }
            database.add(name);
            if (!suite.isAnnotationPresent(DateShifted.class)) {
                unshifted.add(name);
            }
        }
        assertThat(database).as("the guard found the database suites it guards").hasSizeGreaterThan(30)
                .containsAll(ALSO);
        assertThat(unshifted).as("database suites not date-shifted - add @DateShifted (A6-04)").isEmpty();
        for (String exempt : EXEMPT) {
            assertThat(Class.forName(exempt).isAnnotationPresent(DateShifted.class))
                    .as("%s is exempt and unshifted - its exemption is recorded on the debt row", exempt).isFalse();
        }
    }
}
