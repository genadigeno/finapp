plugins {
    id("finapp.java-conventions")
    // The Spring Boot plugin is applied ONLY here, to the executable module.
    //
    // It replaces the plain `jar` task with `bootJar`, which is correct for an
    // application and wrong for a library. Library modules (P0-TSK-002 onward)
    // will consume the Spring Boot BOM via platform() for version alignment
    // without taking on Boot's packaging behaviour.
    alias(libs.plugins.spring.boot)
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
}
