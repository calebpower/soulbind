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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What must be true of a plugin jar, read from the built artifact.
 *
 * <p>A plugin is one file dropped into a host's {@code plugins/} directory, so
 * everything it needs travels inside it. That makes the jar's contents a
 * deployment contract rather than a build detail, and these read the actual
 * zip rather than the build script that was supposed to produce it.
 *
 * <p>The relocation assertions exist because the alternative was a comment.
 * Relocation prevents the host's copy of a library and ours from colliding, and
 * a shading configuration that silently stops relocating produces a jar that
 * looks correct here and throws {@code LinkageError} on an operator's proxy.
 * Nothing about that is visible without opening the file.
 */
class PluginJarGuardTest {

    /**
     * Packages that must never appear at their original path in a plugin jar.
     *
     * <p>Deliberately the ones the shading configuration claims to relocate. A
     * list of "things we happen not to bundle" would pass without asserting
     * anything.
     */
    private static final List<String> MUST_BE_RELOCATED = List.of(
            "com/fasterxml/jackson/",
            "org/tomlj/",
            "org/antlr/",
            "org/checkerframework/");

    /**
     * The host APIs a plugin compiles against and must never carry.
     *
     * <p>{@code com.velocitypowered} is MIT but the proxy supplying it is
     * GPLv3, and {@code com.djrapitops} is LGPL-3.0. Both are {@code
     * compileOnly} precisely so this project distributes neither; bundling
     * either would also mean a plugin carrying a second copy of its own host's
     * classes, which is its own kind of broken.
     */
    private static final List<String> HOST_APIS = List.of(
            "com/velocitypowered/", "com/djrapitops/");

    /**
     * Copyleft artifacts, by a package prefix that identifies their classes.
     *
     * <p>Specification §16 asks for "an artifact-content check asserts no LGPL
     * classes appear inside shaded outputs". It could not be written until
     * there were shaded outputs; there are now. The declaration-level check in
     * {@link DependencyGraphGuardTest} says no such artifact is declared in a
     * bundling configuration -- this says none arrived anyway.
     */
    private static final List<String> COPYLEFT_CLASSES = List.of(
            "org/mariadb/jdbc/",
            "ch/qos/logback/",
            "gnu/trove/",
            "com/sun/jna/");

    private static Path jarFor(String module) throws IOException {
        Path libs = SourceTree.repoRoot().resolve(module).resolve("build/libs");
        assertTrue(Files.isDirectory(libs),
                module + " has no build/libs; the guards' test task is supposed to depend"
                        + " on its shadowJar, so this means that wiring is gone");
        try (var files = Files.list(libs)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().contains("-sources"))
                    .filter(p -> !p.getFileName().toString().contains("-javadoc"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no jar in " + libs));
        }
    }

    private static List<String> entriesOf(Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector-velocity", "connector-plan"})
    @DisplayName("third-party packages are relocated, and something actually was")
    void thirdPartyPackagesAreRelocated(String module) throws IOException {
        List<String> entries = entriesOf(jarFor(module));

        // Both directions, and the second is what stops the first being
        // vacuous: a jar that bundles nothing at all also contains no
        // unrelocated Jackson.
        long relocated = entries.stream()
                .filter(name -> name.startsWith("dev/soulbind/shaded/"))
                .count();
        assertTrue(relocated > 0,
                module + "'s jar contains nothing under dev/soulbind/shaded/, so either"
                        + " shading stopped happening or relocation did. A plugin jar"
                        + " missing its dependencies fails at load on the host, not here.");

        List<String> leaked = entries.stream()
                .filter(name -> MUST_BE_RELOCATED.stream()
                        .anyMatch(prefix -> name.startsWith(prefix)))
                .toList();
        assertTrue(leaked.isEmpty(),
                module + "'s jar carries these classes at their ORIGINAL package path: "
                        + leaked.stream().limit(5).toList()
                        + " (" + leaked.size() + " total). The host has its own copies,"
                        + " and which one this plugin binds to is then decided by the"
                        + " host's classloading rather than by us -- differently on"
                        + " different host builds, surfacing as a LinkageError on an"
                        + " operator's machine.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector-velocity", "connector-plan"})
    @DisplayName("service files name the relocated classes, not the originals")
    void serviceFilesFollowTheRelocation(String module) throws IOException {
        // A relocation that renames classes and leaves META-INF/services
        // pointing at the old names produces a jar whose ServiceLoader lookups
        // silently find nothing. Silently: no error, just absent functionality.
        List<String> services = entriesOf(jarFor(module)).stream()
                .filter(name -> name.startsWith("META-INF/services/"))
                .map(name -> name.substring("META-INF/services/".length()))
                .toList();

        List<String> stale = services.stream()
                .filter(name -> MUST_BE_RELOCATED.stream()
                        .anyMatch(prefix -> name.startsWith(prefix.replace('/', '.'))))
                .toList();
        assertTrue(stale.isEmpty(),
                module + " has service files naming unrelocated classes: " + stale
                        + ". ServiceLoader would find nothing and say nothing.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector-velocity", "connector-plan"})
    @DisplayName("no host API and no copyleft classes are bundled")
    void nothingIsBundledThatMustNotBe(String module) throws IOException {
        List<String> entries = entriesOf(jarFor(module));

        List<String> hosts = entries.stream()
                .filter(name -> HOST_APIS.stream().anyMatch(name::startsWith))
                .toList();
        assertTrue(hosts.isEmpty(),
                module + " bundles host API classes: " + hosts.stream().limit(5).toList()
                        + ". They are compileOnly precisely so this project distributes"
                        + " neither the GPLv3 proxy's API nor Plan's LGPL-3.0 one.");

        List<String> copyleft = entries.stream()
                .filter(name -> COPYLEFT_CLASSES.stream().anyMatch(name::startsWith))
                .toList();
        assertTrue(copyleft.isEmpty(),
                module + " bundles copyleft classes: " + copyleft.stream().limit(5).toList()
                        + ". §16: no LGPL artifact inside any shaded artifact. They ride"
                        + " in lib/ beside the distribution so an operator can replace"
                        + " them, which is what satisfies the relink requirement.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector-velocity", "connector-plan"})
    @DisplayName("the licence files ship inside the jar, because the jar is the artifact")
    void licenceFilesShipInside(String module) throws IOException {
        // §16: LICENSE and a generated NOTICE plus inventory in every
        // distributed artifact. For a service that means files beside the
        // binary; for a plugin there is no beside.
        Set<String> entries = Set.copyOf(entriesOf(jarFor(module)));
        for (String required : List.of("LICENSE", "NOTICE", "THIRD-PARTY.txt")) {
            assertTrue(entries.contains("META-INF/soulbind/" + required),
                    module + "'s jar does not contain META-INF/soulbind/" + required);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector-velocity", "connector-plan"})
    @DisplayName("no foreign signature files survive the repackaging")
    void noStaleSignatures(String module) throws IOException {
        // A jar carrying another project's signature files over classes that
        // have been rewritten is at best inert and at worst a SecurityException
        // at load, which an operator sees and we do not.
        List<String> signatures = entriesOf(jarFor(module)).stream()
                .filter(name -> name.startsWith("META-INF/"))
                .filter(name -> name.endsWith(".SF") || name.endsWith(".DSA")
                        || name.endsWith(".RSA"))
                .toList();
        assertFalse(!signatures.isEmpty(),
                module + " carries signature files over rewritten classes: " + signatures);
    }
}
