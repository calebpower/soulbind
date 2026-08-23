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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What must be true of a distribution archive, read from the archive itself.
 *
 * <p>{@link ServiceDistGuardTest} reads {@code build/install}, which is the
 * unpacked tree and the thing the battery runs. Correct, and not the same
 * artifact: an operator downloads the {@code .tar.gz}. Nothing had ever opened
 * one, and the gap had already cost something — {@code connector-discord}'s
 * archives carried a top-level directory named, character for character,
 * <code>${project.name}-${project.version}</code>, with the scripted driver
 * inside it rather than in the distribution's {@code bin/}. Kotlin's escape for
 * a literal dollar had been used where interpolation was meant.
 *
 * <p>So the general assertion here is not "the scripted driver is in the right
 * place". It is that <em>every</em> path lies under the one expected root, and
 * that no path contains an uninterpolated template. The specific bug is an
 * instance of the class; a guard written only for the instance would have let
 * the next one through, which is the lesson {@code HarnessPinsGuardTest} and
 * {@code ActionPinGuardTest} already carry.
 */
class DistributionArchiveGuardTest {

    /**
     * A path fragment that means a template was never expanded.
     *
     * <p>Deliberately the two-character opener rather than the exact string
     * that went wrong. {@code ${project.version}} would have caught the bug
     * that prompted this and nothing else.
     */
    private static final String UNEXPANDED = "${";

    /** The launcher every service distribution ships, beyond the module's own. */
    private static final List<String> EXPECTED_SCRIPTS = List.of("scripted-driver");

    private static String root(String module) {
        return module + "-" + SourceTree.version();
    }

    /** The archive this build produced, named rather than discovered. */
    private static Path archive(String module, String extension) throws IOException {
        Path dir = SourceTree.repoRoot().resolve(module).resolve("build/distributions");
        assertTrue(Files.isDirectory(dir),
                module + " has no build/distributions; the guards' test task is supposed to"
                        + " depend on its distTar and distZip, so this means that wiring is"
                        + " gone");

        Path file = dir.resolve(root(module) + "." + extension);
        if (!Files.isRegularFile(file)) {
            try (var files = Files.list(dir)) {
                throw new AssertionError(
                        "this build produced no " + file.getFileName() + ". What is in " + dir
                                + ": " + files.map(p -> p.getFileName().toString()).sorted()
                                .toList()
                                + ". An archive there under a DIFFERENT version is left over"
                                + " from an earlier build and is deliberately not accepted as"
                                + " a substitute.");
            }
        }
        return file;
    }

    private static List<String> zipEntries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                names.add(entry.getName());
            }
        }
        return names;
    }

    /**
     * Entry names from a gzipped tar, parsed here rather than with a library.
     *
     * <p>The JDK reads zip and gzip but not tar, and the alternative was a
     * compile dependency on commons-compress for one guard. The format is a
     * 512-byte header per entry — name at offset 0, size as octal ASCII at 124,
     * an optional {@code ustar} prefix at 345 — followed by the content rounded
     * up to a block, and two zero blocks at the end.
     */
    private static List<String> tarEntries(Path tarGz) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(tarGz))) {
            byte[] header = new byte[512];
            while (true) {
                if (in.readNBytes(header, 0, 512) != 512) {
                    break;
                }
                String name = cString(header, 0, 100);
                if (name.isEmpty()) {
                    break; // the terminating zero block
                }
                String prefix = cString(header, 345, 155);
                names.add(prefix.isEmpty() ? name : prefix + "/" + name);

                String octal = cString(header, 124, 12).trim();
                long size = octal.isEmpty() ? 0L : Long.parseLong(octal, 8);
                long skip = (size + 511L) / 512L * 512L;
                if (in.skip(skip) != skip && skip > 0) {
                    // A truncated archive would otherwise be read as a short
                    // entry list and pass every assertion below.
                    throw new IOException("truncated tar: could not skip " + skip
                            + " bytes of content for " + name);
                }
            }
        }
        assertFalse(names.isEmpty(), tarGz.getFileName() + " parsed to zero entries, so every"
                + " assertion about it would be vacuous. Either the archive is empty or this"
                + " parser stopped understanding the format.");
        return names;
    }

    private static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static List<String> entriesOf(String module, String extension) throws IOException {
        Path file = archive(module, extension);
        return extension.equals("zip") ? zipEntries(file) : tarEntries(file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "connector-discord"})
    @DisplayName("every path in both archives lies under the one expected root")
    void everythingIsUnderOneRoot(String module) throws IOException {
        for (String extension : List.of("tar.gz", "zip")) {
            List<String> entries = entriesOf(module, extension);
            String expected = root(module) + "/";

            List<String> strays = entries.stream()
                    .filter(name -> !name.equals(root(module)))
                    .filter(name -> !name.startsWith(expected))
                    .toList();

            assertTrue(strays.isEmpty(),
                    module + "'s " + extension + " has paths outside " + expected + ": "
                            + strays.stream().limit(5).toList()
                            + ". An archive with two top-level directories unpacks into a mess"
                            + " beside whatever the operator was standing in, and only one of"
                            + " them is the distribution.");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "connector-discord"})
    @DisplayName("no path carries a template that was never expanded")
    void nothingIsUninterpolated(String module) throws IOException {
        for (String extension : List.of("tar.gz", "zip")) {
            List<String> unexpanded = entriesOf(module, extension).stream()
                    .filter(name -> name.contains(UNEXPANDED))
                    .toList();

            assertTrue(unexpanded.isEmpty(),
                    module + "'s " + extension + " contains a path with an unexpanded template: "
                            + unexpanded.stream().limit(5).toList()
                            + ". In a Kotlin build script ${'$'} is the escape for a LITERAL"
                            + " dollar, so a path written that way never interpolates and the"
                            + " archive ships the source text as a directory name.");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "connector-discord"})
    @DisplayName("the launcher an operator runs is in bin/, in both archives")
    void theLauncherIsWhereItBelongs(String module) throws IOException {
        for (String extension : List.of("tar.gz", "zip")) {
            List<String> entries = entriesOf(module, extension);
            String expected = root(module) + "/bin/" + module;
            assertTrue(entries.contains(expected),
                    module + "'s " + extension + " has no " + expected + ". docs/install.md"
                            + " tells an operator to unpack this and run it. Found under bin/: "
                            + entries.stream().filter(n -> n.contains("/bin/")).limit(8)
                            .toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector-discord"})
    @DisplayName("a script added to the distribution lands in bin/, not beside it")
    void addedScriptsLandInBin(String module) throws IOException {
        // The instance that prompted this class. Kept alongside the general
        // assertions rather than instead of them: this one names the file, so
        // a failure says which script went missing rather than only that a
        // path looked wrong.
        for (String extension : List.of("tar.gz", "zip")) {
            List<String> entries = entriesOf(module, extension);
            for (String script : EXPECTED_SCRIPTS) {
                String expected = root(module) + "/bin/" + script;
                assertTrue(entries.contains(expected),
                        module + "'s " + extension + " has no " + expected + ". It is added by"
                                + " the module's own build script, which has to place it under"
                                + " the archive's top-level directory by hand -- installDist"
                                + " needs no such prefix, so the two are easy to get out of"
                                + " step. Found under bin/: "
                                + entries.stream().filter(n -> n.contains("/bin/")).limit(8)
                                .toList());
            }
        }
    }
}
