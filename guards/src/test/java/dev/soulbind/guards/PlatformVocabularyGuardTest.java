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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform vocabulary guard, and the proof that it fires.
 *
 * <p>What this does NOT prove: that core is free of platform-specific
 * behaviour. A name is the cheap, mechanical signal. Behaviour that special-
 * cases a platform without naming it is caught by the Tier 4 authorization
 * matrix, where every capability and gate decision is asserted as a table.
 */
class PlatformVocabularyGuardTest {

    @Test
    @DisplayName("core/ and protocol/ name no platform")
    void realTreeIsClean() {
        List<PlatformVocabulary.Violation> violations =
                PlatformVocabulary.scan(SourceTree.repoRoot());

        assertTrue(
                violations.isEmpty(),
                () -> "core/ and protocol/ must not name a platform. Core learns platform "
                        + "kinds at runtime from connector registration; a name compiled in "
                        + "here means that is no longer true.\n  "
                        + String.join("\n  ", violations.stream().map(Object::toString).toList())
                        + "\nIf an occurrence is genuinely unavoidable, add it to "
                        + "guards/platform-vocabulary-allowlist.txt with a reason covering "
                        + "exactly what it narrows.");
    }

    // --- the must-fail fixture: proof the guard actually fires -----------------

    @Test
    @DisplayName("GUARD FIRES: a fixture naming a platform is rejected")
    void fixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");

        List<PlatformVocabulary.Violation> violations =
                PlatformVocabulary.scan(fixtures, List.of("platform-vocabulary-violation"), Set.of());

        assertFalse(
                violations.isEmpty(),
                "The must-fail fixture was not rejected. Either the fixture stopped naming a "
                        + "platform, or the guard stopped detecting one. Both are defects: a "
                        + "guard never observed failing has unmeasured value.");

        assertTrue(
                violations.stream().anyMatch(v -> v.word().equals("discord")),
                () -> "expected the fixture's platform name to be the reported word, got: "
                        + violations);
    }

    @Test
    @DisplayName("GUARD FIRES: word boundaries are respected")
    void wordBoundariesAreRespected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");

        List<PlatformVocabulary.Violation> violations =
                PlatformVocabulary.scan(fixtures, List.of("platform-vocabulary-boundary"), Set.of());

        // "planned" and "explanation" contain "plan"; neither is the platform.
        // A guard that fired on them would be routed around rather than obeyed.
        assertEquals(
                List.of(),
                violations,
                () -> "the guard matched a substring rather than a whole word: " + violations);
    }

    @Test
    @DisplayName("an allowlist entry without a stated reason is itself rejected")
    void allowlistDemandsAReason() {
        Path bad = SourceTree.repoRoot()
                .resolve("guards/src/test/resources/fixtures/allowlist-without-reason");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> PlatformVocabulary.allowlist(bad));

        assertTrue(
                thrown.getMessage().contains("no stated reason"),
                () -> "expected the failure to name the missing reason, got: "
                        + thrown.getMessage());
    }
}
