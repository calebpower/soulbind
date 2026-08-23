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

    // The convention plugins are mostly wiring, and wiring is asserted by the
    // guards module against the build's OUTPUT. SoulbindVersion is the
    // exception: it is a decision with edge cases -- a tag that is not a
    // version, a tree with no tags, a source archive with no .git -- and every
    // one of those is unreachable from a test that can only look at whatever
    // this checkout happens to be. So build-logic has a test source set for
    // exactly that one pure function.
    //
    // Pinned to the same JUnit the rest of the tree uses (gradle/libs.versions
    // .toml, `junit`). It cannot read that catalog -- build-logic is a separate
    // included build with its own settings -- so this is the one place the
    // number is repeated, and BuildLogicJunitPinGuardTest asserts the two agree
    // rather than trusting that anybody noticed.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
