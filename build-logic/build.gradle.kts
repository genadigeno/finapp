plugins {
    // Enables precompiled script plugins: any *.gradle.kts in
    // src/main/kotlin becomes a plugin whose id is the file name.
    `kotlin-dsl`
}

dependencies {
    // Makes the Spring Boot plugin resolvable from a convention plugin. The
    // version comes from the shared catalog; markerCoordinates maps a plugin id
    // to its marker artifact.
    implementation(libs.plugins.spring.boot.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
}

kotlin {
    jvmToolchain(21)
}
