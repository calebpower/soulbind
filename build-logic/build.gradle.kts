plugins {
    `kotlin-dsl`
}

// build-logic itself compiles with the JDK Gradle runs on; it produces no
// distributed artifact and therefore carries no --release contract.

dependencies {
    // The shading/relocation engine for the two PLUGIN jars (Velocity, Plan).
    //
    // A Minecraft plugin is one file dropped into plugins/ -- there is no lib/
    // to unbundle into -- so connector-sdk, protocol, config, tomlj and Jackson
    // have to travel inside it. Relocating them out of their original packages
    // is the point: the jar is loaded into a JVM that has its own copies, and a
    // flat shade leaves it to the HOST's classloading to decide which version
    // our code binds to. That decides itself differently on different proxy
    // builds and surfaces as a LinkageError on somebody else's machine.
    //
    // Build-time only. It ships nowhere, so it is not in the licence inventory,
    // which covers runtime graphs. Apache-2.0 either way.
    //
    // Pinned. The version is a decision, not whatever the portal serves today.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.6.1")
}
