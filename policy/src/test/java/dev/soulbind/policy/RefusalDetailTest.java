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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a refused person is told, which is a different question from whether
 * they were refused.
 *
 * <p>The matrix suite asserts effects across every snapshot and rule. Nothing
 * asserted the {@code detail}, so a mutation sweep found the branches that
 * choose between "you are not linked", "you are not linked and are missing X",
 * and "you are missing X" all surviving: swap them and every test stayed green
 * while the person at the gate is told to go and do the wrong thing.
 *
 * <p>Worth its own file rather than a note in the tail. This project has
 * already shipped one message that sent somebody in a circle — a slash command
 * whose usage line advertised a subcommand that replied with the usage line —
 * and the cost of a wrong instruction is paid by whoever received it.
 */
class RefusalDetailTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final String GATE = "gate.x";
    private static final String REF = "kind-a:acct-1";

    private static String detailFor(SubjectSnapshot snapshot, Rule rule) {
        Decision decision = PolicyEngine.decide(snapshot, rule, List.of(), NOW);
        assertEquals(Effect.DENY, decision.effect(),
                "this fixture was supposed to be refused; the detail below means nothing"
                        + " if it was allowed");
        return decision.detail();
    }

    @Test
    @DisplayName("unlinked, with nothing else asked for: say exactly that")
    void unlinkedOnly() {
        String detail = detailFor(
                SubjectSnapshot.unlinked(REF, NOW),
                new Rule(GATE, Set.of(), true, 0L, Effect.DENY));

        assertEquals("this account is not linked to any other", detail);
    }

    @Test
    @DisplayName("unlinked AND missing kinds: say both, because both must be fixed")
    void unlinkedAndMissing() {
        // Telling somebody only "you are not linked" here would have them link
        // one account, come back, and be refused again for a reason nobody
        // mentioned. Two round trips for a person who did what they were told.
        String detail = detailFor(
                SubjectSnapshot.unlinked(REF, NOW),
                new Rule(GATE, Set.of("kind-b"), true, 0L, Effect.DENY));

        assertTrue(detail.contains("not linked"), detail);
        assertTrue(detail.contains("kind-b"),
                "the missing platform is not named, so the person cannot act on this: "
                        + detail);
    }

    @Test
    @DisplayName("linked but missing a verified kind: do NOT say they are unlinked")
    void linkedButMissingKinds() {
        // The inverse mistake, and the worse one: somebody who HAS linked being
        // told to link is told to redo work they already did, and will conclude
        // the system is broken.
        SubjectSnapshot linked =
                new SubjectSnapshot("s1", REF, Set.of("kind-a"), 2, NOW);
        String detail = detailFor(linked, new Rule(GATE, Set.of("kind-b"), true, 0L, Effect.DENY));

        assertTrue(detail.contains("kind-b"), detail);
        assertTrue(detail.startsWith("missing verified"),
                "a linked account was told it is not linked: " + detail);
    }

    @Test
    @DisplayName("every reason has a distinct wire name")
    void reasonsHaveDistinctWireNames() {
        // NO_COVERAGE in the sweep: nothing in this module called it. The wire
        // name is what a connector switches on, so two reasons sharing one --
        // or one coming back empty -- silently merges two different answers.
        Set<String> seen = new java.util.HashSet<>();
        for (Decision.Reason reason : Decision.Reason.values()) {
            String wire = reason.wireName();
            assertTrue(wire != null && !wire.isBlank(), reason + " has no wire name");
            assertTrue(seen.add(wire), "two reasons share the wire name '" + wire + "'");
        }
        assertEquals(Decision.Reason.values().length, seen.size());
    }
}
