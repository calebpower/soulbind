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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading an effect out of a configuration file, and back onto the wire.
 *
 * <p>Written because a mutation sweep reported every mutant in {@code Effect}
 * and {@code Decision.isAllowed} as <b>NO_COVERAGE</b> — no test in this module
 * executed the lines at all. They are exercised indirectly elsewhere, which is
 * how they escaped notice, and indirectly is not the same as asserted.
 *
 * <p>What rides on {@code fromConfigName} is a rule's {@code defaultEffect}:
 * what happens to somebody whose requirements are unmet. Misreading it inverts
 * a gate.
 */
class EffectTest {

    @Test
    @DisplayName("a configured effect is read case- and whitespace-insensitively")
    void configNamesParse() {
        for (String allow : List.of("allow", "ALLOW", "Allow", "  allow  ")) {
            assertEquals(Effect.ALLOW, Effect.fromConfigName(allow).orElseThrow(),
                    "did not read '" + allow + "' as allow");
        }
        for (String deny : List.of("deny", "DENY", "Deny", "\tdeny\n")) {
            assertEquals(Effect.DENY, Effect.fromConfigName(deny).orElseThrow(),
                    "did not read '" + deny + "' as deny");
        }
    }

    @Test
    @DisplayName("anything else is refused rather than guessed at")
    void unknownNamesAreEmpty() {
        // Empty, not a default. A typo'd defaultEffect silently becoming ALLOW
        // is a gate that admits everybody whose requirements are unmet, which is
        // the exact inverse of what somebody writing "denys" intended.
        for (String bad : List.of("", "  ", "allowed", "denys", "true", "0", "ALLOW DENY")) {
            assertTrue(Effect.fromConfigName(bad).isEmpty(),
                    "'" + bad + "' was read as " + Effect.fromConfigName(bad).orElse(null));
        }
        assertTrue(Effect.fromConfigName(null).isEmpty(), "null was read as an effect");
    }

    @Test
    @DisplayName("every effect survives a round trip through its wire name")
    void wireNamesRoundTrip() {
        // Derived rather than listed: an effect added later is covered the day
        // it is added, instead of the day somebody remembers this file.
        for (Effect effect : Effect.values()) {
            assertEquals(effect.name().toLowerCase(java.util.Locale.ROOT), effect.wireName());
            assertEquals(effect, Effect.fromConfigName(effect.wireName()).orElseThrow(),
                    effect + " does not survive its own wire name");
        }
    }

    @Test
    @DisplayName("isAllowed agrees with the effect it reports")
    void isAllowedAgreesWithEffect() {
        Decision allow = new Decision(
                Effect.ALLOW, Decision.Reason.REQUIREMENTS_MET, "ok", 60, List.of());
        Decision deny = new Decision(
                Effect.DENY, Decision.Reason.NOT_LINKED, "no", 60, List.of());

        assertTrue(allow.isAllowed());
        assertFalse(deny.isAllowed(),
                "a denial reported itself as allowed, which is the one way this helper"
                        + " can be wrong that matters");
    }
}
