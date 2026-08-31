// Intentionally near-empty.
//
// Shared configuration lives in build-logic convention plugins, not here. There
// is no allprojects { } or subprojects { } block by design: cross-project
// configuration hides a module's effective settings from its own build file and
// blocks project isolation. Each module applies the conventions it wants.
//
// If this file starts growing, that is a signal a new convention plugin is
// needed — not that this file should absorb the configuration.

plugins {
    // Provides the root lifecycle tasks (check, build, clean) so repository-wide
    // verification can hang off `check` and therefore run in a normal build.
    base
}

// ---------------------------------------------------------------------------
// Infrastructure version drift check.
//
// compose.yaml must pin the same image versions the JVM-side test infrastructure
// uses (P0-TSK-035, Testcontainers). Keeping the two aligned by convention fails
// silently, and produces the worst kind of defect: one that reproduces locally
// but not in tests, or the reverse. Collation, broker semantics and eviction
// behaviour all vary across versions. This makes the drift a build failure.
// ---------------------------------------------------------------------------
val verifyInfrastructureVersions by tasks.registering {
    group = "verification"
    description = "Fails if compose.yaml image versions differ from the version catalog."

    val composeFile = layout.projectDirectory.file("compose.yaml")
    val expected = mapOf(
        "postgres" to libs.versions.postgresImage.get(),
        "apache/kafka" to libs.versions.kafkaImage.get(),
        "redis" to libs.versions.redisImage.get(),
    )

    inputs.file(composeFile)
    inputs.property("expectedImageVersions", expected)

    doLast {
        // Parsed line by line rather than with a regex: the shape being matched is
        // trivial, and this stays readable to someone who does not want to decode a
        // pattern while debugging a failing build.
        val declared: Map<String, String> = composeFile.asFile.readLines()
            .map { it.trim() }
            .filter { it.startsWith("image:") }
            .mapNotNull { line ->
                val spec = line.removePrefix("image:").trim()
                val separator = spec.lastIndexOf(':')
                if (separator <= 0) null else spec.take(separator) to spec.substring(separator + 1)
            }
            .toMap()

        val problems = buildList {
            expected.forEach { (image, wanted) ->
                when (val found = declared[image]) {
                    null -> add("compose.yaml declares no '" + image + "' image, catalog expects " + wanted)
                    wanted -> Unit
                    else -> add("compose.yaml pins " + image + ":" + found + " but the catalog says " + wanted)
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString(
                    separator = "; ",
                    prefix = "Infrastructure version drift between compose.yaml and gradle/libs.versions.toml: ",
                    postfix = ". Update both, or the local stack and the test stack are not the same software.",
                )
            )
        }
        logger.lifecycle(
            "Infrastructure versions aligned: " +
                expected.entries.joinToString { it.key + ":" + it.value }
        )
    }
}

tasks.check {
    dependsOn(verifyInfrastructureVersions)
}

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
