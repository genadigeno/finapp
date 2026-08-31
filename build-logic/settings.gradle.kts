// build-logic is an included build, not a subproject. It is compiled before the
// main build and contributes precompiled convention plugins to it.
//
// Why an included build rather than a `subprojects { }` block in the root:
// cross-project configuration reaches into other projects' models, which
// defeats project isolation and configuration caching, and it makes each
// module's effective configuration invisible from its own build file. A
// convention plugin is applied explicitly and is greppable.
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // Share the main build's version catalog so plugin versions are declared
    // once, in gradle/libs.versions.toml.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
