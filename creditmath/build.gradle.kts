// The `creditmath` module (P12-TSK-002).
//
// The pure, deterministic credit arithmetic lending and bnpl share - lending's schedule, accrual and allocation
// engines, their records and their version identities, moved here from `lending` unchanged so that `bnpl` can compute
// with them without a `bnpl -> lending` edge (ADR-0101 decision 5, OQ-2; MODULE_ARCHITECTURE.md M12).
//
// NO FLYWAY, deliberately: the module owns no state and no schema, and performs no I/O. NO SPRING, and no project
// dependency but sharedkernel - the sharedkernel posture, for the same reason: arithmetic that a framework or a store
// could reach is arithmetic whose output could depend on something other than its inputs, and every figure computed
// here is replayed years later by the version its agreement pins (INV-LND-05).
plugins {
    id("finapp.java-conventions")
    // The second JVM the engines' determinism is proven in (SecondJvm) is shared with lending's statement suites and
    // its battery (P11-TST-002), so it is published as test fixtures rather than copied - one harness, one set of
    // child defaults.
    `java-test-fixtures`
}

dependencies {
    // `api`, not `implementation`: the engines' signatures are Money and CurrencyCode, so every caller compiles against
    // sharedkernel's types through this module - the exposure is the API, not a leak.
    api(project(":sharedkernel"))

    // Test libraries from the catalog, NOT from the Spring Boot BOM - sharedkernel's reasoning: "no Spring" is a fact
    // of the classpath, not an argument about whether BOM constraints count.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    testFixturesImplementation(libs.assertj.core)
}
