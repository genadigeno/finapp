package com.finapp.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Proves the Spring Boot BOM and plugin are wired correctly.
 *
 * <p>This is a build-wiring test, not a behaviour test. If the BOM were not
 * applied, the versionless {@code spring-boot-starter} declarations in
 * {@code app/build.gradle.kts} would not resolve and this class would not
 * compile; if the Boot plugin were not applied, no context would start.
 */
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
    @DisplayName("Phase 0 exposes no business capability")
    void exposesNoBusinessCapability() {
        // Phase 0 delivers a skeleton and a financial kernel only. If a bean
        // from a business context appears here, scope has leaked forward and
        // EXECUTION_PROTOCOL rule 3 has been violated.
        assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    return lower.contains("account")
                            || lower.contains("ledger")
                            || lower.contains("payment")
                            || lower.contains("customer")
                            || lower.contains("transfer");
                });
    }
}
