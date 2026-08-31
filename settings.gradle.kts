plugins {
    // Provisions a matching JDK automatically when the machine does not already
    // have the toolchain version the build asks for. Without this, "builds from
    // a clean clone on a machine with no prior state" is only true on machines
    // that happen to have JDK 21 installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "finapp"

// Convention plugins are compiled by this included build before the main build
// is configured. See build-logic/settings.gradle.kts for why this is an
// included build rather than a subprojects { } block.
includeBuild("build-logic")

dependencyResolutionManagement {
    // Repositories are declared here and nowhere else. A module that declares
    // its own repository fails the build. This keeps the set of hosts the build
    // will fetch code from small, auditable, and reviewable in one place —
    // dependency sources are a supply-chain surface.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// Modules.
//
// P0-TSK-001 creates one placeholder application module to prove the build
// wiring end to end. The `platform` and `sharedkernel` modules, and the
// enforced dependency direction app -> platform -> sharedkernel, are
// P0-TSK-002 and are deliberately NOT created here.
// ---------------------------------------------------------------------------
include("app")
