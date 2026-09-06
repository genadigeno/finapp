// The `identity` module (P1-TSK-003).
//
// Who can authenticate, with what credential, from which device or session. Owns Identity,
// Credential, MFA enrolment, Device, Session and Role assignment — the platform's
// highest-sensitivity module. Credential material never leaves it in any form (ADR-0032).
//
// WHY FLYWAY IS APPLIED HERE AND NOT CENTRALLY
//   Schema ownership and migration ownership are the same thing (ADR-0006, ADR-0011). This
//   module owns the `identity` schema and nothing else may change it; a single central
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

    schemas = arrayOf("identity")
    defaultSchema = "identity"
    locations = arrayOf("filesystem:src/main/resources/db/migration/identity")

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
    // The documented direction: identity -> platform -> sharedkernel. `implementation`, not
    // `api`: nothing outside this module should compile against platform because it depended on
    // identity. When a published signature genuinely needs a platform type, that edge becomes a
    // reviewed change to `api` rather than an accident.
    implementation(project(":platform"))

    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    // Argon2id (ADR-0032). A vetted library, because the ADR forbids a custom primitive, a
    // hand-rolled comparison and a bespoke salting scheme - exactly what this provides.
    // `implementation`: the encoder is an implementation detail behind PasswordDeriver, and nothing
    // outside this module should compile against Spring Security because it depended on identity.
    implementation(libs.spring.security.crypto)

    // NOT optional, despite spring-security-crypto's POM, which declares only an optional assertj.
    // The encoder needs BouncyCastle to derive and spring-core to verify; both gaps were found by
    // running the real encoder, each as its own NoClassDefFoundError. `identity` therefore does take
    // a Spring Framework runtime dependency - stated rather than described away, since the module
    // boundary rules permit it and the honest description matters more than the tidy one.
    implementation(libs.spring.core)
    runtimeOnly(libs.bouncycastle.provider)
    testRuntimeOnly(libs.bouncycastle.provider)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
