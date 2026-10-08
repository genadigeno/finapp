// Baseline configuration shared by every Java module in the platform.
//
// Applied explicitly by each module. Adding a module to the build does not
// silently opt it into anything; it must ask for these conventions by name.

import java.util.concurrent.atomic.AtomicLong

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
// Lombok - the project-standard boilerplate reducer (.claude/rules/java-lombok.md).
//
// COMPILE TIME ONLY, by construction: compileOnly puts the annotations on the compile classpath
// and annotationProcessor runs the generator, and neither configuration is part of a runtime
// classpath - so Lombok is in no jar, no boot archive and no SBOM. What ships is the bytecode it
// wrote. Wired here, once, so every module and every source set gets the same version from the
// catalog and no module can declare it at a different scope.
//
// The processor path needs the entry as well as the compile path: since JDK 23 javac no longer
// discovers processors on the compile classpath by default, and naming the processor path
// explicitly is correct on 21 too.
// ---------------------------------------------------------------------------
val lombok =
    extensions.getByType<VersionCatalogsExtension>()
        .named("libs")
        .findLibrary("lombok")
        .orElseThrow { GradleException("Version catalog is missing library 'lombok'") }

dependencies {
    "compileOnly"(lombok)
    "annotationProcessor"(lombok)
    "testCompileOnly"(lombok)
    "testAnnotationProcessor"(lombok)
}

// Test fixtures are test code too, in the two modules that publish them.
plugins.withId("java-test-fixtures") {
    dependencies {
        "testFixturesCompileOnly"(lombok)
        "testFixturesAnnotationProcessor"(lombok)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // lombok.config changes what the generator writes, so it is a compilation INPUT - declared,
    // or an edit to it would leave every up-to-date class compiled under the old rules.
    inputs.file(rootProject.layout.projectDirectory.file("lombok.config"))
        .withPropertyName("lombokConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
    // An explicit heap for `test` too (P9-TSK-002), for the external tiers' reason: app's hermetic JVM
    // caches up to 32 Spring contexts AND runs the bytecode sweeps (TestTaxonomyTest,
    // MutationDemonstrationTest) that import every module's test classes repeatedly. On the
    // 512 MiB default it ran within a few MiB of the ceiling, and the fx module's first classes
    // tipped TestTaxonomyTest into OutOfMemoryError - in app alone, reproducibly, never in fx.
    // HermeticTierHeapTest holds it.
    maxHeapSize = "2g"
}

tasks.register<Test>(defaultTierTask) {
    group = "verification"
    description = "Runs tests that need nothing beyond the JVM."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { excludeTags(*taggedTiers.values.toTypedArray()) }
    maxHeapSize = "2g" // as `test`, above: the same classes, the same sweeps
}

taggedTiers.forEach { (taskName, tierTag) ->
    tasks.register<Test>(taskName) {
        group = "verification"
        description = "Runs the '$tierTag' test tier."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags(tierTag) }
        // Every tagged tier gets the explicit heap, not only the external ones (P10-TSK-021): app's
        // architecture tier runs ArchUnit's whole-codebase imports and the sweeps that read every
        // module's test classes (AuditCompletenessTest, SystemActorCallSitesAreEnumeratedTest,
        // ModuleBoundaryRulesTest) in one JVM, and on Gradle's 512 MiB default the second bureau's
        // classes tipped it into OutOfMemoryError - reproducibly, in app's run of the whole tier only,
        // every suite passing alone. P9-TSK-002's finding for the hermetic tier, one tier across.
        // ArchitectureTierHeapTest holds it.
        maxHeapSize = "2g"

        if (taskName in externalInfrastructureTiers) {
            // Never cached: the point is to exercise real infrastructure, and a cached
            // "up to date" result would mean it had not.
            outputs.upToDateWhen { false }
            // An explicit heap, not Gradle's 512 MiB test-worker default (the Phase 7 -> 8
            // transition): one JVM runs the whole tier, Spring caches up to 32 application
            // contexts (each with its pool, schedulers and Kafka producer), and Phase 7's
            // suites added contexts of their own. Late in the fleet-wide run ten concurrent
            // registrations - Argon2id at 19 MiB each - met OutOfMemoryError and answered 500,
            // three failures no targeted tier could see. DatabaseTierHeapDatabaseTest holds it.
            maxHeapSize = "2g"
        }
    }
}

// ---------------------------------------------------------------------------
// Database suites that need a database of their own (X-TSK-016).
//
// The database tier runs in one JVM against one container, so every suite inherits what the
// suites before it committed, in an order that is no contract (TESTING.md section 5). A few
// suites cannot hold under that: their proofs are absolute over the whole database, or they need
// a currency's bank statement chain to start at sequence 1, and two of them need the SAME first
// USD statement. They said so in their javadoc ("runs in its own container") and nothing made it
// true, so the full tier was red in an order-dependent way: 22 failures on one run, 31 on the next.
//
// `own-container` is the declared non-tier selector that makes it structural. `databaseTest` leaves
// those suites out of its shared JVM; `ownContainerDatabaseTest` runs each in a JVM of its own
// (forkEvery = 1), and DatabaseUnderTest starts one container per JVM, so each gets a fresh
// database. It runs after `databaseTest`, failed or not, so `./gradlew databaseTest` - CI's
// invocation - still runs the whole tier. It is a sibling of the tier, not a tier: the tag still
// says `database`, and TestTier is unchanged.
//
// The tag alone cannot scope the sibling. Under the JUnit Platform Gradle hands EVERY class file
// to the worker and the tag is read inside it, so forkEvery = 1 over the whole source set would
// fork a JVM - and DatabaseUnderTest a container - per class file. A module therefore also names
// its own-container suites in `extra["ownContainerSuites"]` (fully-qualified class names), which
// narrows the sibling's scan to those files; TestTaxonomyTest holds that list equal to the set of
// classes carrying the tag, so neither can drift from the other.
// ---------------------------------------------------------------------------
val ownContainerTag = "own-container"
val ownContainerTaskName = "ownContainerDatabaseTest"
val ownContainerSuitesProperty = "ownContainerSuites"

// Read when a task is configured, which is after the module's build script has set it.
fun Project.ownContainerSuites(): List<String> =
    if (extra.has(ownContainerSuitesProperty)) {
        @Suppress("UNCHECKED_CAST")
        (extra[ownContainerSuitesProperty] as List<String>)
    } else {
        emptyList()
    }

val declaredOwnContainerSuites = provider { ownContainerSuites().sorted().joinToString(",") }

// The `--tests` patterns the command line gave THIS project's `databaseTest`. A targeted run
// (`./gradlew :app:databaseTest --tests '*SomeSuite'`) must select the same suites in both halves:
// the shared half alone would answer "no tests found" for an own-container suite, and the sibling
// unfiltered would run every own-container suite behind a one-suite request. Read from the start
// parameter, which is public API; an unqualified task name applies to every project, as Gradle's
// own resolution does.
val databaseTestPatterns: List<String> =
    gradle.startParameter.taskRequests.flatMap { request ->
        val patterns = mutableListOf<String>()
        var selected = false
        var index = 0
        val args = request.args
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--tests" && index + 1 < args.size -> {
                    if (selected) patterns += args[index + 1]
                    index++
                }
                arg.startsWith("--tests=") -> if (selected) patterns += arg.removePrefix("--tests=")
                !arg.startsWith("-") -> {
                    val qualified = if (arg.startsWith(":")) arg else ":$arg"
                    selected = arg == "databaseTest" ||
                        qualified == "${project.path.removeSuffix(":")}:databaseTest"
                }
            }
            index++
        }
        patterns
    }

// Counts what both halves ran, so a pattern that matches nothing in either still fails the build:
// each half must tolerate an empty selection on its own, and that must not let a typo pass.
val databaseTestsRun = AtomicLong()
fun Test.countsDatabaseTests() =
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) databaseTestsRun.addAndGet(result.testCount)
        }
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
    })

val sharedDatabaseTest = tasks.named<Test>("databaseTest") {
    useJUnitPlatform { excludeTags(ownContainerTag) }
    countsDatabaseTests()
    // Relaxed only where the sibling can answer for the pattern instead: in a module with no
    // own-container suite the sibling has no source, never runs its check, and Gradle's own
    // "no tests found" must stay this task's.
    if (databaseTestPatterns.isNotEmpty() && project.ownContainerSuites().isNotEmpty()) {
        filter.isFailOnNoMatchingTests = false
    }
    finalizedBy(ownContainerTaskName)
}

tasks.register<Test>(ownContainerTaskName) {
    group = "verification"
    description = "Runs the database tier's own-container suites, each in a JVM and container of its own."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("${taggedTiers.getValue("databaseTest")} & $ownContainerTag") }
    val suites = project.ownContainerSuites()
    // Only the named suites' own class files - not their nested records, each of which would
    // otherwise be handed to a fresh JVM of its own. An empty list selects nothing at all.
    if (suites.isEmpty()) {
        include("__no_own_container_suite__")
    } else {
        suites.forEach { include(it.replace('.', '/') + ".class") }
    }
    forkEvery = 1
    maxHeapSize = "2g" // as `databaseTest`, for the same reason
    outputs.upToDateWhen { false }
    // A module with no own-container suite discovers nothing, and that is not a failure.
    failOnNoDiscoveredTests = false
    filter.isFailOnNoMatchingTests = false
    databaseTestPatterns.forEach { filter.includeTestsMatching(it) }
    countsDatabaseTests()
    // Whatever a module's build gives `databaseTest` - the container image, its escape hatch -
    // the own-container suites need too. Read when this task is configured, which is after every
    // build script has configured `databaseTest`.
    systemProperties(sharedDatabaseTest.get().systemProperties)
    doLast {
        if (databaseTestPatterns.isNotEmpty() && databaseTestsRun.get() == 0L) {
            throw GradleException(
                "No database tests found for given includes: $databaseTestPatterns" +
                    " (neither databaseTest nor $ownContainerTaskName selected one)"
            )
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
    // The module's declared own-container suites, for TestTaxonomyTest to hold against the tag.
    // At execution, because the module's build script sets the list after this plugin applies.
    doFirst { systemProperty("finapp.test.ownContainerSuites", declaredOwnContainerSuites.get()) }

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
