// The `ledger` module (P3-TSK-001).
//
// The authoritative financial record: the chart of accounts, journal entries and their lines, the
// balance projection and holds (MODULE_ARCHITECTURE.md §4, ADR-0002, ADR-0039…0042). The sole
// writer of postings (INV-LED-04) - every other module REQUESTS a posting and the ledger decides
// whether and how it is written.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `ledger` schema and nothing else may change it; a single central migration runner
//   would let any module alter any schema - and for THIS schema that is not a coupling concern but
//   a correctness one, because "only the ledger writes postings" would then be a convention.
buildscript {
    dependencies {
        classpath(libs.flyway.database.postgresql)
        classpath(libs.postgresql.driver)
    }
}

plugins {
    id("finapp.java-conventions")
    alias(libs.plugins.flyway)
}

// Connection details, resolved exactly as platform resolves them and from the same environment
// variables, so overriding one moves the container, the migration tool and the tests together.
// Duplicated deliberately rather than shared: a Gradle convention plugin that configured Flyway
// for every module would be the central runner this design rejects.
val dbName = providers.environmentVariable("FINAPP_DB_NAME").orElse("finapp").get()
val dbUrl = providers.environmentVariable("FINAPP_DB_URL")
    .orElse("jdbc:postgresql://127.0.0.1:5432/$dbName").get()

// The migrator, never the superuser - and for this module that is the whole task. INV-LED-03
// (posted entries and lines are immutable) and INV-HIST-01 (financial history is never edited) are
// enforced at DB-PRIVILEGE: the application role will hold INSERT and SELECT on the journal tables
// and nothing else. A privilege is only a control if the objects are owned by a role that cannot
// bypass it, and a superuser bypasses every grant.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("ledger")
    defaultSchema = "ledger"
    locations = arrayOf("filesystem:src/main/resources/db/migration/ledger")

    // The same four settings every schema-owning module uses, and for the same reasons: clean is
    // unrecoverable on a database holding history - here, financial history; an edited applied
    // migration means the database and the repository disagree; out-of-order application makes
    // the schema a function of merge order; and an unexpected existing schema is something a
    // human must look at.
    cleanDisabled = true
    validateOnMigrate = true
    outOfOrder = false
    baselineOnMigrate = false
}

dependencies {
    // The documented direction: ledger -> platform -> sharedkernel. No business module, ever:
    // the ledger is commanded, it does not react (MODULE_ARCHITECTURE.md §4), so it has nothing to
    // import from the modules that command it.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
