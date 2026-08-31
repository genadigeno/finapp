// Baseline configuration shared by every Java module in the platform.
//
// Applied explicitly by each module. Adding a module to the build does not
// silently opt it into anything; it must ask for these conventions by name.

plugins {
    java
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
