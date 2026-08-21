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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The named rule shapes mean what they are named.
 *
 * <p>Mutation found these surviving in an unusually instructive way. The
 * factories are used by {@link DecisionMatrixTest}'s parameter source, so a
 * factory returning {@code null} produces a matrix of null rules — and a null
 * rule is treated as "no rule governs this gate", which the matrix's own oracle
 * computes identically. Both sides moved together and every assertion held.
 *
 * <p>That is not a gap in the matrix, which is asserting decisions and does
 * that well. It is the absence of anybody asserting that {@code Rule.open}
 * returns an open rule.
 */
class RuleFactoryTest {

    private static final String GATE = "gate.x";
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);

    @Test
    @DisplayName("open() requires nothing and allows")
    void openRequiresNothing() {
        Rule rule = Rule.open(GATE);

        assertNotNull(rule, "Rule.open returned null, so every caller silently got 'no rule'");
        assertEquals(GATE, rule.gateName());
        assertFalse(rule.requiresSomething(),
                "a rule named 'open' asks for something");
        assertTrue(rule.requiredKinds().isEmpty());
        assertFalse(rule.requireLinked());
    }

    @Test
    @DisplayName("linked() and requiring() both ask for something, and say which")
    void theRestRequireSomething() {
        Rule linked = Rule.linked(GATE);
        assertNotNull(linked);
        assertTrue(linked.requireLinked(), "a rule named 'linked' does not require linkage");
        assertTrue(linked.requiresSomething());

        Rule requiring = Rule.requiring(GATE, "kind-a", "kind-b");
        assertNotNull(requiring,
                "Rule.requiring returned null, so every caller silently got 'no rule'");
        assertEquals(Set.of("kind-a", "kind-b"), requiring.requiredKinds());
        assertTrue(requiring.requiresSomething());
    }

    @Test
    @DisplayName("a rule requiring nothing says so, rather than claiming requirements were met")
    void openRuleExplainsItself() {
        // The distinction reaches whoever reads a decision log. "This gate
        // requires nothing" and "requirements satisfied" are both allows and
        // are not the same fact: the first says the gate is open to everybody,
        // which is usually somebody having forgotten to configure it.
        Decision decision = PolicyEngine.decide(
                SubjectSnapshot.unlinked("kind-a:acct-1", NOW),
                Rule.open(GATE),
                List.of(),
                NOW);

        assertEquals(Effect.ALLOW, decision.effect());
        assertEquals(Decision.Reason.REQUIREMENTS_MET, decision.reason());
        assertTrue(decision.detail().contains("requires nothing"),
                "an unconfigured-in-effect gate reported itself as satisfied requirements: "
                        + decision.detail());
    }
}
