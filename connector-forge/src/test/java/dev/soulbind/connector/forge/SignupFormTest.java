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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What somebody typed, checked without a server in the room. */
class SignupFormTest {

    private static Map<String, String> complete() {
        Map<String, String> f = new HashMap<>();
        f.put(SignupForm.CODE, "BC23DFG4");
        f.put(SignupForm.USERNAME, "ada");
        f.put(SignupForm.EMAIL, "ada@example.invalid");
        f.put(SignupForm.PASSWORD, "a-passphrase");
        f.put(SignupForm.CONFIRM, "a-passphrase");
        return f;
    }

    private static List<String> problems(Map<String, String> fields) {
        return assertInstanceOf(SignupForm.Parsed.Problems.class, SignupForm.parse(fields))
                .problems();
    }

    @Test
    @DisplayName("a complete form parses, so the refusals below mean something")
    void completeFormParses() {
        SignupForm form = assertInstanceOf(
                SignupForm.Parsed.Ready.class, SignupForm.parse(complete())).form();

        assertEquals("BC23DFG4", form.code());
        assertEquals("ada", form.account().username());
        assertEquals("ada@example.invalid", form.account().email());
        assertEquals("a-passphrase", form.account().password());
    }

    @Test
    @DisplayName("an empty submission reports every missing field at once")
    void everythingMissingAtOnce() {
        // Four problems, not the first one. Somebody told one thing at a time
        // submits four times, and the fourth is where they give up.
        List<String> problems = problems(Map.of());

        assertEquals(4, problems.size(), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("code")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("username")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("email")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("password")), problems::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {SignupForm.CODE, SignupForm.USERNAME, SignupForm.EMAIL})
    @DisplayName("a field present but blank is as absent as one that never arrived")
    void blankIsAbsent(String field) {
        Map<String, String> fields = complete();
        fields.put(field, "   ");

        assertEquals(1, problems(fields).size(), () -> problems(fields).toString());
    }

    @Test
    @DisplayName("two passwords that differ are refused, because the account would be unusable")
    void passwordsMustMatch() {
        Map<String, String> fields = complete();
        fields.put(SignupForm.CONFIRM, "a-different-passphrase");

        assertTrue(problems(fields).stream().anyMatch(p -> p.contains("do not match")),
                () -> problems(fields).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ada smith", "ada\tsmith", "ada\nsmith", "a/b", "a\\b", "ada\u0000"})
    @DisplayName("a username carrying whitespace or a separator is refused")
    void usernameCannotCarrySeparators(String username) {
        // Not an opinion on the forge's naming rules -- this value is built
        // into a request to the forge, and one of these makes that request
        // mean something other than what it says.
        Map<String, String> fields = complete();
        fields.put(SignupForm.USERNAME, username);

        assertTrue(problems(fields).stream().anyMatch(p -> p.contains("spaces")),
                () -> problems(fields).toString());
    }

    @Test
    @DisplayName("a code and a name are trimmed; a password is not")
    void trimmingIsSelective() {
        // Pasting a code picks up a trailing space and that is not a choice.
        // A password's spaces ARE part of it, and removing them silently makes
        // a password that cannot be typed back.
        Map<String, String> fields = complete();
        fields.put(SignupForm.CODE, "  BC23DFG4  ");
        fields.put(SignupForm.USERNAME, " ada ");
        fields.put(SignupForm.PASSWORD, " spaced  ");
        fields.put(SignupForm.CONFIRM, " spaced  ");

        SignupForm form = assertInstanceOf(
                SignupForm.Parsed.Ready.class, SignupForm.parse(fields)).form();

        assertEquals("BC23DFG4", form.code());
        assertEquals("ada", form.account().username());
        assertEquals(" spaced  ", form.account().password(),
                "a password was trimmed, so it can never be typed back the same way");
    }

    @Test
    @DisplayName("a null value is treated as absent rather than thrown at")
    void nullsAreAbsent() {
        // A decoder handing back an explicit null for an empty input is common
        // enough that a NullPointerException here would be a 500 on a page
        // somebody is standing in front of.
        Map<String, String> fields = new HashMap<>();
        fields.put(SignupForm.CODE, null);
        fields.put(SignupForm.USERNAME, null);
        fields.put(SignupForm.EMAIL, null);
        fields.put(SignupForm.PASSWORD, null);
        fields.put(SignupForm.CONFIRM, null);

        assertEquals(4, problems(fields).size(), () -> problems(fields).toString());
    }

    @Test
    @DisplayName("a missing confirmation is a mismatch, not a pass")
    void absentConfirmationIsAMismatch() {
        Map<String, String> fields = complete();
        fields.remove(SignupForm.CONFIRM);

        assertTrue(problems(fields).stream().anyMatch(p -> p.contains("do not match")),
                () -> problems(fields).toString());
    }
}
