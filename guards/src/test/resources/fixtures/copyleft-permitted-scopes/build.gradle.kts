// MUST-PASS FIXTURE. Not part of the build; read as text by DependencyGraphGuardTest.
//
// The declarations §16 actually prescribes. This exists so the guard cannot pass
// by rejecting every mention of these artifacts -- a guard that forbids the
// correct usage as well as the wrong one reads as coverage while making the
// module unbuildable.
//
// It also pins the difference between the two rules, which one shared list of
// "bundling configurations" got wrong: runtimeOnly is CORRECT for Connector/J
// (it ships beside the jar, replaceable) and WRONG for Plan (the host provides
// it and a second copy is how the wrong annotations get loaded).

plugins { id("soulbind.java-21") }

dependencies {
    compileOnly("com.github.plan-player-analytics:Plan:5.8.3605")
    testImplementation("com.github.plan-player-analytics:Plan:5.8.3605")
    runtimeOnly(libs.mariadb.jdbc)
    testRuntimeOnly(libs.mariadb.jdbc)
}
