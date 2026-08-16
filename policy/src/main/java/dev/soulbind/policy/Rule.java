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

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a gate requires.
 *
 * @param gateName the gate this governs. Learned at runtime like everything
 *     else; core has no list of gates.
 * @param requiredKinds platform kinds that must be present and <b>verified</b>.
 *     Empty means "no particular kind" — combined with {@code requireLinked} it
 *     expresses "any linked subject will do".
 * @param requireLinked whether the subject must hold more than one identity.
 *     Distinct from {@code requiredKinds} being non-empty: a subject can be
 *     verified on one platform without being linked to anything, and a gate
 *     saying "prove you are also somewhere else" is a different requirement
 *     from "prove you are here".
 * @param graceSeconds how long after first being seen the gate stays open.
 *     A new forum registrant can read before linking; zero means no grace.
 * @param defaultEffect what happens when the requirements are NOT met.
 *     Almost always DENY — a rule whose unmet state is ALLOW is a rule that
 *     does nothing, and making that spellable is deliberate so an operator can
 *     stage a gate before enforcing it.
 */
public record Rule(
        String gateName,
        Set<String> requiredKinds,
        boolean requireLinked,
        long graceSeconds,
        Effect defaultEffect) {

    public Rule {
        Objects.requireNonNull(gateName, "gateName");
        Objects.requireNonNull(defaultEffect, "defaultEffect");
        // Sorted and copied: two rules requiring the same kinds in different
        // orders are the same rule, and a decision that depended on iteration
        // order would be reproducible only by accident.
        requiredKinds = requiredKinds == null
                ? Set.of()
                : Set.copyOf(new TreeSet<>(requiredKinds));
        if (graceSeconds < 0) {
            throw new IllegalArgumentException(
                    "graceSeconds must not be negative; a gate cannot close before the subject "
                            + "existed");
        }
    }

    /** A gate requiring nothing, which therefore allows everyone. */
    public static Rule open(String gateName) {
        return new Rule(gateName, Set.of(), false, 0L, Effect.ALLOW);
    }

    /** A gate requiring the subject to be linked to something. */
    public static Rule linked(String gateName) {
        return new Rule(gateName, Set.of(), true, 0L, Effect.DENY);
    }

    /** A gate requiring verified identities of specific kinds. */
    public static Rule requiring(String gateName, String... kinds) {
        return new Rule(gateName, Set.of(kinds), false, 0L, Effect.DENY);
    }

    /** Whether this rule asks for anything at all. */
    public boolean requiresSomething() {
        return requireLinked || !requiredKinds.isEmpty();
    }
}
