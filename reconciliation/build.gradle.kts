// The `reconciliation` module (P8-TSK-001).
//
// What the platform EXPECTED to happen, compared with what the evidence says: the settlement
// expectations (owned here since ADR-0064), the runs and external items, the deterministic
// matcher, the breaks with their immutable lifecycle, the owned suspense items and the
// controlled, four-eyes resolutions (ADR-0067...ADR-0071; MODULE_ARCHITECTURE.md M8). Never the
// evidence itself: files, batches and lines are `settlement`'s, and there is NO build edge
// between the two modules in either direction - evidence and expectations must never share a
// writer, so every hand-off goes through a port `app` composes.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `reconciliation` schema and nothing else may change it; a single central migration
//   runner would let any module alter any schema. The configuration is duplicated per
//   schema-owning module deliberately rather than shared through a convention plugin, because
//   that plugin would BE the central runner this design rejects.
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
// it applies - and PHASE_8_PLAN.md §8 already commits the break tables to no-DELETE-for-anyone
// grants with a refusing trigger beneath (the break-immutability exit criterion), match decisions
// and allocations to no UPDATE or DELETE at all, and the resolution machine to its generated
// CHECKs - all of which are only available to the later tasks if the owner is right from the
// first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("reconciliation")
    defaultSchema = "reconciliation"
    locations = arrayOf("filesystem:src/main/resources/db/migration/reconciliation")

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
    // The documented direction: reconciliation -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is this module's one permitted business-sibling edge, and it is the defining one
    // (ADR-0071): an approved resolution's compensating entry goes through the ledger's
    // adjustment machinery, and the position proof reads balances through the ledger's API -
    // commanded and read, never written (INV-LED-04). The edge is declared with the module
    // rather than with its first consumer (P8-TSK-004, the expectation register) because the
    // asymmetry is the deliverable: with reconciliation -> ledger in the build graph,
    // ledger -> reconciliation is a Gradle cycle and cannot be added at all, and
    // ReconciliationModuleIsolationTest pins the positive half so the direction is pinned rather
    // than implied.
    //
    // Deliberately NO edge to `settlement`, and none the other way either (ADR-0064): the module
    // that decides what the platform expected must not compile against the module that preserves
    // what the counterparty said - the matcher must not mutate evidence. Neither refusal has a
    // Gradle cycle behind it; the two isolation tests are the only controls. And deliberately NO
    // edge to `payments`, `merchant` or any other sibling: an expectation names the operation
    // that opened it by identifier and posting key, never by type - the openers call ports this
    // module publishes and `app` composes.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles), for the one database-tier
    // test this task ships: the schema floor proven LIVE, not described
    // (ReconciliationMigrationTest). The module that owns the schema owns the proof of its floor
    // - the same doctrine that puts the Flyway configuration above in this file.
    testImplementation(testFixtures(project(":platform")))
}

// The `database` tier's module-specific configuration - the container image, from the version
// catalog, exactly as platform's and app's tasks supply it. Without this the shared harness
// runs, finds no image, and returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
