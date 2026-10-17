// The `bnpl` module (P12-TSK-002).
//
// Buy now, pay later - pay-in-4 and pay-in-N on Phase 10's credit decisions and Phase 11's patterns: the BNPL
// application, the versioned terms, the offer, the immutable agreement and its acceptance evidence, the BNPL purchase,
// the merchant financing record, the customer repayment obligation and every born-once servicing fact, the merchant's
// refunds and the customer's disputes (ADR-0101...ADR-0106; MODULE_ARCHITECTURE.md `bnpl`). It owns those facts and
// NONE of the money: every figure a customer owes or a merchant is credited is a ledger balance, posted through the
// ledger's APIs and never written here (INV-LED-04, INV-BNPL-01).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module owns the `bnpl`
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
// the schema's objects are owned by a role that cannot bypass the grants it applies - and PHASE_12_PLAN.md section 8
// commits this schema to INSERT-only money tables, column-scoped UPDATE of status and permit columns on the machine
// tables and hand-written every-writer triggers, all of which are only available to the later tasks if the owner is
// right from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("bnpl")
    defaultSchema = "bnpl"
    locations = arrayOf("filesystem:src/main/resources/db/migration/bnpl")

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
    // The documented direction: bnpl -> creditmath, ledger, platform -> sharedkernel (ADR-0101 decision 6).
    //
    // `ledger` is this module's one permitted business-sibling edge: the merchant financing, the obligation's
    // billings, accruals, repayments, refunds and corrections are COMMANDED through PostingService / ReversalService and
    // the obligation's balances derived through the ledger's API - never written (INV-LED-04, INV-BNPL-01). Declared
    // with the module, before anything uses it, because the asymmetry is the deliverable: with bnpl -> ledger in the
    // build graph, ledger -> bnpl is a Gradle cycle, and BnplModuleIsolationTest pins the positive half.
    //
    // `creditmath` (ADR-0101 decision 5, OQ-2): lending's pure schedule, accrual and allocation engines, shared without
    // an edge to lending - so bnpl can never reach a lending store.
    //
    // Deliberately NO edge to `lending`, `credit`, `checkout`, `merchant`, `payments`, `accounts`, `party` or
    // `reconciliation`: the decision and its consumption (credit's CreditDecisionConsumptions with
    // ConsumerKind.BNPL_AGREEMENT), the checkout session, the merchant's standing, payable and fee schedule, the wallet,
    // the party's standing and lending capital are each a port this module will declare and `app` implements
    // (BnplCreditDecisions, BnplCheckoutSessions, BnplMerchants, BnplWallets, BnplPartyStanding, BnplCapital). None of
    // these refusals has a Gradle cycle behind it - BnplModuleIsolationTest is the only control.
    implementation(project(":creditmath"))
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles) and the schema floor's one judgement
    // (MoneyColumnFloor), for the database-tier tests this task ships: the schema floor proven LIVE (BnplMigrationTest)
    // and the no-mutable-money guard run against the catalogue as it actually stands (BnplSchemaHasNoMutableMoneyTest).
    // The module that owns the schema owns the proof of its floor.
    testImplementation(testFixtures(project(":platform")))

    // The engines' second JVM (SecondJvm, creditmath's published test fixtures - one harness, never a copy): pay-in-4's
    // schedule engine is proven deterministic across JVMs in it (P12-TSK-008), as lending's engines are.
    testImplementation(testFixtures(project(":creditmath")))
}

// The `database` tier's module-specific configuration - the container image, from the version catalog, exactly as
// platform's, app's and lending's tasks supply it. Without this the shared harness runs, finds no image, and returns
// (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
}
