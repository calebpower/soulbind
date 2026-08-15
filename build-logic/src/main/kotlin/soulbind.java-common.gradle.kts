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

tasks.withType<Jar>().configureEach {
    // Reproducible archives: a rebuild of the same source produces the same
    // bytes, so a diff in a distributed artifact means a diff in the input.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
