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
    // Test fixtures, so the `app` module's database tests can reach the same harness
    // (P0-TSK-035). A test class in platform/src/test is invisible to a dependent module; a
    // fixture is the supported way to share it without inventing a new Gradle module.
    `java-test-fixtures`
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

// The two ordinary roles, created by infra/postgres/initdb/00-roles.sql (P0-TSK-022).
//
// Roles are cluster objects, so they are provisioned by infrastructure rather than by a
// migration; their PRIVILEGES on our tables are granted by the migration that creates each
// table. dbUser above remains the bootstrap superuser and is used by nothing but a fixture
// that needs DDL.
//
// Flyway connects as the migrator, so every object is owned by a role that is NOT a superuser
// -- which is what makes INV-HIST-03 enforceable at all: a superuser ignores every permission
// check, so an append-only table owned and written by one is append-only only by convention.
val migratorUser = providers.environmentVariable("FINAPP_DB_MIGRATOR_USER")
    .orElse("finapp_migrator").get()
val migratorPassword = providers.environmentVariable("FINAPP_DB_MIGRATOR_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()
val appUser = providers.environmentVariable("FINAPP_DB_APP_USER").orElse("finapp_app").get()
val appPassword = providers.environmentVariable("FINAPP_DB_APP_PASSWORD")
    .orElse("local-development-only-not-a-secret").get()

flyway {
    url = dbUrl
    // The migrator, not the superuser: objects must be owned by a role that cannot bypass the
    // grants it applies.
    user = migratorUser
    password = migratorPassword

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

    // The Spring Boot BOM as version constraints only. No Spring artefact enters this
    // module, in main or test — the BOM is here so slf4j resolves to exactly the version
    // the application runs, because a logging facade split across two versions binds to
    // nothing and fails silently rather than loudly.
    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    // `api`, not `implementation`: correlation identifiers are written into the caller's
    // log context, so a consumer of platform legitimately compiles against MDC.
    //
    // The facade only. Binding a backend here would impose it on every consumer, and
    // choosing one is the application's decision.
    api(libs.slf4j.api)

    // The tracing FACADE, on the same terms as slf4j above: platform records spans, and the
    // application decides what records them. micrometer-tracing brings no exporter, no
    // OpenTelemetry SDK and no wire format - binding one here would impose it on every consumer
    // and would put a telemetry stack inside a module that must stay a library.
    api(libs.micrometer.tracing)

    // Platform still holds no Spring code. It will need Spring once it has an outbox
    // relay, an inbox consumer and an HTTP ingress filter; P0-TSK-014 deliberately did not
    // add it, because the correlation kernel and its propagation are framework-free and
    // there is no HTTP surface yet to filter (P0-EPIC-08).

    // A real logging backend, test scope only, so a test can read what was actually
    // written to the log rather than assert that a logging call was made.
    testImplementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    // The monetary round-trip has to run against a real PostgreSQL: the claim being tested
    // is that BIGINT/CHAR(3)/SMALLINT return exactly what was written, and that is a claim
    // about the database and its driver, not about our code.
    testImplementation(libs.postgresql.driver)

    // The database test harness (P0-TSK-035) lives in testFixtures so `app` can use it too.
    // Fixture scope, which is test scope: nothing ships.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.junit.platform.launcher)
    testFixturesImplementation(libs.flyway.core)
    testFixturesRuntimeOnly(libs.flyway.database.postgresql)
    testFixturesImplementation(libs.postgresql.driver)
    // DatabaseRoles asserts the connecting role cannot bypass privileges - the assertion that
    // makes every denial test meaningful - so the fixtures need AssertJ.
    testFixturesImplementation(libs.assertj.core)

    // Test scope only: nothing ships.
    //
    // Flyway is otherwise on the BUILDSCRIPT classpath - it runs as a build tool. The harness
    // applies the same migrations from Java, so a container gets the schema the same way a real
    // database does, in the same order, with the same history table.
    testImplementation(libs.testcontainers.postgresql)
    // The LauncherSessionListener API. Gradle puts the launcher on a hidden configuration for
    // running tests; compiling against it needs it declared.
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
}

// ---------------------------------------------------------------------------
// The `database` tier's module-specific configuration.
//
// The tier TASKS are registered once in finapp.java-conventions, which is what P0-TSK-036
// generalised: the separation between hermetic and infrastructure-bound tests, and the rule that
// the default tier takes whatever no other tier claims, are properties of the build rather than
// of this module. What belongs here is only what is specific to platform — the coordinates the
// database tests connect with.
// ---------------------------------------------------------------------------
tasks.named<Test>("databaseTest") {
    // The same three environment variables the Flyway configuration above uses, so the
    // migration tool and the round-trip test can never be pointed at different databases.
    // The container image, from the version catalog, so it is the same PostgreSQL compose runs
    // and verifyInfrastructureVersions already guards (P0-TSK-035).
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())

    // The compose-stack coordinates are supplied ONLY when FINAPP_DB_URL is set explicitly.
    // Left unset, DatabaseUnderTest starts a container and publishes its own - which is what
    // makes "no test depends on a developer's local services" true. Setting the variable is the
    // deliberate escape hatch for investigating something in a database that outlives the run.
    if (providers.environmentVariable("FINAPP_DB_URL").isPresent) {
        systemProperty("finapp.db.url", dbUrl)
        systemProperty("finapp.db.user", dbUser)
        systemProperty("finapp.db.password", dbPassword)

    // The application role. Tests asserting a privilege-level invariant MUST connect as this
    // and never as dbUser above, because a superuser ignores permission checks and would pass
    // whatever the grants said. DatabaseRoles asserts it is not a superuser for that reason.
        systemProperty("finapp.db.app.user", appUser)
        systemProperty("finapp.db.app.password", appPassword)

    // The migrator, for fixtures that legitimately need DDL - a probe table, say. Application
    // behaviour is never exercised through it.
        systemProperty("finapp.db.migrator.user", migratorUser)
        systemProperty("finapp.db.migrator.password", migratorPassword)
    }

    // `outputs.upToDateWhen { false }` is NOT repeated here. The convention plugin applies it to
    // every tier needing external infrastructure, which is the property being expressed — a
    // second copy would be a second place for it to be forgotten.
}
