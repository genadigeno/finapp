// The `paymentmethods` module (P5-TSK-001).
//
// The tokenised-instrument boundary - the platform's PCI line (MODULE_ARCHITECTURE.md M7,
// INV-PAY-02). It exists as its own module so that "no raw card data crosses this line" is a
// boundary that can be reviewed - and, from this task on, one the build enforces: no business
// sibling may see this module, and this module sees no business sibling. It will hold the
// PaymentMethod aggregate (P5-TSK-004): a token reference and display metadata, never a PAN, a
// CVV, track data or anything from which an instrument could be reconstructed.
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This module
//   owns the `paymentmethods` schema and nothing else may change it; a single central migration
//   runner would let any module alter any schema. The configuration is duplicated per
//   schema-owning module deliberately rather than shared through a convention plugin, because
//   that plugin would BE the central runner this design rejects.
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

// The migrator, never the superuser (P0-TSK-022). The privilege floor matters more here than in
// any sibling: the one table this schema will hold is the one whose columns must be provably
// unable to admit reconstructable instrument data (INV-PAY-02), and the
// information_schema-derived sweeps that prove it (P5-TSK-004) are only meaningful against
// objects owned by a role the application cannot impersonate.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("paymentmethods")
    defaultSchema = "paymentmethods"
    locations = arrayOf("filesystem:src/main/resources/db/migration/paymentmethods")

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
    // The documented direction, with NO business-sibling edge in either direction: this is the
    // most isolated business module on the platform, deliberately. `payments` reaches an
    // instrument through a port `app` implements, never through an import - and this module
    // reaches nothing at all beyond the platform kernel, because a PCI boundary that depends on
    // business siblings is a PCI boundary whose review surface is the whole dependency ball.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
