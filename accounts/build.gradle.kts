// The `accounts` module (P3-TSK-011).
//
// The customer-facing account and wallet PRODUCT: the agreement, its status, its lifecycle - and
// deliberately not its money (MODULE_ARCHITECTURE.md §4, ADR-0042). A Customer Account carries no
// balance; it references the ledger account(s) recording its position, and a balance query on the
// product is a query against those. "A balance is not a field on an account."
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `accounts` schema and nothing else may change it; a single central migration runner
//   would let any module alter any schema. The configuration is duplicated per schema-owning
//   module deliberately rather than shared through a convention plugin, because that plugin would
//   BE the central runner this design rejects.
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
val dbName = providers.environmentVariable("FINAPP_DB_NAME").orElse("finapp").get()
val dbUrl = providers.environmentVariable("FINAPP_DB_URL")
    .orElse("jdbc:postgresql://127.0.0.1:5432/$dbName").get()

// The migrator, never the superuser (P0-TSK-022). The privilege floor this module ships with is
// only a control because the schema's objects are owned by a role that cannot bypass the grants it
// applies - and PHASE_3_PLAN.md §8 already commits `accounts.customer_account` to a column-narrowed
// UPDATE grant, which is only available to P3-TSK-012 if the owner is right from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("accounts")
    defaultSchema = "accounts"
    locations = arrayOf("filesystem:src/main/resources/db/migration/accounts")

    // The same four settings every schema-owning module uses, and for the same reasons: clean is
    // unrecoverable on a database holding history; an edited applied migration means the database
    // and the repository disagree; out-of-order application makes the schema a function of merge
    // order; and an unexpected existing schema is something a human must look at.
    cleanDisabled = true
    validateOnMigrate = true
    outOfOrder = false
    baselineOnMigrate = false
}

dependencies {
    // The documented direction: accounts -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is the platform's first business-sibling edge, and it is this module's defining
    // dependency (ADR-0042): the product asks the ledger for balances and requests postings
    // through its command API - it never writes one (INV-LED-04). The edge is declared with the
    // module rather than with its first consumer (P3-TSK-012, one task away) because the
    // asymmetry is the deliverable here: with accounts -> ledger in the build graph,
    // ledger -> accounts is a Gradle cycle and cannot be added at all, and
    // AccountsModuleIsolationTest asserts the positive half so the direction is pinned rather
    // than implied.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
