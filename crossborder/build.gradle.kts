// The `crossborder` module (P9-TSK-001).
//
// The customer's instruction to pay a beneficiary abroad and the corridor rules governing it:
// corridors, beneficiaries and their screening state, offers, the payment's business lifecycle
// and cancellation by recall (ADR-0079...ADR-0081; MODULE_ARCHITECTURE.md `crossborder`). It
// DECIDES; `fx` prices and books and `payments` executes - and there is NO build edge to either:
// every hand-off goes through a port this module declares and `app` composes.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `crossborder` schema and nothing else may change it; a single central migration runner
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
// only a control because the schema's objects are owned by a role that cannot bypass the grants
// it applies - and PHASE_9_PLAN.md already commits this schema's tables to explicit per-table
// grants, frozen columns by trigger and generated CHECKs, all of which are only available to the
// later tasks if the owner is right from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("crossborder")
    defaultSchema = "crossborder"
    locations = arrayOf("filesystem:src/main/resources/db/migration/crossborder")

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
    // The documented direction: crossborder -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is this module's one permitted business-sibling edge: the hold a cross-border
    // authorization places and the corridor accounts its postings name are reached through the
    // ledger's API - commanded and read, never written (INV-LED-04). The edge is declared with the
    // module because the asymmetry is the deliverable: with crossborder -> ledger in the build
    // graph, ledger -> crossborder is a Gradle cycle, and CrossborderModuleIsolationTest pins the
    // positive half.
    //
    // Deliberately NO edge to `fx`, and none the other way either (ADR-0079): the module that
    // decides who is paid must not compile against the module that prices and books, nor the
    // reverse - neither refusal has a Gradle cycle behind it; the two isolation tests are the only
    // controls. And deliberately NO edge to `payments`, `kyc` or `accounts`: execution, screening
    // and the customer's wallet are reached through CrossBorderExecution, CounterpartyScreening
    // and CrossBorderParticipants, ports `app` implements.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles), for the one database-tier
    // test this task ships: the schema floor proven LIVE, not described
    // (CrossborderMigrationTest). The module that owns the schema owns the proof of its floor
    // - the same doctrine that puts the Flyway configuration above in this file.
    testImplementation(testFixtures(project(":platform")))
}

// The `database` tier's module-specific configuration - the container image, from the version
// catalog, exactly as platform's and app's tasks supply it. Without this the shared harness
// runs, finds no image, and returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
