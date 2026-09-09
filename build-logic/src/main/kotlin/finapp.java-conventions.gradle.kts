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

// The toolchain version comes from the version catalog, so it has exactly one definition
// across the convention plugin, build-logic's own compilation, and the tests that assert
// it. A precompiled script plugin cannot use the generated `libs` accessor, so the catalog
// is read through its extension instead.
val javaToolchainVersion: Int =
    extensions.getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("javaToolchain")
        .orElseThrow { GradleException("Version catalog is missing 'javaToolchain'") }
        .requiredVersion
        .toInt()

java {
    toolchain {
        // The Java version is pinned by the Gradle toolchain, NOT by whichever
        // JDK happens to be on JAVA_HOME. Gradle locates or provisions a
        // matching JDK, so the compiler and target bytecode are identical on a
        // developer laptop and on CI. This is an acceptance criterion of
        // P0-TSK-001.
        languageVersion = JavaLanguageVersion.of(javaToolchainVersion)
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

// ---------------------------------------------------------------------------
// Dependency locking (P0-TSK-039).
//
// WHAT THIS ADDS OVER gradle/verification-metadata.xml, which already refuses any artefact
// whose checksum is not recorded. The two are not the same control, and the difference was
// measured rather than assumed: on its very first generation the verification file recorded
// **69 of 342 modules at more than one version** - jackson-databind at three, jackson-bom at
// five - because the buildscript, plugin, compile and test classpaths legitimately resolve
// different versions of the same module. Verification therefore cannot tell a deliberate
// resolution from a drift *between versions it already knows*: both pass.
//
// A lockfile records the version resolved per configuration, so that drift becomes a diff.
// It matters most for the thirteen dependencies whose versions come from the Spring Boot BOM
// and are written down nowhere in this repository - a BOM bump silently moves them today.
//
// The honest limit: this defends against accidental drift, not against an attacker. Someone
// who can edit the lockfile can edit the version catalog beside it. The control against a
// substituted artefact is the checksum; the control against a substituted VERSION is review,
// and a lockfile is what gives review something to look at.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    // Deterministic bytecode regardless of the building machine's default charset.
    options.encoding = "UTF-8"
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

// ---------------------------------------------------------------------------
// Test tiers (P0-TSK-036).
//
// A tier is defined by WHAT A TEST NEEDS IN ORDER TO RUN, and by nothing else. That is the only
// axis on which a tier can be decided mechanically, and it is the axis that matters for
// scheduling: a test needing a real PostgreSQL cannot share a task with one needing nothing,
// because the task then costs what its heaviest member costs and fails when that member's
// infrastructure is absent.
//
// THE DEFAULT TIER TAKES EVERYTHING NOT CLAIMED BY ANOTHER. `unitTest` EXCLUDES the tagged tiers
// rather than INCLUDING a `unit` tag, so a test can never land in no tier at all - which is the
// hole a set of includeTags-only tasks opens, and it is a silent one: the test compiles, reports
// nothing, and is believed to be running.
//
// docs/project/TESTING.md is the document and TestTier is the Java-side declaration; both are
// held to this list by TestTaxonomyTest rather than by agreement.
// ---------------------------------------------------------------------------
val defaultTierTask = "unitTest"

// Tier task -> the JUnit tag that selects it. Order is the escalation order: each tier needs
// strictly more than the one above it.
val taggedTiers = linkedMapOf(
    "architectureTest" to "architecture",
    "sliceTest" to "slice",
    "databaseTest" to "database",
    // A Kafka client gets its own tier rather than being folded into `database` - recorded in
    // TESTING.md by P0-TSK-036, two phases before the client existed (P2-TSK-001). The tier
    // needs a broker AND a database: the thing under test is the outbox reaching the broker.
    "kafkaTest" to "kafka",
)

// Tiers needing something outside the JVM. These are excluded from `test`, so `./gradlew build`
// stays green on a machine with nothing running - and they are a task you can SEE did not run,
// rather than a test that skips itself, because a skipped test reports success.
val externalInfrastructureTiers = setOf("databaseTest", "kafkaTest")

val externalTierTags = taggedTiers.filterKeys { it in externalInfrastructureTiers }.values.toTypedArray()

tasks.test {
    useJUnitPlatform { excludeTags(*externalTierTags) }
}

tasks.register<Test>(defaultTierTask) {
    group = "verification"
    description = "Runs tests that need nothing beyond the JVM."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { excludeTags(*taggedTiers.values.toTypedArray()) }
}

taggedTiers.forEach { (taskName, tierTag) ->
    tasks.register<Test>(taskName) {
        group = "verification"
        description = "Runs the '$tierTag' test tier."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags(tierTag) }

        if (taskName in externalInfrastructureTiers) {
            // Never cached: the point is to exercise real infrastructure, and a cached
            // "up to date" result would mean it had not.
            outputs.upToDateWhen { false }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Tests assert the toolchain pin (BuildToolchainTest). Injecting it here means the
    // expected value has one definition rather than a second copy hardcoded in the test.
    systemProperty("finapp.java.toolchain", javaToolchainVersion)

    // The tier declaration, crossing the Gradle/Java boundary as data. TestTaxonomyTest asserts
    // it equals TestTier, so the tasks Gradle registers and the tiers the guard checks cannot
    // drift apart — a build script and a Java enum have no other way to share one definition.
    systemProperty(
        "finapp.test.tiers",
        (mapOf(defaultTierTask to "") + taggedTiers).entries.joinToString(",") { "${it.key}=${it.value}" }
    )
    systemProperty(
        "finapp.test.tiers.external",
        externalInfrastructureTiers.sorted().joinToString(",")
    )

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
