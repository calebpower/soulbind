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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runner's reporting and its refusals. */
class RunnerTest {

    @TempDir Path tempDir;

    private static World world() {
        return new World(
                List.of(new Actor("alex", List.of("game:alex", "chat:alex"), 0),
                        new Actor("sam", List.of("forum:sam", "chat:sam"), 0)),
                List.of("game.join"));
    }

    @Test
    @DisplayName("what was NOT checked is reported first, on a green run")
    void inertInvariantsAreSurfacedEvenWhenClean() {
        // The whole point. A narrowing that only appears in a failing run is a
        // narrowing nobody reads, because the runs that matter are the green
        // ones -- somebody looking at a green result should be able to see what
        // it did not cover without going to the source.
        InMemoryCore core = new InMemoryCore();
        CoreView viewWithAGap = new CoreView() {
            @Override
            public java.util.Optional<Subject> describe(String kind, String id) {
                return core.describe(kind, id);
            }

            @Override
            public List<AuditRow> auditSince(long after) {
                return core.auditSince(after);
            }

            @Override
            public boolean codeRedeemable(String code) {
                return core.codeRedeemable(code);
            }

            @Override
            public boolean reachable() {
                return core.reachable();
            }

            @Override
            public List<String> transportComplaints() {
                return core.transportComplaints();
            }

            @Override
            public List<String> inertInvariants() {
                return List.of("redeemed-codes-stay-redeemed: no non-mutating way to ask");
            }
        };
        Simulation.Outcome outcome = Simulation.run(1L, world(), core, core, 50, 25);
        String report = Runner.report(List.of(outcome), viewWithAGap);

        assertTrue(report.contains("NOT CHECKED"),
                () -> "a green report does not say what it skipped:\n" + report);
        assertTrue(report.contains("redeemed-codes-stay-redeemed"),
                () -> "the report does not name the inert invariant:\n" + report);
        assertTrue(report.indexOf("NOT CHECKED") < report.indexOf("seeds clean"),
                () -> "the narrowing is printed after the verdict, where it reads as a"
                        + " footnote to a pass:\n" + report);
    }

    @Test
    @DisplayName("a failing seed comes with the line that promotes it")
    void failingSeedsPrintTheirPromotion() {
        InMemoryCore core = new InMemoryCore().with(InMemoryCore.Defect.REDEEM_DOES_NOT_LINK);
        Simulation.Outcome outcome = Simulation.run(20260820L, world(), core, core, 200, 50);

        String report = Runner.report(List.of(outcome), core);

        assertTrue(report.contains("promote this seed"),
                () -> "a failing seed did not print its promotion line, so the rule that"
                        + " every defect-finding seed is kept forever depends on somebody"
                        + " remembering it:\n" + report);
        assertTrue(report.contains(String.valueOf(20260820L)));
    }

    @Test
    @DisplayName("a credential file missing a reserved entry is refused")
    void reservedCredentialsAreRequired() throws Exception {
        Path file = tempDir.resolve("creds");
        Files.writeString(file, "alex=cred-a\nsam=cred-b\n", StandardCharsets.UTF_8);

        // Without an admin credential the rule and config classes cannot run,
        // and without a retired one the stale-credential class cannot. A run
        // that silently drops a nemesis class reports green for less work.
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Runner.readCredentials(file));
        assertTrue(thrown.getMessage().contains("admin"));
    }

    @Test
    @DisplayName("a credential file with no actors is refused")
    void actorsAreRequired() throws Exception {
        Path file = tempDir.resolve("creds");
        Files.writeString(file, "admin=a\nretired=r\n", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> Runner.readCredentials(file));
    }

    @Test
    @DisplayName("actors span platforms")
    void actorsAreCrossPlatform() {
        Map<String, String> credentials =
                Map.of("admin", "a", "retired", "r", "alex", "c1", "sam", "c2");
        World world = Runner.worldFor(credentials, "-run7");

        assertTrue(world.actors().size() >= 2, "the cast is too small to link anybody");
        for (Actor actor : world.actors()) {
            assertTrue(actor.identities().size() >= 2,
                    () -> actor.name() + " exists on one platform, so nothing this actor does"
                            + " can exercise the cross-platform graph -- which is where §11"
                            + " says the defects worth finding live");
            assertTrue(actor.identities().stream().allMatch(ref -> ref.endsWith("-run7")),
                    () -> "an identity does not carry the run tag, so a replay would collide"
                            + " with an earlier run's rows: " + actor.identities());
        }
    }
}
