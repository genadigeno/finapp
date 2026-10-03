// The `fx` module (P9-TSK-001).
//
// Currency conversion at a server-authoritative, time-bounded, single-use price, and the
// platform's own FX exposure: the quote as a frozen posting plan with stored rate provenance,
// the trade that posts exactly that plan, and the back-to-back cover with the FX provider
// (ADR-0074...ADR-0078, ADR-0083; MODULE_ARCHITECTURE.md `fx`). Never the instruction to pay
// abroad: that is `crossborder`'s, and there is NO build edge between the two modules in either
// direction - crossborder decides, fx prices and books, payments executes (ADR-0079), and every
// hand-off goes through a port `app` composes.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `fx` schema and nothing else may change it; a single central migration runner
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

    schemas = arrayOf("fx")
    defaultSchema = "fx"
    locations = arrayOf("filesystem:src/main/resources/db/migration/fx")

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
    // The documented direction: fx -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is this module's one permitted business-sibling edge, and it is the defining one
    // (ADR-0076): a trade posts the quote's frozen plan through the ledger's PostingService and a
    // reversal through ReversalService - commanded, never written (INV-LED-04). The edge is
    // declared with the module rather than with its first consumer because the asymmetry is the
    // deliverable: with fx -> ledger in the build graph, ledger -> fx is a Gradle cycle and
    // cannot be added at all, and FxModuleIsolationTest pins the positive half.
    //
    // Deliberately NO edge to `crossborder`, and none the other way either (ADR-0079): the module
    // that prices and books must not compile against the module that decides who is paid, nor
    // the reverse - neither refusal has a Gradle cycle behind it; the two isolation tests are the
    // only controls. And deliberately NO edge to `payments`, `accounts` or `kyc`: the wallet a
    // conversion credits is reached through ConversionParticipants, a port `app` implements.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles), for the one database-tier
    // test this task ships: the schema floor proven LIVE, not described
    // (FxMigrationTest). The module that owns the schema owns the proof of its floor
    // - the same doctrine that puts the Flyway configuration above in this file.
    testImplementation(testFixtures(project(":platform")))
}

// The `database` tier's module-specific configuration - the container image, from the version
// catalog, exactly as platform's and app's tasks supply it. Without this the shared harness
// runs, finds no image, and returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
