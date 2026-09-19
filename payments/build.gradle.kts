// The `payments` module (P5-TSK-001).
//
// Money movement whose outcome is decided by an unreliable third party: the PaymentIntent,
// PaymentAttempt and Refund aggregates, their lifecycles and their retained provider evidence
// (MODULE_ARCHITECTURE.md §4, bounded context 9) - and never a posting. The ledger is the sole
// writer of journal entries (INV-LED-04); this module COMMANDS the capture's and the refund's
// postings through `PostingService` in the outcome transaction ADR-0046 decides, and writes no
// journal row itself (ADR-0048: authorization posts nothing at all - the ledger's first touch is
// capture).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `payments` schema and nothing else may change it; a single central migration runner
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
// it applies - and PHASE_5_PLAN.md §8 already commits `payments.provider_evidence` to append-only
// grants (INV-HIST-02), the attempt to insert-carries-outcome with per-operation idempotency
// references, and the refund bound to an in-trigger sum binding every writer - all of which are
// only available to P5-TSK-008 if the owner is right from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("payments")
    defaultSchema = "payments"
    locations = arrayOf("filesystem:src/main/resources/db/migration/payments")

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
    // The documented direction: payments -> ledger -> platform -> sharedkernel.
    //
    // `ledger` is this module's one permitted business-sibling edge, and it is the defining one
    // (ADR-0048): a capture's money movement IS a ledger posting - debit PSP clearing, credit the
    // customer wallet - commanded through the ledger's API in the outcome transaction and never
    // written here (INV-LED-04). The edge is declared with the module rather than with its first
    // consumer (P5-TSK-010, two milestones away) because the asymmetry is the deliverable: with
    // payments -> ledger in the build graph, ledger -> payments is a Gradle cycle and cannot be
    // added at all, and PaymentsModuleIsolationTest pins the positive half so the direction is
    // pinned rather than implied.
    //
    // Deliberately NO edge to `paymentmethods` - and unlike the ledger asymmetry, NO CYCLE backs
    // this refusal, so PaymentsModuleIsolationTest is the only control. The module that talks to
    // providers must not compile against the module that holds the PCI boundary: the instrument
    // resolves through a port `app` implements (the AccountHolderVerification shape, P5-TSK-009),
    // and what this buys is that "no raw card data crosses this line" stays a review of ONE
    // module's surface (MODULE_ARCHITECTURE.md M7, INV-PAY-02).
    //
    // Deliberately NO edge to `accounts` either: the wallet resolves through the ledger and the
    // established ports, the transfers precedent.
    implementation(project(":ledger"))
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
