// The `migrator` module (P15-TSK-004): what runs the migrations in a deployed environment.
//
// WHY A MODULE AND NOT THE FLYWAY COMMAND LINE. The image is distroless (the application image's bases, one definition,
// the root Dockerfile), so it has no shell to loop over twenty-one schemas; and a shell loop could not say what the
// Job needs said: which failure a retry can help, and which it cannot. A small program over Flyway's API, in the same
// Flyway version the build's plugin uses, is the same engine with the same settings.
//
// WHY IT DEPENDS ON NOTHING HERE. Schema ownership stays where it is (ADR-0006, ADR-0011): each module owns its
// migrations, and this reads them as files. No project(...) dependency, so the runner can neither compile against nor
// ship a module's code - and `app` does not depend on it, so Flyway stays absent from the application (ADR-0011).
plugins {
    id("finapp.java-conventions")
}

dependencies {
    // The catalog's versions: exactly the plugin's Flyway, so a checksum computed here is the one the build computed.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    implementation(libs.postgresql.driver)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

// The pinned order is the one definition of which schemas the image carries: MigrationPlan reads it at run time and this
// task reads it to stage their files, so a schema cannot be applied without being shipped, or shipped without being applied.
val schemaOrder = layout.projectDirectory.file("src/main/resources/com/finapp/migrator/schema-order.txt")
val schemas = schemaOrder.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

// The image's whole context: one tar, reproducible, root-owned and read-only to the process. Permissions and order are
// fixed here rather than inherited, because the file modes of a checkout differ between a developer's Windows machine and
// CI's Linux runner, and "the same bytes" must not depend on which one built it.
val imageTar by tasks.registering(Tar::class) {
    group = "build"
    description = "Stages the migrator image's context: the runner, its libraries and every module's migrations."
    destinationDirectory = layout.buildDirectory.dir("image")
    archiveFileName = "finapp-migrator.tar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    filePermissions { unix("644") }
    dirPermissions { unix("755") }
    into("migrator/lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
    schemas.forEach { schema ->
        into("migrator/sql/$schema") {
            from(rootProject.layout.projectDirectory.dir("$schema/src/main/resources/db/migration/$schema"))
            include("V*.sql")
        }
    }
}

// These tests read the build's own files: settings, every module's Flyway block and main source set, the Dockerfile, CI, the
// image's scripts and the contract written from them. Declared, or an edit to any of them would leave the task UP-TO-DATE over a claim nobody re-checked.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.layout.projectDirectory) {
            include(
                "settings.gradle.kts",
                "*/build.gradle.kts",
                "*/src/main/**",
                "docs/architecture/DATA_MIGRATIONS.md",
                "Dockerfile*",
                ".dockerignore",
                "migrator/.dockerignore",
                ".github/workflows/ci.yml",
                "infra/scripts/**",
                "infra/postgres/initdb/**",
            )
            exclude("**/build/**")
        }
    )
        .withPropertyName("buildsOwnFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
