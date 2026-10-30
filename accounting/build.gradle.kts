// The `accounting` module (P14-TSK-001).
//
// Accounting / General Ledger (25) and Reporting (27) - one module (ADR-0113; MODULE_ARCHITECTURE.md `accounting`):
// the General Ledger as a versioned mapping by ledger purpose over the ONE journal, accounting periods and their
// four-eyes close, the posting rule register and the GL <-> ledger <-> source checks, valuation rate sets, accounting
// breaks, prior-period adjustment records, immutable report runs, regulatory obligations and submissions. It owns the
// mapping, the period, the close record, the run and the submission, and NONE of the money: no table here holds a
// balance or a line (INV-ACC-06), it writes the journal only through the ledger's own ACCOUNTING-origin adjustment
// door, and its reporting never writes (INV-ACC-14).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module owns the
//   `accounting` schema and nothing else may change it; a single central migration runner would let any module alter
//   any schema. The configuration is duplicated per schema-owning module deliberately rather than shared through a
//   convention plugin, because that plugin would BE the central runner this design rejects.
buildscript {
    dependencies {
        classpath(libs.flyway.database.postgresql)
        classpath(libs.postgresql.driver)
    }
}

plugins {
    id("finapp.java-conventions")
    alias(libs.plugins.flyway)
    // The GL mapping fixtures (P14-TSK-003; owner decision C2 - test-only content, production activates no version): the
    // reference version over the ledger's forty purposes and an in-memory store, shared by this module's suites and
    // app's (the chart adapter's proof against the ledger's own enum and seed, the desk's, the database suite's) - one
    // reference, never a copy. Test code: it ships in no jar. The risk precedent (RiskPolicyFixtures).
    `java-test-fixtures`
}

// Connection details, resolved exactly as platform resolves them and from the same environment variables, so
// overriding one moves the container, the migration tool and the tests together.
val dbName = providers.environmentVariable("FINAPP_DB_NAME").orElse("finapp").get()
val dbUrl = providers.environmentVariable("FINAPP_DB_URL")
    .orElse("jdbc:postgresql://127.0.0.1:5432/$dbName").get()

// The migrator, never the superuser (P0-TSK-022). The privilege floor this module ships with is only a control because
// the schema's objects are owned by a role that cannot bypass the grants it applies - and PHASE_14_PLAN.md section 9
// commits this schema to SELECT, INSERT on every table, UPDATE only on the status columns of the machine rows through
// conditional edges, report content frozen by trigger and no DELETE for any role, all of which are only available to
// the later tasks if the owner is right from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("accounting")
    defaultSchema = "accounting"
    locations = arrayOf("filesystem:src/main/resources/db/migration/accounting")

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
    // The documented direction: accounting -> platform -> sharedkernel (ADR-0113 decision 1), and nothing else.
    //
    // Deliberately NO edge to `ledger`: the GL is a function of the journal, not a store (INV-ACC-06), so accounting
    // reads the journal through `JournalReads`, the ledger's chart through `LedgerChart`, commands the cutoff through
    // `PostingCutoff` and posts a prior-period adjustment through `PriorPeriodPostings` - ports `app` implements over the
    // ledger's own API - and with no edge no code here can even name a posting service. Deliberately NO edge to any
    // business module or to `risk`: the product proofs, the accrual completeness, reconciliation's disclosure, fx's
    // reference rates and the regulatory provider are ports too. None of these refusals has a Gradle cycle behind it -
    // AccountingModuleIsolationTest is the only control - and no module but `app` depends on `accounting`: every
    // sibling's isolation test refuses it from its side.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles) for the database-tier test this task ships: the
    // schema floor proven LIVE (AccountingMigrationTest). The module that owns the schema owns the proof of its floor.
    testImplementation(testFixtures(project(":platform")))

    // The fixtures see this module's GL mapping types (main) and platform's Actor - nothing else, and no ledger.
    testFixturesImplementation(project(":platform"))
    testFixturesImplementation(platform(libs.spring.boot.bom))
}

// The `database` tier's module-specific configuration - the container image, from the version catalog, exactly as
// platform's, app's, lending's, bnpl's and risk's tasks supply it. Without this the shared harness runs, finds no
// image, and returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
