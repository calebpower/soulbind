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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the full-stack battery against claiming tiers it does not run.
 *
 * <p>The battery's own rule is that a stage cannot report success for work it
 * did not do, and the runner enforces that at run time. This enforces the
 * things run time cannot: that the stage list, the stage implementations and
 * the documented table all name the same set, and that the runner still
 * contains the check which makes a resultless stage a failure.
 *
 * <p>Why a static guard for a shell script. The session battery is expensive
 * and runs on a machine that is not this one; a stage that silently does
 * nothing would be discovered, at best, the next time somebody read the log
 * carefully. Every failure this repository has had of that shape — a task
 * reporting green having executed nothing — was found by mutation rather than
 * by a red run, so the cheap check that runs everywhere is worth having.
 */
class FullstackStagesGuardTest {

    private static final Path RUNNER =
            SourceTree.repoRoot().resolve("harness/fullstack/run.sh");
    private static final Path JOURNEYS =
            SourceTree.repoRoot().resolve("harness/fullstack/journeys.sh");
    private static final Path README =
            SourceTree.repoRoot().resolve("harness/fullstack/README.md");

    /**
     * Strips shell comments so the guard cannot match its own explanatory prose.
     *
     * <p>Not caution for its own sake. This has now happened four times in this
     * repository, twice after being fixed elsewhere in the same change — most
     * recently a javadoc warning about a tag which itself contained the tag, and
     * so satisfied the check that read for it.
     */
    private static String code(Path file) {
        StringBuilder sb = new StringBuilder();
        for (String line : SourceTree.read(file).split("\n", -1)) {
            int hash = line.indexOf('#');
            sb.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return sb.toString();
    }

    /** The value of a {@code NAME="a b c"} assignment, as a list. */
    private static List<String> shellList(Path file, String variable) {
        Matcher m = Pattern.compile("^" + variable + "=\"([^\"]*)\"", Pattern.MULTILINE)
                .matcher(code(file));
        assertTrue(
                m.find(),
                () -> variable + " is not assigned in " + file.getFileName()
                        + ", so this guard would pass having checked nothing");
        List<String> names = new ArrayList<>();
        for (String token : m.group(1).trim().split("\\s+")) {
            if (!token.isBlank()) {
                names.add(token);
            }
        }
        assertFalse(
                names.isEmpty(),
                () -> variable + " is empty; a battery that runs no stages is not a battery");
        return names;
    }

    /**
     * The detector, used by the real check AND by the must-fail fixture.
     *
     * <p>The fixture originally re-derived {@code STAGES} with its own regex and
     * re-applied its own containment test, so it proved that a private copy
     * worked and nothing about the check it is named after. Disabling the real
     * detection left all seven cases green. {@code SourceTree}'s own javadoc
     * says it: a fixture checked by a second, parallel implementation proves
     * only that the second implementation works.
     */
    private static List<String> unimplementedStages(Path runner) {
        String body = code(runner);
        List<String> missing = new ArrayList<>();
        for (String stage : shellList(runner, "STAGES")) {
            if (!body.contains("stage_" + stage + "()")) {
                missing.add(stage);
            }
        }
        return missing;
    }

    @Test
    @DisplayName("every listed stage has an implementation")
    void everyStageIsImplemented() {
        List<String> missing = unimplementedStages(RUNNER);

        assertEquals(
                List.of(),
                missing,
                "a stage named in STAGES with no stage_<name> function is a tier the battery "
                        + "reports on and never runs");
    }

    @Test
    @DisplayName("every implemented stage is listed, so none is unreachable")
    void everyImplementationIsListed() {
        List<String> listed = shellList(RUNNER, "STAGES");
        Matcher m = Pattern.compile("^\\s*stage_([A-Za-z0-9_-]+)\\s*\\(\\)", Pattern.MULTILINE)
                .matcher(code(RUNNER));

        List<String> unreachable = new ArrayList<>();
        while (m.find()) {
            if (!listed.contains(m.group(1))) {
                unreachable.add(m.group(1));
            }
        }

        assertEquals(
                List.of(),
                unreachable,
                "a stage_<name> function that STAGES does not list can never be asked for. "
                        + "Either list it or delete it -- an unreachable stage reads as coverage "
                        + "to anybody scanning the file");
    }

    @Test
    @DisplayName("every stage is documented in the README's table")
    void everyStageIsDocumented() {
        String readme = SourceTree.read(README);
        List<String> undocumented = new ArrayList<>();
        for (String stage : shellList(RUNNER, "STAGES")) {
            if (!readme.contains("| `" + stage + "` |")) {
                undocumented.add(stage);
            }
        }

        assertEquals(
                List.of(),
                undocumented,
                "the README's stage table is where somebody looks to find out what the battery "
                        + "covers. A stage missing from it is a tier nobody knows to run");
    }

    @Test
    @DisplayName("the runner still fails a stage that emits no result")
    void theResultlessStageCheckIsStillThere() {
        // The single most important line in the runner, and the one whose
        // deletion would be invisible: everything would still pass, and stages
        // that did nothing would report nothing and be counted as fine.
        String body = code(RUNNER);

        // Anchored on the fault block's OWN message, not on "HARNESS FAULT" --
        // which result_pass and result_fail each log for their own write
        // failures, so the bare string survived deleting this block entirely.
        // The inert assertion carried the message describing the important
        // property, which is how the same mistake reached the exit-status check
        // below.
        assertTrue(
                body.contains("finished without emitting a result"),
                "run.sh no longer fails a stage that finished without emitting a result. That "
                        + "check is what stops a stage reporting success for work it did not do, "
                        + "and removing it breaks nothing visible");
        assertTrue(
                body.contains("! -f \"$OUT/$requested.xml\""),
                "the resultless-stage check no longer tests for the result file, so it cannot "
                        + "detect the thing it exists to detect");
    }

    @Test
    @DisplayName("the resultless-stage check ORs its two conditions")
    void theFaultCheckOrsItsConditions() {
        // Both existing assertions only check that the two operands are present.
        // Changing `||` to `&&` leaves every guard case green and turns a stage
        // that returned 0 having emitted nothing from "HARNESS FAULT, exit 1"
        // into silence and exit 0 -- the single invariant this harness exists to
        // hold, defeated by one character.
        assertTrue(
                code(RUNNER).contains(
                        "[ -n \"$STAGE_STARTED\" ] || [ ! -f \"$OUT/$requested.xml\" ]"),
                "the resultless-stage check no longer ORs its conditions. With && it fires only "
                        + "when a stage BOTH left itself in progress AND wrote no file, which is "
                        + "not the case it was written for");
    }

    @Test
    @DisplayName("stale results are cleared before any stage runs")
    void staleResultsAreClearedUpFront() {
        // Found by probing rather than by reading: a stage that dies BEFORE
        // result_open leaves the previous run's file in place, and the
        // resultless-stage check only asks whether a result exists. A stage that
        // did nothing and returned 0 was therefore reported with last run's
        // PASS, and the run exited 0.
        //
        // out/ is the only thing reaper syncs back, so that stale file is exactly
        // what a reader sees. Evidence that outlives the run which produced it is
        // worse than none, because it looks current.
        //
        // Deleting the clearing loop restores that hole and changes nothing
        // visible on a green run, which is why it needs a guard rather than a
        // comment.
        String body = code(RUNNER);

        assertTrue(
                body.contains("rm -rf \"$OUT\""),
                "run.sh no longer clears the result directory before running stages, so a stage "
                        + "that fails early is reported with the previous run's result, a stage "
                        + "from an earlier invocation is reported alongside this run's, and a "
                        + "failed journeys stage points the reader at last run's transcript");
        // Anchored on the CALL, not on the name: "stage_$requested" also appears
        // in the pre-flight validation loop above, which runs earlier than the
        // clearing and made this comparison test the wrong pair.
        int clears = body.indexOf("rm -rf \"$OUT\"");
        int runs = body.indexOf("if \"stage_$requested\"; then");
        assertTrue(runs > 0, "the stage invocation was not found, so ordering cannot be checked");
        assertTrue(
                clears < runs,
                "the clearing loop must run BEFORE the stages, or it deletes the results they "
                        + "just wrote");
    }

    @Test
    @DisplayName("the runner's failure actually reaches its exit status")
    void failureReachesTheExitStatus() {
        // Nothing covered this. Changing `exit $failed` to `exit 0`, or the
        // fault branch's `failed=1` to `failed=0`, leaves every other assertion
        // in this class green while the runner reports success for a stage it
        // just recorded as failed -- which is the invariant defeated at the last
        // possible line.
        String body = code(RUNNER);

        assertTrue(
                body.contains("exit $failed"),
                "run.sh no longer exits with its accumulated failure status, so a recorded "
                        + "failure does not reach the caller");
        // Anchored on the fault branch specifically. Bare "failed=1" is
        // satisfied by the ordinary stage-failure branch twelve lines earlier,
        // so this assertion could not fail for the mutation its own comment
        // names: flipping the FAULT branch to failed=0 left all 44 guard cases
        // green while the runner recorded a failure and exited 0.
        assertTrue(
                body.contains("nothing it claims can be trusted\"\n        failed=1"),
                "the resultless-stage fault no longer sets the failure flag, so a stage that "
                        + "emitted nothing is recorded as failed and the run still exits 0");
    }

    @Test
    @DisplayName("the runner's state directory is gitignored")
    void theRunDirectoryIsIgnored() {
        // A near-miss worth a guard. The runner creates `run-<db>` under
        // harness/fullstack when REAPER_STATE is unset, and .gitignore named
        // `run/` -- the instance, not the class. `git status` duly offered 182 MB
        // of generated Paper world as untracked and committable.
        //
        // This is the third time in this repository that a rule written for one
        // filename missed its successor; the .gitignore comment for pins.env
        // records the first two.
        String ignore = SourceTree.read(SourceTree.repoRoot().resolve(".gitignore"));
        String runner = code(RUNNER);

        assertTrue(
                runner.contains("run-$DB"),
                "the runner no longer names its state directory run-$DB, so the check below is "
                        + "guarding a path that is not used any more");
        assertTrue(
                ignore.contains("/harness/fullstack/run-*/"),
                "harness/fullstack/run-<db> is where a live stack keeps its Paper world, its "
                        + "SQLite database and its credentials. Without a glob covering every "
                        + "backend, one `git add -A` commits hundreds of megabytes of generated "
                        + "state -- and a credentials file");
    }

    @Test
    @DisplayName("no stage can report a skip")
    void thereIsNoSkipResult() {
        assertFalse(
                code(RUNNER).contains("result_skip"),
                "a skip is how a tier stops running without anybody noticing. Every narrowing "
                        + "this project has needed is stated at the point that narrows it, with "
                        + "a reason -- never as a green result carrying the word 'skipped'");
    }

    @Test
    @DisplayName("every journey has an implementation and a generated coverage note")
    void everyJourneyIsImplemented() {
        String body = code(JOURNEYS);
        List<String> missing = new ArrayList<>();
        for (String journey : shellList(JOURNEYS, "JOURNEYS")) {
            if (!body.contains("journey_" + journey.replace('-', '_') + "()")) {
                missing.add(journey);
            }
        }

        assertEquals(
                List.of(),
                missing,
                "a journey listed but not implemented would emit no transcript, and Tier 11's "
                        + "whole output is the transcript");
        // Anchored on the CALL at column 0, not on the name -- which
        // `write_coverage() {` satisfies, so deleting the call while keeping the
        // function left this green. Third assertion in this class to fail that
        // way, and the lesson is written four methods above in
        // staleResultsAreClearedUpFront: anchor on the call, not the name.
        assertTrue(
                body.contains("\nwrite_coverage\n"),
                "COVERAGE.md is generated from the journey list rather than maintained by hand, "
                        + "because a hand-maintained coverage note is one that stops being true. "
                        + "The function is still defined but nothing calls it, so the uncovered "
                        + "journeys stop being recorded anywhere");
    }

    @Test
    @DisplayName("the journey's own assertions are still there")
    void journeyAssertionsAreStillThere() {
        // Nothing covered these. All three load-bearing checks were deleted from
        // journeys.sh and every guard case stayed green -- the same shape as the
        // defect this class was written for, one file over. It matters more
        // here: `journeys` runs only in the expensive session, so a silent loss
        // would surface as a tier that has quietly stopped asserting anything.
        String body = code(JOURNEYS);

        assertTrue(
                body.contains("[ \"$effect\" != \"deny\" ]"),
                "the journey no longer asserts that an UNLINKED player is refused. Without it "
                        + "the flow starts from an already-open gate and documents nothing about "
                        + "linking -- and against an unconfigured gate core answers allow/no-rule, "
                        + "so it would pass");
        assertTrue(
                body.contains("[ \"$effect\" != \"allow\" ]"),
                "the journey no longer asserts that the player is admitted AFTER linking, so the "
                        + "link having worked is not part of the verdict");
        assertTrue(
                body.contains("[ \"$step_n\" -eq 0 ]"),
                "a journey that records no steps is no longer a fault. `-s` on the transcript is "
                        + "satisfied by the header transcript_open writes, so the step count is "
                        + "the only thing that says work happened");
        assertTrue(
                body.contains("exit $failed"),
                "journeys.sh no longer exits with its accumulated failure status");
    }

    @Test
    @DisplayName("the journey reads core's answer as JSON, not as a substring")
    void theJourneyDoesNotSubstringMatchDecisions() {
        String body = code(JOURNEYS);

        // The first version matched *ALLOW*|*allow* over merged stdout and
        // stderr, which any text containing the word satisfies -- including an
        // error explaining that nothing was allowed. protocol.md: match on
        // `reason`, never on `detail`.
        assertFalse(
                body.contains("*allow*") || body.contains("*ALLOW*"),
                "the journey is substring-matching a decision again. Any message containing the "
                        + "word 'allow' satisfies that, including one saying nothing was allowed");
        assertTrue(
                body.contains("field effect"),
                "the journey no longer parses the decision's effect out of the response");
    }

    @Test
    @DisplayName("GUARD FIRES: a stage listed without an implementation is rejected")
    void guardFiresOnTheFixture() throws Exception {
        Path fixture = SourceTree.repoRoot()
                .resolve("guards/src/test/resources/fixtures/fullstack-unimplemented-stage/run.sh");
        assertTrue(
                Files.isRegularFile(fixture),
                "the must-fail fixture is missing, so this check would pass having scanned "
                        + "nothing at all");

        // The SAME detector the real check uses. Anything else tests a copy.
        List<String> missing = unimplementedStages(fixture);

        assertEquals(
                List.of("ghost"),
                missing,
                "the fixture lists a stage with no implementation and the scan must name it; "
                        + "catching nothing means the detection stopped working");
    }
}
