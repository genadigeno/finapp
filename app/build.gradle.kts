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
    // Business modules will sit between app and platform as they are created;
    // none exist yet.
    implementation(project(":platform"))

    implementation(libs.spring.boot.starter)
    testImplementation(libs.spring.boot.starter.test)

    // Architecture rules live here because `app` is the only module that sees every other
    // one — enforcing a boundary requires being able to observe both sides of it. As
    // business modules are added, `app` depends on them too, so the rules keep their full
    // view without needing to be moved.
    testImplementation(libs.archunit.junit5)
}

tasks.test {
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
}
