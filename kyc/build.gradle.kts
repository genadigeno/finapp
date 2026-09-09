// The `kyc` module (P2-TSK-003).
//
// Whether a party may be onboarded, and the evidence for that decision. Owns the KYC/KYB case,
// its checks, screening results, document references and the decision itself (ADR-0035): the
// verification outcome has ONE authority, and it is here — `party.customer.status` is a
// projection of it, never a peer (`INV-KYC-05`).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This
//   module owns the `kyc` schema and nothing else may change it; a single central migration
//   runner would let any module alter any schema, which is precisely the coupling
//   schema-per-module exists to prevent. The history table lives in this schema too.
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
// Duplicated deliberately rather than shared: a Gradle convention plugin that configured Flyway
// for every module would be the central runner this design rejects.
val dbName = providers.environmentVariable("FINAPP_DB_NAME").orElse("finapp").get()
val dbUrl = providers.environmentVariable("FINAPP_DB_URL")
    .orElse("jdbc:postgresql://127.0.0.1:5432/$dbName").get()

// The migrator, never the superuser: objects must be owned by a role that cannot bypass the
// grants it applies, which is what makes the DB-PRIVILEGE invariants enforceable at all — and
// this module's whole later posture (immutable decisions, append-only evidence, INV-KYC-02/-06)
// rests on that rank being available.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("kyc")
    defaultSchema = "kyc"
    locations = arrayOf("filesystem:src/main/resources/db/migration/kyc")

    // The same four settings platform uses, and for the same reasons: clean is unrecoverable on
    // a database holding history; an edited applied migration means the database and the
    // repository disagree; out-of-order application makes the schema a function of merge order;
    // and an unexpected existing schema is something a human must look at.
    cleanDisabled = true
    validateOnMigrate = true
    outOfOrder = false
    baselineOnMigrate = false
}

dependencies {
    // The documented direction: kyc -> platform -> sharedkernel. `implementation`, not `api`:
    // nothing outside this module should compile against platform because it depended on kyc.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // P2-TSK-009: the SimulatedProvider harness (P0-TSK-037), getting its first real caller
    // two phases after it was built - the verification adapters are exactly what it was for.
    testImplementation(testFixtures(project(":platform")))
}
