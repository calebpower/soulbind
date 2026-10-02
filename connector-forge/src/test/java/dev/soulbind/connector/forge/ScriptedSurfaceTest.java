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
package dev.soulbind.connector.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scripted forge, tested as the oracle it is.
 *
 * <p>{@link RegistrationTest} asserts things like "no account was created" by
 * asking this class, so a double that always answered zero would make those
 * assertions vacuous and the suite would stay green through the defect it
 * exists to catch. Mutation coverage said exactly that: {@code createCalls}
 * replaced by a constant zero, {@code exists} by false and {@code isActive} by
 * true all survived, because every assertion against them only ever expected
 * the mutant's answer.
 *
 * <p>So each observer is asserted in both directions, and each counter is
 * asserted to move.
 */
class ScriptedSurfaceTest {

    private static ForgeSurface.Account account(String username) {
        return new ForgeSurface.Account(username, username + "@example.invalid", "a-passphrase");
    }

    @Test
    @DisplayName("presence tells apart absent, present and unreachable")
    void presenceHasThreeAnswers() {
        ScriptedSurface forge = new ScriptedSurface();

        assertEquals(ForgeSurface.Presence.ABSENT, forge.presence("ada"));

        forge.withAccount("ada");
        assertEquals(ForgeSurface.Presence.PRESENT, forge.presence("ada"));

        forge.goDown();
        assertEquals(ForgeSurface.Presence.UNKNOWN, forge.presence("ada"),
                "an unreachable forge answered about a name it could not have checked");

        // Through comeBack()'s own return value, so a mutant dropping it is
        // not merely unobserved.
        assertEquals(ForgeSurface.Presence.PRESENT, forge.comeBack().presence("ada"));
    }

    @Test
    @DisplayName("creating gives three distinct answers, and only one of them is success")
    void creationHasThreeAnswers() {
        ScriptedSurface forge = new ScriptedSurface();

        assertEquals(ForgeSurface.Creation.CREATED, forge.create(account("ada")));
        assertTrue(forge.exists("ada"), "a created account did not exist afterwards");
        assertEquals(ForgeSurface.Creation.ALREADY_EXISTS, forge.create(account("ada")),
                "a second creation of the same name reported success, which is how somebody "
                        + "is handed an account that was never theirs");

        forge.goDown();
        assertEquals(ForgeSurface.Creation.FAILED, forge.create(account("grace")));
        assertFalse(forge.exists("grace"));
    }

    @Test
    @DisplayName("activation is a desired state, so applying it twice still succeeds")
    void activationIsIdempotent() {
        // Events are at-least-once: the same transition arrives twice and the
        // second application must be a no-op that still reports success.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        assertTrue(forge.setActive("ada", false));
        assertFalse(forge.isActive("ada"));
        assertTrue(forge.setActive("ada", false), "re-applying a state already held failed");

        assertTrue(forge.setActive("ada", true));
        assertTrue(forge.isActive("ada"));
    }

    @Test
    @DisplayName("activation fails for an account that is not there, or a forge that is not")
    void activationFailsHonestly() {
        ScriptedSurface forge = new ScriptedSurface();

        assertFalse(forge.setActive("nobody", true),
                "activating an account that does not exist reported success");

        forge.withAccount("ada").goDown();
        assertFalse(forge.setActive("ada", true));
    }

    @Test
    @DisplayName("isActive is false for an account that is not there at all")
    void isActiveIsFalseForAbsent() {
        // The other direction of the same observer. Asserted because a mutant
        // returning true unconditionally passed every test that only ever
        // expected true.
        assertFalse(new ScriptedSurface().isActive("nobody"));
    }

    @Test
    @DisplayName("every call is counted, and the counts move")
    void callsAreCounted() {
        // These counters are how absence is observed elsewhere -- "no account
        // was created" is only a claim if something counted. A counter stuck at
        // zero would satisfy that assertion while hiding the very call it was
        // meant to rule out.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        assertEquals(0, forge.presenceCalls());
        assertEquals(0, forge.createCalls());
        assertEquals(0, forge.setActiveCalls());

        forge.presence("ada");
        forge.presence("grace");
        forge.create(account("grace"));
        forge.setActive("ada", false);

        assertEquals(2, forge.presenceCalls());
        assertEquals(1, forge.createCalls());
        assertEquals(1, forge.setActiveCalls());
    }

    @Test
    @DisplayName("created() lists what was created, in order, and nothing else")
    void createdIsOrdered() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("seeded");

        forge.create(account("ada"));
        forge.create(account("grace"));
        forge.create(account("ada"));

        assertEquals(List.of("ada", "grace"), forge.created(),
                "a seeded account or a rejected duplicate was counted as created");
    }
}
