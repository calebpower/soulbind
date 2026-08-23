// Root build. Deliberately thin: every convention lives in build-logic so that
// there is exactly one place a toolchain or release decision can be made.

tasks.register("guards") {
    group = "verification"
    description = "Run the seam guards (see guards/README.md and plan §5)."
    dependsOn(":guards:test")

    // build-logic is a separate included build, so nothing in the main build
    // reaches its tests by default -- `./gradlew build` would compile the
    // convention plugins and never run a line of SoulbindVersionTest. Without
    // this the version derivation would be the one decision in the tree with no
    // suite behind it, which is exactly backwards: it names every artifact this
    // project publishes.
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
