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

package dev.soulbind.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.StorageBackends;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The doctor checks that have to look at the real world.
 *
 * <p>Everything the doctor did before this read the configuration and reasoned
 * about it. That misses the failures an operator actually hits, because those
 * are about the machine: a config file anybody can read, a database directory
 * the service user cannot write, a path that means something different under
 * systemd than it did in the shell where it was tested.
 *
 * <p>The worst of them is the writability check. "It runs by hand and fails
 * under systemd" is nearly always a hardened unit with {@code
 * ProtectSystem=strict} and a database path outside {@code ReadWritePaths=},
 * and it arrives as a JDBC error about a file several layers from the cause.
 */
class DoctorFilesystemTest {

    @TempDir Path tempDir;

    /**
     * A config file whose storage points at {@code where}.
     *
     * <p>Backend name and URL both come from the storage helper. Writing
     * {@code jdbc:sqlite:} here would give this test compile-time knowledge of
     * which database is in use, and the storage seam guard fired on exactly
     * that when this file was first written -- twice, since moving the parsing
     * into {@code Backend} fixed the production side and left the test naming
     * it anyway.
     */
    private Path writeConfig(Path where, String extra) throws IOException {
        Backend backend = StorageBackends.any();
        Path file = tempDir.resolve("soulbind.toml");
        Files.writeString(file, """
                [server]
                port = 7180

                [storage]
                backend = "%s"
                url = "%s"
                %s
                """.formatted(
                        StorageBackends.configNameFor(backend),
                        StorageBackends.jdbcUrlFor(backend, where),
                        extra),
                StandardCharsets.UTF_8);
        return file;
    }

    private Path writeConfig(Path where) throws IOException {
        return writeConfig(where, "");
    }

    /**
     * Skips when the configured backend keeps nothing on this machine.
     *
     * <p>The narrowing is exactly that and no more: these checks are about a
     * directory the service has to write, and a backend reached over the
     * network has none. Asked through {@link Backend} rather than by naming a
     * backend, so the question stays on the right side of the seam.
     */
    private static String urlOf(Path where) {
        return StorageBackends.jdbcUrlFor(StorageBackends.any(), where);
    }

    private static void assumeLocalFiles(Path where) {
        assumeTrue(
                StorageBackends.any().writableDirectory(urlOf(where)).isPresent(),
                "the available backend keeps no local files, so there is no directory"
                        + " for these checks to be about");
    }

    private static List<String> details(List<Doctor.Finding> findings, String check,
            Doctor.Level level) {
        return findings.stream()
                .filter(f -> f.check().equals(check) && f.level() == level)
                .map(Doctor.Finding::detail)
                .toList();
    }

    @Test
    @DisplayName("a world-readable config file is a warning, and a private one is not")
    void worldReadableConfigIsFlagged() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "no POSIX permissions on this filesystem, so there is nothing to check");

        Path config = writeConfig(tempDir);

        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
        List<String> warned = details(Doctor.examine(config), "permissions", Doctor.Level.WARN);
        assertEquals(1, warned.size(),
                "a config file readable by every user on the machine was not flagged. It may"
                        + " hold the storage password, and nothing else in the system would"
                        + " ever mention it.");
        assertTrue(warned.get(0).contains("chmod"),
                "the finding does not say what to do about it: " + warned.get(0));

        // And the control. A check that fires on everything asserts nothing.
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r-----"));
        assertTrue(details(Doctor.examine(config), "permissions", Doctor.Level.WARN).isEmpty(),
                "a correctly-permissioned config file was flagged anyway");
    }

    @Test
    @DisplayName("a database directory that does not exist fails, naming the directory")
    void missingDatabaseDirectoryFails() throws IOException {
        assumeLocalFiles(tempDir.resolve("nowhere"));
        Path config = writeConfig(tempDir.resolve("nowhere"));

        List<String> failed = details(Doctor.examine(config), "storage", Doctor.Level.FAIL);
        assertEquals(1, failed.size(),
                "core would fail at start-up creating the database and the doctor said"
                        + " nothing, which is the one job it has");
        assertTrue(failed.get(0).contains("nowhere"),
                "the finding does not name the directory: " + failed.get(0));
    }

    @Test
    @DisplayName("a database directory the user cannot write fails, and says why systemd")
    void unwritableDatabaseDirectoryFails() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "no POSIX permissions on this filesystem");
        assumeTrue(!"root".equals(System.getProperty("user.name")),
                "root can write anything, so an unwritable directory cannot be simulated");

        Path locked = Files.createDirectory(tempDir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeLocalFiles(locked);
            Path config = writeConfig(locked);

            List<String> failed = details(Doctor.examine(config), "storage", Doctor.Level.FAIL);
            assertEquals(1, failed.size(),
                    "an unwritable database directory was not reported. This is the most"
                            + " common cause of 'it works by hand and fails under systemd'.");
            assertTrue(failed.get(0).contains("ReadWritePaths"),
                    "the finding does not mention the unit setting that usually causes it,"
                            + " so an operator reading it still has to go and find out: "
                            + failed.get(0));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    @DisplayName("a relative database path warns that systemd will resolve it elsewhere")
    void relativeDatabasePathWarns() throws IOException {
        assumeLocalFiles(tempDir);
        Path config = writeConfig(Path.of("a-relative-directory"));

        List<String> warned = details(Doctor.examine(config), "storage", Doctor.Level.WARN);
        assertTrue(warned.stream().anyMatch(d -> d.contains("relative")),
                "a relative database path was not flagged. It resolves against the working"
                        + " directory, which under systemd is not the one the operator tested"
                        + " in -- so core comes up with an empty database and no error."
                        + " Found: " + warned);
    }

    @Test
    @DisplayName("a setting that does nothing is called out rather than ignored")
    void unusedSettingIsFlagged() throws IOException {
        // Everything works. The finding is here because a setting that appears
        // present and has no effect is how somebody loses an afternoon.
        assumeTrue(!StorageBackends.any().usesCredentials(),
                "the available backend uses credentials, so storage.user is not an unused"
                        + " setting on it -- this check is about one that is");
        Path config = writeConfig(tempDir, "user = \"soulbind\"");

        List<String> warned = details(Doctor.examine(config), "storage", Doctor.Level.WARN);
        assertTrue(warned.stream().anyMatch(d -> d.contains("storage.user")),
                "storage.user is set, the configured backend ignores it, and nothing"
                        + " said so. Found: "
                        + warned);
        assertTrue(warned.stream().anyMatch(d -> d.contains("storage.backend")),
                "the finding does not point at the likely mistake -- meaning to use a"
                        + " different backend and changing one line of two: " + warned);
    }

    @Test
    @DisplayName("a healthy configuration produces no failures")
    void healthyConfigurationPasses() throws IOException {
        // The control for all of the above. Every check here fires on a
        // specific defect; one that fired on a correct installation would make
        // the doctor useless in the way that matters -- ignored.
        Path config = writeConfig(tempDir);

        List<Doctor.Finding> findings = Doctor.examine(config);
        List<Doctor.Finding> failures = findings.stream()
                .filter(f -> f.level() == Doctor.Level.FAIL)
                .toList();
        assertTrue(failures.isEmpty(),
                "a healthy configuration produced failures: " + failures);
    }
}
