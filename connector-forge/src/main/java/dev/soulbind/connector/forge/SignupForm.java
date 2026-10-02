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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What somebody typed, before anything is done with it.
 *
 * <p>Pure, and deliberately so: a form is the part of a web surface most worth
 * testing and the part least worth standing a server up for. Nothing here names
 * a transport type, so all of it runs as plain assertions.
 *
 * <p><b>It does not judge what the host judges.</b> Password strength, username
 * character rules and what counts as an email address are the forge's policy,
 * and a second opinion here would either contradict it or drift from it. The
 * checks below are only the ones this form owns: that a field arrived at all,
 * that the two passwords match, and that a username cannot carry whitespace or
 * control characters into a request built around it.
 */
public record SignupForm(String code, ForgeSurface.Account account) {

    /** Field names, stated once so the server and the tests cannot disagree. */
    public static final String CODE = "code";
    public static final String USERNAME = "username";
    public static final String EMAIL = "email";
    public static final String PASSWORD = "password";
    public static final String CONFIRM = "confirm";

    /** Either a form, or every reason it is not one. */
    public sealed interface Parsed {

        record Ready(SignupForm form) implements Parsed {}

        /**
         * Everything wrong with it, together.
         *
         * <p>One problem per attempt means somebody submits four times to learn
         * four things, and the fourth submission is where they give up.
         */
        record Problems(List<String> problems) implements Parsed {

            public Problems {
                problems = List.copyOf(problems);
            }
        }
    }

    public static Parsed parse(Map<String, String> fields) {
        List<String> problems = new ArrayList<>();

        String code = value(fields, CODE);
        String username = value(fields, USERNAME);
        String email = value(fields, EMAIL);
        String password = raw(fields, PASSWORD);
        String confirm = raw(fields, CONFIRM);

        if (code.isBlank()) {
            problems.add("Enter the code you were given.");
        }
        if (username.isBlank()) {
            problems.add("Choose a username.");
        } else if (hasSpaceOrControl(username)) {
            // Not a view on what the forge permits -- it is that this value is
            // built into a request to the forge, and a username carrying a
            // newline or a slash makes that request mean something else.
            problems.add("A username cannot contain spaces or control characters.");
        }
        if (email.isBlank()) {
            problems.add("Enter an email address.");
        }
        if (password.isEmpty()) {
            problems.add("Choose a password.");
        } else if (!password.equals(confirm)) {
            // The form's own business, unlike password strength: somebody who
            // mistyped it twice differently would be locked out of an account
            // that was created successfully.
            problems.add("The two passwords do not match.");
        }

        if (!problems.isEmpty()) {
            return new Parsed.Problems(problems);
        }
        return new Parsed.Ready(
                new SignupForm(code, new ForgeSurface.Account(username, email, password)));
    }

    /** Trimmed, because a trailing space in a pasted code or name is not a choice. */
    private static String value(Map<String, String> fields, String name) {
        String v = fields.get(name);
        return v == null ? "" : v.strip();
    }

    /**
     * Untrimmed, because a password's leading and trailing spaces are part of
     * it and silently removing them makes a password that cannot be typed back.
     */
    private static String raw(Map<String, String> fields, String name) {
        String v = fields.get(name);
        return v == null ? "" : v;
    }

    private static boolean hasSpaceOrControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '/' || c == '\\') {
                return true;
            }
        }
        return false;
    }
}
