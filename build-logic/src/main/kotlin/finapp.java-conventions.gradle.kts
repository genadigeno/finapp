// Baseline configuration shared by every Java module in the platform.
//
// Applied explicitly by each module. Adding a module to the build does not
// silently opt it into anything; it must ask for these conventions by name.

plugins {
    // java-library, not plain java: it provides the api/implementation split.
    //
    // That split is a boundary control, not a build detail. `implementation`
    // keeps a dependency off consumers' compile classpaths, so a module cannot
    // accidentally leak its internals to everything downstream; `api` makes
    // exposure a deliberate, reviewable choice. In a modular monolith, where
    // boundaries are not enforced by process isolation, controlling transitive
    // exposure is one of the few mechanisms that actually holds.
    `java-library`
}

group = "com.finapp"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        // The Java version is pinned by the Gradle toolchain, NOT by whichever
        // JDK happens to be on JAVA_HOME. Gradle locates or provisions a
        // matching JDK, so the compiler and target bytecode are identical on a
        // developer laptop and on CI. This is an acceptance criterion of
        // P0-TSK-001.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Gradle does not put the JUnit Platform launcher on the test runtime
    // classpath implicitly. Without it every module fails identically with
    // "Failed to load JUnit Platform", which is a confusing message for a
    // missing-dependency problem — so it is declared once here rather than
    // rediscovered in each new module.
    //
    // Deliberately versionless: the version comes from whichever BOM the module
    // applies (junit-bom in sharedkernel, the Spring Boot BOM elsewhere), so a
    // module's launcher always matches its JUnit.
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Deterministic bytecode regardless of the building machine's locale.
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-processing",
            // Warnings are errors from the first commit. A codebase that
            // tolerates warnings stops surfacing the one that mattered; in a
            // financial codebase that is how a narrowing conversion or an
            // unchecked cast reaches a money path unnoticed.
            "-Werror",
        )
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.withType<Jar>().configureEach {
    // Reproducible archives: identical inputs produce a byte-identical jar.
    // Required for build provenance, and it makes "did this artefact change?"
    // an answerable question.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
