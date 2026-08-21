plugins {
    id("soulbind.java-21")
    id("soulbind.licence-inventory")
}

dependencies {
    implementation(project(":connector-sdk"))

    // compileOnly: the proxy supplies this at runtime. Bundling it would ship a
    // second copy for the classloader to disagree about, and the api module is
    // MIT while the proxy is GPLv3 -- compiling against the api only is what
    // keeps this module distributable under Apache-2.0 (§16).
    compileOnly(libs.velocity.api)

    // Available to tests, because a component test constructs the API's own
    // types. Still never shipped.
    testCompileOnly(libs.velocity.api)
    testRuntimeOnly(libs.velocity.api)
}

// A fat jar, because a proxy plugin gets one file and no classpath.
//
// The first stack run failed with NoClassDefFoundError for the SDK: the plain
// jar carried this module's classes and nothing else, so the plugin could not
// load at all. Worse, the harness reported the join gate WORKING -- it accepted
// any kick as proof of a refusal, and an unrelated connection failure satisfied
// it. Both were fixed together; neither was visible from the other.
//
// §16: nothing LGPL may enter a shaded artifact. What lands here is soulbind's
// own modules plus Jackson, tomlj and slf4j -- Apache-2.0 and MIT. The
// velocity-api is compileOnly and so is absent, which is correct: the proxy
// supplies it, and bundling it would give the classloader two copies to
// disagree about.
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Declared, not inferred. Reading the runtime classpath inside from{} makes
    // this task consume other projects' jars without Gradle knowing, so it can
    // run before they exist -- and it only surfaced on a clean tree, after
    // passing repeatedly on one where they happened to be built already.
    dependsOn(configurations.runtimeClasspath)

    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Module descriptors from dependencies describe a module this jar is not.
    // Left in, they make the JVM refuse to read the jar on a module path.
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}
