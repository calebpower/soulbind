plugins { id("soulbind.java-21") }

// Release 21, not 25: this loader is shared by core (standalone, 25) and by
// every connector that loads inside a server operator's JVM (21). The lower
// floor is the one that has to hold.

dependencies {
    // `implementation`, not `api`, and deliberately so. tomlj is an
    // implementation detail: nothing in this module's public surface exposes a
    // tomlj type, so no consumer gains a TOML parser on its compile classpath by
    // depending on the loader. That is what makes "the shared loader is the only
    // TOML entry point" (specification §5) mechanically true rather than a
    // convention people are asked to respect.
    implementation(libs.toml)
}
