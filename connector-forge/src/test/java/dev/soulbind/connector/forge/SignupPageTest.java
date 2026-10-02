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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The page, as a string.
 *
 * <p>The property asserted hardest is that nothing a person types becomes
 * markup. It is testable here precisely because this class is pure; behind a
 * server it would be the kind of property asserted once and then never again.
 */
class SignupPageTest {

    private static final String PATH = "/soulbind/signup";

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert(1)</script>",
        "ada" + '"' + " onfocus=" + '"' + "alert(1)",
        "ada'>",
        "a&b"
    })
    @DisplayName("nothing typed into a field can escape its attribute")
    void valuesCannotBecomeMarkup(String hostile) {
        String html = SignupPage.form(PATH, List.of(), Map.of(SignupForm.USERNAME, hostile));

        // The raw sequence must not survive anywhere in the document. Checking
        // for the absence of "<script" alone would pass a payload that broke
        // out of the attribute with a quote and used an event handler instead.
        assertFalse(html.contains(hostile),
                () -> "a value reached the document unescaped: " + html);
    }

    @Test
    @DisplayName("a problem message is escaped too, not only a field value")
    void problemsAreEscaped() {
        // Problems are partly composed from input -- a refusal names the
        // username -- so the list is as much an injection point as the fields.
        String html = SignupPage.form(PATH, List.of("<b>no</b>"), Map.of());

        assertFalse(html.contains("<b>no</b>"), html);
        assertTrue(html.contains("&lt;b&gt;no&lt;/b&gt;"), html);
    }

    @Test
    @DisplayName("the password is never written back into the form")
    void passwordIsNeverPrefilled() {
        // Even on a redisplay, and even though it would save a retype. A
        // password in the markup is a password in every proxy log and browser
        // cache between here and the person.
        String html = SignupPage.form(PATH, List.of("try again"),
                Map.of(SignupForm.USERNAME, "ada", SignupForm.PASSWORD, "a-passphrase"));

        assertFalse(html.contains("a-passphrase"), html);
        assertTrue(html.contains("ada"), () -> "the non-secret fields were dropped too: " + html);
    }

    @Test
    @DisplayName("the form posts to the path it was served at")
    void formPostsToItsOwnPath() {
        // A form whose action does not match the proxy's rule is a submit
        // button that 404s, and the proxy will not say why.
        assertTrue(SignupPage.form(PATH, List.of(), Map.of()).contains("action=\"" + PATH + "\""),
                "the form would submit somewhere other than where it was served");
    }

    @Test
    @DisplayName("a result message is escaped")
    void resultIsEscaped() {
        String html = SignupPage.result(new SignupResponse(403, "the name '<b>x</b>' is taken"));

        assertFalse(html.contains("<b>x</b>"), html);
    }

    @Test
    @DisplayName("the three outcomes are visually distinct, not three identical pages")
    void outcomesLookDifferent() {
        String ok = SignupPage.result(new SignupResponse(200, "done"));
        String no = SignupPage.result(new SignupResponse(403, "not yet"));
        String wait = SignupPage.result(new SignupResponse(503, "hold on"));

        assertTrue(ok.contains("Done"), ok);
        assertTrue(no.contains("Not yet"), no);
        assertTrue(wait.contains("Try again shortly"), wait);
        assertFalse(ok.contains("Not yet"), ok);

        // The styling too, not only the heading: the colour is what somebody
        // reads before the words, and three outcomes sharing one class is a
        // page that looks the same whether it worked or not.
        assertTrue(ok.contains("class=\"ok\""), ok);
        assertTrue(no.contains("class=\"no\""), no);
        assertTrue(wait.contains("class=\"wait\""), wait);
    }

    @Test
    @DisplayName("escaping covers all five characters, including both quote forms")
    void escapeCoversBothQuotes() {
        // Three-character escaping is the common half-measure, and values here
        // are interpolated into attributes where a bare quote is enough.
        String escaped = SignupPage.escape("&<>" + '"' + "'");

        assertTrue(escaped.contains("&amp;"), escaped);
        assertTrue(escaped.contains("&lt;"), escaped);
        assertTrue(escaped.contains("&gt;"), escaped);
        assertTrue(escaped.contains("&quot;"), escaped);
        assertTrue(escaped.contains("&#39;"), escaped);
    }

    @Test
    @DisplayName("ordinary text passes through unchanged")
    void ordinaryTextIsUntouched() {
        // The positive control: an escaper that mangled everything would pass
        // every test above.
        assertTrue(SignupPage.escape("ada smith-1").equals("ada smith-1"));
    }
}
