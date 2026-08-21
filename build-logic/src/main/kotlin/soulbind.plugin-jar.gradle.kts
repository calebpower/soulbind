/*
 * Copyright (c) 2026 Caleb L. Power
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * A single-file plugin jar, with its dependencies relocated.
 *
 * ONLY for the two modules a host application loads out of a plugins/
 * directory: connector-velocity and connector-plan. Core and connector-discord
 * are services and ship as distributions instead -- see the departures table.
 * A plugin has no lib/ to unbundle into, so everything it needs travels inside
 * the one file.
 *
 * WHY RELOCATED RATHER THAN SHADED FLAT: the jar is loaded into a JVM that
 * already has its own libraries -- and connector-plan loads into Plan, which
 * loads into a proxy, so there are more layers still. If the host ships a copy
 * of something we shaded under its original name, there are now two classes
 * with that name visible, and which one our code binds to is decided by the
 * host's classloading rather than by us. It decides itself differently on
 * different host builds, and the symptom is a LinkageError or NoSuchMethodError
 * on an operator's machine rather than a failure here. Nothing else in any JVM
 * can be called dev.soulbind.shaded.*, so relocation makes the question moot.
 *
 * WHAT IS NOT CLAIMED: this does not prove immunity to every host build. It
 * proves the collision cannot happen. That relocation actually took effect is
 * asserted mechanically by PluginJarGuardTest reading the built artifact, and
 * that relocating broke nothing is what the full-stack tier is for -- it loads
 * this jar into a real proxy. Both are checks, not comments.
 */

plugins {
    id("java-library")
    id("com.gradleup.shadow")
}

/** Where relocated third-party packages live. */
val shadedPrefix = "dev.soulbind.shaded"

/**
 * The packages that get moved.
 *
 * Named explicitly rather than "relocate everything not ours". A blanket rule
 * would also rewrite the host API packages this plugin compiles against --
 * com.velocitypowered, com.djrapitops -- and a plugin whose references to its
 * host have been renamed does not load at all.
 */
val relocatedPackages = listOf(
    "com.fasterxml.jackson",
    "org.tomlj",
    "org.antlr",
    "org.checkerframework",
)

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    relocatedPackages.forEach { relocate(it, "$shadedPrefix.$it") }

    // Signatures do not survive repackaging: a jar carrying another project's
    // signature files over classes that have been rewritten is at best inert
    // and at worst a SecurityException at load.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    // Module descriptors from dependencies describe a module this jar is not.
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")

    // §16: LICENSE and the generated inventory ship in every distributed
    // artifact, and a plugin jar is the whole artifact.
    from(rootProject.file("LICENSE")) { into("META-INF/soulbind") }
    from(rootProject.file("NOTICE")) { into("META-INF/soulbind") }
    from(tasks.named("licenceInventory")) { into("META-INF/soulbind") }

    mergeServiceFiles()
}

// `build` produces the artifact an operator installs, rather than a thin jar
// that would fail at runtime for want of everything it needs.
tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
