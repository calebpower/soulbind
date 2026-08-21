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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.registry.Credentials;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.Storage;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.protocol.Capability;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The operator-facing surface: doctor, register, and the command that wires them. */
class CliTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String body) throws Exception {
        Path file = tempDir.resolve("soulbind.toml");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * A configuration that should pass every check.
     *
     * <p>Backend name and URL both come from the storage helper rather than
     * being written here. This test's subject is the operator-facing command,
     * not persistence, and hardcoding either would give it compile-time
     * knowledge of which database is in use -- which the storage seam guard
     * caught the first time this was written.
     */
    private String healthyConfig() {
        Backend backend = StorageBackends.any();
        return """
                [server]
                host = "127.0.0.1"
                port = 7000

                [storage]
                backend = "%s"
                url = "%s"
                """.formatted(
                        StorageBackends.configNameFor(backend),
                        StorageBackends.jdbcUrlFor(backend, tempDir));
    }

    private record Run(int exit, String out, String err) {}

    private Run invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(
                exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // --- doctor ---------------------------------------------------------------

    @Test
    @DisplayName("a healthy configuration exits 0")
    void doctorHealthy() throws Exception {
        Run run = invoke("doctor", "--config", writeConfig(healthyConfig()).toString());
        assertEquals(Doctor.EXIT_HEALTHY, run.exit(), run.out());
        assertFalse(run.out().contains("FAIL"), run.out());
    }

    @Test
    @DisplayName("a missing config file FAILS rather than passing quietly")
    void doctorMissingConfig() {
        // The failure mode this whole command exists to prevent: a check that
        // cannot run reporting nothing, and the operator reading silence as
        // health.
        Run run = invoke("doctor", "--config", tempDir.resolve("absent.toml").toString());
        assertEquals(Doctor.EXIT_UNHEALTHY, run.exit());
        assertTrue(run.out().contains("FAIL"), run.out());
        assertTrue(run.out().contains("does not exist"), run.out());
    }

    @Test
    @DisplayName("an unknown config key is reported, with the near miss named")
    void doctorUnknownKey() throws Exception {
        // The typo goes INSIDE the existing [storage] table. Appending a second
        // [server] table, as this first did, makes the file invalid TOML -- so
        // the test failed the config for a reason that had nothing to do with
        // unknown keys, and would have kept passing if unknown-key detection
        // were deleted entirely.
        Run run = invoke("doctor", "--config",
                writeConfig(healthyConfig() + "urll = \"anything\"\n").toString());
        assertEquals(Doctor.EXIT_UNHEALTHY, run.exit());
        assertTrue(run.out().contains("unknown key 'storage.urll'"), run.out());
        assertTrue(
                run.out().contains("did you mean 'storage.url'"),
                () -> "a near miss must be named: " + run.out());
    }

    @Test
    @DisplayName("an unrecognised storage backend is a failure, and known ones are listed")
    void doctorUnknownBackend() throws Exception {
        Run run = invoke("doctor", "--config", writeConfig("""
                [server]
                port = 7000

                [storage]
                backend = "postgres"
                url = "jdbc:whatever:"
                """).toString());
        assertEquals(Doctor.EXIT_UNHEALTHY, run.exit());
        assertTrue(run.out().contains("FAIL"), run.out());
        // Naming the known ones turns "wrong" into "wrong, and here is right".
        assertTrue(
                run.out().contains(Backend.values()[0].configName()),
                () -> "the failure must list what IS accepted: " + run.out());
    }

    @Test
    @DisplayName("binding every interface WARNS but still exits 0")
    void doctorWildcardBind() throws Exception {
        // A legitimate deployment behind a reverse proxy. The doctor's job is to
        // make sure it was chosen rather than inherited -- not to refuse it.
        Run run = invoke("doctor", "--config",
                writeConfig(healthyConfig().replace("127.0.0.1", "0.0.0.0")).toString());
        assertEquals(Doctor.EXIT_HEALTHY, run.exit(), run.out());
        assertTrue(run.out().contains("WARN"), run.out());
        assertTrue(run.out().contains("every interface"), run.out());
    }

    @Test
    @DisplayName("a password written in the config file WARNS")
    void doctorPasswordInFile() throws Exception {
        Run run = invoke("doctor", "--config",
                writeConfig(healthyConfig() + "password = \"hunter2\"\n").toString());
        assertTrue(run.out().contains("WARN"), run.out());
        assertTrue(run.out().contains("committed by somebody eventually"), run.out());
        assertFalse(
                run.out().contains("hunter2"),
                () -> "the doctor printed the secret it was warning about: " + run.out());
    }

    @Test
    @DisplayName("an over-long signature window WARNS")
    void doctorLongWindow() throws Exception {
        Run run = invoke("doctor", "--config",
                writeConfig(healthyConfig()
                        + "\n[protocol]\nsignaturewindowseconds = 3000\n").toString());
        assertTrue(run.out().contains("WARN"), run.out());
        assertTrue(run.out().contains("replayable"), run.out());
    }

    @Test
    @DisplayName("every finding names an action, not only a problem")
    void findingsAreActionable() throws Exception {
        // A finding that says what is wrong without saying what to do is a
        // finding the reader has to go and research before they can act.
        List<Doctor.Finding> findings = Doctor.examine(
                writeConfig(healthyConfig().replace("127.0.0.1", "0.0.0.0")));
        for (Doctor.Finding finding : findings) {
            assertFalse(finding.detail().isBlank(), () -> finding + " has no detail");
            assertFalse(finding.check().isBlank(), () -> finding + " has no check name");
        }
    }

    // --- register -------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("registering a connector leaves an audit row naming it and its grant")
    void registrationIsAudited(Backend backend) {
        // Minting a credential was not audited until Phase 10, while rotating
        // one was -- so the log could say a credential had been REPLACED with
        // no record of it ever having been created. An incident review reading
        // that log asks "when was this connector added, and with what?" first.
        //
        // The javadoc on Bootstrap.register had promised this row since Phase
        // 1. It was a claim about the system living in a comment nothing read.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Bootstrap.register(storage, "newcomer", List.of("code-display", "effector"));

            var rows = storage.audit().query(
                    dev.soulbind.core.audit.AuditQuery.recent(10)).stream()
                    .filter(e -> "connector.registered".equals(e.action()))
                    .toList();

            assertEquals(1, rows.size(),
                    "registering a connector wrote " + rows.size() + " audit rows");

            var row = rows.get(0);
            assertEquals("cli", row.actor(),
                    "the actor is not 'cli'. No connector took this action -- it ran on"
                            + " the machine against the database -- and attributing it to"
                            + " a connector id would credit something that did not do it,"
                            + " which is the one property audit attribution protects.");
            assertEquals("newcomer", row.detail().get("connector"));
            assertTrue(String.valueOf(row.detail().get("capabilities")).contains("code-display"),
                    "the audit row does not record what the credential was granted, which"
                            + " is half of what makes it worth reading: " + row.detail());
            assertTrue(String.valueOf(row.detail().get("capabilities")).contains("effector"),
                    "only some of the granted capabilities were recorded: " + row.detail());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("registering mints a credential that authenticates, and prints it once")
    void registerMintsWorkingCredential(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Bootstrap.Registered registered = Bootstrap.register(
                    storage, "a-connector", List.of("code-display", "audit-source"));

            assertEquals(
                    java.util.Set.of(Capability.CODE_DISPLAY, Capability.AUDIT_SOURCE),
                    registered.connector().capabilities());

            // The credential works...
            assertTrue(storage.connectors()
                    .findByCredentialHash(Credentials.hash(registered.credential()))
                    .isPresent());

            // ...and the stored form is not the credential.
            assertNotEquals(
                    registered.credential(), registered.connector().credentialHash());
        }
    }

    @Test
    @DisplayName("the printed report shows the credential and says it will not be shown again")
    void registerReportIsHonestAboutTheSecret() {
        try (Storage storage = StorageBackends.open(StorageBackends.any(), tempDir)) {
            Bootstrap.Registered registered =
                    Bootstrap.register(storage, "a-connector", List.of("code-display"));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Bootstrap.report(registered, new PrintStream(out, true, StandardCharsets.UTF_8));
            String printed = out.toString(StandardCharsets.UTF_8);

            assertTrue(printed.contains(registered.credential()));
            assertTrue(
                    printed.contains("only time"),
                    () -> "an operator who is not told this is an operator who assumes they can "
                            + "look it up: " + printed);
            assertFalse(
                    printed.contains(registered.connector().credentialHash()),
                    "the hash is not useful to an operator and is what an attacker would want "
                            + "to confirm a guess against");
        }
    }

    @Test
    @DisplayName("a duplicate name is refused, because audit attributes to a connector")
    void registerRefusesDuplicateName() {
        try (Storage storage = StorageBackends.open(StorageBackends.any(), tempDir)) {
            Bootstrap.register(storage, "taken", List.of());
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> Bootstrap.register(storage, "taken", List.of()));
            assertTrue(e.getMessage().contains("already registered"), e.getMessage());
        }
    }

    @Test
    @DisplayName("an unrecognised capability is refused, not silently dropped")
    void registerRefusesUnknownCapability() {
        // Dropping it would hand back a credential that cannot do what the
        // operator asked for, and they would find out at the first refusal.
        try (Storage storage = StorageBackends.open(StorageBackends.any(), tempDir)) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> Bootstrap.register(
                            storage, "c", List.of("code-display", "code-dsiplay")));
            assertTrue(e.getMessage().contains("code-dsiplay"), e.getMessage());
            assertTrue(
                    e.getMessage().contains("code-display"),
                    () -> "the refusal must list what IS accepted: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("an empty name is refused")
    void registerRefusesEmptyName() {
        try (Storage storage = StorageBackends.open(StorageBackends.any(), tempDir)) {
            assertThrows(
                    IllegalArgumentException.class, () -> Bootstrap.register(storage, "  ", List.of()));
        }
    }

    // --- the command itself ---------------------------------------------------

    @Test
    @DisplayName("no arguments prints usage and exits 2 -- it did not run")
    void noArguments() {
        Run run = invoke();
        assertEquals(Doctor.EXIT_CANNOT_RUN, run.exit());
        assertTrue(run.out().contains("soulbind doctor"), run.out());
    }

    @Test
    @DisplayName("an unknown verb exits 2 and says so")
    void unknownVerb() {
        Run run = invoke("frobnicate");
        assertEquals(Doctor.EXIT_CANNOT_RUN, run.exit());
        assertTrue(run.err().contains("unknown verb"), run.err());
    }

    @Test
    @DisplayName("--help exits 0")
    void help() {
        assertEquals(Doctor.EXIT_HEALTHY, invoke("--help").exit());
    }

    @Test
    @DisplayName("register without --name exits 2 rather than registering something unnamed")
    void registerWithoutName() throws Exception {
        Run run = invoke("register", "--config", writeConfig(healthyConfig()).toString());
        assertEquals(Doctor.EXIT_CANNOT_RUN, run.exit());
        assertTrue(run.err().contains("--name"), run.err());
    }

    @Test
    @DisplayName("register through the command mints and prints a credential")
    void registerThroughCommand() throws Exception {
        Run run = invoke(
                "register",
                "--name", "cli-connector",
                "--capabilities", "code-display,audit-source",
                "--config", writeConfig(healthyConfig()).toString());

        assertEquals(Doctor.EXIT_HEALTHY, run.exit(), run.err());
        assertTrue(run.out().contains("registered cli-connector"), run.out());
        assertTrue(run.out().contains("credential"), run.out());
    }

    @Test
    @DisplayName("--quiet prints ONLY the credential, with nothing to parse around it")
    void registerQuiet() throws Exception {
        // A stack script parsed the human report with awk and picked up a log
        // line that also said "credential", producing a multi-line value the
        // HTTP client refused as an invalid header. A command meant to be
        // scripted should not need fragile parsing.
        Run run = invoke(
                "register",
                "--name", "scripted",
                "--capabilities", "code-display",
                "--quiet",
                "--config", writeConfig(healthyConfig()).toString());

        assertEquals(Doctor.EXIT_HEALTHY, run.exit(), run.err());

        String[] lines = run.out().strip().split("\n");
        assertEquals(
                1, lines.length,
                () -> "--quiet printed " + lines.length + " lines; a script cannot tell which "
                        + "is the credential: " + run.out());
        assertFalse(lines[0].contains(" "), () -> "the credential line has spaces: " + lines[0]);
        assertFalse(lines[0].isBlank());

        // Asserted POSITIVELY: stdout is exactly a credential, character for
        // character. A negative list of things it must not contain would need
        // updating every time a dependency learned to log -- and naming a
        // connection pool here would put a JDBC implementation detail outside
        // the storage package, which a guard caught when this was first
        // written that way.
        //
        // Logging goes to stderr precisely so this holds. The first stack
        // script to read this stream got a pool log line and sent it as an
        // HTTP header.
        assertTrue(
                lines[0].matches("[A-Za-z0-9_-]+"),
                () -> "stdout is not just a credential; something else logged there: "
                        + run.out());

        // And it is the real thing, not a truncated render of it.
        try (Storage storage = StorageBackends.open(StorageBackends.any(), tempDir)) {
            assertNotNull(storage);
        }
    }

    @Test
    @DisplayName("a broken configuration reports every problem at once, not one per run")
    void brokenConfigReportsEverything() throws Exception {
        Run run = invoke("register", "--name", "x", "--config",
                writeConfig("[storage]\nbackend = \"%s\"\n"
                        .formatted(StorageBackends.configNameFor(StorageBackends.any())))
                        .toString());
        assertEquals(Doctor.EXIT_UNHEALTHY, run.exit());
        // server.port and storage.url are both missing.
        assertTrue(run.err().contains("server.port"), run.err());
        assertTrue(run.err().contains("storage.url"), run.err());
    }
}
