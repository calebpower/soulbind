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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The seam's own types: what an account refuses to be, and what it refuses to print. */
class ForgeSurfaceTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("an account refuses a username that is absent or only whitespace")
    void usernameMustBeReal(String username) {
        assertThrows(IllegalArgumentException.class,
                () -> new ForgeSurface.Account(username, "a@example.invalid", "a-passphrase"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("an account refuses an email that is absent or only whitespace")
    void emailMustBeReal(String email) {
        assertThrows(IllegalArgumentException.class,
                () -> new ForgeSurface.Account("ada", email, "a-passphrase"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("an account refuses an absent or empty password")
    void passwordMustBePresent(String password) {
        assertThrows(IllegalArgumentException.class,
                () -> new ForgeSurface.Account("ada", "a@example.invalid", password));
    }

    @Test
    @DisplayName("a passphrase of only spaces is allowed, because that is the owner's business")
    void whitespacePasswordIsAccepted() {
        // Deliberately NOT blank-checked, unlike the other two. A password is
        // opaque to this connector: judging its contents here would both
        // duplicate the host's own policy and reject passphrases the host
        // accepts. Only ABSENT is refused.
        assertDoesNotThrow(
                () -> new ForgeSurface.Account("ada", "a@example.invalid", "   "));
    }

    @Test
    @DisplayName("a valid account is accepted, so the refusals above mean something")
    void theValidCaseIsAccepted() {
        // The positive control. Without it, a constructor that threw on
        // everything would pass every test above.
        assertDoesNotThrow(
                () -> new ForgeSurface.Account("ada", "ada@example.invalid", "a-passphrase"));
    }

    @Test
    @DisplayName("an account never prints its own password")
    void redactsThePassword() {
        String shown =
                new ForgeSurface.Account("ada", "ada@example.invalid", "a-passphrase").toString();

        assertFalse(shown.contains("a-passphrase"), shown);
        assertTrue(shown.contains("redacted"), shown);
        assertTrue(shown.contains("ada"), () -> "redaction removed the useful part too: " + shown);
    }
}
