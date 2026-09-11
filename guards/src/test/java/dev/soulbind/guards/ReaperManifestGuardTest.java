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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session manifest still runs the stage that stands in for a guard which
 * cannot run in the build container.
 *
 * <p><b>Why this exists.</b> {@link PlanCheckWalkerGuardTest} executes the
 * probes shipped inside {@code plan-check.sh}, and those probes run
 * {@code python3}. The digest-pinned toolchain image the build verb runs inside
 * has no interpreter, so every probe there exits 127 and the whole class SKIPS
 * — six tests, silently, on every build.
 *
 * <p>That is deliberate and it is not a hole, because the property is asserted
 * somewhere stronger: {@code harness/fullstack/mutation/run.sh} drives thirteen
 * mutants of a recorded dashboard response plus a control, on the session guest,
 * which does have an interpreter. Thirteen mutants that must each turn the stage
 * red is a better check than the single read the skipped guard performs.
 *
 * <p><b>And the two were coupled only by prose.</b> The reasoning lived in a
 * comment in {@code .reaper.toml} and in that guard's own javadoc. Delete the
 * stage from the manifest — while tidying, or while narrowing a session for an
 * unrelated reason — and the guard goes on skipping, the build stays green, and
 * thirteen mutants stop running with nothing anywhere saying so. The coverage
 * would vanish in a commit that looked like housekeeping.
 *
 * <p>So the coupling is checked rather than described. This guard fails if the
 * manifest stops invoking the runner, if the catalogue it drives empties out, or
 * if the skip it compensates for disappears — that last one because if the guard
 * ever stops skipping, the argument for this one has changed and somebody should
 * read it again rather than inherit a rule whose reason has quietly expired.
 */
class ReaperManifestGuardTest {

    private static final Path MANIFEST = SourceTree.repoRoot().resolve(".reaper.toml");
    private static final Path RUNNER =
            SourceTree.repoRoot().resolve("harness/fullstack/mutation/run.sh");
    private static final Path CATALOGUE =
            SourceTree.repoRoot().resolve("harness/fullstack/mutation/mutants.txt");
    private static final Path SKIPPING_GUARD = SourceTree.repoRoot()
            .resolve("guards/src/test/java/dev/soulbind/guards/PlanCheckWalkerGuardTest.java");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the session manifest still runs the shell mutation battery")
    void manifestRunsTheMutationBattery() throws IOException {
        String manifest = read(MANIFEST);

        assertTrue(Files.isRegularFile(RUNNER),
                () -> RUNNER + " does not exist, so the stage that stands in for "
                        + "PlanCheckWalkerGuardTest cannot run at all.");

        assertTrue(manifest.contains("harness/fullstack/mutation/run.sh"),
                () -> ".reaper.toml no longer invokes harness/fullstack/mutation/run.sh."
                        + " That stage is the ONLY place plan-check.sh's readers are"
                        + " exercised: PlanCheckWalkerGuardTest skips in the build container,"
                        + " which ships no python3. Removing the stage removes the coverage,"
                        + " and nothing else would have said so — the build stays green and"
                        + " the guard goes on skipping.");
    }

    @Test
    @DisplayName("the mutant catalogue it drives is not empty")
    void theCatalogueStillHasMutants() throws IOException {
        long mutants = read(CATALOGUE).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .count();

        // A floor rather than an exact count: the catalogue is meant to grow.
        // But a stage driving an EMPTY catalogue passes while asserting nothing,
        // which is the same shape as the guard it is standing in for skipping —
        // green, and covering nothing.
        assertTrue(mutants >= 10,
                () -> "the mutation catalogue has only " + mutants + " mutants."
                        + " A stage that drives an empty or near-empty catalogue reports"
                        + " success without exercising the readers it exists to check.");
    }

    @Test
    @DisplayName("the guard this compensates for does still skip, so the reason still holds")
    void theCompensatedGuardStillSkips() throws IOException {
        String guard = read(SKIPPING_GUARD);

        assertTrue(guard.contains("assumeTrue") && guard.contains("python3"),
                () -> "PlanCheckWalkerGuardTest no longer skips when python3 is absent."
                        + " That is good news, and it means THIS guard's justification has"
                        + " changed: the manifest stage was required because the guard could"
                        + " not run in the container. Re-read both and decide what is still"
                        + " needed, rather than keeping a rule whose reason has expired.");
    }
}
