// The Flyway tasks resolve their JDBC driver and database support from the buildscript
// classpath, not from the project's dependencies — Flyway runs as a build tool here, not as
// part of the application. Versions still come from the version catalog, so the rule that no
// version literal appears in a build script holds.
//
// Flyway 10+ ships per-database support as a separate artefact, so flyway-database-postgresql
// is required alongside the JDBC driver; without it Flyway reports
// "No Flyway database plugin found to handle jdbc:postgresql://...".
buildscript {
    dependencies {
        classpath(libs.flyway.database.postgresql)
        classpath(libs.postgresql.driver)
    }
}

plugins {
    id("finapp.java-conventions")
    // Flyway is applied to platform, not to the root build, because platform owns the
    // `platform` schema. Each module that owns a schema runs its own migrations against
    // its own history table — schema ownership and migration ownership are the same thing
    // (ADR-0006). A single central migration runner would let any module change any
    // schema, which is precisely the coupling schema-per-module exists to prevent.
    alias(libs.plugins.flyway)
}

// ---------------------------------------------------------------------------
// Migration settings.
//
// Connection details default to the local Docker Compose stack and are overridable by
// environment variable. No credential is committed beyond the marked local-only default
// already established in compose.yaml.
// ---------------------------------------------------------------------------
flyway {
    url = providers.environmentVariable("FINAPP_DB_URL")
        .orElse("jdbc:postgresql://127.0.0.1:5432/finapp").get()
    user = providers.environmentVariable("FINAPP_DB_USER").orElse("finapp").get()
    password = providers.environmentVariable("FINAPP_DB_PASSWORD")
        .orElse("local-development-only-not-a-secret").get()

    schemas = arrayOf("platform")
    defaultSchema = "platform"
    locations = arrayOf("filesystem:src/main/resources/db/migration/platform")

    // --- Settings chosen for financial correctness, not convenience ---

    // flywayClean drops every object in the managed schemas. On a database holding
    // financial history that is an unrecoverable operation, and there is no situation in
    // this platform where it is the right answer. Disabled permanently.
    cleanDisabled = true

    // Refuse to run if an already-applied migration's checksum no longer matches the file.
    // Editing an applied migration means the database and the repository disagree about
    // what was executed, and no later reasoning about the schema can be trusted.
    validateOnMigrate = true

    // Migrations apply in version order only. Out-of-order application makes the schema a
    // function of *when* each branch was merged rather than of the version sequence, so two
    // environments at the same version could differ. That is not acceptable where the
    // schema holds the ledger.
    outOfOrder = false

    // Never adopt a non-empty database by assuming its current state is the baseline. An
    // unexpected existing schema is a situation a human must look at, not something the
    // tool should paper over.
    baselineOnMigrate = false
}

dependencies {
    // Drivers for the Flyway tasks. Flyway 10+ ships database support separately, so
    // flyway-database-postgresql is required in addition to the JDBC driver.
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
