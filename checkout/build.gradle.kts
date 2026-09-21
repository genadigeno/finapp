// The `checkout` module (P6-TSK-001).
//
// The customer-facing purchase experience and the commercial fact it produces: the
// CheckoutSession and Order aggregates and their lifecycles (MODULE_ARCHITECTURE.md §4, bounded
// context 11; ADR-0053 - two aggregates, because an order that expires is not a fact). A session
// is a short-lived, expiring OFFER to pay; the order is born only from a paid session; the
// payment itself is Phase 5's machinery, referenced by identifier and commanded through a port
// `app` implements - never imported. Expiry gates dispatch; landed money always wins
// (INV-MER-06): the race rule is the module's defining behaviour, and it is decided by
// conditional transitions in the database, never by anything process-local.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `checkout` schema and nothing else may change it; a single central migration runner
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
// PHASE_6_PLAN.md §8 commits the session's token to hashed-at-rest storage under frozen columns,
// the order to append-only grants (an order is a FACT), and the session's transitions to
// every-writer triggers - grant shapes only available because the objects are owned by a role
// the application cannot impersonate, from the first object.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("checkout")
    defaultSchema = "checkout"
    locations = arrayOf("filesystem:src/main/resources/db/migration/checkout")

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
    // The documented direction, with NO business-sibling edge in either direction - the
    // `paymentmethods` posture, chosen for a different reason: `paymentmethods` is isolated so
    // the PCI line is one module's surface; `checkout` is isolated so the purchase experience
    // cannot grow into a god-orchestrator (PHASE_6_PLAN.md §18's named risk). Payment execution
    // (`payments`), merchant resolution (`merchant`) and the ADR-0050 posting composition all
    // reach this module through ports `app` implements. None of the three refusals has a Gradle
    // cycle behind it; CheckoutModuleIsolationTest is the only control, which is why it exists.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
