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

/** Allow or deny. There is no third answer, and there deliberately is not. */
public enum Effect {
    ALLOW,
    DENY;

    /**
     * Parses a configured value, empty rather than defaulting.
     *
     * <p>A typo in a rule's default effect must not quietly become
     * {@code ALLOW}. Empty makes the caller decide what an unreadable rule
     * means, and every caller here decides "deny".
     */
    public static java.util.Optional<Effect> fromConfigName(String s) {
        if (s == null) {
            return java.util.Optional.empty();
        }
        String needle = s.strip().toLowerCase(java.util.Locale.ROOT);
        for (Effect e : values()) {
            if (e.name().toLowerCase(java.util.Locale.ROOT).equals(needle)) {
                return java.util.Optional.of(e);
            }
        }
        return java.util.Optional.empty();
    }

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
