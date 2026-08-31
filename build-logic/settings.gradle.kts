// build-logic is an included build, not a subproject. It is compiled before the
// main build and contributes precompiled convention plugins to it.
//
// Why an included build rather than a `subprojects { }` block in the root:
// cross-project configuration reaches into other projects' models, which
// defeats project isolation and configuration caching, and it makes each
// module's effective configuration invisible from its own build file. A
// convention plugin is applied explicitly and is greppable.
plugins {
    // An included build has its own settings and does NOT inherit plugins from
    // the main build. Without this, build-logic can only use a JDK that already
    // happens to be installed: on a machine whose ambient JDK is not 21, the
    // build fails with "Cannot find a Java installation ... Toolchain download
    // repositories have not been configured".
    //
    // This was caught by building with JAVA_HOME pointed at a different JDK.
    // Keep it in step with the resolver version in the root settings file.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

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
