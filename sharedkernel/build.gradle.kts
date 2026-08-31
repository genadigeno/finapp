plugins {
    id("finapp.java-conventions")
}

// sharedkernel is the bottom of the dependency graph. It depends on nothing in
// this build, and on no framework.
//
// Deliberately absent, and required to stay absent:
//   - the Spring Boot plugin and any Spring artefact
//   - JPA / persistence
//   - any project(...) dependency
//
// Its contents are value types only. Money, CurrencyCode and RoundingPolicy
// exist (P0-TSK-009, P0-TSK-010); typed identifiers (P0-TSK-012), the Clock
// abstraction (P0-TSK-013) and the event envelope (P0-TSK-018) follow.
// Business concepts may never enter it — a shared kernel
// that accumulates domain nouns becomes the coupling sink a modular monolith
// exists to prevent (MODULE_ARCHITECTURE.md §2).

dependencies {
    // Test libraries come from the catalog, NOT from the Spring Boot BOM, so
    // that "no Spring Framework dependency" is a fact rather than an argument
    // about whether BOM constraints count. See gradle/libs.versions.toml.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
