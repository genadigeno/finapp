// The `risk` module (P13-TSK-001).
//
// Risk, fraud, AML / transaction monitoring and case management - one module, three capabilities kept distinct by
// timing and obligation (ADR-0107; MODULE_ARCHITECTURE.md `risk`, M3, M6): the versioned risk policy and its rules as
// data, the deterministic engine, the sealed assessment and its decision, the durable velocity windows, the customer
// risk profile, the born-once signal, the monitored transaction read from the ledger's journal, the born-once alert, the
// case and its investigator acts, the downstream action's record, the screening list version and the regulatory report.
// It owns the judgement, the evidence and the case, and NONE of the money: it never posts, never holds, never releases a
// hold of its own and never writes another module's row (INV-RSK-02). A decision changes a money movement only because
// the owning domain consulted a seam in its own transaction and committed its own outcome.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module owns the `risk`
//   schema and nothing else may change it; a single central migration runner would let any module alter any schema.
//   The configuration is duplicated per schema-owning module deliberately rather than shared through a convention
//   plugin, because that plugin would BE the central runner this design rejects.
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

// Connection details, resolved exactly as platform resolves them and from the same environment variables, so
// overriding one moves the container, the migration tool and the tests together.
val dbName = providers.environmentVariable("FINAPP_DB_NAME").orElse("finapp").get()
val dbUrl = providers.environmentVariable("FINAPP_DB_URL")
    .orElse("jdbc:postgresql://127.0.0.1:5432/$dbName").get()

// The migrator, never the superuser (P0-TSK-022). The privilege floor this module ships with is only a control because
// the schema's objects are owned by a role that cannot bypass the grants it applies - and PHASE_13_PLAN.md section 8
// commits this schema to INSERT-only fact tables, a column-scoped UPDATE of status, permit, revision and decision columns
// on the machine tables, the one-statement bounded upsert of the velocity windows and no DELETE for any role, all of
// which are only available to the later tasks if the owner is right from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("risk")
    defaultSchema = "risk"
    locations = arrayOf("filesystem:src/main/resources/db/migration/risk")

    // The same four settings every schema-owning module uses, and for the same reasons: clean is unrecoverable on a
    // database holding history; an edited applied migration means the database and the repository disagree;
    // out-of-order application makes the schema a function of merge order; and an unexpected existing schema is
    // something a human must look at.
    cleanDisabled = true
    validateOnMigrate = true
    outOfOrder = false
    baselineOnMigrate = false
}

dependencies {
    // The documented direction: risk -> platform -> sharedkernel (ADR-0107 decision 2), and nothing else.
    //
    // Deliberately NO edge to `ledger`: risk posts nothing, holds nothing and releases nothing of its own, and reads the
    // journal only through the `MonitoredLedger` port `app` implements - so with no edge, no code here can even name a
    // posting service. Deliberately NO edge to any business module: the seams the consumers declare
    // (`transfers.TransferLimitCheck`, `crossborder.CrossBorderRiskDecision`, `credit.CreditRiskSignal`, payments' own)
    // are implemented in `app` as adapters over this module's evaluation API, and every domain an action reaches
    // (identity, accounts, merchant, crossborder, payments, kyc) is a port this module declares and `app` implements.
    // None of these refusals has a Gradle cycle behind it - RiskModuleIsolationTest is the only control - and no module
    // depends on `risk`: every sibling's isolation test refuses it from its side.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles) for the database-tier test this task ships: the
    // schema floor proven LIVE (RiskMigrationTest). The module that owns the schema owns the proof of its floor.
    testImplementation(testFixtures(project(":platform")))
}

// The `database` tier's module-specific configuration - the container image, from the version catalog, exactly as
// platform's, app's, lending's and bnpl's tasks supply it. Without this the shared harness runs, finds no image, and
// returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
