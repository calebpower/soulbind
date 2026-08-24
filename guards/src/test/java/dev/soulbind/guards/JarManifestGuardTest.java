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

package dev.soulbind.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every shipped jar carries the version it was built as, in its manifest.
 *
 * <p>{@code CoreVersion} resolves {@code Package.getImplementationVersion()}
 * and falls back to {@code (development)} when there is none. Gradle does not
 * write that attribute unless told to, and for the first two releases nothing
 * told it to — so {@code core-0.1.1.jar} was published, installed on a live
 * host, and announced itself on startup as
 * {@code soulbind (development) listening on 127.0.0.1:7180}.
 *
 * <p>{@code CoreVersion}'s own javadoc says it exists so that "which build is
 * running" is answerable from the outside, because that is the first question
 * asked of a deployment behaving unexpectedly. It was not answerable, and no
 * test noticed, because every test that cares about the version reads it from
 * the build rather than from the artifact. This one opens the file.
 */
class JarManifestGuardTest {

    /**
     * The jar this build produced for a module, resolved by name.
     *
     * <p>Not "the first jar in build/libs": that directory is never cleaned and
     * the version moves with every commit, so it accumulates. It held thirteen
     * {@code core} jars when this guard was written, and a {@code core-*.jar}
     * glob resolving to the oldest of them is what made the manifest bug look
     * unfixed after it had been fixed. The build sweeps stale jars now; this
     * still names what it wants, because a sweep is a convenience and naming is
     * a guarantee.
     */
    private static Path jarFor(String module) throws IOException {
        Path libs = SourceTree.repoRoot().resolve(module).resolve("build/libs");
        assertTrue(Files.isDirectory(libs),
                module + " has no build/libs; the guards' test task is supposed to depend"
                        + " on its jar, so this means that wiring is gone");

        Path jar = libs.resolve(module + "-" + SourceTree.version() + ".jar");
        if (!Files.isRegularFile(jar)) {
            try (var files = Files.list(libs)) {
                throw new AssertionError(
                        "this build produced no " + jar.getFileName() + ". What is in " + libs
                                + ": " + files.map(p -> p.getFileName().toString()).sorted()
                                .toList());
            }
        }
        return jar;
    }

    private static Manifest manifestOf(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            assertNotNull(entry, jar.getFileName() + " has no manifest at all");
            try (InputStream in = zip.getInputStream(entry)) {
                return new Manifest(in);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "core", "protocol", "config", "policy", "connector-sdk",
        "connector-discord", "connector-velocity", "connector-plan",
    })
    @DisplayName("a shipped jar can say which build it is, without being asked nicely")
    void everyJarDeclaresItsVersion(String module) throws IOException {
        var attributes = manifestOf(jarFor(module)).getMainAttributes();

        String version = attributes.getValue("Implementation-Version");
        assertNotNull(version,
                module + "'s jar has no Implementation-Version. A process running this jar"
                        + " reports its own version as (development), which is what core did"
                        + " in production through v0.1.1.");
        assertEquals(SourceTree.version(), version,
                module + "'s jar was built as " + SourceTree.version() + " and its manifest"
                        + " claims " + version + ".");

        String title = attributes.getValue("Implementation-Title");
        assertEquals(module, title,
                module + "'s jar declares Implementation-Title '" + title + "'. The version"
                        + " alone does not say WHICH artifact is running, and a lib/ directory"
                        + " holds forty-six of them.");
    }

    // NOT here: a separate assertion that the SHADED jars keep the manifest.
    // One was written and removed before this was committed. jarFor() resolves
    // build/libs/<module>-<version>.jar, and for connector-velocity and
    // connector-plan that file IS shadowJar's output -- so the loop above
    // already opens the shaded artifact, and a second test naming it asserted
    // the identical bytes. A duplicate that reads as extra coverage is worse
    // than no test at all, because somebody trusts it.
}
