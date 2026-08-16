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

import java.util.List;
import java.util.Objects;

/**
 * The answer, and why.
 *
 * @param effect allow or deny
 * @param reason a stable, machine-readable code. Connectors show people
 *     messages; a connector matching on prose breaks the first time the prose
 *     is improved.
 * @param detail human-readable, for operators and logs. Never matched on.
 * @param ttlSeconds how long a connector may cache this. Short by default and
 *     carried in the response, so cache behaviour is core-tunable without
 *     redeploying every connector.
 * @param missingKinds what the subject would need. Present on a denial so a
 *     connector can tell somebody what to do rather than only that they cannot.
 */
public record Decision(
        Effect effect, Reason reason, String detail, int ttlSeconds, List<String> missingKinds) {

    /** Why the answer is what it is. */
    public enum Reason {
        /** No rule governs this gate. */
        NO_RULE,
        /** An operator override said so. */
        OVERRIDE,
        /** Inside the grace period. */
        GRACE,
        /** The rule's requirements are satisfied. */
        REQUIREMENTS_MET,
        /** The subject is not linked to anything. */
        NOT_LINKED,
        /** Required platform kinds are missing or unverified. */
        MISSING_KINDS,
        /** The rule's requirements are unmet and its default applies. */
        DEFAULT;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    public Decision {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(reason, "reason");
        missingKinds = missingKinds == null ? List.of() : List.copyOf(missingKinds);
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must not be negative");
        }
    }

    public boolean isAllowed() {
        return effect == Effect.ALLOW;
    }
}
