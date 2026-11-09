import java.math.BigInteger
import java.util.zip.ZipFile

plugins {
    id("finapp.java-conventions")
    // The Spring Boot plugin is applied ONLY here, to the executable module.
    //
    // It replaces the plain `jar` task with `bootJar`, which is correct for an
    // application and wrong for a library. Library modules (P0-TSK-002 onward)
    // will consume the Spring Boot BOM via platform() for version alignment
    // without taking on Boot's packaging behaviour.
    alias(libs.plugins.spring.boot)

    // Produces the CycloneDX SBOM that CI's dependency scan reads. A scanner cannot resolve
    // a Gradle dependency graph, and Gradle exports no machine-readable dependency set of
    // its own, so the SBOM is what makes a dependency scan possible at all.
    //
    // app is where it belongs: it is the only module that resolves the complete dependency
    // set.
    //
    // SCOPE: this SBOM covers the WHOLE resolved dependency set, test dependencies
    // included — 21 of its ~61 components are test-only. Plugin 3.4.1 exposes no
    // configuration filter, and although it marks test components with the property
    // `cdx:maven:package:test`, that is not the `scope` field a scanner reads.
    //
    // Scanning test dependencies is deliberate and defensible: a compromised test library
    // executes on CI runners with repository access, which is a real supply-chain path. But
    // it means a HIGH/CRITICAL advisory in a test-only library will fail the build even
    // though nothing vulnerable ships.
    //
    // KNOWN LIMITATION: this SBOM must NOT be published as shipping provenance while it
    // includes test scope — it would overstate what is deployed. Narrowing it is owned by
    // Phase 15 (supply chain and provenance); see CURRENT_STATE.md.
    alias(libs.plugins.cyclonedx)
}

dependencies {
    // platform() applies the Spring Boot BOM as a set of version constraints.
    // Every Spring dependency below is therefore declared without a version:
    // the BOM decides, so the whole Spring dependency graph stays internally
    // consistent. If these resolve, the BOM is wired correctly — which is the
    // point of the smoke test.
    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    // app is the composition root. It depends on platform, and receives
    // sharedkernel transitively because platform exposes it via `api`. This is
    // the documented chain: app -> platform -> sharedkernel.
    //
    // Business modules sit between app and platform; each is declared below with the task that
    // created it.
    implementation(project(":platform"))

    // The Phase 1 business modules (P1-TSK-003). app is the composition root and the only module
    // that sees every other one, which is what lets the architecture rules observe both sides of
    // a boundary - so a business module must be on app's classpath or ProductionModules cannot
    // derive it and every rule silently stops protecting it.
    implementation(project(":party"))
    implementation(project(":identity"))
    implementation(project(":kyc"))
    implementation(project(":consent"))
    implementation(project(":ledger"))
    implementation(project(":accounts"))
    // The Phase 4 transfers module (P4-TSK-001). Its absence here was caught exactly as the
    // comment above predicts: the planted-double probe SURVIVED until this line existed, because
    // ProductionModules derives the swept set from app's classpath and a module not on it is a
    // module no rule protects.
    implementation(project(":transfers"))
    // The Phase 5 payment modules (P5-TSK-001), on the classpath for the same reason - and the
    // planted-double probe is run once PER module deliberately, because one module's catch
    // cannot vouch for the other's coverage.
    implementation(project(":payments"))
    implementation(project(":paymentmethods"))
    // The Phase 6 commercial modules (P6-TSK-001), on the classpath for the same reason: a
    // module not on app's classpath is a module ProductionModules sweeps no rule over. The
    // planted-double probe runs once per module, as always.
    implementation(project(":checkout"))
    implementation(project(":merchant"))

    // The Phase 8 settlement modules (P8-TSK-001), on the classpath for the same reason: a
    // module not on app's classpath is a module ProductionModules sweeps no rule over. Their
    // hand-offs never touch each other directly - every port between them is composed here.
    implementation(project(":settlement"))
    implementation(project(":reconciliation"))

    // The Phase 9 FX and cross-border modules (P9-TSK-001), on the classpath for the same reason:
    // a module not on app's classpath is a module ProductionModules sweeps no rule over. They never
    // touch each other directly - CrossBorderFx is composed here.
    implementation(project(":fx"))
    implementation(project(":crossborder"))

    // The Phase 10 credit module (P10-TSK-001), on the classpath for the same reason: a module
    // not on app's classpath is a module ProductionModules sweeps no rule over - the floating-point
    // guard covers credit from its first class (INV-CRD-12).
    implementation(project(":credit"))

    // The Phase 11 lending module (P11-TSK-002), on the classpath for the same reason: a module not
    // on app's classpath is a module ProductionModules sweeps no rule over - the floating-point
    // guard covers lending from its first class (INV-MON-01, INV-LND-09). And `app` is the only place
    // lending and credit are both visible, so the vocabularies' agreement - each LoanProduct with
    // its CreditProduct - is checked here (LendingVocabularyTest), and every port between them will
    // be composed here.
    implementation(project(":lending"))

    // The Phase 12 modules (P12-TSK-002), on the classpath for the same reason: a module not on app's classpath is a
    // module ProductionModules sweeps no rule over - the floating-point guard covers creditmath and bnpl from their first
    // classes (INV-MON-01). `creditmath` directly, not only through lending: app composes lending's engine records
    // (Schedule, AllocationLine, ...) in its doors and reports, and names them. And `app` is the only place bnpl and
    // credit are both visible, so the vocabularies' agreement - each BnplProduct with its CreditProduct - is checked here
    // (BnplVocabularyTest), and every port between them will be composed here.
    implementation(project(":creditmath"))
    implementation(project(":bnpl"))

    // The Phase 13 module (P13-TSK-001), on the classpath for the same reasons: the floating-point and audit guards
    // reach `risk` from its first classes (INV-MON-01), every seam adapter (transfers', crossborder's, credit's,
    // payments') and every port `risk` declares (the acting party, the session's assurance, the monitored ledger, the
    // filing provider, kyc's clearances and rescreening, the owning domains' action doors) is composed here - the one
    // module that sees both sides - and NoCustomerSurfaceReadsRiskTest holds every customer and merchant surface away
    // from it (INV-RSK-08).
    implementation(project(":risk"))

    // The Phase 14 module (P14-TSK-001), on the classpath for the same reasons: the floating-point and audit guards
    // reach `accounting` from its first classes (INV-MON-01), every port it declares (the journal's reads, the ledger's
    // chart, the posting cutoff, the prior-period adjustment door, the product proofs, the accrual completeness,
    // reconciliation's disclosure, fx's reference rates, the regulatory provider) is implemented here - the one module
    // that sees both sides - NoCustomerSurfaceReadsAccountingTest holds every customer and merchant surface away from it,
    // and ReportingNeverWritesTest holds its report code away from every writer (INV-ACC-14).
    implementation(project(":accounting"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)

    // Tomcat above what the Spring Boot BOM manages, to clear three CRITICAL advisories in
    // 11.0.24 (CVE-2026-65182, -65905, -68525). The reasoning and the revisit condition are in
    // the version catalog beside the pin.
    //
    // CONSTRAINTS, not a forced version and not a direct dependency:
    //   - a direct dependency would make `app` declare a servlet container it does not use
    //     directly, and the starter is what should own that edge;
    //   - `force` would win against a HIGHER version too, so a future Spring Boot managing
    //     11.0.26 would be silently held back at 11.0.25 - the failure mode of a pin nobody
    //     revisits, which is what ADR-0026 exists to avoid;
    //   - a constraint raises the floor and loses to anything newer, which is what is wanted.
    //
    // WHAT PROTECTS THIS, stated accurately - the first version of this comment claimed the
    // lockfile "rejects" removing the block, and probing showed that is false. With the
    // constraints deleted, resolution still yields 11.0.25, because the lock applies its own
    // `{strictly 11.0.25}`. The lock KEEPS the version; it does not object to the loss.
    //
    // A regression therefore needs two steps: delete this block AND regenerate the locks. The
    // second produces a reviewable diff showing 11.0.25 -> 11.0.24 across five configurations,
    // and the `dependency-scan` job then fails on the regenerated SBOM. That is the control -
    // the scan, not the lock.
    //
    // No test asserts the version, deliberately. It would duplicate the scan and would need
    // editing on every legitimate Tomcat bump, which is the stale-list defect this repository
    // has met four times.
    constraints {
        implementation(libs.tomcat.embed.core)
        implementation(libs.tomcat.embed.el)
        implementation(libs.tomcat.embed.websocket)
    }

    // Health, readiness and build info (P0-TSK-027).
    implementation(libs.spring.boot.starter.actuator)

    // A DataSource, and nothing more. Readiness must be answered through the pool the
    // application uses, so the application has to have one.
    //
    // NOT spring-boot-starter-data-jpa, and since ADR-0033 that is a decision rather than a
    // deferral: authoritative writes use explicit SQL through JdbcClient, which lives in
    // spring-jdbc. NoObjectRelationalMapperTest fails the build if an ORM reaches this classpath,
    // so the comment is no longer the only thing holding the line.
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.postgresql.driver)

    // The tracing IMPLEMENTATION (P0-TSK-028). app binds it because app is the composition root:
    // platform records spans through a facade, and choosing what records them - here, the
    // OpenTelemetry SDK that SYSTEM_ARCHITECTURE.md names - is the application's decision, not a
    // library's. Exactly the slf4j split, one layer up.
    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    implementation(libs.micrometer.tracing.bridge.otel)

    // Prometheus metrics (P0-TSK-029). The registry is the implementation; `platform` records
    // through Micrometer's MeterRegistry facade, the same split as slf4j and the tracer.
    implementation(libs.micrometer.registry.prometheus)

    // The first HTTP surface (P0-TSK-024, M0.4). app is where it belongs: MODULE_ARCHITECTURE.md
    // §M10 puts routing, content negotiation and error rendering here, and the error CONTRACT -
    // the codes and the problem-detail shape - in platform, which stays framework-free because a
    // published contract must not be a function of the web stack underneath it.
    testImplementation(libs.spring.boot.starter.test)

    // The shared database test harness: a container, the role script and the migrations
    // (P0-TSK-035). Without it, app's database tests would need a developer's compose stack -
    // which is exactly what this task removes.
    testImplementation(testFixtures(project(":platform")))
    testImplementation(testFixtures(project(":identity")))
    // The broker client's MockConsumer (P15-TSK-008): InboxConsumersTest drives a stopping instance's consumer loops over
    // the real KafkaEventReceiver without a broker. Test scope only - the artefact is already on app's runtime classpath
    // through platform, so this adds no library, only the test compile's view of it; production code in app still
    // touches no Kafka type (NoDirectBrokerPublicationRulesTest). Version from the Spring Boot BOM.
    testImplementation(libs.kafka.clients)
    // The refund allocator's reference merchant fee arithmetic, proven equal here to merchant's FeeCalculation
    // (BnplFeeArithmeticAgreesWithMerchantTest, P12-TSK-009) - app is the one module that sees both.
    testImplementation(testFixtures(project(":bnpl")))
    // The policy v1 fixtures (RiskPolicyFixtures, P13-TSK-002, owner decision C8): the policy desk's round-trip and the
    // door tests propose exactly the content the engine and the battery evaluate - one reference, never a copy.
    testImplementation(testFixtures(project(":risk")))
    // The GL mapping fixtures (P14-TSK-003, owner decision C2): the reference version proven total and compatible here
    // against the ledger's own enum and seed (LedgerPurposeChartTest), and the desk's in-memory store - one reference.
    testImplementation(testFixtures(project(":accounting")))

    // Architecture rules live here because `app` is the only module that sees every other
    // one — enforcing a boundary requires being able to observe both sides of it. As
    // business modules are added, `app` depends on them too, so the rules keep their full
    // view without needing to be moved.
    testImplementation(libs.archunit.junit5)

    // Bytecode inspection for the one single-instance pattern ArchUnit cannot see: a
    // `synchronized` block is a MONITORENTER instruction rather than an access flag, so it is
    // invisible to a model built on accesses (P0-TSK-041, ADR-0024). Test scope: nothing ships.
    testImplementation(libs.asm)

    // OpenAPI generation (P0-TSK-026), test scope only.
    //
    // springdoc READS the request mappings; it never changes them, so a document generated
    // with it on the test classpath describes the application that actually ships. Keeping it
    // off the runtime classpath means the contract is a reviewed artefact under docs/api/
    // rather than a live /v3/api-docs endpoint - one fewer unauthenticated surface, and one
    // fewer library in the shipped dependency set.
    testImplementation(libs.springdoc.openapi.webmvc)

    // Reads back the spans that were actually recorded, rather than asserting that a tracing call
    // was made. Same argument as logback's list appender in the correlation tests: a telemetry
    // test that mocks the telemetry proves nothing about what an operator would see.
    testImplementation(libs.opentelemetry.sdk.testing)
}

// ---------------------------------------------------------------------------
// Build identity for /actuator/info, so an operator can tell which build is running.
//
// The timestamp is deliberately omitted. `isPreserveFileTimestamps = false` and
// `isReproducibleFileOrder = true` in the java conventions make archives reproducible - two
// builds of the same source produce identical bytes - and a build time embedded in a resource
// would defeat exactly that, for information the version and (later) the commit already carry
// better. Reproducibility is a supply-chain property; a "when was this built" field is not
// worth losing it for.
// ---------------------------------------------------------------------------
springBoot {
    buildInfo {
        // `excludes`, not `properties { time = null }`. The latter compiles - `time` is a
        // Property<String> and accepts null - and changes nothing, because the generator reads
        // `getTimeIfNotExcluded()` and falls back to the build instant when the field is not in
        // this set. Verified by reading the generated build-info.properties, not by reading the
        // build file.
        excludes.add("time")
    }
}

// ---------------------------------------------------------------------------
// No SBOM inside the boot jar (P15-TSK-003).
//
// With the CycloneDX plugin applied, Spring Boot copies the build's SBOM into the jar
// (META-INF/sbom/application.cdx.json, through processResources) and names it in the manifest.
// That file carries a random serial number and the instant it was generated, and the plugin has
// no setting for the instant, so two builds of one commit produced two different jars: measured,
// 2026-11-08, by rebuilding with --rerun-tasks (the archive settings above held; this file did
// not). The SBOM is also the WHOLE resolved set, test scope included (the plugin note above), so
// shipping it inside the artefact would publish it as the shipping provenance it must not be.
// The SBOM the dependency scan reads is unaffected (build/reports/cyclonedx); an SBOM per image
// is P15-TSK-006's. infra/scripts/image-build.sh rebuilds the jar from source and refuses a
// second jar that differs from the first.
// ---------------------------------------------------------------------------
tasks.processResources {
    // By destination path, in eachFile: Spring Boot adds the SBOM through a child spec
    // (`into("META-INF/sbom")`), and an `exclude` pattern matches a file's SOURCE path, which is
    // just its name - `exclude("META-INF/sbom/**")` compiled, ran and excluded nothing (verified
    // by listing the jar, 2026-11-08).
    eachFile {
        if (relativePath.pathString.startsWith("META-INF/sbom/")) {
            exclude()
        }
    }
}

// ---------------------------------------------------------------------------
// The schema versions this build expects (P15-TSK-005; DATA_MIGRATIONS.md section 4.1).
//
// Read from the migrations actually on the runtime classpath - each module's jar, the same scripts the migrator image
// stages - and never from a list, so a module's new migration moves what a replica requires of its database with no edit
// here. The application's SchemaVersionGuard reads the file; ExpectedSchemaVersionsTest holds it equal to a scan of the
// classpath and a walk of the repository, which also catches this parsing and that one disagreeing.
//
// Compared as versions, part by part as whole numbers: as text, V9 would sort after V10. No instant and a sorted order, so
// that two builds of one commit embed the same bytes (P15-TSK-003).
// ---------------------------------------------------------------------------
val expectedSchemaVersions by tasks.registering {
    group = "build"
    description = "Writes each schema's latest migration version, read from the runtime classpath, for the application to embed."
    val written = layout.buildDirectory.file("generated/schema-versions/expected-schema-versions.properties")
    inputs.files(configurations.runtimeClasspath).withPropertyName("runtimeClasspath").withNormalizer(ClasspathNormalizer::class.java)
    outputs.file(written).withPropertyName("expectedSchemaVersions")
    doLast {
        val script = Regex("db/migration/([^/]+)/V([0-9]+(?:[._][0-9]+)*)__[^/]*\\.sql")
        fun parts(version: String) = version.split('.', '_').map { it.toBigInteger() }
        fun above(one: String, other: String): Boolean {
            val (a, b) = parts(one) to parts(other)
            for (i in 0 until maxOf(a.size, b.size)) {
                val compared = a.getOrElse(i) { BigInteger.ZERO }.compareTo(b.getOrElse(i) { BigInteger.ZERO })
                if (compared != 0) return compared > 0
            }
            return false
        }
        val latest = sortedMapOf<String, String>()
        inputs.files.files.filter { it.isFile && it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().mapNotNull { script.matchEntire(it.name) }.forEach { match ->
                    val (schema, version) = match.destructured
                    if (latest[schema]?.let { above(version, it) } != false) latest[schema] = version
                }
            }
        }
        check(latest.isNotEmpty()) { "no migration on the runtime classpath: the guard would expect nothing" }
        written.get().asFile.writeText(latest.entries.joinToString("") { (schema, version) -> "$schema=$version\n" })
    }
}

tasks.processResources {
    from(expectedSchemaVersions) { into("com/finapp/app/database") }
}

// ---------------------------------------------------------------------------
// The configuration register (P15-TSK-031; ADR-0125 decision 3), embedded as it stands in the repository.
//
// finapp.config.fingerprint is a hash over the effective values of the register's FINANCIAL rows, so the running application
// has to read those rows, and a second list kept beside them is the defect the register exists to prevent. The document is
// the one list; ConfigurationFingerprintTest holds these bytes equal to it. A register the build cannot read refuses to
// start at the fingerprint's bean. The image's context is the boot jar alone, so nothing else reaches the docs tree.
// ---------------------------------------------------------------------------
tasks.processResources {
    from(rootProject.file("docs/architecture/CONFIGURATION_REGISTER.md")) {
        into("com/finapp/app/telemetry")
        rename { "configuration-register.md" }
    }
}

tasks.bootJar {
    manifest.attributes.remove("Sbom-Format")
    manifest.attributes.remove("Sbom-Location")
}

// ---------------------------------------------------------------------------
// The `database` tier's module-specific configuration.
//
// The tier tasks themselves are registered once in finapp.java-conventions (P0-TSK-036). Only
// what is specific to app belongs here.
//
// No database CREDENTIALS are wired, deliberately. The application reads its settings from the
// same FINAPP_DB_* environment variables compose.yaml uses, and a Gradle test JVM inherits the
// environment - so the test connects exactly the way the deployed application would, through the
// configuration the application actually has, rather than through a second set of values a test
// fixture chose.
// ---------------------------------------------------------------------------
// The application's RUNTIME classpath, supplied to EVERY test task rather than to one of them.
//
// Two guards now assert on it, and they are in different tiers:
//   - HealthReadinessDatabaseTest  (database) - ADR-0011: Flyway is absent, so "migrations never
//     run at startup" is structural rather than a setting;
//   - NoObjectRelationalMapperTest (architecture) - ADR-0033: no ORM is present, so nothing can
//     emit a statement nobody wrote against a table the application role holds no UPDATE on.
//
// It is wired with `withType` rather than named per tier deliberately. This property was set on
// `databaseTest` alone, and a second guard in a second tier is exactly the shape of the
// `:platform:databaseTest` defect the P0-TSK-027 review found: a list of one that goes stale the
// moment there are two. `test` also runs both tiers' tests, so naming tiers individually would
// have needed three entries kept in step by hand.
//
// A missing property does not weaken either guard - both assert it is non-blank first, so a check
// that cannot see the classpath fails loudly instead of reporting that it found nothing wrong.
val applicationRuntimeArtefacts =
    configurations.runtimeClasspath.map { cfg -> cfg.files.joinToString(",") { it.name } }

tasks.withType<Test>().configureEach {
    doFirst { systemProperty("finapp.runtime.classpath", applicationRuntimeArtefacts.get()) }
}

// The kafka tier, exactly as platform's task supplies it (P2-TSK-001).
tasks.named<Test>("kafkaTest") {
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())
    systemProperty("finapp.kafka.image", "apache/kafka:" + libs.versions.kafkaImage.get())
}

tasks.named<Test>("databaseTest") {
    // The container image, from the version catalog, exactly as platform's task supplies it.
    // Without this the shared harness runs, finds no image, and returns - which is how these
    // tests silently kept connecting to a developer's compose stack (P0-TSK-035).
    systemProperty("finapp.db.image", "postgres:" + libs.versions.postgresImage.get())

    // One suite runs LAST: ReconciledPositionResidueDatabaseTest judges what every other suite
    // left in the shared container (INV-REC-06) - a fixture's committed line on a reconciled
    // position used to surface only when the offender happened to run before one of the two
    // suites that sweep the proof, and Gradle's order is no contract (the file scan, with last
    // run's failures first). The orderer reads @Order; every class without one keeps its place (a
    // stable sort on the default order). The suite asserts this line is present, so removing it
    // fails the tier rather than quietly leaving @Order as decoration.
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")

    // The date-shifted suites' zone pinned for a diagnosis (P12-TST-001, A6-04): `-Pfinapp.db.zone=<zone id>` reaches
    // DateShiftedZone (`UTC` turns the shift off); unset, each @DateShifted suite takes lending's P11-TSK-032 rule.
    // Read here, at configuration, so `ownContainerDatabaseTest` - which copies this task's properties - sees it too.
    providers.gradleProperty("finapp.db.zone").orNull?.let { systemProperty("finapp.db.zone", it) }

    // `outputs.upToDateWhen { false }` is applied by the convention plugin to every tier needing
    // external infrastructure, so it is not repeated here.
}

// The database suites that need a database of their own (X-TSK-016): each carries
// @Tag("own-container"), `databaseTest` leaves it out of the shared JVM, and the convention
// plugin's `ownContainerDatabaseTest` runs it in a JVM - so a container - of its own. Each is here
// for a reason its javadoc states: absolute proofs over the whole database, or a bank statement
// chain that must start at sequence 1 - so the shared JVM writes no bank statement at all.
// TestTaxonomyTest holds this list equal to the tagged set.
extra["ownContainerSuites"] =
    listOf(
        // P15-TSK-022: three contexts of its own, for the storms' reason: they would otherwise add up on the tier's shared JVM.
        "com.finapp.app.abuse.SourceLimitClusterDatabaseTest",
        // P14-TST-002: it opens the year 2001 as the database's first periods and closes all twelve for good, activates two
        // mapping versions and commits some 22,000 journal lines on operational accounts no expectation names.
        "com.finapp.app.accounting.AccountingBatteryTest",
        // P15-TSK-033: PI-013's reproduction locks, unlocks and proposes January 1999 on a shadowed database clock, as
        // PeriodCloseDatabaseTest does without one.
        "com.finapp.app.accounting.CloseRunClockStepDatabaseTest",
        "com.finapp.app.accounting.GlReadModelDatabaseTest",
        "com.finapp.app.accounting.OperationalReportsDatabaseTest",
        "com.finapp.app.accounting.PeriodCloseDatabaseTest",
        // P14-REM-009: it locks January 1999 and opens the months after February on a shadowed database clock, as the suites of 1999 do.
        "com.finapp.app.accounting.PeriodDatesAreTheDatabasesDatabaseTest",
        "com.finapp.app.accounting.PeriodReopenDatabaseTest",
        "com.finapp.app.accounting.PriorPeriodAdjustmentDatabaseTest",
        "com.finapp.app.accounting.QuietMonthDatabaseTest",
        "com.finapp.app.accounting.RegulatorySubmissionDatabaseTest",
        "com.finapp.app.accounting.SafeguardingReturnDatabaseTest",
        "com.finapp.app.accounting.StatementsDatabaseTest",
        "com.finapp.app.accounting.TrialBalanceRunDatabaseTest",
        "com.finapp.app.accounting.ValuationRateSetDatabaseTest",
        "com.finapp.app.accounting.ValuationReferenceGapDatabaseTest",
        "com.finapp.app.bnpl.BnplAcceptanceApiTest",
        "com.finapp.app.bnpl.BnplApplicationDatabaseTest",
        "com.finapp.app.bnpl.BnplCollectionLedgerDatabaseTest",
        "com.finapp.app.bnpl.BnplCreditExposureDatabaseTest",
        "com.finapp.app.bnpl.BnplDisputeApiTest",
        "com.finapp.app.bnpl.BnplMerchantPayoutDatabaseTest",
        "com.finapp.app.bnpl.BnplMerchantReadDatabaseTest",
        "com.finapp.app.bnpl.BnplOneKeyRaceDatabaseTest",
        "com.finapp.app.bnpl.BnplOriginationDatabaseTest",
        "com.finapp.app.bnpl.BnplOutboxAtomicityDatabaseTest",
        "com.finapp.app.bnpl.BnplPayoffApiTest",
        "com.finapp.app.bnpl.BnplProofApiTest",
        "com.finapp.app.bnpl.BnplProofDatabaseTest",
        "com.finapp.app.bnpl.BnplPurchaseDatabaseTest",
        "com.finapp.app.bnpl.BnplReportsDatabaseTest",
        "com.finapp.app.bnpl.BnplRefundApiTest",
        "com.finapp.app.bnpl.BnplRepaymentApiTest",
        "com.finapp.app.bnpl.BnplServicingApiTest",
        "com.finapp.app.bnpl.BnplStormDatabaseTest",
        "com.finapp.app.bnpl.BnplWriteOffLedgerDatabaseTest",
        "com.finapp.app.checkout.CheckoutFinancingDatabaseTest",
        "com.finapp.app.credit.BnplProductDecisionDatabaseTest",
        "com.finapp.app.credit.BureauSelectionDatabaseTest",
        "com.finapp.app.credit.CreditDecisionDatabaseTest",
        "com.finapp.app.credit.CreditDecisionStormDatabaseTest",
        // P14-DOC-001's database pass: its world brings the credit policy seeds and the RETAIL_SCORECARD seed into force,
        // activates a CREDIT_LINE version and a MONITORING risk version - the shared JVM's endpoint suites assert the seeds
        // are still proposals (ScorecardAdministrationEndpointDatabaseTest failed behind it; CreditPolicyEndpointDatabaseTest
        // passed only by running first), and a MONITORING version judges every profile recompute in the database.
        "com.finapp.app.credit.CreditRiskSeamDatabaseTest",
        "com.finapp.app.credit.DecisionExplanationDatabaseTest",
        "com.finapp.app.credit.DecisionReplayDatabaseTest",
        "com.finapp.app.credit.DecisionReproducibilityBatteryTest",
        "com.finapp.app.credit.DecisionOrchestrationDatabaseTest",
        "com.finapp.app.credit.DecisionRequestDatabaseTest",
        "com.finapp.app.credit.Phase10ReportsDatabaseTest",
        "com.finapp.app.credit.Phase10SpansDatabaseTest",
        "com.finapp.app.credit.UnderwritingCaseDatabaseTest",
        // P15-TSK-008: two outbox relays over one database, the counted drain case; the relay reads the whole outbox, so on
        // a shared container it would publish - and so mark published - every other suite's pending rows.
        "com.finapp.app.eventing.ScheduleDrainDatabaseTest",
        // P13-TSK-011: it activates a EUR CROSS_BORDER risk version, after which every cross-border payment in the
        // database is judged by it - a shared container's EUR 600 payments would be stepped up at the door, its
        // EUR-JPY-JP ones refused (the suite's high-risk corridor), its non-EUR-leg ones refused by the fail-safe.
        "com.finapp.app.fx.CrossBorderRiskSeamDatabaseTest",
        "com.finapp.app.fx.FxCrossBorderStormDatabaseTest",
        // P14-DOC-001's database pass: the FX position report lists every open cover leg in the database, oldest first,
        // bounded at 100 - the suite's new leg is listed only while fewer than 100 older legs stand open, and a shared
        // container holds hundreds (the value battery executes one cover in twenty by design).
        "com.finapp.app.fx.Phase9ReportsDatabaseTest",
        "com.finapp.app.lending.BnplCapitalDatabaseTest",
        "com.finapp.app.lending.LendingCapitalDatabaseTest",
        "com.finapp.app.lending.LendingCreditExposureDatabaseTest",
        "com.finapp.app.lending.LendingReportsDatabaseTest",
        "com.finapp.app.lending.SafeguardingCommittedDatabaseTest",
        "com.finapp.app.lending.LoanWriteOffLedgerDatabaseTest",
        "com.finapp.app.lending.LendingStormDatabaseTest",
        "com.finapp.app.lending.LoanAccountDatabaseTest",
        "com.finapp.app.lending.LoanApplicationDatabaseTest",
        "com.finapp.app.lending.LoanOfferDatabaseTest",
        "com.finapp.app.lending.LoanPayoutDatabaseTest",
        "com.finapp.app.merchant.PayoutReturnDatabaseTest",
        // P13-TSK-013: it activates EUR WITHDRAWAL and MONITORING risk versions, after which every withdrawal and every
        // profile recompute in the database is judged by them - a shared container's credential-changed customers'
        // withdrawals would be held and alerted, its recovered parties alerted by any sweep.
        "com.finapp.app.payments.AccountTakeoverDatabaseTest",
        // P13-TSK-010: it activates EUR CARD_PAYMENT and WALLET_PAYMENT risk versions, after which every merchant-bound
        // card and wallet payment in the database is judged by them - a shared container's USD checkouts would be
        // refused by the open-ended fail-safe band, its EUR 600 ones challenged, its EUR 2,500 card ones held.
        // P13-DOC-001's database pass: its resolvers sweep the whole database and it asserts no row failed the card sweep
        // - another suite's stranded authorization on a fixture's posting account fails the chain in a shared container.
        "com.finapp.app.payments.MultiRailConservationStormDatabaseTest",
        "com.finapp.app.payments.PaymentRiskSeamDatabaseTest",
        "com.finapp.app.payments.SystemWithdrawalDatabaseTest",
        // P13-TSK-009: it activates a EUR WITHDRAWAL risk version, after which every withdrawal in the database is
        // judged by it - a shared container's USD withdrawals would be refused, its EUR 2,500 ones held.
        "com.finapp.app.payments.WithdrawalRiskSeamDatabaseTest",
        "com.finapp.app.reconciliation.BatchRepudiationDatabaseTest",
        "com.finapp.app.reconciliation.JpyAndBhdPostableDatabaseTest",
        "com.finapp.app.reconciliation.ResolutionBatteryDatabaseTest",
        // P14-DOC-001's database pass: its collision case commits a planted CARD_CAPTURE expectation (12.00 EUR on
        // SETTLEMENT_CLEARING) with no line behind it - an open remainder the residue judge reads as an unexplained identity;
        // SettlementAcceptanceDatabaseTest's register rebuild used to delete it in the shared JVM, masking it.
        "com.finapp.app.reconciliation.SettlementExpectationDatabaseTest",
        "com.finapp.app.reconciliation.SettlementReconciliationStormDatabaseTest",
        "com.finapp.app.reconciliation.UnmatchedConfirmationSuspenseDatabaseTest",
        // P13-TSK-014: it activates EUR CARD_PAYMENT, WALLET_PAYMENT and MONITORING risk versions carrying the abuse
        // rules - a shared container's merchants with refunds would be alerted, its sessions' sixth checkout challenged.
        "com.finapp.app.risk.MerchantAbuseDatabaseTest",
        // P13-DOC-001's database pass: it activates a MONITORING version and drains the rule leg over the whole journal
        // from the one global watermark - every other suite's movements judged, and a fixture's non-UUIDv7 account
        // owner refused by the account-to-party join.
        "com.finapp.app.risk.MonitoringRulesDatabaseTest",
        // P13-DOC-001's database pass: each activation of a screening-list version enumerates every ACTIVE and PENDING
        // party in the database and rescreens each through kyc - a shared container's parties would all be asked, and a
        // party another suite inserted under a non-UUIDv7 identifier fails the enumeration (ADR-0013).
        "com.finapp.app.risk.RescreeningDatabaseTest",
        // P13-DOC-001's database pass: it commits what no real path writes - a phantom monitored transaction, an assessment
        // of no command, a window counted past its evidence - append-only rows every whole-database census then holds.
        "com.finapp.app.risk.RiskProofsDatabaseTest",
        // P13-TSK-008: each activates a EUR TRANSFER risk version, after which every transfer in the database is
        // judged by it - a shared container's USD transfers would be refused by the open-ended fail-safe band.
        "com.finapp.app.risk.RiskPolicyEndpointDatabaseTest",
        // P13-TST-001: the risk storm - two instances, every operation's version activated, absolute censuses over the
        // whole database (every assessment against its command, every window against its evidence) at every rest.
        "com.finapp.app.risk.RiskStormTest",
        "com.finapp.app.settlement.BankStatementCashDatabaseTest",
        "com.finapp.app.settlement.PayoutSettlementCashDatabaseTest",
        "com.finapp.app.settlement.SchemeCycleCashDatabaseTest",
        // P14-DOC-001's database pass: its rebuild deletes every unheld expectation in the database as the root, commits,
        // and holds every verdict explained after the backfill - another suite's port-opened expectation over a fixture
        // entry (FxProviderSourceDatabaseTest's FX_BUY_LEG) is deleted and never re-derived, failing it and the residue.
        "com.finapp.app.settlement.SettlementAcceptanceDatabaseTest",
        "com.finapp.app.transfers.TransferRiskSeamDatabaseTest",
    )

// The document inputs below are declared on EVERY test task, not only on `test`.
//
// Each document-backed guard runs in whichever tier its own test belongs to:
// DashboardQueriesResolveTest is `database` and resolves the Grafana dashboard against a live
// registry, while the rest are `unit`. A task that does not declare the document it reads goes
// UP-TO-DATE over an edit to it and reports green having opened nothing - a failure this
// repository has now met three times (see the comments below). P0-TSK-036 split one test task
// into five, which is precisely the change that would have reintroduced it four more times, so
// the declaration is made against the type rather than against a named task.
// TestTaxonomyTest sweeps the compiled TEST classes of every module, because a module's test
// output is deliberately not on another module's classpath — so it reads them from disk.
//
// Gradle cannot infer that, and without these two lines the sweep reads whatever happened to be
// compiled last. PROVEN, not assumed: the first mutation of this task — removing @Tag("database")
// from a platform test — SURVIVED, because :platform:testClasses had not re-run and :app's task
// read a stale class file. That is the same "check that reports success for work it did not do"
// the document-input lines below exist for, one module across.
//
// Derived from the subprojects rather than listed, so a fourth module is swept without anyone
// remembering — the stale-list defect this repository has now met four times.
val siblingModules = rootProject.subprojects.filter { it.path != project.path }

tasks.withType<Test>().configureEach {
    siblingModules.forEach { sibling -> dependsOn("${sibling.path}:testClasses") }
    inputs.files(
        siblingModules.map {
            rootProject.layout.projectDirectory.dir("${it.name}/build/classes/java/test")
        }
    )
        .withPropertyName("siblingTestClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // ArchitectureRulesAreDocumentedTest reads this document and asserts it names exactly the
    // rules that run on every build. Gradle cannot infer that a markdown file is an input, so
    // without this declaration a doc-only edit leaves :app:test UP-TO-DATE and the check
    // reports green over a document it never opened — the same "check that reports success for
    // work it did not do" this suite exists to prevent. Verified by breaking the document and
    // watching the task re-run and fail.
    inputs.file(rootProject.layout.projectDirectory.file("docs/architecture/MODULE_ARCHITECTURE.md"))
        .withPropertyName("moduleArchitectureDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // AuditableActionRegistryTest reads this one and asserts it names exactly the actions the
    // code declares. Declared for the identical reason, and it is worth noting that the reason
    // did not generalise on its own: P0-TSK-023 added a second document-backed guard and
    // reintroduced the same defect, proven by breaking the catalogue and watching :app:test
    // report UP-TO-DATE and the build go green. A third such guard needs a third line here.
    inputs.file(rootProject.layout.projectDirectory.file("docs/architecture/AUDITABLE_ACTIONS.md"))
        .withPropertyName("auditableActionsCatalogue")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The third. ErrorCodeRegistryTest reads it and asserts it names exactly the codes the
    // taxonomy declares. Declared for the reason the comment above gives - and this is the
    // "third line" that comment predicted would be needed.
    inputs.file(rootProject.layout.projectDirectory.file("docs/architecture/ERROR_CONTRACT.md"))
        .withPropertyName("errorContractCatalogue")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The fourth. OpenApiContractTest compares the generated OpenAPI document against this
    // committed one and fails on any difference. Undeclared, editing the baseline would leave the
    // task UP-TO-DATE and the build would go green over a contract nothing had compared - which is
    // the one failure mode a published contract cannot afford, since the whole point is that a
    // change to it is noticed. Third occurrence of this defect class; see the two comments above.
    // `inputs.files`, not `inputs.file`, and that is the whole point of the difference. A single
    // file input is validated to exist, so a missing baseline aborts the task before any test runs
    // and reports an internal property name - which made OpenApiContractTest's own message, the one
    // that says a first baseline has been generated and where to find it, unreachable from the
    // build that needs it. A file COLLECTION tolerates absence and still snapshots content, so both
    // properties hold: editing the baseline re-runs the task, and deleting it lets the test speak.
    //
    // `optional(true)` was tried first and does nothing here: it permits a null VALUE, and the
    // value is present - it is the file behind it that is missing.
    inputs.files(rootProject.layout.projectDirectory.file("docs/api/openapi.json"))
        .withPropertyName("openApiContractBaseline")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The fifth. ApiConventionsAreAccurateTest holds this document and the implementation to each
    // other - the prefix, the header name, the correlation charset and bound, the size limit and
    // its property, the problem-detail members. Same reason as every line above it, and the same
    // failure if omitted: an edit that makes the document wrong would leave the task UP-TO-DATE
    // and the build green.
    inputs.files(rootProject.layout.projectDirectory.file("docs/architecture/API_CONVENTIONS.md"))
        .withPropertyName("apiConventionsDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The sixth. DashboardQueriesResolveTest reads the committed Grafana dashboard and asserts
    // every series it queries is one the application actually publishes - the check that would
    // have caught a renamed metric silently turning every panel into "No data", which is how a
    // dashboard lies during an incident. Undeclared, editing the dashboard would leave the task
    // UP-TO-DATE and the build green over a query nothing had resolved.
    inputs.files(rootProject.layout.projectDirectory.file("infra/grafana/dashboards/finapp-platform.json"))
        .withPropertyName("grafanaDashboard")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The seventh, and the first that is a SET rather than a named file.
    // CommittedConfigurationHoldsNoSecretTest walks the repository for configuration and fails
    // the build on a credential literal that is not the one marked local default. Naming the
    // files it checks would defeat it: a new application-prod.yaml has to be covered without
    // anyone remembering, which is the "list of one that went stale" defect the P0-TSK-027
    // review found in this repository's own CI. So the input is the discovery, not a list.
    //
    // The tree is filtered to the extensions the test scans and to the directories it walks;
    // including everything would make :app:test re-run on any file in the repository at all.
    inputs.files(
        rootProject.layout.projectDirectory.asFileTree.matching {
            include("**/*.yaml", "**/*.yml", "**/*.properties", "**/*.sql", "**/*.kts",
                    "**/*.env", "**/*.conf", "**/*.ini", "**/*.sh")
            exclude("**/build/**", "**/.gradle/**", "**/.git/**", "**/.idea/**", "**/out/**")
        }
    )
        .withPropertyName("committedConfiguration")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // KubernetesManifestsTest holds every rule id in the policy file against the check's plants
    // (P15-TSK-009). The manifests and the script are in the set above; the policy file is not.
    inputs.files(rootProject.layout.projectDirectory.file("infra/scripts/manifest-policy/finapp.rego"))
        .withPropertyName("manifestPolicy")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // ... and it holds the schemas' Kubernetes minor to each cluster's kubernetes_version, which no set above reads.
    inputs.files(
        rootProject.layout.projectDirectory.file("infra/terraform/environments/staging/main.tf"),
        rootProject.layout.projectDirectory.file("infra/terraform/environments/production/main.tf")
    )
        .withPropertyName("clusterKubernetesVersions")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The fourteenth and fifteenth. DomainGlossaryTest holds the glossary to DOMAIN_MODEL.md's
    // canonical term list and to CLAUDE.md's §Domain Distinctions. CLAUDE.md and
    // MODULE_ARCHITECTURE.md are already declared above, so only these two are new.
    inputs.files(rootProject.layout.projectDirectory.file("docs/domain/GLOSSARY.md"))
        .withPropertyName("domainGlossary")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.layout.projectDirectory.file("docs/domain/DOMAIN_MODEL.md"))
        .withPropertyName("domainModel")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The eleventh, twelfth and thirteenth. MutationDemonstrationTest holds the register of
    // mutation demonstrations, the invariant catalogue and the backlog to each other, so that
    // PHASE_GATES.md criterion 3 - every in-scope invariant has a test that FAILS when it is
    // broken - is checked continuously rather than discovered at the gate.
    //
    // BACKLOG.md is a large and frequently edited file, so declaring it re-runs :app:test often.
    // That is the correct trade: the alternative is a guard that goes stale precisely when a new
    // P0-TST-* item is added, which is the one moment it exists for.
    inputs.files(rootProject.layout.projectDirectory.file("docs/project/MUTATION_TESTING.md"))
        .withPropertyName("mutationDemonstrationRegister")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.layout.projectDirectory.file("docs/domain/FINANCIAL_INVARIANTS.md"))
        .withPropertyName("invariantCatalogue")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.layout.projectDirectory.file("docs/project/BACKLOG.md"))
        .withPropertyName("backlog")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The eighth and ninth. ProviderFailureCoverageTest holds CLAUDE.md's Failure Engineering
    // list and ADR-0008's contract-test requirement to the harness's capabilities. CLAUDE.md is
    // the project's own instruction file, so it is the last document anyone would think to declare
    // as a build input - and a bullet added to it is exactly the change this guard exists to catch.
    inputs.files(rootProject.layout.projectDirectory.file("CLAUDE.md"))
        .withPropertyName("failureEngineeringList")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        rootProject.layout.projectDirectory.file("docs/adr/ADR-0008-provider-adapters.md")
    )
        .withPropertyName("providerAdapterAdr")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The eleventh and twelfth, added by the Phase 1 -> 2 transition, which found them by
    // probing: flipping a phase to COMPLETE in CURRENT_STATE.md left :app:test UP-TO-DATE and the
    // probe reported green having run nothing - the P0-TSK-023 defect class, in the two guards
    // that derive the enforced phase from this document (MutationDemonstrationTest,
    // PlannedMetersExistTest). The phase plans are the meters guard's other undeclared half: a
    // meter renamed in a plan alone would never re-run the guard that reads it. CURRENT_STATE.md
    // is large and edited constantly, so this re-runs :app:test often - the same trade BACKLOG.md
    // above already accepted, for the same reason: the alternative goes stale at exactly the
    // moment the guard exists for, the phase status flip.
    inputs.files(rootProject.layout.projectDirectory.file("docs/project/CURRENT_STATE.md"))
        .withPropertyName("currentState")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        rootProject.fileTree("docs/project") { include("PHASE_*_PLAN.md") }
    )
        .withPropertyName("phasePlans")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The tenth. TestTaxonomyTest holds this document and the tier declaration to each other:
    // the document must name exactly the tiers that exist, with the task and tag each one
    // actually uses. Same reason as every line above it.
    inputs.files(rootProject.layout.projectDirectory.file("docs/project/TESTING.md"))
        .withPropertyName("testingConventions")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The sixteenth, and like the seventh a SET rather than a named file.
    // AdrRegistersAreReconciledTest holds every ADR file's Status: line and the README index's
    // status column to each other, and the two sets to a bijection — the second-copy decay
    // P2-DOC-001 and P3-DOC-001 each found by hand becomes a build failure (P4-TSK-002). A file
    // tree rather than a list because a NEW ADR file must re-run the guard without anyone
    // remembering: an ADR added without its index row is exactly the half of the defect a
    // named-file list could never see. ADR-0008 is also declared individually above for
    // ProviderFailureCoverageTest; the overlap is harmless.
    inputs.files(
        rootProject.fileTree("docs/adr") { include("ADR-*.md", "README.md") }
    )
        .withPropertyName("adrRegisters")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The seventeenth and eighteenth, the first a SET. ThreatModelIsCompleteTest (P15-TSK-001) reads
    // docs/architecture/THREAT_MODEL.md and resolves every evidence path the register cites, and the evidence is
    // open-ended: a test, a migration, a CI workflow, a pin list, an ADR or another document. Compiled code is
    // already an input through the classpath and siblingTestClasses, and *.yml, *.sh and *.sql through
    // committedConfiguration. The documents and the dependency verification metadata are not, so they are
    // declared here. Without them, deleting a cited document would leave the task UP-TO-DATE and the register
    // green over evidence nobody opened. A tree, not the one file, so a document cited later is covered without
    // anyone remembering. PRODUCTION_READINESS.md sits in the same tree for P15-TSK-034's guard.
    inputs.files(rootProject.fileTree("docs") { include("**/*.md") })
        .withPropertyName("documentsTree")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("docs/events"))
        .withPropertyName("eventContractCatalogues")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // MigrationsAreExpandOnlyTest (P15-TSK-005) reads the committed floor of the last release.
    inputs.files(rootProject.fileTree("docs/migrations"))
        .withPropertyName("schemaFloor")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.layout.projectDirectory.file("gradle/verification-metadata.xml"))
        .withPropertyName("dependencyVerificationMetadata")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The nineteenth, P15-TSK-001's fix rounds: the trees later tasks' evidence lives in - infra/ (pins, scripts,
    // Terraform, policies), deploy/ (manifests, -009 onward), .github/ (the workflow and, with -017, CODEOWNERS), every
    // Dockerfile at any depth (-003 may write app/Dockerfile) and each module's build script (whose Flyway settings
    // MigrationSettingsAreForwardOnlyTest reads) - whatever their extension, since a Dockerfile or a CODEOWNERS file
    // has none. A deleted or edited file there re-runs the register's guard and the evidence tests that read it.
    // Worktrees and git's own objects under the root are not evidence and are left out.
    inputs.files(
        rootProject.fileTree(rootProject.layout.projectDirectory) {
            include("infra/**", "deploy/**", ".github/**", "**/Dockerfile*", "*/build.gradle.kts", "settings.gradle.kts",
                "migrator/src/main/resources/**", "migrator/src/main/java/**")
            exclude("**/build/**", "**/.gradle/**", ".git/**", ".claude/**", "**/node_modules/**")
        }
    )
        .withPropertyName("infrastructureEvidence")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
