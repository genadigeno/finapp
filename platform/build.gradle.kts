plugins {
    id("finapp.java-conventions")
}

dependencies {
    // `api`, not `implementation`: sharedkernel types (Money, typed identifiers,
    // the event envelope) will appear in platform's own public signatures — an
    // outbox record carries an envelope, an audit record carries an actor id.
    // Consumers of platform therefore need those types to compile against it.
    //
    // This is what makes the documented chain app -> platform -> sharedkernel
    // literally true: app depends on platform and receives sharedkernel through
    // it, rather than declaring a second edge.
    api(project(":sharedkernel"))

    // The Spring Boot BOM is applied for version alignment only. The Boot
    // *plugin* is not applied here: it would replace `jar` with `bootJar`, which
    // is correct for an executable and wrong for a library.
    //
    // No Spring artefact is declared yet. Platform's contents — outbox, inbox,
    // idempotency, audit, correlation, telemetry, error contract — arrive in
    // P0-TSK-014 onward and will add what they actually need at that point.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
}
