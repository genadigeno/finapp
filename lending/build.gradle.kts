import java.time.Instant
import java.time.ZoneOffset

// The `lending` module (P11-TSK-002).
//
// Originating and servicing credit - the amortising PERSONAL_LOAN and the revolving CREDIT_LINE -
// on Phase 10's credit decisions: the application, the versioned terms, the offer, the immutable
// agreement and its acceptance evidence, the loan account of either kind and every born-once
// servicing fact (ADR-0090...ADR-0100; MODULE_ARCHITECTURE.md `lending`). It owns the CONTRACT and
// its servicing facts and NONE of the money: every figure a borrower is charged is a ledger
// balance, posted through the ledger's APIs and never written here (INV-LED-04, INV-LND-01).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `lending` schema and nothing else may change it; a single central migration runner
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
// it applies - and PHASE_11_PLAN.md section 8 commits this schema to INSERT-only money tables,
// column-scoped UPDATE of status and permit columns on the machine tables and hand-written
// every-writer triggers, all of which are only available to the later tasks if the owner is right
// from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("lending")
    defaultSchema = "lending"
    locations = arrayOf("filesystem:src/main/resources/db/migration/lending")

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
    // The documented direction: lending -> ledger -> platform -> sharedkernel (ADR-0090 point 5), and since P12-TSK-002
    // lending -> creditmath -> sharedkernel (below).
    //
    // `ledger` is this module's one permitted business-sibling edge (creditmath is arithmetic, no bounded context): disbursements, accruals,
    // billings, repayments, draws and corrections are COMMANDED through PostingService /
    // ReversalService and the loan's balances derived through the ledger's API - never written
    // (INV-LED-04, INV-LND-01). The edge is declared with the module, before anything uses it,
    // because the asymmetry is the deliverable: with lending -> ledger in the build graph,
    // ledger -> lending is a Gradle cycle, and LendingModuleIsolationTest pins the positive half.
    //
    // Deliberately NO edge to `credit` (A16, the crossborder precedent): the decision, its
    // consumption (credit's CreditDecisionConsumptions, P11-TSK-001) and the party's exposure lock
    // are reached through LoanCreditDecisions, a port this module will declare and `app` composes.
    // Deliberately NO edge to `accounts`, `payments`, `party` or `reconciliation` either: the wallet,
    // the external payout, the party's standing and the capital contribution's recognition are each
    // a port `app` implements (LoanWallets, LoanPayouts, LoanPartyStanding, the recognition read).
    // None of these refusals has a Gradle cycle behind it - LendingModuleIsolationTest is the only
    // control.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    // `creditmath` (P12-TSK-002; ADR-0101 decision 5, MODULE_ARCHITECTURE.md M12): the schedule, accrual and allocation
    // engines this module computed with since Phase 11, moved there unchanged so bnpl shares the arithmetic without an
    // edge to lending. The module's second sibling edge, and like the ledger's one-way: with lending -> creditmath in
    // the build graph, creditmath -> lending is a Gradle cycle. Lending keeps the LoanTerms side of the edge
    // (LoanEngines) - creditmath sees no lending type.
    implementation(project(":creditmath"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The shared database harness (DatabaseUnderTest, DatabaseRoles), for the database-tier tests
    // this task ships: the schema floor proven LIVE (LendingMigrationTest) and the no-mutable-money
    // guard run against the catalogue as it actually stands (LendingSchemaHasNoMutableMoneyTest).
    // The module that owns the schema owns the proof of its floor.
    testImplementation(testFixtures(project(":platform")))

    // The engines' second JVM (SecondJvm), published by creditmath with the engines' own suites (P12-TSK-002): the
    // statement engine's two-JVM comparison and the battery (P11-TST-002) run lending's code in it - one harness, never
    // a copy.
    testImplementation(testFixtures(project(":creditmath")))
}

// The `database` tier's module-specific configuration - the container image, from the version
// catalog, exactly as platform's and app's tasks supply it. Without this the shared harness
// runs, finds no image, and returns (P0-TSK-035).
tasks.named<Test>("databaseTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())

    // Date-shifted, always (P11-TSK-032). Lending judges every date on the database clock in the agreement's pinned
    // zone; a fixture that plants `current_date` (the session's zone, which the JDBC driver takes from this JVM's
    // default) or an instance clock's date is a day off whenever the machine's date is not UTC's - on a UTC+4 machine
    // only from 20:00 to 24:00 UTC, so the defect hid for twenty hours a day. The test JVM therefore runs in a zone
    // whose date differs from UTC's at the hour it starts: UTC-12 before 11:00 UTC (a day behind until 12:00), UTC+14
    // from 11:00 (a day ahead from 10:00). A run spanning that hour, or midnight UTC, loses the shift only for its
    // tail. `-Pfinapp.db.zone=<zone id>` pins another zone - `UTC` turns the shift off for a diagnosis.
    val pinnedZone = providers.gradleProperty("finapp.db.zone")
    val test = this
    doFirst {
        val zone = pinnedZone.orNull
            ?: if (Instant.now().atZone(ZoneOffset.UTC).hour < 11) "Etc/GMT+12" else "Etc/GMT-14"
        test.systemProperty("user.timezone", zone)
        logger.lifecycle("${test.path}: the test JVM runs in $zone" + (if (pinnedZone.isPresent) " (pinned by"
            + " -Pfinapp.db.zone)" else " - a date other than UTC's") + " (P11-TSK-032)")
    }
}

// The battery's probe-only size knob (P11-TST-002, AmortisationBatteryTest) is read from the environment, which Gradle
// does not see: declared as an input of every test task, so a shrunk probe run is never answered UP-TO-DATE from a
// full-size green run (or the other way round) - the completion gate's finding.
tasks.withType<Test>().configureEach {
    inputs.property("finapp.lending.battery.loans",
        providers.environmentVariable("FINAPP_LENDING_BATTERY_LOANS").orElse("full size"))
    inputs.property("finapp.lending.battery.lines",
        providers.environmentVariable("FINAPP_LENDING_BATTERY_LINES").orElse("full size"))
}
