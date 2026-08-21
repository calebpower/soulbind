plugins {
    id("soulbind.java-21")
    id("soulbind.licence-inventory")
}

dependencies {
    implementation(project(":connector-sdk"))

    /*
     * Plan's DataExtension API.
     *
     * compileOnly, because Plan is the host: it is on the classpath at runtime
     * by definition, and bundling a second copy is how a plugin ends up loading
     * annotations that are not the ones the host scans for.
     *
     * The artifact is the API only -- 96 KB, not the plugin -- and it arrives
     * through JitPack, which is why settings.gradle.kts scopes that repository
     * to this one publisher.
     *
     * Pinned exactly, not to a range. Plan's extension API is annotation-driven
     * and a provider signature that stops matching produces a page with a
     * missing panel and no error anywhere -- the same silent shape as the
     * unbuilt frontend bundle in the forum connector.
     */
    compileOnly("com.github.plan-player-analytics:Plan:5.8.3605")

    /*
     * The same artifact on the test classpath, for real. compileOnly alone
     * leaves every provider in SoulbindDataExtension unexecutable by a test:
     * the annotations compile, the bodies never run, and a units error or an
     * empty-to-placeholder slip ships looking exactly like working code.
     * Runtime here is a test-only concession; nothing is bundled.
     */
    testImplementation("com.github.plan-player-analytics:Plan:5.8.3605")

    /*
     * Plan's Table.Factory calls commons-lang3 and its POM does not declare it
     * -- the plugin supplies it at runtime, so the API artifact alone is not
     * enough to execute a provider that builds a table. Test scope only, and
     * present for the same reason the line above is: without it the two table
     * assertions cannot run, and "cannot run" quietly becomes "not covered".
     * Nothing here reaches a distributed artifact.
     */
    testRuntimeOnly("org.apache.commons:commons-lang3:3.20.0")

    // The shared TOML loader. One parser in this repository, one idea of what an
    // unknown key means.
    implementation(project(":config"))

    /*
     * The proxy platform this connector bootstraps on.
     *
     * Velocity rather than Paper, because §16 prefers it: Plan supports the
     * platform and velocity-api is MIT where paper-api is GPLv3, so this module
     * stays distributable under Apache-2.0 without a departure.
     *
     * compileOnly, like connector-velocity: the proxy supplies it at runtime and
     * bundling it would give the classloader two copies to disagree about.
     */
    compileOnly(libs.velocity.api)
    testCompileOnly(libs.velocity.api)
    testRuntimeOnly(libs.velocity.api)
}

/*
 * A fat jar, because a proxy plugin gets one file and no classpath.
 *
 * connector-velocity learned this the hard way -- a plain jar carried its own
 * classes and nothing else, so the plugin could not load at all. Same shape
 * here, and worse to diagnose: a Plan extension that fails to register produces
 * a page with a missing panel and no error anywhere.
 *
 * §16: nothing LGPL enters a shaded artifact. Plan's API is LGPL-3.0 and is
 * compileOnly, so it is absent from this jar -- the host supplies it, which is
 * also why a second copy would load the wrong annotations. velocity-api is
 * compileOnly for the same reason. What lands here is soulbind's own modules
 * plus Jackson, tomlj and slf4j: Apache-2.0 and MIT.
 */
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Declared, not inferred. Reading the runtime classpath inside from{} makes
    // this task consume other projects' jars without Gradle knowing, so it can
    // run before they exist -- and that only surfaces on a clean tree.
    dependsOn(configurations.runtimeClasspath)

    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}
