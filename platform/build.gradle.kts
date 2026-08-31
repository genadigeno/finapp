// The Flyway tasks resolve their JDBC driver and database support from the buildscript
// classpath, not from the project's dependencies — Flyway runs as a build tool here, not as
// part of the application. Versions still come from the version catalog, so the rule that no
// version literal appears in a build script holds.
//
// Flyway 10+ ships per-database support as a separate artefact, so flyway-database-postgresql
// is required alongside the JDBC driver; without it Flyway reports
// "No Flyway database plugin found to handle jdbc:postgresql://...".
buildscript {
    dependencies {
        classpath(libs.flyway.database.postgresql)
        classpath(libs.postgresql.driver)
    }
}

plugins {
    id("finapp.java-conventions")
    // Flyway is applied to platform, not to the root build, because platform owns the
    // `platform` schema. Each module that owns a schema runs its own migrations against
    // its own history table — schema ownership and migration ownership are the same thing
    // (ADR-0006). A single central migration runner would let any module change any
    // schema, which is precisely the coupling schema-per-module exists to prevent.
    alias(libs.plugins.flyway)
}

// ---------------------------------------------------------------------------
// Migration settings.
//
// Connection details default to the local Docker Compose stack and are overridable by
// environment variable. No credential is committed beyond the marked local-only default
// already established in compose.yaml.
// ---------------------------------------------------------------------------
// Connection details, resolved once and shared by the migration tool and the round-trip
// test. Reading them in two places would let the two be pointed at different databases,
// which is exactly the kind of divergence that makes a green test meaningless.
//
// These defaults MUST match the defaults in compose.yaml — they are the same environment
// variables, so overriding FINAPP_DB_NAME (or user, or password) moves the container, the
// migration tool and the test together rather than only one of them.
val dbName = providers.environmentVariable("FINAPP_DB_NAME").orElse("finapp").get()
val dbUrl = providers.environmentVariable("FINAPP_DB_URL")
    .orElse("jdbc:postgresql://127.0.0.1:5432/$dbName").get()
val dbUser = providers.environmentVariable("FINAPP_DB_USER").orElse("finapp").get()
val dbPassword = providers.environmentVariable("FINAPP_DB_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    user = dbUser
    password = dbPassword

    schemas = arrayOf("platform")
    defaultSchema = "platform"
    locations = arrayOf("filesystem:src/main/resources/db/migration/platform")

    // --- Settings chosen for financial correctness, not convenience ---

    // flywayClean drops every object in the managed schemas. On a database holding
    // financial history that is an unrecoverable operation, and there is no situation in
    // this platform where it is the right answer. Disabled permanently.
    cleanDisabled = true

    // Refuse to run if an already-applied migration's checksum no longer matches the file.
    // Editing an applied migration means the database and the repository disagree about
    // what was executed, and no later reasoning about the schema can be trusted.
    validateOnMigrate = true

    // Migrations apply in version order only. Out-of-order application makes the schema a
    // function of *when* each branch was merged rather than of the version sequence, so two
    // environments at the same version could differ. That is not acceptable where the
    // schema holds the ledger.
    outOfOrder = false

    // Never adopt a non-empty database by assuming its current state is the baseline. An
    // unexpected existing schema is a situation a human must look at, not something the
    // tool should paper over.
    baselineOnMigrate = false
}

dependencies {
    // `api`, not `implementation`: sharedkernel types (Money, typed identifiers, the event
    // envelope) will appear in platform's own public signatures — an outbox record carries
    // an envelope, an audit record carries an actor id. Consumers of platform therefore
    // need those types to compile against it.
    //
    // This is what makes the documented chain app -> platform -> sharedkernel literally
    // true: app depends on platform and receives sharedkernel through it, rather than
    // declaring a second edge.
    api(project(":sharedkernel"))

    // No Spring artefact, in main or test.
    //
    // Platform will need Spring once it has outbox, inbox, idempotency and audit
    // components (P0-TSK-014 onward), and it will be added by the task that needs it.
    // Declaring it now is speculative, and it would put the whole Spring test stack on the
    // classpath of a test that uses only JUnit and AssertJ.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The monetary round-trip has to run against a real PostgreSQL: the claim being tested
    // is that BIGINT/CHAR(3)/SMALLINT return exactly what was written, and that is a claim
    // about the database and its driver, not about our code.
    testImplementation(libs.postgresql.driver)
}

// ---------------------------------------------------------------------------
// Tests that need a database are separated from those that do not.
//
// `test` stays hermetic so `./gradlew build` is green on a machine with nothing
// running. `databaseTest` is a task you can see did not run, rather than a test
// that silently skips itself when the database is absent — a skipped test
// reports success, which is the failure mode this project keeps finding.
//
// P0-TSK-036 (test taxonomy) generalises this; it is deliberately minimal here.
// ---------------------------------------------------------------------------
val databaseTag = "database"

tasks.test {
    useJUnitPlatform { excludeTags(databaseTag) }
}

tasks.register<Test>("databaseTest") {
    group = "verification"
    description = "Runs tests that require a live PostgreSQL (docker compose up -d postgres)."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags(databaseTag) }

    // The same three environment variables the Flyway configuration above uses, so the
    // migration tool and the round-trip test can never be pointed at different databases.
    systemProperty("finapp.db.url", dbUrl)
    systemProperty("finapp.db.user", dbUser)
    systemProperty("finapp.db.password", dbPassword)

    // Never cached: the point is to exercise a real database, and a cached "up to date"
    // result would mean it had not.
    outputs.upToDateWhen { false }
}
