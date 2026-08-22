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

// One PIT run at a time across the whole build. See MutationLock.
val mutationLock = gradle.sharedServices.registerIfAbsent(
        "soulbindMutationLock", MutationLock::class.java) {
    maxParallelUsages.set(1)
}

if (hasMainSources) tasks.register<JavaExec>("mutationTest") {
    group = "verification"
    description = "Runs PIT mutation coverage over this module's main classes."

    dependsOn(tasks.named<Test>("test"))
    usesService(mutationLock)

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
        // The previous report is DELETED before this one runs.
        //
        // Without this, a failed run leaves the last successful report on disk,
        // where it reads as current -- and it will be read as current. That is
        // not hypothetical: connector-velocity's run broke the moment
        // LuckPermsGroups landed, stayed broken for a day, and its ten-hour-old
        // numbers were reported as fresh, recorded in a baseline, and used to
        // rank which module to work on next. Nothing about the stale file said
        // it was stale. DECISIONS 10.31.
        //
        // A missing report is a loud, obvious failure. A stale one is a quiet,
        // confident wrong answer, which is strictly worse.
        val stale = reportDir.get().asFile
        if (stale.exists()) {
            stale.deleteRecursively()
        }

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

// --- the ratchet -----------------------------------------------------------
//
// A mutation report nobody reads is a mutation report that was not produced.
// Until Phase 10 the Java tiers ran only when somebody remembered to ask, which
// is how connector-discord reached 48% without anybody deciding that it should.
//
// A RATCHET, not a threshold, and the distinction is the whole design. The
// comment on `--failWhenNoMutations` above is right that thresholds get lowered
// the first time one is inconvenient, and that a lowered threshold is a decision
// about what this project permanently stops noticing. A ratchet asks a different
// question: is this module WORSE than it was? Nobody has to defend a number,
// and nobody can drift past one either.
//
// The baseline is committed, and raising it is an edit somebody makes in the
// same commit as the code that needed it -- which is a decision, visible in a
// diff, rather than a slide.
//
// ONE TABLE, and that is a MEASURED claim rather than an assumption.
//
// This was keyed by environment for one release, on the reasoning that a reaper
// session has a MariaDB server and the workstation does not, so `core`'s
// backend-specific paths would be covered in one and not the other. A session
// run emitted its rows and every one of them matched the workstation's exactly,
// `core` included -- because the session stage deliberately runs this WITHOUT
// MariaDB in scope, which is stated in `.reaper.toml` at the invocation.
//
// So the two tables would have been identical forever, and two tables that must
// be kept identical drift. Drift makes the guard protect less without saying
// so, which is worse than the problem the keying was meant to solve.
//
// What keeps this true is the pinned invocation: the fuzz tier excluded, no
// MariaDB, one Gradle command in the manifest. Change any of those and the
// numbers move for a reason that is not a regression. DECISIONS 10.43.

val baselineFile = rootProject.layout.projectDirectory.file("mutation-baseline.txt")

/** One module's row. */
data class MutationCounts(
        val total: Int,
        val killed: Int,
        val survived: Int,
        val noCoverage: Int,
        val timedOut: Int)

fun readReport(xml: java.io.File): MutationCounts {
    // The XML is one <mutation status="..."> per mutant. Counted by hand rather
    // than with a parser dependency: build-logic ships no XML library, and
    // adding one to read four numbers would be a dependency in the graph the
    // licence guard reads.
    var total = 0
    var killed = 0
    var survived = 0
    var noCoverage = 0
    var timedOut = 0
    Regex("""status=['"]([A-Z_]+)['"]""").findAll(xml.readText()).forEach { match ->
        total++
        when (match.groupValues[1]) {
            "KILLED" -> killed++
            "SURVIVED" -> survived++
            "NO_COVERAGE" -> noCoverage++
            // A KILL, in PIT's terms: the mutant made the test hang and PIT
            // stopped it. Recorded in its own column rather than folded in,
            // because it is the one status that moves on its own -- a mutant
            // near the timeout budget lands in TIMED_OUT on a loaded machine
            // and in SURVIVED on an idle one. When this guard fires and the
            // only movement is between these two columns, it is noise; re-run
            // the module to confirm before touching anything.
            "TIMED_OUT" -> timedOut++
            else -> { }
        }
    }
    return MutationCounts(total, killed, survived, noCoverage, timedOut)
}

fun readBaseline(file: java.io.File): Map<String, MutationCounts> {
    if (!file.exists()) {
        return emptyMap()
    }
    return file.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .associate { line ->
            val parts = line.split(Regex("""\s+"""))
            require(parts.size == 6) {
                "mutation-baseline.txt: expected '<module> <total> <killed> <survived> " +
                        "<noCoverage> <timedOut>', got: $line"
            }
            parts[0] to MutationCounts(
                    parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt(),
                    parts[5].toInt())
        }
}

if (hasMainSources) tasks.register("mutationRatchet") {
    group = "verification"
    description = "Fails if this module's mutation coverage is worse than the committed baseline."

    dependsOn(tasks.named("mutationTest"))

    val module = project.name
    val report = layout.buildDirectory.file("reports/pitest/mutations.xml")
    val baseline = baselineFile

    doLast {
        val xml = report.get().asFile
        if (!xml.exists()) {
            throw GradleException(
                    "no mutation report at $xml. mutationTest produced nothing, which means " +
                            "this ratchet would have passed a module it never measured.")
        }
        val now = readReport(xml)
        val was = readBaseline(baseline.asFile)[module]
                ?: throw GradleException(
                        "no baseline row for '$module' in ${baseline.asFile}.\n" +
                                "A module with no recorded baseline is a module whose coverage " +
                                "nobody has decided, so this refuses rather than passing.\n" +
                                "Observed now -- add this line if it is what you intend:\n" +
                                "    $module ${now.total} ${now.killed} ${now.survived} " +
                                "${now.noCoverage} ${now.timedOut}")

        // SURVIVED and NO_COVERAGE, not the percentage. A percentage moves when
        // mutants are added or removed and says nothing about whether anything
        // got worse; these two are counts of mutants nobody catches, and that
        // number going up is the regression.
        //
        // New untested code raising NO_COVERAGE is not an exception to this. It
        // is the case the ratchet exists for.
        val complaints = mutableListOf<String>()
        if (now.survived > was.survived) {
            complaints.add(
                    "survivors ${was.survived} -> ${now.survived}: " +
                            "${now.survived - was.survived} more mutant(s) that a test runs " +
                            "and does not notice")
        }
        if (now.noCoverage > was.noCoverage) {
            complaints.add(
                    "uncovered ${was.noCoverage} -> ${now.noCoverage}: " +
                            "${now.noCoverage - was.noCoverage} more mutant(s) that no test " +
                            "executes at all")
        }

        if (complaints.isNotEmpty()) {
            throw GradleException(
                    "mutation coverage regressed in :$module\n" +
                            complaints.joinToString("\n") { "  $it" } + "\n\n" +
                            "Read build/reports/pitest/index.html and either kill them, or " +
                            "-- if the change is deliberate and the reason is written down " +
                            "-- update this line in ${baseline.asFile.name} in the SAME " +
                            "commit.\n" +
                            "If the ONLY movement is between survived and timed-out " +
                            "(${was.timedOut} -> ${now.timedOut} here), this is timing noise " +
                            "rather than a regression -- re-run this module alone to " +
                            "confirm before changing anything.\n" +
                            "    $module ${now.total} ${now.killed} ${now.survived} " +
                            "${now.noCoverage} ${now.timedOut}")
        }

        val improved = (was.survived - now.survived) + (was.noCoverage - now.noCoverage)
        if (improved > 0) {
            logger.lifecycle(
                    ":$module mutation coverage IMPROVED by $improved mutant(s) " +
                            "(survivors ${was.survived} -> ${now.survived}, uncovered " +
                            "${was.noCoverage} -> ${now.noCoverage}). Tighten the baseline in " +
                            "${baseline.asFile.name} so it cannot slide back:\n" +
                            "    $module ${now.total} ${now.killed} ${now.survived} " +
                            "${now.noCoverage} ${now.timedOut}")
        } else {
            logger.lifecycle(
                    ":$module mutation coverage held: ${now.killed}/${now.total} killed, " +
                            "${now.survived} survived, ${now.noCoverage} uncovered, " +
                            "${now.timedOut} timed out")
        }
    }
}

// Deliberately NOT wired into `check`. A mutation run costs minutes per module
// where the test task costs seconds, and a slow `check` is a `check` people stop
// running. It is a thing you go and ask for.
tasks.matching { it.name == "check" }.configureEach {
    // Stated so the absence is a decision rather than an oversight.
    logger.debug("mutationTest is not a check dependency by design; run it explicitly")
}
