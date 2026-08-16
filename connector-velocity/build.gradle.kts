plugins { id("soulbind.java-21") }

dependencies {
    implementation(project(":connector-sdk"))

    // compileOnly: the proxy supplies this at runtime. Bundling it would ship a
    // second copy for the classloader to disagree about, and the api module is
    // MIT while the proxy is GPLv3 -- compiling against the api only is what
    // keeps this module distributable under Apache-2.0 (§16).
    compileOnly(libs.velocity.api)

    // Available to tests, because a component test constructs the API's own
    // types. Still never shipped.
    testCompileOnly(libs.velocity.api)
    testRuntimeOnly(libs.velocity.api)
}
