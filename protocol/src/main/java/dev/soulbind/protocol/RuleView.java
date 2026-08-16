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
 * A gate's rule on the wire.
 *
 * @param requiredKinds platform kinds that must be present AND verified
 * @param requireLinked whether the subject must hold more than one identity.
 *     Distinct from a non-empty {@code requiredKinds}: a subject can be
 *     verified on one platform without being linked to anything, and "prove you
 *     are also somewhere else" is a different requirement from "prove you are
 *     here".
 * @param defaultEffect what happens when the requirements are unmet
 */
public record RuleView(
        String gate,
        List<String> requiredKinds,
        boolean requireLinked,
        long graceSeconds,
        String defaultEffect) {

    public RuleView {
        requiredKinds = requiredKinds == null ? List.of() : List.copyOf(requiredKinds);
    }
}
