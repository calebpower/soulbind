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

import java.util.List;

/**
 * What a person is told, and what status carries it.
 *
 * <p>Pure, for the same reason {@link SignupForm} is: the mapping from an
 * outcome to a status is a decision worth asserting, and standing a server up
 * to assert it would make it the one thing nobody checks.
 *
 * <p>The distinction the whole connector rests on survives to here. A refusal
 * is <b>403</b> — an answer, about this person, which retrying will not change.
 * An outage is <b>503</b> — not about them at all, and worth retrying. Serving
 * one as the other is how somebody concludes they are banned during an
 * incident, or keeps retrying a refusal forever.
 */
public record SignupResponse(int status, String message) {

    public static SignupResponse of(Registration.Outcome outcome) {
        return switch (outcome) {
            case Registration.Outcome.Created created -> new SignupResponse(
                    200,
                    "Your account '" + created.username() + "' is ready. You can sign in to the"
                            + " forge with it now.");
            case Registration.Outcome.Linked linked -> new SignupResponse(
                    200,
                    "That code is now linked to this forge name. Choose a password to finish"
                            + " creating the account.");
            case Registration.Outcome.Refused refused -> new SignupResponse(
                    403, withMissing(refused.detail(), refused.missingKinds()));
            case Registration.Outcome.Unavailable unavailable -> new SignupResponse(
                    503, unavailable.message());
        };
    }

    /** The reason, and what to go and do about it. */
    private static String withMissing(String detail, List<String> missingKinds) {
        if (missingKinds.isEmpty()) {
            return detail;
        }
        // A refusal that names the requirement without naming what satisfies it
        // leaves somebody re-reading the same sentence. The kinds come from
        // core sorted, so the wording is stable rather than incidental.
        return detail + " Link your " + String.join(" and ", missingKinds)
                + (missingKinds.size() == 1 ? " account" : " accounts")
                + ", then come back to this page -- you will not need a new code.";
    }
}
