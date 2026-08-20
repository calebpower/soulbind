plugins { id("soulbind.java-25") }

// The simulated-user tier (§11 Tier 9). It drives core the way a connector
// does and never ships anywhere, so it targets 25 like the other
// non-distributed modules.

dependencies {
    // connector-sdk and protocol, and deliberately NOT core.
    //
    // The tier's whole claim is that a fleet of independent actors, each with
    // its own credential, cannot make core contradict itself. An actor that
    // could import core would be reaching past the door every real connector
    // has to use, and the first invariant to be quietly satisfied by an
    // in-process shortcut would be the one nobody notices.
    implementation(project(":connector-sdk"))
    implementation(project(":protocol"))
    implementation(project(":policy"))
}
