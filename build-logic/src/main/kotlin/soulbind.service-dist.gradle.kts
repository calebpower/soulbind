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
 * The packaging for a soulbind service: core and connector-discord.
 *
 * A DISTRIBUTION -- bin/ and lib/ -- rather than a fat jar, and that is a
 * departure from §14's "fat JARs" recorded in the README departures table.
 * Two reasons, in order of weight:
 *
 * 1. §16 forbids an LGPL artifact inside a shaded artifact. In this layout
 *    every dependency is already its own file, so the rule holds BY
 *    CONSTRUCTION. In a fat jar it would hold by an exclusion list, which is a
 *    thing that can be wrong -- silently, in the direction of a licence
 *    violation. The licence inventory found two copyleft artifacts nobody had
 *    noticed; the packaging should not depend on noticing.
 *
 * 2. Javalin, Jetty, Flyway and the JDBC drivers all register through
 *    META-INF/services. Merging those correctly is what a shading plugin is
 *    for, and getting it wrong drops a services file rather than failing the
 *    build -- the symptom is "no suitable driver" on the operator's machine.
 *
 * Against that there is no operator benefit: a systemd unit runs a start
 * script either way, and this is already the layout the full-stack and forum
 * tiers have been running core from since Phase 7.
 *
 * The PLUGIN jars are shaded, because a host loads one file out of plugins/
 * and there is no lib/ to unbundle into. See soulbind.plugin-jar.gradle.kts.
 */

plugins {
    id("application")
}

// Gzipped, and the extension says so. The application plugin's default is an
// uncompressed .tar, which made docs/install.md's `tar -xzf core-*.tar.gz`
// wrong -- an install document whose first command fails is the defect the
// clean-install gate exists to catch, and it is cheaper to catch it here.
tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

// Sweep archives this module built under a DIFFERENT version.
//
// build/distributions accumulates exactly as build/libs does, and for the same
// reason: Gradle writes into it and never removes anything. Harmless while the
// version was a literal that never moved; now that it comes from the git tag,
// every commit leaves another pair behind for anything saying
// `distributions/*.tar.gz` to pick up. Nothing globs them today -- release.yml
// names what it means and DistributionArchiveGuardTest resolves by version --
// but the last three places that did glob were all written when there was only
// ever one file to find.
//
// Mirrors the sweep in soulbind.plugin-jar, with the same limit: doFirst runs
// only when the archive is actually being written.
listOf("distTar", "distZip").forEach { taskName ->
    tasks.named<AbstractArchiveTask>(taskName) {
        doFirst {
            val current = archiveFile.get().asFile
            val prefix = "${project.name}-"
            val suffix = "." + archiveExtension.get()
            current.parentFile?.listFiles()?.forEach { file ->
                if (file.isFile && file != current
                    && file.name.startsWith(prefix) && file.name.endsWith(suffix)) {
                    logger.lifecycle("removing stale artifact ${file.name}")
                    file.delete()
                }
            }
        }
    }
}

extensions.configure<org.gradle.api.distribution.DistributionContainer>("distributions") {
    named("main") {
        contents {
            // The unit and the samples travel WITH the distribution rather
            // than living only in the repository. An operator installing from
            // a tarball has the tarball; telling them to go and find a service
            // file in a source tree is how a hardened unit turns into
            // `nohup java -jar &`.
            // packaging/<module>/, so the mapping is the directory layout
            // rather than a list in this file. The first version shipped
            // everything to everything, and core's tarball arrived carrying
            // the Discord connector's sample config -- which an operator
            // reasonably reads as "core needs a bot token".
            from(rootProject.file("packaging/${project.name}")) { into("packaging") }
        }
    }
}
