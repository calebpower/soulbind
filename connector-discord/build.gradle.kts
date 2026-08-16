plugins {
    id("soulbind.java-25")
    application
}

application {
    mainClass.set("dev.soulbind.connector.discord.Main")
}

// A second entry point for the battery's chat side. It runs the real connector
// over the scripted surface, so a stack run exercises this connector's command
// handling rather than only core's.
//
// A start SCRIPT, not just a JavaExec task: the battery invokes it from a shell
// in another process, and `gradle run` would rebuild, re-resolve and print its
// own output onto the stream the driver's reply travels on.
val scriptedDriverScripts by tasks.registering(CreateStartScripts::class) {
    mainClass.set("dev.soulbind.connector.discord.ScriptedDriver")
    applicationName = "scripted-driver"
    outputDir = layout.buildDirectory.dir("scripts-scripted-driver").get().asFile
    classpath = tasks.named<Jar>("jar").get().outputs.files +
        configurations.runtimeClasspath.get()
}

tasks.named<Sync>("installDist") {
    into("bin") {
        from(scriptedDriverScripts)
        // Executable. Gradle drops the permission bit when copying a generated
        // script into a distribution, and the battery invokes this from a
        // shell -- where a non-executable file is a "command not found" that
        // says nothing about why.
        filePermissions { unix("rwxr-xr-x") }
    }
}

tasks.named<org.gradle.api.tasks.bundling.Tar>("distTar") {
    into("${'$'}{project.name}-${'$'}{project.version}/bin") { from(scriptedDriverScripts) }
}

tasks.named<org.gradle.api.tasks.bundling.Zip>("distZip") {
    into("${'$'}{project.name}-${'$'}{project.version}/bin") { from(scriptedDriverScripts) }
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
