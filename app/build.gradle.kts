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

    // `outputs.upToDateWhen { false }` is applied by the convention plugin to every tier needing
    // external infrastructure, so it is not repeated here.
}

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
}
