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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
        put("config", 21);             // shared loader; the lower floor is the one that holds
        put("policy", 21);             // the SDK caches decisions and needs these types
        put("connector-sdk", 21);      // depended on by the plugins
        put("connector-velocity", 21); // loads inside a proxy JVM
        put("connector-plan", 21);     // loads inside a server JVM
        put("core", 25);               // standalone service
        put("connector-discord", 25);  // standalone daemon
    }};

    /**
     * Modules the release table deliberately omits.
     *
     * <p>{@code guards} produces no production bytecode and ships nowhere. The
     * exclusion covers exactly that module.
     */
    /**
     * Modules no release level governs, because nothing distributes them.
     *
     * <p>`guards` reads the repository as data and `sim` drives it as a client;
     * neither is packaged, published or loaded into anybody's JVM, so there is
     * no runtime whose floor could be violated. The exemption covers exactly
     * that — it is not a licence to omit a module that DOES ship.
     */
    private static final java.util.Set<String> NOT_RELEASE_GOVERNED =
            java.util.Set.of("guards", "sim");

    @Test
    @DisplayName("every module in the build appears in the release table")
    void tableCoversEveryModule() {
        // Without this, adding a module and forgetting to add a row here would
        // leave it entirely outside the guard -- green, and uncovered. That is
        // the failure this whole guard exists to prevent, applied to itself.
        java.util.List<String> uncovered = new java.util.ArrayList<>();
        for (String module : SourceTree.allModules()) {
            if (!EXPECTED_RELEASE.containsKey(module) && !NOT_RELEASE_GOVERNED.contains(module)) {
                uncovered.add(module);
            }
        }
        assertTrue(
                uncovered.isEmpty(),
                () -> "modules in settings.gradle.kts with no declared release level: "
                        + uncovered + ". Decide the level deliberately -- a module that loads "
                        + "inside a server operator's JVM and targets too high a release fails "
                        + "at class-load time, far from its cause.");
    }

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

            // An unbuilt module is a FAILURE, not a skip.
            //
            // This previously did `continue`, with a comment saying an unbuilt
            // tree must not read as a green guard -- while doing exactly that.
            // The guards task now depends on every inspected module's `classes`
            // task, so reaching here with nothing compiled means the dependency
            // was dropped, and the honest report is that the guard could not
            // look rather than that it looked and was satisfied.
            assertTrue(
                    Files.isDirectory(classesDir),
                    () -> module + " has no compiled output at " + classesDir
                            + ". The guard cannot inspect bytecode that does not exist; wire "
                            + "the module into guards/build.gradle.kts rather than letting it "
                            + "drop out of coverage.");

            List<Path> classFiles = classFilesUnder(classesDir);
            assertFalse(
                    classFiles.isEmpty(),
                    () -> module + " compiled to zero class files, so this module contributed "
                            + "no evidence to the guard");

            // Every class file, not a sample: one arbitrary class agreeing proves
            // nothing about the rest, and a mixed-version output is exactly the
            // kind of thing a sample misses.
            for (Path classFile : classFiles) {
                int actualMajor = classFileMajor(classFile);
                assertEquals(
                        expectedMajor,
                        actualMajor,
                        () -> ("%s emitted class-file major %d for %s but must emit %d "
                                + "(Java %d). A module that loads inside a server operator's "
                                + "JVM and targets too high a release fails at class-load "
                                + "time, not at build time.")
                                .formatted(module, actualMajor, SourceTree.rel(classFile),
                                        expectedMajor, e.getValue()));
            }
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

    private static List<Path> classFilesUnder(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList();
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
