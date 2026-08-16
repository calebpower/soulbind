plugins { id("soulbind.java-21") }

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
}
