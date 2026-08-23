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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            public String decide(String gate, String kind, String id) {
                return core.decide(gate, kind, id);
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

    // --- reading credentials --------------------------------------------------

    @Test
    @DisplayName("credentials are read by name, and both reserved names are required")
    void readCredentials(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("creds.env");
        java.nio.file.Files.writeString(file, """
                # a comment, and a blank line follow

                admin = admin-secret
                retired=retired-secret
                proxy = proxy-secret
                """);

        java.util.Map<String, String> read = Runner.readCredentials(file);

        assertEquals("admin-secret", read.get("admin"),
                "spaces around the separator were not stripped, so the credential carries a"
                        + " leading space and every signed request is refused");
        assertEquals("retired-secret", read.get("retired"));
        assertEquals("proxy-secret", read.get("proxy"));
        assertEquals(3, read.size(),
                "a comment or a blank line was read as a credential: " + read.keySet());
    }

    @Test
    @DisplayName("a line that is not name=value is refused, rather than half-read")
    void malformedCredentialLine(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("creds.env");
        java.nio.file.Files.writeString(file, "admin=a\nretired=b\nthis-has-no-separator\n");

        assertThrows(IllegalStateException.class, () -> Runner.readCredentials(file),
                "a malformed line was skipped, so a typo'd credential name silently becomes a"
                        + " missing connector rather than an error");
    }

    @Test
    @DisplayName("a missing reserved credential is named, not merely counted")
    void missingReservedCredential(@TempDir java.nio.file.Path dir) throws Exception {
        // Both are reserved for a reason: without `admin` the run cannot set a
        // rule, and without `retired` it cannot generate the stale-credential
        // action -- so the run would be quietly narrower than it claims.
        for (String present : new String[] {"admin", "retired"}) {
            String missing = present.equals("admin") ? "retired" : "admin";
            java.nio.file.Path file = dir.resolve(present + ".env");
            // Two actors as well, so the file fails the reserved-name check
            // rather than the "no actors at all" one -- otherwise this passes
            // for a reason that has nothing to do with what it is testing.
            java.nio.file.Files.writeString(
                    file, present + "=one\nalex=two\nsam=three\n");

            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class, () -> Runner.readCredentials(file));
            assertTrue(thrown.getMessage().contains("'" + missing + "'"),
                    () -> "the complaint does not name the credential that is missing, so an"
                            + " operator has to work out which of the two it means: "
                            + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("the two reserved names alone are not a usable cast")
    void reservedNamesAloneAreRefused(@TempDir java.nio.file.Path dir) throws Exception {
        // `size() <= 2`, and the boundary is the point: a file holding exactly
        // admin and retired has both reserved credentials and no actors, so
        // every action would be performed by nobody. The run would complete and
        // report clean.
        java.nio.file.Path file = dir.resolve("two.env");
        java.nio.file.Files.writeString(file, "admin=a\nretired=b\n");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> Runner.readCredentials(file));
        assertTrue(thrown.getMessage().contains("no actor credentials"),
                () -> "a file with the two reserved names and nobody to act was accepted, or "
                        + "refused for the wrong reason: " + thrown.getMessage());

        // One more and it is a cast.
        java.nio.file.Path three = dir.resolve("three.env");
        java.nio.file.Files.writeString(three, "admin=a\nretired=b\nalex=c\n");
        assertEquals(3, Runner.readCredentials(three).size());
    }

    @Test
    @DisplayName("a line with no name before the separator is refused")
    void credentialWithNoName(@TempDir java.nio.file.Path dir) throws Exception {
        // `=secret` has no name. Filing it under the empty string leaves a
        // credential that matches no actor, so the run is one principal short
        // and reports clean about a cast it never assembled.
        java.nio.file.Path file = dir.resolve("noname.env");
        java.nio.file.Files.writeString(file, "admin=a\nretired=b\nalex=c\n=orphan\n");

        assertThrows(IllegalStateException.class, () -> Runner.readCredentials(file),
                "a credential line with no name was accepted");
    }

    @Test
    @DisplayName("an equals sign inside the value is part of the value")
    void valueMayContainAnEquals() {
        // `indexOf` rather than a split: a credential is base64 and can end in
        // padding. Splitting on every separator would truncate it, and the
        // symptom is every request refused for a reason that looks like a
        // server fault.
        assertDoesNotThrow(() -> {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("creds");
            java.nio.file.Path file = dir.resolve("creds.env");
            // An actor as well: readCredentials refuses a file with only the
            // two reserved names, because a run with no actors exercises
            // nothing.
            java.nio.file.Files.writeString(file, "admin=abc==\nretired=b\nalex=c\n");
            assertEquals("abc==", Runner.readCredentials(file).get("admin"));
        });
    }

    // --- the hunt -------------------------------------------------------------

    @Test
    @DisplayName("a hunt budget below one is refused, because it reads as a clean hunt")
    void huntBudgetFloor() {
        // `< 1`, and the boundary matters in the direction that looks like
        // success: a budget of zero tries nothing and reports having found
        // nothing, which is indistinguishable from a hunt that looked properly.
        assertThrows(
                IllegalArgumentException.class,
                () -> Runner.hunt(() -> 1L, 0, seed -> null));
        assertDoesNotThrow(
                () -> Runner.hunt(() -> 1L, 1,
                        seed -> new Simulation.Outcome(seed, 1, 1, 0, List.of(), List.of())));
    }

    // --- the report a person reads --------------------------------------------

    @Test
    @DisplayName("the tally counts seeds that failed, and both kinds of failure count")
    void reportTalliesBothFailureKinds() {
        // `failed++` in two arms and one subtraction at the end. A seed that
        // linked nothing is a HARNESS fault and a seed with violations is a
        // PRODUCT fault, and both must come off the clean count -- otherwise
        // "3 of 3 seeds clean" is printed above three paragraphs explaining
        // what went wrong.
        String report = Runner.report(
                List.of(
                        new Simulation.Outcome(1L, 400, 5, 10, List.of(), List.of()),
                        new Simulation.Outcome(2L, 400, 0, 400, List.of(), List.of()),
                        new Simulation.Outcome(3L, 400, 5, 10,
                                List.of(new Checker.Violation("linkage", "gone", 12)),
                                List.of())),
                new FakeCore());

        assertTrue(report.contains("1 of 3 seeds clean"),
                "the tally disagrees with the failures printed above it:\n" + report);
        assertTrue(report.contains("HARNESS FAULT"),
                "the seed that linked nothing was not called out:\n" + report);
        assertTrue(report.contains("promote this seed"),
                "the seed that found a violation was not offered for promotion:\n" + report);
        assertTrue(report.contains("linkage"),
                "the promotion line does not name the invariant that fired:\n" + report);
    }

    @Test
    @DisplayName("a run of nothing but clean seeds says so")
    void reportOnAllClean() {
        String report = Runner.report(
                List.of(
                        new Simulation.Outcome(1L, 400, 5, 10, List.of(), List.of()),
                        new Simulation.Outcome(2L, 400, 7, 12, List.of(), List.of())),
                new FakeCore());

        assertTrue(report.contains("2 of 2 seeds clean"), report);
        assertFalse(report.contains("HARNESS FAULT"), report);
        assertFalse(report.contains("promote this seed"), report);
    }
}
