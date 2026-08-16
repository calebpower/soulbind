plugins { id("soulbind.java-25") }

// The guards module contains no production code. It reads the repository as
// data -- source text, module metadata, dependency graphs -- and fails when a
// seam has been crossed. It targets 25 because it never ships anywhere.

dependencies {
    // Test-only, and deliberately a real dependency rather than source-text
    // scraping: the protocol-doc guard compares the DECLARED operation table in
    // `Authorizer.Operation` against `docs/protocol.md`. Reflecting over the
    // enum asserts the thing that actually runs; parsing the Java source would
    // assert a second reading of it, and could agree with the document while
    // both disagreed with the compiled behaviour.
    testImplementation(project(":core"))
}

// Modules whose COMPILED OUTPUT a guard inspects.
//
// The release-level guard reads class-file major versions, which only exist
// after compilation. Without these dependencies the guard ran against whatever
// happened to be on disk from an earlier build -- or against nothing at all,
// which it used to treat as a pass. Anything added here must also appear in
// ReleaseLevelGuardTest's expected table, and vice versa; a module in one and
// not the other is a module that quietly left coverage.
val inspectedModules = listOf(
    ":protocol", ":config", ":policy", ":core", ":connector-sdk",
    ":connector-discord", ":connector-velocity", ":connector-plan",
)

tasks.withType<Test>().configureEach {
    dependsOn(inspectedModules.map { "$it:classes" })

    // The repository root, because a guard's subject is the whole tree.
    systemProperty("soulbind.repoRoot", rootProject.projectDir.absolutePath)

    // NEVER up-to-date, and this is load-bearing rather than cautious.
    //
    // A guard's inputs are "most of the repository": every module's build file,
    // every source file under the guarded modules, the dependency graph. Gradle
    // cannot infer that, so by default it treated this task as up-to-date
    // whenever the guards module itself had not changed -- and silently skipped
    // it while a real violation sat in connector-velocity/build.gradle.kts.
    //
    // Found by mutation-checking: the deliberate breakage produced a GREEN run.
    // Forcing a re-run costs a few seconds; the alternative is a guard that
    // reports success without having looked, which is worse than no guard at
    // all because it is trusted.
    //
    // Declaring the inputs precisely instead was considered and rejected: the
    // declaration would need updating every time a module is added, and a guard
    // that silently stops covering new modules is the same failure wearing a
    // different hat.
    outputs.upToDateWhen { false }
}
