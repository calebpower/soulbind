// MUST-FAIL FIXTURE. Not part of the build; read as text by DependencyGraphGuardTest.
//
// Specification §16 states two DIFFERENT rules, and this fixture violates both.
//
// Plan is "provided scope only ... never bundles Plan code", so anything that
// puts it on a shipped classpath is wrong -- runtimeOnly included.
//
// MariaDB Connector/J is "never shaded ... ships as a separate jar in lib/",
// which runtimeOnly is how you express. Bundling it into the artifact is the
// violation, and here it is done through a VERSION CATALOG alias -- the form
// every real declaration in this repository uses, and the form the first
// version of this guard could not see at all.

plugins { id("soulbind.java-21") }

dependencies {
    implementation("com.github.plan-player-analytics:Plan:5.8.3605")
    runtimeOnly("com.github.plan-player-analytics:Plan:5.8.3605")
    implementation(libs.mariadb.jdbc)
}
