plugins {
    id("soulbind.java-21")
    id("soulbind.licence-inventory")
}

dependencies {
    // Both are api, not implementation: a connector handling a Decision or a
    // Capability must be able to name its type without re-declaring the module
    // the SDK already handed it one from.
    api(project(":protocol"))
    api(project(":policy"))

    // The shared config loader, handed to connectors through the SDK -- which
    // is what §5 means by "one shared loader". api, not implementation: a
    // connector declares its own ConfigKeys and must be able to name the type.
    // tomlj stays invisible, because  declares it as implementation, so
    // the one-TOML-parser guard still holds.
    api(project(":config"))

    // The SDK parses envelopes, so it needs a JSON reader. Jackson rather than
    // hand-rolling: the wire format is a contract with a PHP implementation, and
    // two hand-written parsers is one more place for them to disagree.
    implementation(libs.bundles.jackson)
}
