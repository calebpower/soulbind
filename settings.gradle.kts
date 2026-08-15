// soulbind — Gradle multi-module build.
//
// connector-flarum is deliberately absent: it is a PHP composer package, not a
// Gradle module. It lives in the same repository because the golden vectors are
// the oracle proving the Java and PHP implementations of the protocol agree,
// and an oracle whose two sides live in different repositories drifts.

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "soulbind"

include(
    "protocol",
    "config",
    "core",
    "connector-sdk",
    "connector-discord",
    "connector-velocity",
    "connector-plan",
    "guards",
)
