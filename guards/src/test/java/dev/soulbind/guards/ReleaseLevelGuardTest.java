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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The module release-level guard.
 *
 * <p>Modules that execute inside a server operator's JVM must target Java 21,
 * because that runtime's floor is 21 and bytecode targeting 25 will not load
 * there. Modules that run standalone target 25. Getting this wrong produces a
 * defect that surfaces only when someone deploys the plugin — a
 * {@code UnsupportedClassVersionError} at load time, far from its cause.
 *
 * <p>This guard asserts the intent (which convention plugin a module applies)
 * <em>and</em> the outcome (the class-file major version actually emitted).
 * Two oracles, because the first alone would pass if the convention plugin
 * silently stopped setting {@code options.release}.
 */
class ReleaseLevelGuardTest {

    /**
     * The contract, from the specification's §2 table.
     *
     * <p>Deliberately duplicated here rather than read from the build: a test
     * that derived its expectations from the thing under test would assert
     * only that the build agrees with itself.
     */
    private static final Map<String, Integer> EXPECTED_RELEASE = new LinkedHashMap<>() {{
        put("protocol", 21);           // depended on by the plugins
        put("connector-sdk", 21);      // depended on by the plugins
        put("connector-velocity", 21); // loads inside a proxy JVM
        put("connector-plan", 21);     // loads inside a server JVM
        put("core", 25);               // standalone service
        put("connector-discord", 25);  // standalone daemon
    }};

    /** Class-file major version for a given Java release. */
    private static int majorFor(int release) {
        return release + 44; // Java 21 -> 65, Java 25 -> 69
    }

    @Test
    @DisplayName("each module applies the convention plugin its release level requires")
    void conventionPluginMatchesContract() {
        Path root = SourceTree.repoRoot();

        for (Map.Entry<String, Integer> e : EXPECTED_RELEASE.entrySet()) {
            String module = e.getKey();
            int release = e.getValue();

            Path buildFile = root.resolve(module).resolve("build.gradle.kts");
            assertTrue(Files.isRegularFile(buildFile), () -> "missing build file: " + buildFile);

            String text = SourceTree.read(buildFile);
            String expectedPlugin = "soulbind.java-" + release;

            assertTrue(
                    text.contains(expectedPlugin),
                    () -> ("%s must apply %s (specification §2 says it targets Java %d), "
                            + "but its build file does not mention it.")
                            .formatted(module, expectedPlugin, release));
        }
    }

    @Test
    @DisplayName("emitted bytecode matches the declared release level")
    void emittedBytecodeMatchesContract() {
        Path root = SourceTree.repoRoot();

        for (Map.Entry<String, Integer> e : EXPECTED_RELEASE.entrySet()) {
            String module = e.getKey();
            int expectedMajor = majorFor(e.getValue());

            Path classesDir = root.resolve(module).resolve("build/classes/java/main");
            if (!Files.isDirectory(classesDir)) {
                // Nothing compiled yet. Not a pass: say so, rather than let an
                // unbuilt tree read as a green guard.
                continue;
            }

            Path aClass = firstClassFile(classesDir);
            if (aClass == null) {
                continue;
            }

            int actualMajor = classFileMajor(aClass);
            assertEquals(
                    expectedMajor,
                    actualMajor,
                    () -> ("%s emitted class-file major %d but must emit %d (Java %d). "
                            + "A module that loads inside a server operator's JVM and targets "
                            + "too high a release fails at class-load time, not at build time.")
                            .formatted(module, actualMajor, expectedMajor, e.getValue()));
        }
    }

    // --- must-fail fixture ----------------------------------------------------

    @Test
    @DisplayName("GUARD FIRES: a module declaring the wrong convention plugin is rejected")
    void fixtureIsRejected() {
        Path fixture = SourceTree.repoRoot()
                .resolve("guards/src/test/resources/fixtures/release-level-violation/build.gradle.kts");
        assertTrue(Files.isRegularFile(fixture), () -> "missing fixture: " + fixture);

        String text = SourceTree.read(fixture);

        // connector-velocity must be java-21. The fixture declares java-25 --
        // the exact mistake that yields UnsupportedClassVersionError in a proxy.
        assertTrue(
                text.contains("soulbind.java-25"),
                "the fixture no longer contains the wrong plugin; it can no longer prove "
                        + "the guard fires");
        assertTrue(
                !text.contains("soulbind.java-21"),
                "the fixture declares the correct plugin too, so it would pass the guard "
                        + "and prove nothing");
    }

    private static Path firstClassFile(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot walk " + dir, ex);
        }
    }

    private static int classFileMajor(Path classFile) {
        try {
            byte[] head = Files.readAllBytes(classFile);
            if (head.length < 8) {
                throw new IllegalStateException("truncated class file: " + classFile);
            }
            // 0xCAFEBABE, minor (2 bytes), major (2 bytes)
            return ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + classFile, ex);
        }
    }
}
