// The `merchant` module (P6-TSK-001).
//
// The merchant as a commercial counterparty: the Merchant, MerchantApiKey, FeeSchedule,
// PayoutDestination and MerchantPayout aggregates and their lifecycles (MODULE_ARCHITECTURE.md
// §4, bounded context 12; ADR-0050…0052) - and never a balance. What the platform owes a
// merchant is the merchant's payable LEDGER POSITION - captured minus fees minus payouts - and
// exists nowhere else (INV-MER-02): no table in this schema will ever hold a payable column, and
// the payout's bound is judged inside the payable account's lock with in-flight amounts held
// (INV-MER-05, ADR-0051's hold-then-dispatch).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `merchant` schema and nothing else may change it; a single central migration runner
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

// The migrator, never the superuser (P0-TSK-022). The floor matters before the first table does:
// PHASE_6_PLAN.md §8 commits the fee schedule's versions to immutability by trigger
// (INV-MER-03), the API key to hashed-at-rest storage (INV-IDN-01), the destination's proposal
// flow to a proposer-is-not-approver CHECK (INV-AUD-04), and the whole schema to holding no
// balance column of any kind (INV-MER-02) - grant and constraint shapes only available because
// the objects are owned by a role the application cannot impersonate, from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("merchant")
    defaultSchema = "merchant"
    locations = arrayOf("filesystem:src/main/resources/db/migration/merchant")

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
    // The documented direction: merchant -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is this module's one permitted business-sibling edge, and it is the defining one
    // (INV-MER-02, ADR-0050, ADR-0051): the merchant payable is a ledger position derived from
    // postings - the capture credits it gross with the fee assessed in the same entry, the
    // payout debits it under a hold placed inside the account lock - COMMANDED through the
    // ledger's APIs and never written here (INV-LED-04), and never stored here as a column. The
    // edge is declared with the module rather than with its first consumer (P6-TSK-003, the
    // payable account's creation) because the asymmetry is the deliverable: with
    // merchant -> ledger in the build graph, ledger -> merchant is a Gradle cycle and cannot be
    // added at all, and MerchantModuleIsolationTest pins the positive half so the direction is
    // pinned rather than implied.
    //
    // Deliberately NO edge to `checkout` - a session references its merchant by identifier and
    // resolves it through a port `app` implements, and the merchant's lifecycle must not be
    // shaped by the purchase experience built over it. No Gradle cycle backs this refusal;
    // MerchantModuleIsolationTest is the only control. And deliberately NO edge to `payments`:
    // the fee assessment rides the capture through the ADR-0050 §6 composition seam in `app`,
    // so the module that prices the platform's service never sees provider machinery.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
