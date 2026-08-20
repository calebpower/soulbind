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

package dev.soulbind.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hunting: fresh seeds, until something is found or the budget runs out.
 *
 * <p>Tested against a stub runner rather than a real simulation, because what
 * is being checked is the hunt's own behaviour — when it stops, what it keeps,
 * what it says — and running four hundred actions to find that out would make
 * these tests slow enough to skip.
 */
class HuntTest {

    private static Simulation.Outcome clean(long seed) {
        return new Simulation.Outcome(seed, 400, List.of(), List.of());
    }

    private static Simulation.Outcome dirty(long seed) {
        return new Simulation.Outcome(seed, 400,
                List.of(new Checker.Violation("linkage-mirrors-model", "a link vanished", 75)),
                List.of());
    }

    @Test
    @DisplayName("it stops at the first finding rather than exhausting the budget")
    void stopsOnTheFirstFinding() {
        // The budget is a bound, not a target. Once there is something to fix,
        // spending another hour looking for a second thing delays the first.
        AtomicLong next = new AtomicLong();
        Runner.Hunt hunt = Runner.hunt(
                next::incrementAndGet, 100, seed -> seed == 3 ? dirty(seed) : clean(seed));

        assertTrue(hunt.found().isPresent(), "the hunt missed a seed that fails");
        assertEquals(3L, hunt.found().get().seed());
        assertEquals(3, hunt.seedsTried().size(),
                () -> "the hunt kept going after finding something: " + hunt.seedsTried());
    }

    @Test
    @DisplayName("every seed it tried is kept, so a finding is reproducible")
    void everySeedIsRecorded() {
        AtomicLong next = new AtomicLong();
        Runner.Hunt hunt = Runner.hunt(next::incrementAndGet, 5, HuntTest::clean);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), hunt.seedsTried());
        assertTrue(hunt.found().isEmpty());
    }

    @Test
    @DisplayName("finding nothing is not reported as a clean bill of health")
    void anEmptyHuntSaysWhatItMeans() {
        // The distinction matters more here than anywhere else in the tier. A
        // hunt that finds nothing has established that these particular N seeds
        // found nothing -- which is the budget running out, not evidence of
        // correctness, and the wording has to stop somebody quoting it as the
        // latter.
        Runner.Hunt hunt = Runner.hunt(new AtomicLong()::incrementAndGet, 3, HuntTest::clean);
        String summary = hunt.summary();

        assertTrue(summary.contains("found nothing"), summary);
        assertTrue(summary.contains("budget"),
                () -> "an empty hunt does not say that it ran out of budget, so it reads as"
                        + " a clean result: " + summary);
    }

    @Test
    @DisplayName("a finding prints the seed and the line that promotes it")
    void aFindingIsActionable() {
        Runner.Hunt hunt = Runner.hunt(
                new AtomicLong(41)::incrementAndGet, 10, seed -> dirty(seed));
        String summary = hunt.summary();

        assertTrue(summary.contains("42"), () -> "the failing seed is not in the report: "
                + summary);
        assertTrue(summary.contains("promote it"),
                () -> "a finding did not print its promotion line, so the rule that every"
                        + " defect-finding seed is kept forever depends on somebody"
                        + " remembering it: " + summary);
        assertTrue(summary.contains("linkage-mirrors-model"), summary);
    }

    @Test
    @DisplayName("a budget below one is refused")
    void budgetMustBeSane() {
        // Zero would try nothing and report having found nothing, which reads
        // exactly like a clean hunt.
        assertThrows(IllegalArgumentException.class,
                () -> Runner.hunt(new AtomicLong()::incrementAndGet, 0, HuntTest::clean));
    }

    @Test
    @DisplayName("hunting is not enabled anywhere in the battery")
    void theBatteryNeverHunts() throws Exception {
        // A hunt is nondeterministic in runtime AND outcome. A battery whose
        // green depends on a dice roll is a battery people stop believing, and
        // the failure would present as flakiness rather than as a finding.
        String manifest = java.nio.file.Files.readString(
                java.nio.file.Path.of(System.getProperty("user.dir"), "..", "..",
                        ".reaper.toml").normalize(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(manifest.contains("SOULBIND_SIM_HUNT"),
                "the reaper manifest enables hunting. The committed seed set is what runs on"
                        + " every session; hunting is opt-in and run deliberately.");
    }
}
