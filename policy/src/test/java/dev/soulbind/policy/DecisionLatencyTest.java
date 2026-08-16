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
package dev.soulbind.policy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The decision latency budget, measured and reported.
 *
 * <p><b>Informational, not gating.</b> The specification asks for p99 under
 * 50ms in-process and says explicitly that the number is recorded rather than
 * enforced. This test therefore prints the distribution and asserts only a
 * ceiling loose enough that crossing it means something is badly wrong rather
 * than that the machine was busy.
 *
 * <p>A tight assertion here would be a flaky test, and a flaky test in a suite
 * this size gets muted — at which point the measurement stops happening at all.
 * The honest arrangement is a loud number and a loose bound.
 *
 * <p>Tagged so it does not run in the ordinary suite: it is a measurement, and
 * measuring on every compile makes every compile slower for no signal.
 */
@Tag("latency")
class DecisionLatencyTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);

    /** Well above any plausible in-process figure. Crossing this is a defect. */
    private static final long CEILING_MICROS = 50_000; // 50ms, the spec's target

    @Test
    @DisplayName("decision latency, measured over a realistic mix")
    void measure() {
        List<SubjectSnapshot> snapshots = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        List<List<PolicyOverride>> overrideSets = new ArrayList<>();

        Random random = new Random(seed());

        for (int i = 0; i < 200; i++) {
            snapshots.add(new SubjectSnapshot(
                    "s" + i,
                    "kind-a:acct-" + i,
                    Set.of("kind-a", "kind-b"),
                    2,
                    NOW.minusSeconds(random.nextInt(10_000))));
            rules.add(new Rule(
                    "gate.x",
                    Set.of("kind-a", "kind-b", "kind-c"),
                    true,
                    random.nextInt(600),
                    Effect.DENY));

            // A realistic override list: most subjects have none, a few have
            // several. Measuring only the empty case would measure the fast
            // path and call it the budget.
            List<PolicyOverride> overrides = new ArrayList<>();
            for (int j = 0; j < random.nextInt(5); j++) {
                overrides.add(new PolicyOverride(
                        "gate.x", "s" + random.nextInt(200), null,
                        random.nextBoolean() ? Effect.ALLOW : Effect.DENY,
                        "measured", null));
            }
            overrideSets.add(overrides);
        }

        // Warm up. Measuring a cold JIT measures the JIT.
        for (int i = 0; i < 20_000; i++) {
            int n = i % snapshots.size();
            PolicyEngine.decide(snapshots.get(n), rules.get(n), overrideSets.get(n), NOW);
        }

        int iterations = 200_000;
        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            int n = i % snapshots.size();
            long start = System.nanoTime();
            PolicyEngine.decide(snapshots.get(n), rules.get(n), overrideSets.get(n), NOW);
            samples[i] = System.nanoTime() - start;
        }

        java.util.Arrays.sort(samples);
        long p50 = samples[iterations / 2];
        long p99 = samples[(int) (iterations * 0.99)];
        long p999 = samples[(int) (iterations * 0.999)];
        long max = samples[iterations - 1];

        System.out.printf(
                "[latency] decide() over %,d calls: p50=%,dns p99=%,dns p99.9=%,dns max=%,dns%n",
                iterations, p50, p99, p999, max);
        System.out.printf(
                "[latency] p99 = %.3f ms against a 50 ms target (informational)%n",
                p99 / 1_000_000.0);

        assertTrue(
                p99 / 1_000 < CEILING_MICROS,
                () -> "p99 was " + (p99 / 1_000_000.0) + "ms, past the 50ms target. That is "
                        + "not a slow machine -- an in-process pure function is microseconds. "
                        + "Something is doing I/O.");
    }

    private static long seed() {
        String configured = System.getProperty("soulbind.seed");
        if (configured != null && !configured.isBlank()) {
            return Long.parseLong(configured.strip());
        }
        return new Random().nextLong();
    }
}
