plugins {
    // Enables precompiled script plugins: any *.gradle.kts in
    // src/main/kotlin becomes a plugin whose id is the file name.
    `kotlin-dsl`
}

// Dependency locking, for the same reason the java-conventions plugin enables it (ADR-0025) -
// and this build needs it as much as any: a precompiled script plugin runs in every build with
// full build privileges. Declared here rather than inherited, because build-logic applies
// `kotlin-dsl` and not our own convention plugin, so nothing else would reach it.
//
// Its artefacts were already checksum-verified: dependency verification is a Gradle-wide feature
// that covers included builds, confirmed by finding gradle-kotlin-dsl-plugins in the metadata.
// What was missing was the version record.
dependencyLocking {
    lockAllConfigurations()
}

kotlin {
    // Sourced from the catalog so the toolchain version has one definition.
    jvmToolchain(libs.versions.javaToolchain.get().toInt())
}
