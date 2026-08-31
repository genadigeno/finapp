plugins {
    // Enables precompiled script plugins: any *.gradle.kts in
    // src/main/kotlin becomes a plugin whose id is the file name.
    `kotlin-dsl`
}

kotlin {
    // Sourced from the catalog so the toolchain version has one definition.
    jvmToolchain(libs.versions.javaToolchain.get().toInt())
}
