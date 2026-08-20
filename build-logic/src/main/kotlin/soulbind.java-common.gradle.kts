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

import java.util.concurrent.atomic.AtomicLong

plugins {
    // java-library, not java: connector-sdk exposes protocol's types to every
    // connector that depends on it, which is an `api` relationship. Expressing
    // that honestly means a consumer sees exactly what the SDK intends to
    // expose, and no more.
    `java-library`
    // Applied here rather than module by module, so a new module cannot be
    // created without it. A tool that has to be remembered is a tool that is
    // missing from exactly the module nobody thought about.
    id("soulbind.mutation")
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
    // Captured outside the closure: configureEach applies to EVERY Test task,
    // including fuzzTest, and JUnit resolves a tag that is both included and
    // excluded as excluded. Applying the exclusion unconditionally therefore
    // made fuzzTest run zero tests -- a green run that fuzzed nothing.
    val testTaskName = name

    useJUnitPlatform {
        // The fuzz tier is excluded from the DEFAULT test task and run by
        // `fuzzTest` below instead. Two reasons, and the first is the one that
        // matters: a fuzz run must never be skipped as up-to-date. A fresh seed
        // explores something new every time, so "fuzz clean" reported from a
        // cached result is a claim about whenever it last happened to run --
        // which is exactly the failure Phase 0 found in the guards.
        //
        // The second is ordinary: running it in both tasks would fuzz twice per
        // build for no added coverage.
        //
        // The narrowing covers exactly the `fuzz` tag. Nothing else is excluded,
        // and fuzzTest is wired into `check`, so a normal build still runs it.
        if (testTaskName != "fuzzTest") {
            excludeTags("fuzz")
        }
        // Latency is a MEASUREMENT, not a check. Running it on every compile
        // makes every compile slower for no signal, and a measurement inside a
        // pass/fail suite eventually gets a tight assertion bolted on and
        // becomes flaky. It runs under `latencyTest`, and its number goes in
        // STATUS.md.
        if (testTaskName != "latencyTest") {
            excludeTags("latency")
        }
    }

    // Every randomised tier prints its seed and accepts it back. Wiring the
    // environment variable through here means a failing run is replayable
    // without editing code.
    systemProperty("soulbind.seed", System.getenv("SOULBIND_SEED") ?: "")

    // The storage environment is a task INPUT, so the build cache cannot serve
    // a run that had one backend to a run that has two.
    //
    // Gradle keys a cached Test result on its declared inputs, and an
    // environment variable is not one of them. The default `test` task is the
    // only Test task here without `outputs.upToDateWhen { false }`, and it is
    // the task carrying the two-backend claim -- so with `org.gradle.caching`
    // on and a GRADLE_USER_HOME inside the persistent guest work tree, a second
    // session at an unchanged commit replayed the previous result and reported
    // `:core:test FROM-CACHE` with **no MariaDB running at all**, leaving XML
    // that claimed 471 tests and seventy MARIADB cases. SOULBIND_REQUIRE_MARIADB
    // could not catch it, because the task never ran.
    //
    // Declared rather than disabling the cache: a SQLite-only result and a
    // two-backend result now have different keys, so each is reusable and
    // neither can stand in for the other.
    inputs.property("mariadbUrl", System.getenv("SOULBIND_TEST_MARIADB_URL") ?: "")
    inputs.property("mariadbRequired", System.getenv("SOULBIND_REQUIRE_MARIADB") ?: "")

    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

/**
 * Makes a tag-selected task refuse to pass having run nothing.
 *
 * A task wired into `check` that discovers zero tests reports success, and the
 * build is greener for it. That is not hypothetical here: the comment on the
 * default test task records a narrowing that "made fuzzTest run zero tests -- a
 * green run that fuzzed nothing", and `outputs.upToDateWhen { false }` was the
 * fix. That stops a CACHED empty result; it does nothing about a genuinely empty
 * one. Empirically, `fuzzTest` executed zero tests in eight of nine modules and
 * `charsetHostilityTest` in seven, every one of them reporting success.
 *
 * A module with no tagged tests legitimately has nothing to run, so this cannot
 * simply demand a non-zero count everywhere. Instead it asks the source: if this
 * module's test sources CONTAIN the tag, the task must have executed at least
 * one test. A tag that is renamed, mis-spelled, moved to a class the task does
 * not scan, or excluded by a future narrowing then fails the build instead of
 * quietly reducing coverage to nothing.
 *
 * The narrowing is exactly that: modules with no occurrence of the tag are not
 * required to run anything, because for them zero is the right answer.
 */
fun Test.failIfTaggedTestsExistButNoneRan(tag: String) {
    val testSourceSet = project.extensions.getByType<SourceSetContainer>().named("test").get()
    val sources = testSourceSet.allJava.srcDirs
    val taskName = name
    val projectPath = project.path

    // Counted from THIS run, not from the results directory.
    //
    // The first version of this check counted XML files under
    // build/test-results/<task>. Gradle does not clear that directory when a run
    // discovers nothing, so the previous run's files were still sitting there and
    // the count was never zero. Both mutations survived: excluding the fuzz tag
    // from fuzzTest -- the exact historical bug this exists to catch -- and
    // pointing the task at a tag that matches nothing. A check that reads last
    // run's evidence is a check that cannot observe this one.
    val executed = AtomicLong()
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            executed.incrementAndGet()
        }
    })

    doLast {
        // Concatenated, not interpolated. Written as "@Tag(\"${'$'}tag\")" this
        // compiles to the LITERAL text `@Tag("$tag")` -- ${'$'} is Kotlin's escape
        // for a dollar sign, so the tag was never substituted, `declaresTag` was
        // always false, and the whole check silently did nothing. Both mutations
        // survived until this line was fixed.
        val marker = "@Tag(\"" + tag + "\")"

        // NARROWING, stated: this reads the `test` source set's .java files for
        // the tag written literally. A tag supplied by a constant, a
        // meta-annotation, or a test living in
        // another source set reads as "this module has no tagged tests" -- and
        // the check then permits zero execution, which is the very condition it
        // exists to catch.
        //
        // It is a backstop for the ordinary mistake (a renamed tag, one more
        // exclusion, a moved class), not a proof of absence, and the gap is
        // written down rather than implied. Every tag in this repository is
        // written literally.
        //
        // The grouped form needs no special case: Java's
        // @Tags({@Tag("charset"), ...}) CONTAINS the literal @Tag("charset"),
        // so this finds it already. A clause added for it was redundant, and
        // its unanchored fallback -- @Tags( anywhere plus the quoted tag
        // anywhere in the same file -- failed a build for a class holding an
        // unrelated string constant. It errs red rather than green, but it
        // asserts something untrue, so it is gone.
        // Comments stripped before matching.
        //
        // This is the fourth time in this repository that a check has matched
        // its own explanatory prose. Here it was worse than noise: a javadoc in
        // StorageBackends warning "drop @Tag(\"fuzz\") and the battery stays
        // green" itself contains the literal, so `core` declared the tag partly
        // because of a comment about the tag. That makes the warning false, and
        // it means a module that legitimately loses its last fuzz test would
        // fail the build forever with a message asserting something untrue --
        // the exact defect class cited when the @Tags clause was deleted.
        //
        // The copyleft guard in this same change already strips comments. This
        // one did not, which is precisely how it went unnoticed.
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("//.*")
        val declaresTag = sources.any { dir ->
            dir.walkTopDown().any {
                it.isFile && it.extension == "java"
                        && it.readText()
                            .replace(blockComment, "")
                            .replace(lineComment, "")
                            .contains(marker)
            }
        }
        if (declaresTag && executed.get() == 0L) {
            throw GradleException(
                projectPath + ":" + taskName + " declares " + marker + " in its test sources " +
                    "but executed no tests. A tag-selected task that discovers nothing reports " +
                    "success, so this would have reduced the tier to zero coverage while the " +
                    "build stayed green."
            )
        }
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

    // Never up-to-date, for a different reason than fuzzTest's.
    //
    // This tier IS deterministic, so a cached result is not stale in the way a
    // cached fuzz run is. The problem is what rides along: the zero-tests check
    // below lives in a `doLast`, and `doLast` does not run on a task Gradle
    // skips. Observed directly -- two consecutive invocations both reported
    // `:protocol:charsetHostilityTest UP-TO-DATE`, so on an ordinary incremental
    // `./gradlew check` the hostile-charset tier AND the guard that says whether
    // it ran anything both silently did nothing.
    //
    // A guard that is skipped whenever the thing it guards is skipped guards
    // nothing. The tier is ten tests across two modules -- protocol 8, core 2
    // -- and running them every time measures at about three seconds on a warm
    // build, which is what it costs to make the green mean this build.
    outputs.upToDateWhen { false }

    // The tagged tests read this and refuse to pass silently if the hostile
    // charset did not actually take effect -- otherwise a future JDK that
    // ignores file.encoding would turn this whole task into a second identical
    // run, and nothing would say so.
    systemProperty("soulbind.hostileCharset", "true")

    failIfTaggedTestsExistButNoneRan("charset")
}

// The fuzz tier.
//
// Its own task because it must NEVER be up-to-date: each run draws a fresh seed
// and explores inputs no previous run tried, so a cached "success" is a
// statement about a run that already happened rather than about this build.
//
// SOULBIND_SEED replays a specific run exactly. Every run prints its seed
// whether it passed or failed, because a fuzz failure nobody can reproduce is a
// fuzz failure nobody will fix -- and if the seed is only printed on failure,
// the first failure is the first time anybody tests that the printing works.
val fuzzTest = tasks.register<Test>("fuzzTest") {
    description = "Runs the seeded fuzz tier. Never up-to-date; replay with SOULBIND_SEED."
    group = "verification"

    val testSourceSet = project.extensions.getByType<SourceSetContainer>().named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform { includeTags("fuzz") }
    outputs.upToDateWhen { false }

    testLogging {
        // The seed line goes to stdout, and it is the whole point of the run.
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    failIfTaggedTestsExistButNoneRan("fuzz")
}

// The latency measurement.
//
// Its own task, and deliberately NOT wired into `check`: it is informational
// per the specification, and a number that fails a build is a number somebody
// will loosen until it stops failing. Run it when the figure matters, and
// record what it said.
val latencyTest = tasks.register<Test>("latencyTest") {
    description = "Measures decision latency and prints the distribution. Informational."
    group = "verification"

    val testSourceSet = project.extensions.getByType<SourceSetContainer>().named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform { includeTags("latency") }
    outputs.upToDateWhen { false }

    testLogging {
        showStandardStreams = true
        events("failed")
    }
}

tasks.named("check") {
    dependsOn(charsetHostilityTest, fuzzTest)
}

tasks.withType<Jar>().configureEach {
    // Reproducible archives: a rebuild of the same source produces the same
    // bytes, so a diff in a distributed artifact means a diff in the input.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
