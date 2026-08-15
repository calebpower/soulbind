// Conventions every Java module in soulbind inherits.
//
// The toolchain is Java 25 EVERYWHERE. What varies per module is the bytecode
// target, expressed as `--release`, applied by the soulbind.java-21 and
// soulbind.java-25 plugins. One toolchain, two targets: modules that execute
// inside a server operator's JVM target 21 because that runtime's floor is 21;
// everything standalone targets 25.
//
// A Tier 3 structural test asserts each module's resolved release level against
// the table in the plan, so this file and that test are two statements of one
// rule — deliberately, per the methodology's "assert against the source, not a
// re-export".

plugins {
    // java-library, not java: connector-sdk exposes protocol's types to every
    // connector that depends on it, which is an `api` relationship. Expressing
    // that honestly means a consumer sees exactly what the SDK intends to
    // expose, and no more.
    `java-library`
}

group = "dev.soulbind"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// No `repositories { }` block here on purpose. settings.gradle.kts sets
// RepositoriesMode.FAIL_ON_PROJECT_REPOS, so repositories are declared once, in
// dependencyResolutionManagement, and a module cannot quietly introduce its own
// source of artifacts. Declaring one here is a build failure, by design.

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Warnings are information, not noise to be silenced. If one becomes
    // load-bearing enough to fail the build, that is a deliberate narrowing
    // with a stated reason, not a blanket -Werror added in passing.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Every randomised tier prints its seed and accepts it back. Wiring the
    // environment variable through here means a failing run is replayable
    // without editing code.
    systemProperty("soulbind.seed", System.getenv("SOULBIND_SEED") ?: "")

    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

// The charset-hostility run.
//
// Code that hashes or signs must encode to UTF-8 explicitly, because a digest
// taken over platform-default bytes differs between hosts and locks out every
// credential minted on the other one. Tests assert that with pinned vectors --
// but on this JVM the default charset IS UTF-8 (JEP 400), so `getBytes()` and
// `getBytes(UTF_8)` produce identical bytes and the assertion cannot observe the
// difference. Found by mutation-checking: replacing the explicit UTF-8 with the
// platform default produced a GREEN run.
//
// So the tagged tests run a SECOND time under a default charset that is not
// UTF-8. Under that JVM the two spellings diverge and the pinned vectors fail.
// The tag is deliberately narrow: it selects only tests whose claim is about
// byte encoding, because running the whole suite under a hostile charset would
// be testing the JDK rather than this code.
val charsetHostilityTest = tasks.register<Test>("charsetHostilityTest") {
    description = "Re-runs @Tag(\"charset\") tests under a non-UTF-8 default charset."
    group = "verification"

    val testSourceSet = project.extensions.getByType<SourceSetContainer>().named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform { includeTags("charset") }

    // Passed as a raw JVM arg rather than via systemProperty: file.encoding is
    // read during JVM start-up, before anything Gradle could set afterwards.
    jvmArgs("-Dfile.encoding=ISO-8859-1")

    // The tagged tests read this and refuse to pass silently if the hostile
    // charset did not actually take effect -- otherwise a future JDK that
    // ignores file.encoding would turn this whole task into a second identical
    // run, and nothing would say so.
    systemProperty("soulbind.hostileCharset", "true")
}

tasks.named("check") {
    dependsOn(charsetHostilityTest)
}

tasks.withType<Jar>().configureEach {
    // Reproducible archives: a rebuild of the same source produces the same
    // bytes, so a diff in a distributed artifact means a diff in the input.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
