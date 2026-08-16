plugins { id("soulbind.java-21") }

dependencies {
    // Both are api, not implementation: a connector handling a Decision or a
    // Capability must be able to name its type without re-declaring the module
    // the SDK already handed it one from.
    api(project(":protocol"))
    api(project(":policy"))
}
