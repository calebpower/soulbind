// Modules that execute inside a server operator's JVM, or are consumed by one.
//
// Target 21 because Paper and Velocity's floor is 21: bytecode targeting 25
// would not load there. `protocol` and `connector-sdk` inherit the lower target
// because the plugins depend on them — a dependency compiled to 25 would fail
// at class-load time inside a 21 runtime, which is a defect that surfaces only
// in production if the build does not prevent it.

plugins {
    id("soulbind.java-common")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
