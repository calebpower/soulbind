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
package dev.soulbind.protocol;

import java.util.List;

/**
 * A gate and its rule on the wire.
 *
 * <p>The rule and the gate are different things -- clearing a rule leaves the
 * gate -- and they share a shape here because they are set and read together.
 * An operator writing a rule is the moment they know what the gate is for, and
 * a second operation to say so would be a second operation nobody calls.
 *
 * @param requiredKinds platform kinds that must be present AND verified
 * @param requireLinked whether the subject must hold more than one identity.
 *     Distinct from a non-empty {@code requiredKinds}: a subject can be
 *     verified on one platform without being linked to anything, and "prove you
 *     are also somewhere else" is a different requirement from "prove you are
 *     here".
 * @param defaultEffect what happens when the requirements are unmet
 * @param description what this gate is for, in a sentence, for whoever reads
 *     the policy next. Optional in both directions: absent on the way in means
 *     "leave whatever is there", which is what lets {@code decide} declare a
 *     gate on every permission check without erasing an operator's note.
 *     <b>There is deliberately no way to blank one</b> -- an empty string is
 *     treated as absent rather than as an instruction to delete, because
 *     "clear the documentation" is not a thing anybody has needed and the
 *     alternative is a request that wipes a note by omission.
 * @param registeredBy the connector that first declared this gate.
 *     <b>Response-only.</b> It is ignored on the way in: a caller does not get
 *     to state who introduced a gate, least of all somebody else.
 */
public record RuleView(
        String gate,
        List<String> requiredKinds,
        boolean requireLinked,
        long graceSeconds,
        String defaultEffect,
        String description,
        String registeredBy) {

    public RuleView {
        requiredKinds = requiredKinds == null ? List.of() : List.copyOf(requiredKinds);
        description = description == null || description.isBlank() ? null : description;
    }

    /** The rule alone, for callers that have nothing to say about the gate. */
    public RuleView(
            String gate,
            List<String> requiredKinds,
            boolean requireLinked,
            long graceSeconds,
            String defaultEffect) {
        this(gate, requiredKinds, requireLinked, graceSeconds, defaultEffect, null, null);
    }
}
