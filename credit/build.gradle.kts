// The `credit` module (P10-TSK-001).
//
// Credit decisioning: the credit profile, the collected credit data and its evidence, the frozen
// decision snapshot, affordability, exposure, the versioned scorecard and policy, the immutable
// decision with its ordered reason codes, underwriting, explanation and replay (ADR-0084...ADR-0089;
// MODULE_ARCHITECTURE.md `credit`). It DECIDES whether credit may be offered - and it moves no
// money: the loan that would consume a decision is Phase 11's, and nothing is ever posted here.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `credit` schema and nothing else may change it; a single central migration runner
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
// it applies - and PHASE_10_PLAN.md commits this schema's decision tables to INSERT-only grants
// and every-writer triggers, which are only available to the later tasks if the owner is right
// from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("credit")
    defaultSchema = "credit"
    locations = arrayOf("filesystem:src/main/resources/db/migration/credit")

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
    // The documented direction: credit -> platform -> sharedkernel, and NOTHING else (ADR-0084).
    //
    // Deliberately NO edge to `ledger`: credit moves no money, so there is nothing for it to
    // command - the first credit module with no ledger edge, and the absence is the point. And
    // deliberately NO edge to `consent`, `kyc`, `party` or `identity`: the lawful basis for bureau
    // access, the party's standing and the customer's identity are reached through ports this
    // module declares and `app` composes (CreditConsentGate, P10-TSK-002 onwards). None of these
    // refusals has a Gradle cycle behind it - CreditModuleIsolationTest is the only control.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles), for the database-tier test
    // this task ships: the schema floor and the immutable reason-code catalogue proven LIVE
    // (CreditMigrationTest). The module that owns the schema owns the proof of its floor.
    testImplementation(testFixtures(project(":platform")))
}

// The `database` tier's module-specific configuration - the container image, from the version
// catalog, exactly as platform's and app's tasks supply it. Without this the shared harness
// runs, finds no image, and returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
