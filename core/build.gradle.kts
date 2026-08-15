plugins {
    id("soulbind.java-25")
    application
}

application {
    // One entry point. doctor, register and serve are verbs on it rather than
    // three scripts, so an operator has one thing to find and one --config flag
    // to learn.
    mainClass.set("dev.soulbind.core.cli.Main")
}

dependencies {
    // `api`, not `implementation`: core's own public signatures return protocol
    // types -- Authorizer.Operation.required() is an Optional<Capability>, and
    // ConnectorRecord.capabilities() is a Set<Capability>. Declaring that as
    // `implementation` would understate the API surface, forcing every consumer
    // to re-declare protocol to use methods core already hands them.
    api(project(":protocol"))

    implementation(libs.javalin)
    implementation(libs.bundles.jackson)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    // The shared TOML loader, not a TOML parser. core gets unknown-key
    // rejection, environment overrides and redaction from `config`; it never
    // sees tomlj, because `config` declares that as `implementation`. A guard
    // asserts `config` is the only module declaring a parser at all.
    api(project(":config"))
    implementation(libs.slf4j.api)

    runtimeOnly(libs.logback.classic)

    // SQLite driver is Apache-2.0 and may be shaded.
    implementation(libs.sqlite.jdbc)

    // MariaDB Connector/J is LGPL-2.1 and MUST NOT be shaded. runtimeOnly keeps
    // it off the compile classpath -- nothing here may reference its types --
    // and it ships in lib/ beside the fat jar so an operator can replace it.
    // That replaceability is what satisfies the relink requirement in practice.
    runtimeOnly(libs.mariadb.jdbc)

    testImplementation(libs.sqlite.jdbc)
}
