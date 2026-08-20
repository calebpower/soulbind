// Mutation coverage: does a test FAIL when the thing it covers is broken?
//
// This repository's recurring defect is an assertion that cannot fail. A dozen
// were found in Phase 8 alone -- a concurrency test whose threads shared a key,
// an eviction test whose put() overwrote the key it was checking, a memo test
// asserting equality where identity was the claim, three assertTrues a
// preceding count had already forced true. Every one of them was green. Every
// one was found by breaking the covered code BY HAND and watching what
// happened. That operation is mechanical, and PIT performs it exhaustively.
//
// Apache-2.0, and a TOOL rather than a dependency: nothing here reaches a
// compiled artifact, which is why the versions live in this file instead of
// gradle/libs.versions.toml. That catalogue exists so the licence guard has one
// list of things that SHIP to read; adding a test-time launcher to it would
// make the list mean something different.
//
// PIT is invoked directly rather than through the community Gradle plugin. That
// plugin's last release predates Gradle 9 by two major versions, and a build
// tool that breaks on upgrade is a tool that gets disabled in a hurry the first
// time it is inconvenient. The command line is a stable interface; this is
// about forty lines and it will not rot.

plugins {
    // Declared, not assumed. Applied from soulbind.java-common's plugins block,
    // this script's body was running before java-library had registered
    // SourceSetContainer -- "Extension of type 'SourceSetContainer' does not
    // exist". Naming the dependency here makes the plugin self-sufficient and
    // applicable in any order; a second application of java-library is a no-op.
    `java-library`
}

val pitestVersion = "1.25.9"
val pitestJunit5Version = "1.2.3"

val pitest: Configuration = configurations.create("pitest") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    pitest("org.pitest:pitest-command-line:$pitestVersion")
    // REQUIRED, not optional. Without it PIT finds no tests, reports "no
    // mutations" and exits 0 -- a green run that mutated nothing, which is the
    // exact failure shape this task exists to detect. `--failWhenNoMutations`
    // below is what turns that into a red.
    pitest("org.pitest:pitest-junit5-plugin:$pitestJunit5Version")
}

// `guards` has thirteen test files and no main sources: it grades the tree, it
// is not graded. Registering the task there would produce a module whose
// mutation run fails for having nothing to mutate, and the usual response to
// that is an exclusion nobody revisits. Deciding it here, from the source set,
// means a module that GROWS main code gets the task without anybody
// remembering to add it.
// Resolved HERE, at project scope. Inside `tasks.register { }` the receiver is
// the TASK, so `extensions` is the task's own container -- which holds only
// ExtraPropertiesExtension, and the failure reads "Extension of type
// 'SourceSetContainer' does not exist" as though the java plugin were missing.
val sourceSets = extensions.getByType<SourceSetContainer>()
val toolchains = extensions.getByType<JavaToolchainService>()
val mainSourceSet = sourceSets.named("main").get()
val testSourceSet = sourceSets.named("test").get()

val hasMainSources = mainSourceSet.java.srcDirs.any { dir ->
    dir.walkTopDown().any { it.isFile && it.extension == "java" }
}

if (hasMainSources) tasks.register<JavaExec>("mutationTest") {
    group = "verification"
    description = "Runs PIT mutation coverage over this module's main classes."

    dependsOn(tasks.named<Test>("test"))

    classpath = pitest
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")

    javaLauncher.set(toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })

    val reportDir = layout.buildDirectory.dir("reports/pitest")
    outputs.dir(reportDir)

    // Never up-to-date. A mutation report is a claim about the CURRENT tests,
    // and Gradle cannot see that a test's assertion was weakened without its
    // inputs changing -- which is precisely the edit this task exists to catch.
    outputs.upToDateWhen { false }

    doFirst {
        val runtime = testSourceSet.runtimeClasspath.filter { it.exists() }
        val mutable = mainSourceSet.output.classesDirs.filter { it.exists() }
        val sources = mainSourceSet.java.srcDirs.filter { it.exists() }

        args = listOf(
                "--classPath", runtime.joinToString(","),
                // Only THIS module's classes. Without it PIT would happily
                // mutate every dev.soulbind class reachable on the classpath,
                // so :core's report would include :protocol's code, graded by
                // :core's tests -- a low score that says nothing about either.
                "--mutableCodePaths", mutable.joinToString(","),
                "--targetClasses", "dev.soulbind.*",
                "--targetTests", "dev.soulbind.*",
                "--sourceDirs", sources.joinToString(","),
                "--reportDir", reportDir.get().asFile.absolutePath,
                "--outputFormats", "HTML,XML",
                "--timestampedReports", "false",
                // A surviving mutant is information, not a build failure -- yet.
                // Thresholds get lowered the first time one is inconvenient, and
                // a lowered threshold is a decision about what this project
                // permanently stops noticing. The report is read by a person
                // until there is a number worth defending.
                "--failWhenNoMutations", "true",
                "--threads", (Runtime.getRuntime().availableProcessors() / 2)
                        .coerceAtLeast(1).toString(),
                // Tests that hang are killed rather than hanging the run. A
                // mutant that turns a bounded loop unbounded is a real mutant
                // and must be reported as killed-by-timeout, not waited on.
                "--timeoutConst", "10000",
                "--timeoutFactor", "2.0",
                // NARROWING, and the reason covers exactly this: the seeded fuzz
                // tier is its own Gradle task, runs hundreds of generated cases
                // per test, and PIT re-runs the covering tests once per mutant.
                // Including it multiplies the run by the corpus size to gain
                // kill power the deterministic tests mostly already have. It
                // does NOT excuse a surviving mutant -- it means a mutant killed
                // only by fuzzing is reported here as surviving, which is the
                // honest direction to be wrong in.
                // Re-enable with -PmutationIncludesFuzz.
                *(if (project.hasProperty("mutationIncludesFuzz")) arrayOf()
                  else arrayOf("--excludedGroups", "fuzz")))
    }
}

// Deliberately NOT wired into `check`. A mutation run costs minutes per module
// where the test task costs seconds, and a slow `check` is a `check` people stop
// running. It is a thing you go and ask for.
tasks.matching { it.name == "check" }.configureEach {
    // Stated so the absence is a decision rather than an oversight.
    logger.debug("mutationTest is not a check dependency by design; run it explicitly")
}
