// Modules that run standalone, in a JVM soulbind controls.
//
// Target 25 because nothing constrains them downward: `core` and
// `connector-discord` are services we deploy, not plugins loaded into somebody
// else's runtime.

plugins {
    id("soulbind.java-common")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}
