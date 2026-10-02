plugins {
    id("soulbind.licence-inventory")
    id("soulbind.service-dist")
    id("soulbind.java-25")
    application
}

application {
    mainClass.set("dev.soulbind.connector.forge.Main")
}

dependencies {
    implementation(project(":connector-sdk"))

    // The listener for the signup form. An IMPLEMENTATION detail, never api:
    // only the transport package names it, and the seam guard would catch it if
    // anything else did. Already in the catalogue for core, with its licence
    // and Jetty's recorded there.
    implementation(libs.javalin)

    // Declared rather than taken from javalin's transitive graph: Main logs
    // through it, and a connector whose logging breaks when its HTTP library is
    // swapped has a dependency nobody declared.
    implementation(libs.slf4j.api)

    runtimeOnly(libs.logback.classic)
}
