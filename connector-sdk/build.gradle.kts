plugins { id("soulbind.java-21") }

dependencies {
    // Both are api, not implementation: a connector handling a Decision or a
    // Capability must be able to name its type without re-declaring the module
    // the SDK already handed it one from.
    api(project(":protocol"))
    api(project(":policy"))

    // The SDK parses envelopes, so it needs a JSON reader. Jackson rather than
    // hand-rolling: the wire format is a contract with a PHP implementation, and
    // two hand-written parsers is one more place for them to disagree.
    implementation(libs.bundles.jackson)
}
