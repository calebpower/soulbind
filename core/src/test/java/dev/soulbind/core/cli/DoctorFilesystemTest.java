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

    private Path writeConfig(String storage) throws IOException {
        Path file = tempDir.resolve("soulbind.toml");
        Files.writeString(file, """
                [server]
                port = 7180

                [storage]
                """ + storage + "\n", StandardCharsets.UTF_8);
        return file;
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

        Path config = writeConfig(
                "backend = \"sqlite\"\nurl = \"jdbc:sqlite:" + tempDir + "/s.db\"");

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
        Path config = writeConfig("backend = \"sqlite\"\nurl = \"jdbc:sqlite:"
                + tempDir.resolve("nowhere/soulbind.db") + "\"");

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
            Path config = writeConfig("backend = \"sqlite\"\nurl = \"jdbc:sqlite:"
                    + locked.resolve("soulbind.db") + "\"");

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
        Path config = writeConfig("backend = \"sqlite\"\nurl = \"jdbc:sqlite:soulbind.db\"");

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
        Path config = writeConfig("backend = \"sqlite\"\nurl = \"jdbc:sqlite:"
                + tempDir + "/s.db\"\nuser = \"soulbind\"");

        List<String> warned = details(Doctor.examine(config), "storage", Doctor.Level.WARN);
        assertTrue(warned.stream().anyMatch(d -> d.contains("storage.user")),
                "storage.user is set, SQLite ignores it, and nothing said so. Found: "
                        + warned);
        assertTrue(warned.stream().anyMatch(d -> d.contains("sqlite")),
                "the finding does not point at the likely mistake -- meaning to use MariaDB"
                        + " and leaving the backend on sqlite: " + warned);
    }

    @Test
    @DisplayName("a healthy configuration produces no failures")
    void healthyConfigurationPasses() throws IOException {
        // The control for all of the above. Every check here fires on a
        // specific defect; one that fired on a correct installation would make
        // the doctor useless in the way that matters -- ignored.
        Path config = writeConfig(
                "backend = \"sqlite\"\nurl = \"jdbc:sqlite:" + tempDir + "/soulbind.db\"");

        List<Doctor.Finding> findings = Doctor.examine(config);
        List<Doctor.Finding> failures = findings.stream()
                .filter(f -> f.level() == Doctor.Level.FAIL)
                .toList();
        assertTrue(failures.isEmpty(),
                "a healthy configuration produced failures: " + failures);
    }
}
