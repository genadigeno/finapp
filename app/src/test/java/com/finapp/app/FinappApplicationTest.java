package com.finapp.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.ProductionModules;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Proves the Spring Boot BOM and plugin are wired correctly.
 *
 * <p>This is a build-wiring test, not a behaviour test. If the BOM were not applied, the
 * versionless {@code spring-boot-starter} declarations in {@code app/build.gradle.kts} would
 * not resolve and this class would not compile; if the Boot plugin were not applied, no
 * context would start.
 */
@Tag("slice")
@SpringBootTest
class FinappApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Spring context starts, proving the Boot BOM and plugin are wired")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(FinappApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("every application bean comes from a module that actually exists")
    void everyBeanBelongsToARealModule() {
        // A business module appearing in the context that does not exist on the classpath means
        // scope has leaked forward, violating EXECUTION_PROTOCOL rule 3.
        //
        // Asserted structurally, by the package a bean's class actually lives in, rather than by
        // matching substrings against bean names: a name-based check only catches business concepts
        // somebody happened to name recognisably, which is more assurance than it earns.
        //
        // The allowed set is DERIVED from the classpath rather than listed. It was a list of three
        // until `P1-TSK-006` wired the first `party` and `identity` beans, and a list would have had
        // to be edited every time a module started contributing one - which is the stale-list defect
        // this repository has met in CI's task list, in a coverage guard and in a privilege check.
        // Derived, the guard keeps working and keeps meaning the same thing.
        List<String> allowedModulePackages =
                ProductionModules.onClasspathWithProductionClasses().stream()
                        .map(module -> "com.finapp." + module + ".")
                        .toList();

        assertThat(allowedModulePackages)
                .as("the derivation must see the modules, or this guard checks nothing")
                .contains("com.finapp.app.", "com.finapp.platform.", "com.finapp.sharedkernel.");

        List<String> unexpected = Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(Objects::nonNull)
                .map(Class::getName)
                .filter(className -> className.startsWith("com.finapp."))
                .filter(className -> allowedModulePackages.stream().noneMatch(className::startsWith))
                .distinct()
                .sorted()
                .toList();

        assertThat(unexpected)
                .as("beans belonging to modules that do not exist")
                .isEmpty();
    }
}
