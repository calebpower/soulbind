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
 * The third-party licence inventory generator (specification §16).
 *
 * NOTICE claimed one of these from Phase 0 until Phase 8 and there was none --
 * see docs/DECISIONS.md 10.1. This is it.
 *
 * WHAT IT INVENTORIES: the RESOLVED runtime classpath, not the declared
 * dependencies. Those are different lists and the difference is the point: the
 * catalogue declares about a dozen libraries and the graph that actually ships
 * is around sixty artifacts. A redistributor's legal review needs the second
 * one, and hand-maintaining it is how it goes stale.
 *
 * WHERE THE LICENCE COMES FROM: each artifact's own POM, walking up the parent
 * chain, because most multi-module projects (Jetty, Jackson, Kotlin) declare
 * the licence once on the parent. An artifact whose chain names no licence is
 * NOT guessed at -- it fails the build until `gradle/licences.conf` records one
 * with a reason.
 *
 * WHAT FAILS THE BUILD: an unknown licence name, an artifact with no licence
 * anywhere, or an LGPL artifact in a configuration that would bundle it. §16's
 * "new licences entering the graph fail the build until allowlisted with a
 * stated reason" -- the same narrowing discipline as every other guard.
 */

plugins {
    id("java-library")
}

/** One entry of the parsed side-file. */
data class LicenceRules(
    val allowed: Map<String, Pair<String, String>>,
    val aliases: Map<String, String>,
    val overrides: Map<String, String>,
    val dual: Map<String, String>,
    val neverBundled: Set<String>,
)

/*
 * A hand-rolled parser for a hand-written file, rather than a TOML library.
 *
 * build-logic is a separate build and could depend on anything, but the
 * repository holds that exactly one module declares a TOML parser -- the entry
 * point guard exists so a second parser cannot appear without a decision. A
 * sectioned key=value file needs twenty lines to read and keeps that true.
 */
fun parseLicenceRules(file: java.io.File): LicenceRules {
    val allowed = linkedMapOf<String, Pair<String, String>>()
    val aliases = linkedMapOf<String, String>()
    val overrides = linkedMapOf<String, String>()
    val dual = linkedMapOf<String, String>()
    val neverBundled = linkedSetOf<String>()
    var section = ""

    file.readLines().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.trim('[', ']')
            return@forEach
        }
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=', "").trim()
        when (section) {
            "allowed" -> {
                // "<handling> | <reason>". The handling is a FIELD rather than
                // something inferred from the licence id, because the question
                // "may this be shaded?" is the one §16 actually turns on, and
                // inferring it from a name means a new licence gets whatever
                // the string matching happens to decide.
                val handling = value.substringBefore('|').trim()
                val reason = value.substringAfter('|', "").trim()
                if (handling !in setOf("shadeable", "unbundled", "not-distributed")) {
                    throw GradleException(
                        "licences.conf: $key has handling '$handling'; expected" +
                            " shadeable, unbundled or not-distributed"
                    )
                }
                allowed[key] = handling to reason
            }
            "aliases" -> aliases[key] = value
            "overrides" -> overrides[key] = value
            "dual" -> dual[key] = value
            "never-bundled" -> neverBundled += key
            else -> throw GradleException(
                "licences.conf line outside any section: $raw"
            )
        }
    }
    return LicenceRules(allowed, aliases, overrides, dual, neverBundled)
}

/** Reads `<licenses><license><name>` out of a POM, ignoring namespaces. */
fun licencesIn(pom: java.io.File): List<String> {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .also {
            // A POM is data from the network. It gets no doctype, no external
            // entities and no schema fetch, in a task that runs on a build
            // machine with credentials on it.
            it.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            it.isExpandEntityReferences = false
        }
        .newDocumentBuilder()
        .parse(pom)

    val names = mutableListOf<String>()
    val licences = doc.getElementsByTagName("license")
    for (i in 0 until licences.length) {
        val children = licences.item(i).childNodes
        for (j in 0 until children.length) {
            val child = children.item(j)
            if (child.nodeName == "name") {
                child.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { names += it }
            }
        }
    }
    return names
}

/** The `<parent>` coordinate of a POM, if it has one. */
fun parentOf(pom: java.io.File): String? {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .also {
            it.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            it.isExpandEntityReferences = false
        }
        .newDocumentBuilder()
        .parse(pom)
    val parents = doc.getElementsByTagName("parent")
    if (parents.length == 0) return null
    val fields = mutableMapOf<String, String>()
    val children = parents.item(0).childNodes
    for (j in 0 until children.length) {
        val child = children.item(j)
        fields[child.nodeName] = child.textContent?.trim().orEmpty()
    }
    val group = fields["groupId"] ?: return null
    val name = fields["artifactId"] ?: return null
    val version = fields["version"] ?: return null
    return "$group:$name:$version"
}

val licenceInventory = tasks.register("licenceInventory") {
    group = "documentation"
    description = "Generates the third-party licence inventory for this module's runtime graph."

    val rulesFile = rootProject.file("gradle/licences.conf")
    val output = layout.buildDirectory.file("licence-inventory/THIRD-PARTY.txt")
    val runtime = configurations.named("runtimeClasspath")
    val moduleName = project.name
    val projectGroup = project.group.toString()

    inputs.file(rulesFile)
    inputs.files(runtime)
    outputs.file(output)

    // Resolving POMs needs the repositories and a detached configuration, and
    // both have to be captured outside doLast for the configuration cache.
    val dependencyFactory = project.dependencies
    val configurationContainer = project.configurations

    doLast {
        val rules = parseLicenceRules(rulesFile)

        val artifacts = runtime.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group != projectGroup }          // our own modules are Apache-2.0
            .map { "${it.group}:${it.name}:${it.version}" }
            .distinct()
            .sorted()

        // Every POM in one detached configuration, plus room for parents
        // discovered as we walk. Resolving them one at a time would be one
        // network round trip each.
        val pomCache = mutableMapOf<String, java.io.File?>()
        fun pomFor(coordinate: String): java.io.File? = pomCache.getOrPut(coordinate) {
            val detached = configurationContainer.detachedConfiguration(
                dependencyFactory.create("$coordinate@pom")
            )
            detached.isTransitive = false
            try {
                detached.resolve().firstOrNull()
            } catch (e: Exception) {
                null
            }
        }

        fun licenceOf(coordinate: String): List<String> {
            var current: String? = coordinate
            var depth = 0
            while (current != null && depth < 10) {
                val pom = pomFor(current) ?: return emptyList()
                val found = licencesIn(pom)
                if (found.isNotEmpty()) return found
                current = parentOf(pom)
                depth++
            }
            return emptyList()
        }

        val unknownLicences = sortedSetOf<String>()
        val undeclared = sortedSetOf<String>()
        val unelectedDuals = sortedSetOf<String>()
        val unlistedCopyleft = sortedSetOf<String>()
        val rows = mutableListOf<Triple<String, String, String>>()

        artifacts.forEach { coordinate ->
            val module = coordinate.substringBeforeLast(':')
            val override = rules.overrides[module] ?: rules.overrides[coordinate]
            val canonical: String? = if (override != null) {
                override
            } else {
                val raw = licenceOf(coordinate)
                if (raw.isEmpty()) {
                    undeclared += coordinate
                    null
                } else {
                    val mapped = raw.mapNotNull { rules.aliases[it] }.distinct()
                    if (mapped.isEmpty()) {
                        unknownLicences += raw.joinToString(" / ") + "   [$coordinate]"
                        null
                    } else if (mapped.size == 1) {
                        mapped.single()
                    } else {
                        // Dual-licensed. Taking the first one the POM happens
                        // to list is a decision about this project's licensing
                        // obligations made by the ordering of somebody else's
                        // XML -- and it got Jetty wrong, electing EPL-2.0 when
                        // §16 says Apache-2.0. An election has to be recorded.
                        val elected = rules.dual[module]
                            ?: rules.dual[module.substringBefore(':') + ":*"]
                        if (elected == null) {
                            unelectedDuals += "$module offers ${mapped.joinToString(" / ")}"
                            null
                        } else if (elected !in mapped) {
                            unelectedDuals += "$module is recorded as $elected," +
                                " which its POM does not offer (${mapped.joinToString(" / ")})"
                            null
                        } else {
                            elected
                        }
                    }
                }
            }
            if (canonical != null) {
                if (!rules.allowed.containsKey(canonical)) {
                    unknownLicences += "$canonical   [$coordinate]"
                }
                // §16: no LGPL artifact inside any shaded or fat artifact. The
                // list of what must ride outside the jar is DERIVED from the
                // resolved graph rather than hand-kept, so a copyleft artifact
                // arriving as somebody else's transitive dependency cannot be
                // bundled by nobody having noticed it.
                val handling = rules.allowed[canonical]?.first
                if (handling == "unbundled" && module !in rules.neverBundled) {
                    unlistedCopyleft += "$module ($canonical)"
                }
                rows += Triple(coordinate, canonical, rules.allowed[canonical]?.second.orEmpty())
            }
        }

        if (unelectedDuals.isNotEmpty()) {
            throw GradleException(
                "these artifacts are dual-licensed and this project has not recorded" +
                    " which licence it takes them under:\n  " +
                    unelectedDuals.joinToString("\n  ") +
                    "\n\nAdd each to [dual] in gradle/licences.conf with the elected" +
                    " licence and the reason. Taking whichever the POM lists first is a" +
                    " decision about this project's obligations made by the ordering of" +
                    " somebody else's XML."
            )
        }
        if (unlistedCopyleft.isNotEmpty()) {
            throw GradleException(
                "copyleft artifacts in the runtime graph that are not marked" +
                    " never-bundled:\n  " +
                    unlistedCopyleft.joinToString("\n  ") +
                    "\n\nSpecification §16: no LGPL artifact inside any shaded or fat" +
                    " artifact -- they ride in lib/ beside it so an operator can replace" +
                    " them, which is what satisfies the relink requirement in practice." +
                    " Add each to [never-bundled] in gradle/licences.conf, or remove the" +
                    " dependency."
            )
        }

        if (undeclared.isNotEmpty()) {
            throw GradleException(
                "these artifacts declare no licence anywhere in their POM parent chain," +
                    " and the inventory will not guess one:\n  " +
                    undeclared.joinToString("\n  ") +
                    "\n\nAdd each to [overrides] in gradle/licences.conf with the licence" +
                    " and a comment saying where you established it."
            )
        }
        if (unknownLicences.isNotEmpty()) {
            throw GradleException(
                "licences entering the graph that are not allowlisted:\n  " +
                    unknownLicences.joinToString("\n  ") +
                    "\n\nSpecification §16: a new licence fails the build until it is" +
                    " allowlisted with a stated reason. Add the raw name to [aliases] and," +
                    " if the canonical id is new, to [allowed] in gradle/licences.conf."
            )
        }

        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("Third-party licence inventory for soulbind :$moduleName")
            appendLine()
            appendLine("GENERATED by the `licenceInventory` Gradle task from the RESOLVED")
            appendLine("runtime classpath -- not from a hand-maintained list. Each licence is")
            appendLine("read from the artifact's own POM, walking the parent chain, or is")
            appendLine("recorded in gradle/licences.conf with a reason. An artifact with no")
            appendLine("licence and a licence not on the allowlist both fail the build.")
            appendLine()
            appendLine("soulbind itself is Apache-2.0; see LICENSE. This file covers only")
            appendLine("third-party components.")
            appendLine()
            appendLine("${rows.size} third-party artifacts:")
            appendLine()
            val width = rows.maxOfOrNull { it.first.length } ?: 0
            rows.forEach { (coordinate, licence, _) ->
                appendLine("  ${coordinate.padEnd(width)}  $licence")
            }
            appendLine()
            appendLine("Licences appearing above:")
            appendLine()
            rows.map { it.second }.distinct().sorted().forEach { licence ->
                appendLine("  $licence")
                rules.allowed[licence]?.let { (handling, reason) ->
                    appendLine("      $handling -- $reason")
                }
            }
            // Only the ones actually IN this module's graph. Printing the
            // whole never-bundled list told connector-velocity's readers that
            // trove4j and logback ship beside its jar, and neither is anywhere
            // near it -- a false statement about packaging in the file whose
            // job is to be accurate about packaging.
            val bundledHere = rows.map { it.first.substringBeforeLast(':') }.toSet()
            rules.neverBundled.filter { it in bundledHere }.takeIf { it.isNotEmpty() }?.let { never ->
                appendLine()
                appendLine("Never shaded or bundled, shipped unmodified beside the fat jar so")
                appendLine("the operator can replace them -- which is what satisfies the LGPL")
                appendLine("relink requirement in practice:")
                appendLine()
                never.sorted().forEach { appendLine("  $it") }
            }
        })
        logger.lifecycle("licence inventory: ${rows.size} artifacts -> ${file.path}")
    }
}

// §16: "a dependency-license report runs in the default test task". Wired into
// `check` rather than left to be remembered, because a generator nobody runs is
// the same shape as the NOTICE claim it replaced -- see docs/DECISIONS.md 10.1.
tasks.named("check") {
    dependsOn(licenceInventory)
}

// Ships in the distribution, per §16: LICENSE and a generated NOTICE plus
// third-party inventory in every distributed artifact.
plugins.withId("application") {
    the<JavaApplication>()
    extensions.configure<org.gradle.api.distribution.DistributionContainer>("distributions") {
        named("main") {
            contents {
                from(licenceInventory) { into("") }
                from(rootProject.file("LICENSE")) { into("") }
                from(rootProject.file("NOTICE")) { into("") }
            }
        }
    }
}
