// The `transfers` module (P4-TSK-001).
//
// The first customer-visible money MOVEMENT: the Transfer and Beneficiary aggregates, their
// lifecycles and their history (MODULE_ARCHITECTURE.md §4) - and never a posting. The ledger is
// the sole writer of journal entries (INV-LED-04); this module COMMANDS postings through
// `PostingService` and reversals through `ReversalService`, in the one local transaction ADR-0043
// decides, and writes no journal row itself.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `transfers` schema and nothing else may change it; a single central migration runner
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
// applies - and PHASE_4_PLAN.md §8 already commits `transfers.transfer` to a column-narrowed
// UPDATE grant, `transfer_event` to SELECT/INSERT only, and the transition trigger to binding
// every writer, all of which are only available to P4-TSK-004 if the owner is right from the
// first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("transfers")
    defaultSchema = "transfers"
    locations = arrayOf("filesystem:src/main/resources/db/migration/transfers")

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
    // The documented direction: transfers -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is this module's one permitted business-sibling edge, and it is the defining one
    // (ADR-0043): a transfer's money movement IS a ledger posting, commanded through the ledger's
    // API and never written here (INV-LED-04). The edge is declared with the module rather than
    // with its first consumer (P4-TSK-003, two tasks away) because the asymmetry is the
    // deliverable: with transfers -> ledger in the build graph, ledger -> transfers is a Gradle
    // cycle and cannot be added at all, and TransfersModuleIsolationTest pins the positive half so
    // the direction is pinned rather than implied.
    //
    // Deliberately NO edge to `accounts`. The transfer resolves the caller's products through a
    // port `app` implements (the AccountHolderVerification shape, P4-TSK-005): the module that
    // owns the product and the module that moves the money must not become one dependency ball,
    // and the refusal is enforced by TransfersModuleIsolationTest from this side and by
    // AccountsModuleIsolationTest from the other.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
