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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code plan-check.sh}'s readers against a real recorded Plan response.
 *
 * <p><b>Why this exists.</b> That script's JSON walker shipped unable to match
 * any real response: it looked for a node carrying both {@code name} and
 * {@code value}, and Plan nests the name under {@code description} with
 * {@code value} as its sibling. The Plan stage went red on a run in which all
 * six providers had rendered correctly. The unit fixture written alongside the
 * walker had been imagined from the same wrong picture, so the two agreed with
 * each other and neither was checked against Plan. {@code docs/DECISIONS.md}
 * 8.19.
 *
 * <p>A guard cannot re-run the battery. It can do the one thing that would have
 * caught this in a second on any machine: take the reader as it is actually
 * written in the script, point it at a response Plan actually sent, and require
 * the values to come back.
 *
 * <p><b>The readers are extracted, not reimplemented.</b> A copy of the walker
 * living here would be a second definition that passes while the shipped one
 * fails — which is precisely the failure being guarded against. The script is
 * the source, and this test reads it.
 *
 * <p><b>This test needs {@code python3}, and the build container does not have
 * one.</b> The workstation does; the guest host does; the digest-pinned Temurin
 * image the build verb runs inside does not, and every probe there exits 127.
 * An earlier version of this comment claimed the opposite and claimed a skip
 * that was never implemented — so the guard failed the whole battery at the
 * build stage, before a single tier had run. {@code docs/DECISIONS.md} 8.3 is
 * the same defect: a guard documenting a rule it did not implement.
 *
 * <p>So it skips where the interpreter is absent, and the skip is narrow: it
 * covers exactly "this environment cannot execute the shipped probes", nothing
 * about whether they are correct. The property is not left unasserted there —
 * {@code harness/fullstack/mutation/run.sh} exercises the same probes on the
 * guest, where {@code python3} exists, against thirteen mutants and a control.
 * That is a stronger check than this one; this is the cheap version that runs
 * in a second on a workstation.
 */
class PlanCheckWalkerGuardTest {

    private static final Path CHECK =
            SourceTree.repoRoot().resolve("harness/fullstack/plan-check.sh");
    private static final Path FIXTURES =
            SourceTree.repoRoot().resolve("harness/fullstack/fixtures");

    /**
     * What the recorded player response actually contains.
     *
     * <p>Every one of these was read out of the capture, not chosen. If a future
     * Plan changes shape, re-record the fixture — do not adjust these.
     */
    private static final Map<String, String> PLAYER_VALUES = Map.of(
            "linked", "True",
            "linkStatus", "linked",
            "platforms", "game, harness",
            "proof", "link-code",
            "linkedSince", "1787201694000");

    @TempDir Path tempDir;

    /**
     * Whether the shipped probes can be executed at all here.
     *
     * <p>Probed rather than assumed. Checking a path or an environment variable
     * would answer a different question — whether something is installed where
     * this expects it — and the question that matters is whether running it
     * works.
     */
    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @BeforeEach
    void requireAnInterpreter() {
        assumeTrue(pythonAvailable(),
                "python3 is not executable here, so the shipped probes cannot be run. This"
                        + " is the build container, not a verdict on plan-check.sh; the"
                        + " mutation battery covers the same probes on the guest.");
    }

    @Test
    @DisplayName("the shipped walker reads every provider out of a real Plan response")
    void walkerReadsRealResponse() throws Exception {
        String walker = extract("value_of");
        for (Map.Entry<String, String> expected : PLAYER_VALUES.entrySet()) {
            String got = run(walker, expected.getKey(), FIXTURES.resolve("plan-player.json"));
            assertEquals(expected.getValue(), got,
                    () -> "the walker in plan-check.sh returned '" + "" + "' for provider '"
                            + expected.getKey() + "'. Plan nests the provider name under"
                            + " `description`; a walker expecting `name` and `value` on one node"
                            + " matches nothing Plan sends, and the stage then fails on a healthy"
                            + " system. DECISIONS 8.19.");
        }
    }

    @Test
    @DisplayName("the walker reads the server-wide providers, including Plan's own aggregate")
    void walkerReadsServerResponse() throws Exception {
        String walker = extract("value_of");
        Path fixture = FIXTURES.resolve("plan-server.json");
        for (String provider : List.of("linkedPlayers", "unlinkedPlayers", "unknownPlayers")) {
            assertEquals("0", run(walker, provider, fixture),
                    provider + " is not readable from the recorded server response");
        }
        // Not one of this connector's providers: Plan aggregates the per-player
        // boolean across its whole player table. It is the only server-side
        // value in the capture that is non-zero, and therefore the only one the
        // stage can assert on that distinguishes working from broken.
        assertEquals("50%", run(walker, "linked_aggregate", fixture),
                "Plan's own aggregate is not readable, so the server page has nothing"
                        + " asserted on it that could fail");
    }

    @Test
    @DisplayName("an absent provider and a null value are both refused")
    void absentAndNullAreRefused() throws Exception {
        String walker = extract("value_of");
        assertEquals("", run(walker, "noSuchProvider", FIXTURES.resolve("plan-player.json")),
                "a provider that is not there produced a value");

        // A JSON null must read as absent, not as the string "None". `proof` is
        // the one assertion in the stage that accepts any non-empty string, so
        // when the walker printed Python's None it accepted a response with no
        // proof method in it.
        Path nulled = tempDir.resolve("nulled.json");
        Files.writeString(nulled, Files.readString(FIXTURES.resolve("plan-player.json"),
                        StandardCharsets.UTF_8)
                .replace("\"value\": \"link-code\"", "\"value\": null"),
                StandardCharsets.UTF_8);
        assertNotEquals("None", run(walker, "proof", nulled),
                "a null provider value read back as the string \"None\", which every"
                        + " non-empty test in the stage accepts");
        assertEquals("", run(walker, "proof", nulled),
                "a null provider value must read as absent");
    }

    @Test
    @DisplayName("the table probe refuses a table with no columns, and text that merely names one")
    void tableProbeIsStructural() throws Exception {
        String probe = extract("table_present");
        assertEquals(0, exitOf(probe, "unlinkedTable", FIXTURES.resolve("plan-server.json")),
                "the recorded server response does contain unlinkedTable");

        // The version this replaced was `grep -q unlinkedTable`, which a plain
        // text file containing the word satisfied.
        Path prose = tempDir.resolve("prose.txt");
        Files.writeString(prose, "linkedPlayers unlinkedPlayers unknownPlayers unlinkedTable\n",
                StandardCharsets.UTF_8);
        assertNotEquals(0, exitOf(probe, "unlinkedTable", prose),
                "a plain-text file naming the table satisfied the table probe");

        Path noColumns = tempDir.resolve("nocols.json");
        Files.writeString(noColumns, Files.readString(FIXTURES.resolve("plan-server.json"),
                        StandardCharsets.UTF_8)
                .replace("\"Player\"", ""),
                StandardCharsets.UTF_8);
        assertNotEquals(0, exitOf(probe, "unlinkedTable", noColumns),
                "a table with no columns satisfied the table probe");
    }

    // --- the mutation catalogue ------------------------------------------------

    @Test
    @DisplayName("every catalogued mutant is implemented, and the catalogue is not empty")
    void mutationCatalogueIsWhole() throws IOException {
        Path catalogue = SourceTree.repoRoot()
                .resolve("harness/fullstack/mutation/mutants.txt");
        Path mutator = SourceTree.repoRoot()
                .resolve("harness/fullstack/mutation/mutate.py");
        String implemented = Files.readString(mutator, StandardCharsets.UTF_8);

        List<String> names = Files.readAllLines(catalogue, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.split("\\s+")[0])
                .toList();

        // The catalogue emptying out is the failure mode worth guarding. Every
        // claim the runner makes is of the form "each mutant died", and an
        // empty list satisfies it -- so the runner checks that too, at run time.
        // This is the cheap version that runs on every build.
        assertTrue(names.size() >= 12,
                () -> "the mutation catalogue lists " + names.size() + " mutants; it had 13"
                        + " when written. A shrinking catalogue is how a check quietly stops"
                        + " being tested.");

        for (String name : names) {
            assertTrue(implemented.contains("\"" + name + "\""),
                    () -> "mutants.txt lists '" + name + "' and mutate.py does not implement"
                            + " it. The runner exits on an unknown mutant rather than"
                            + " skipping it, so this is a broken battery, not a silent one"
                            + " -- but it is cheaper to learn here.");
        }
    }

    @Test
    @DisplayName("the mutation runner still requires a passing control and a non-empty run")
    void mutationRunnerKeepsItsOwnGuards() throws IOException {
        String runner = Files.readString(
                SourceTree.repoRoot().resolve("harness/fullstack/mutation/run.sh"),
                StandardCharsets.UTF_8);

        // Without the control, a check that rejects EVERYTHING scores a perfect
        // kill rate. That is not a hypothetical: the walker this battery was
        // built against could not match any real Plan response, so it failed on
        // the recorded evidence too.
        assertTrue(runner.contains("CONTROL FAILED"),
                "the runner no longer fails when the unmutated response is rejected");
        assertTrue(runner.contains("no mutants ran"),
                "the runner no longer fails when it executes zero mutants");
    }

    // --- extracting the readers from the script --------------------------------

    /**
     * Lifts a shell function body out of {@code plan-check.sh} verbatim.
     *
     * <p>Deliberately crude — brace matching on a hand-written script would be
     * more machinery than the job needs. The functions this reads are top-level
     * and terminated by a line containing only {@code }}, and the extraction
     * asserts it found something that runs python, so a rename or a reformat
     * fails this test rather than silently testing nothing.
     */
    private static String extract(String function) throws IOException {
        String script = Files.readString(CHECK, StandardCharsets.UTF_8);
        int start = script.indexOf("\n" + function + "() {");
        assertTrue(start >= 0,
                () -> "plan-check.sh no longer defines " + function + "(). If it was renamed,"
                        + " rename it here too; if it was deleted, its assertions went with it.");
        int end = script.indexOf("\n}\n", start);
        assertTrue(end > start, () -> "could not find the end of " + function + "()");
        String body = script.substring(start, end);
        assertTrue(body.contains("python3"),
                () -> function + "() no longer runs python3; this guard extracts and executes"
                        + " it, so it is testing something other than what ships");
        return body + "\n}\n";
    }

    private String run(String function, String provider, Path json) throws Exception {
        Process p = shell(function, "value_of", provider, json);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out.strip();
    }

    private int exitOf(String function, String name, Path json) throws Exception {
        Process p = shell(function, "table_present", name, json);
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private Process shell(String body, String call, String arg, Path json) throws Exception {
        Path script = tempDir.resolve("probe.sh");
        Files.writeString(script,
                "#!/bin/sh\n" + body + "\n" + call + " \"$1\" \"$2\"\n",
                StandardCharsets.UTF_8);
        return new ProcessBuilder("sh", script.toString(), arg, json.toString())
                .redirectErrorStream(false)
                .start();
    }
}
