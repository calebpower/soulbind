// Root build. Deliberately thin: every convention lives in build-logic so that
// there is exactly one place a toolchain or release decision can be made.

tasks.register("guards") {
    group = "verification"
    description = "Run the seam guards (see guards/README.md and plan §5)."
    dependsOn(":guards:test")
}
