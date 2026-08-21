plugins {
    id("soulbind.licence-inventory")
    id("soulbind.service-dist")
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
    // JDA's voice support is excluded, and it is a LICENSING decision as much
    // as a size one.
    //
    // club.minnced:opus-java pulls net.java.dev.jna:jna 4.4.0, whose POM
    // declares LGPL-2.1 and nothing else -- JNA only became dual-licensed with
    // Apache-2.0 in later releases, and 4.4.0's metadata predates that. §16
    // forbids an LGPL artifact inside a fat jar, so keeping it would mean
    // shipping a separate jar in lib/ and taking on the relink obligation for
    // a library this connector does not use: it sends messages and applies
    // roles, and never touches a voice channel.
    //
    // Found by the licence inventory generator rather than by reading the
    // dependency tree, which is the point of having one.
    implementation(libs.jda) {
        exclude(group = "club.minnced", module = "opus-java")
    }

    runtimeOnly(libs.logback.classic)
}
