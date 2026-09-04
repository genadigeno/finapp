// The `party` module (P1-TSK-003).
//
// Who exists as a legal party and what commercial relationship they hold. Owns Party,
// Customer and profile data. Deliberately separate from `identity`: who exists as a legal party
// and who can log in are different questions with different lifecycles (ADR-0029).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This
//   module owns the `party` schema and nothing else may change it; a single central
//   migration runner would let any module alter any schema, which is precisely the coupling
//   schema-per-module exists to prevent. The history table lives in this schema too, so each
//   module's migration state is its own.
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
// grants it applies, which is what makes the DB-PRIVILEGE invariants enforceable at all.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = migratorUser
    password = migratorPassword

    schemas = arrayOf("party")
    defaultSchema = "party"
    locations = arrayOf("filesystem:src/main/resources/db/migration/party")

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
    // The documented direction: party -> platform -> sharedkernel. `implementation`, not
    // `api`: nothing outside this module should compile against platform because it depended on
    // party. When a published signature genuinely needs a platform type, that edge becomes a
    // reviewed change to `api` rather than an accident.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
