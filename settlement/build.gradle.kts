// The `settlement` module (P8-TSK-001).
//
// External settlement evidence and its recognition: what the card PSP, the instant scheme, the
// payout provider and the platform's bank SAY happened - files, batches, canonical lines - and
// the recognition postings their acceptance commands (ADR-0064, ADR-0065, ADR-0066;
// MODULE_ARCHITECTURE.md M8). Never the expectations, the matching, the breaks or the suspense:
// those are `reconciliation`'s, and there is NO build edge between the two modules in either
// direction - evidence and expectations must never share a writer, so every hand-off goes
// through a port `app` composes.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `settlement` schema and nothing else may change it; a single central migration runner
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
// it applies - and PHASE_8_PLAN.md §8 already commits every evidence table to append-only grants
// with no DELETE for finapp_app anywhere in the schema (the break-immutability exit criterion's
// settlement half), which is only available to the later tasks if the owner is right from the
// first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("settlement")
    defaultSchema = "settlement"
    locations = arrayOf("filesystem:src/main/resources/db/migration/settlement")

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
    // The documented direction: settlement -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is this module's one permitted business-sibling edge, and it is the defining one
    // (ADR-0065): recognition - the counterparty's fees, and cash on the bank's own statement -
    // IS a ledger posting, commanded through PostingService and never written here (INV-LED-04).
    // The edge is declared with the module rather than with its first consumer (P8-TSK-009, the
    // acceptance's recognition) because the asymmetry is the deliverable: with
    // settlement -> ledger in the build graph, ledger -> settlement is a Gradle cycle and cannot
    // be added at all, and SettlementModuleIsolationTest pins the positive half so the direction
    // is pinned rather than implied.
    //
    // Deliberately NO edge to `reconciliation`, and none the other way either (ADR-0064): the
    // module that preserves what the counterparty said must not compile against the module that
    // decides what the platform expected - acceptance must not peek at dispositions. Neither
    // refusal has a Gradle cycle behind it; the two isolation tests are the only controls. And
    // deliberately NO edge to `payments`, `merchant` or any other sibling: a settlement line
    // names the operations it settles by identifier, never by type.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // A real logging backend for the needle tests: "the identifier reaches no log line" is
    // only a control when something would have written one (version from the Boot BOM).
    testImplementation("ch.qos.logback:logback-classic")

    // The shared database harness (DatabaseUnderTest, DatabaseRoles), for the one database-tier
    // test this task ships: the schema floor proven LIVE, not described (SettlementMigrationTest).
    // The module that owns the schema owns the proof of its floor - the same doctrine that puts
    // the Flyway configuration above in this file.
    testImplementation(testFixtures(project(":platform")))
}

// The `database` tier's module-specific configuration - the container image, from the version
// catalog, exactly as platform's and app's tasks supply it. Without this the shared harness
// runs, finds no image, and returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
