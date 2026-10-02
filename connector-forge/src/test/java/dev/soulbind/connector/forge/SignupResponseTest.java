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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An outcome, as a person receives it. */
class SignupResponseTest {

    @Test
    @DisplayName("a refusal and an outage do not share a status")
    void refusalAndOutageAreDistinguishable() {
        // The claim the whole connector rests on, at the last place it can be
        // lost. Serving one as the other has somebody concluding they are
        // banned during an incident, or retrying a refusal forever.
        SignupResponse refused =
                SignupResponse.of(new Registration.Outcome.Refused("no", List.of()));
        SignupResponse unavailable =
                SignupResponse.of(new Registration.Outcome.Unavailable("our fault"));

        assertEquals(403, refused.status());
        assertEquals(503, unavailable.status());
        assertNotEquals(refused.status(), unavailable.status());
    }

    @Test
    @DisplayName("a created account is reported with its name")
    void createdNamesTheAccount() {
        SignupResponse response = SignupResponse.of(new Registration.Outcome.Created("ada"));

        assertEquals(200, response.status());
        assertTrue(response.message().contains("ada"), response::message);
    }

    @Test
    @DisplayName("a link tells somebody what is left to do")
    void linkedSaysWhatIsNext() {
        SignupResponse response = SignupResponse.of(new Registration.Outcome.Linked("s-1"));

        assertEquals(200, response.status());
        assertFalse(response.message().isBlank());
    }

    @Test
    @DisplayName("a refusal names what to go and link, and says no new code is needed")
    void refusalNamesWhatIsMissing() {
        SignupResponse response = SignupResponse.of(new Registration.Outcome.Refused(
                "You need a verified forum account.", List.of("forum")));

        assertTrue(response.message().contains("forum"), response::message);
        assertTrue(response.message().contains("not need a new code"),
                () -> "somebody refused after linking will assume their code is spent: "
                        + response.message());
    }

    @Test
    @DisplayName("two missing kinds read as a list, not as one malformed word")
    void twoMissingKindsArePlural() {
        SignupResponse response = SignupResponse.of(
                new Registration.Outcome.Refused("Not yet.", List.of("forum", "game")));

        assertTrue(response.message().contains("forum and game"), response::message);
        assertTrue(response.message().contains("accounts"),
                () -> "the plural was wrong, which reads as a bug to whoever is reading it: "
                        + response.message());
    }

    @Test
    @DisplayName("one missing kind is singular")
    void oneMissingKindIsSingular() {
        // The positive control for the plural above: without it, hard-coding
        // either form would pass one of the two tests and look fine.
        SignupResponse response = SignupResponse.of(
                new Registration.Outcome.Refused("Not yet.", List.of("forum")));

        assertTrue(response.message().contains("forum account,"), response::message);
        assertFalse(response.message().contains("accounts"), response::message);
    }

    @Test
    @DisplayName("a refusal with nothing missing is left exactly as it was")
    void refusalWithoutKindsIsUnchanged() {
        // A name already in use has no missing identity, and appending "link
        // your  accounts" to it would be worse than saying nothing.
        SignupResponse response = SignupResponse.of(new Registration.Outcome.Refused(
                "the name 'ada' is already in use on the forge", List.of()));

        assertEquals("the name 'ada' is already in use on the forge", response.message());
    }

    @Test
    @DisplayName("an outage repeats the system's own wording rather than inventing one")
    void outageCarriesItsMessage() {
        SignupResponse response =
                SignupResponse.of(new Registration.Outcome.Unavailable("temporarily on hold"));

        assertEquals("temporarily on hold", response.message());
    }
}
