// Intentionally near-empty.
//
// Shared configuration lives in build-logic convention plugins, not here. There
// is no allprojects { } or subprojects { } block by design: cross-project
// configuration hides a module's effective settings from its own build file and
// blocks project isolation. Each module applies the conventions it wants.
//
// If this file starts growing, that is a signal a new convention plugin is
// needed — not that this file should absorb the configuration.

tasks.register("toolchainInfo") {
    group = "help"
    description = "Prints the pinned toolchain and build versions."
    val gradleVersion = gradle.gradleVersion
    val javaVersion = JavaVersion.current().toString()
    doLast {
        logger.lifecycle("Gradle      : $gradleVersion")
        logger.lifecycle("Launcher JVM: $javaVersion")
    }
}
