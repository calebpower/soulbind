plugins {
    id("soulbind.java-25")
    application
}

application {
    mainClass.set("dev.soulbind.connector.discord.Main")
}

dependencies {
    implementation(project(":connector-sdk"))

    // JDA is an IMPLEMENTATION detail, never api. Only ChatSurface's JDA
    // implementation names it; the connector's logic must not, and a guard
    // would catch it if it did. Keeping it off the api surface is what stops a
    // future consumer compiling against a chat library by accident.
    implementation(libs.jda)

    runtimeOnly(libs.logback.classic)
}
