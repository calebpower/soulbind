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

        // The proxy API is not on Maven Central and will not be. This is the
        // only reason a second repository exists, and it is scoped by content
        // filter so it can never satisfy anything else -- a typo'd coordinate
        // resolving from an unexpected host is how a supply chain gets a new
        // participant nobody chose.
        maven {
            name = "papermc"
            url = uri("https://repo.papermc.io/repository/maven-public/")
            content {
                includeGroup("com.velocitypowered")
            }
        }
        // Plan's DataExtension API is published only through JitPack, which
        // builds artifacts on demand from arbitrary GitHub repositories. That is
        // a materially larger trust surface than the one above -- papermc serves
        // one project's own releases, JitPack serves anyone's -- so the content
        // filter matters more here, not less.
        //
        // Scoped to exactly the publisher whose API this is. A typo'd coordinate
        // cannot resolve from here, and neither can anything else: JitPack will
        // happily build a package with a plausible name from a repository nobody
        // chose, and that is how a supply chain gains a participant.
        maven {
            name = "jitpack"
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.plan-player-analytics")
            }
        }
    }
}

rootProject.name = "soulbind"

include(
    "protocol",
    "config",
    "policy",
    "core",
    "connector-sdk",
    "connector-discord",
    "connector-velocity",
    "connector-plan",
    "guards",
    // The simulated-user tier. Named `sim` rather than `harness:sim` so the
    // Gradle path stays flat and the guards that enumerate modules do not have
    // to learn about nested projects; the DIRECTORY is harness/sim, where §4
    // puts it.
    "sim",
)

project(":sim").projectDir = file("harness/sim")
