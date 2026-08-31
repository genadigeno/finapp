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
// Dependency direction is strictly downward and acyclic:
//
//     app  ->  platform  ->  sharedkernel
//
// Gradle enforces the direction structurally: a module can only see what its
// build file declares, and a cycle fails configuration outright. The finer
// rules that Gradle cannot express — no cross-module internals, no cross-module
// entity references, no framework leakage into sharedkernel — are ArchUnit
// rules in P0-TSK-007.
//
// Business modules (identity, ledger, payments, ...) sit between app and
// platform and belong to their own phases. None exist yet.
// ---------------------------------------------------------------------------
include("sharedkernel")
include("platform")
include("app")
