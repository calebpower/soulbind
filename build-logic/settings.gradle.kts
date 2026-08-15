// Convention plugins for the soulbind build. Included, not published: these
// exist to keep toolchain and --release decisions in exactly one place, which
// is what the Tier 3 structural test asserts against.

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
