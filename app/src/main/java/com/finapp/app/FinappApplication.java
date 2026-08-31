package com.finapp.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root for the modular monolith (ADR-0001).
 *
 * <p>This class deliberately contains no business capability. Phase 0 delivers a
 * buildable, boundary-enforced skeleton and a financial kernel; customers,
 * accounts, the ledger and payments belong to later phases and must not appear
 * here.
 *
 * <p>Module wiring for {@code platform} and {@code sharedkernel} is P0-TSK-002.
 */
@SpringBootApplication
public class FinappApplication {

    private FinappApplication() {
        // Composition root; not instantiable.
    }

    public static void main(String[] args) {
        SpringApplication.run(FinappApplication.class, args);
    }
}
