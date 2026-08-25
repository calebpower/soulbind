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

package dev.soulbind.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exactly one forum route is exempt from CSRF, and it is the webhook.
 *
 * <p>The webhook is the one endpoint core calls, and core presents an HMAC
 * signature rather than a session — so it has no CSRF token to offer and never
 * will. Registering it on {@code Extend\Routes('forum')} put it behind Flarum's
 * {@code CheckCsrfToken}, which refused it with HTTP 400 before
 * {@code WebhookVerifier} ran. The endpoint was unreachable for as long as it
 * had existed, and the comment above it said the opposite. Found by posting to
 * it on a live forum; see DECISIONS 10.58.
 *
 * <p>The dangerous half of that fix is over-applying it. {@code soulbind.link}
 * is called by a member's own browser, carries a session, and changes that
 * member's link state — exempting it would let any site they visit drive it.
 * So this asserts the exemption list is exactly one name, rather than that it
 * contains the one we wanted.
 *
 * <p>Source text, because {@code extend.php} is declarative wiring with no
 * behaviour to unit-test, and the PHP suite cannot stand up Flarum's middleware
 * stack. The failure it guards against is silent in both directions: too few
 * exemptions and core is refused, too many and CSRF quietly stops protecting a
 * route that needs it.
 */
class CsrfExemptionGuardTest {

    private static final String EXTEND_PHP = "connector-flarum/extend.php";

    /** {@code ->exemptRoute('soulbind.webhook')}, however the calls are chained. */
    private static final Pattern EXEMPTED =
            Pattern.compile("->exemptRoute\\(\\s*'([^']+)'\\s*\\)");

    /** Route names the extension declares, e.g. {@code ->post('/x', 'soulbind.x', ...)}. */
    private static final Pattern DECLARED =
            Pattern.compile("->(?:post|get|put|patch|delete)\\(\\s*'[^']+'\\s*,\\s*'([^']+)'");

    private static List<String> matches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("the webhook route, and only the webhook route, is exempt from CSRF")
    void exactlyTheWebhookIsExempt() throws IOException {
        String extendPhp = Files.readString(SourceTree.repoRoot().resolve(EXTEND_PHP));
        List<String> exempted = matches(EXEMPTED, extendPhp);

        assertEquals(List.of("soulbind.webhook"), exempted,
                "the CSRF exemption list is " + exempted + ", and it must be exactly"
                        + " [soulbind.webhook]. Too few and core's signed webhook is refused"
                        + " by CheckCsrfToken before WebhookVerifier runs -- which is how it"
                        + " shipped. Too many and a route that a member's browser calls with"
                        + " a session stops being protected, which is worse.");
    }

    @Test
    @DisplayName("the member-facing route is declared and deliberately not exempt")
    void theMemberRouteKeepsItsCsrf() throws IOException {
        // Without this, deleting soulbind.link entirely would satisfy the test
        // above. The point is that it exists AND is not exempt.
        String extendPhp = Files.readString(SourceTree.repoRoot().resolve(EXTEND_PHP));
        List<String> declared = matches(DECLARED, extendPhp);

        assertTrue(declared.contains("soulbind.link"),
                "soulbind.link is no longer declared, so the exemption test above is now"
                        + " asserting a rule about a route set that changed underneath it."
                        + " Declared routes: " + declared);
        assertTrue(!matches(EXEMPTED, extendPhp).contains("soulbind.link"),
                "soulbind.link is CSRF-exempt. It is called by the member's own browser"
                        + " with a session and changes their link state; exempting it makes"
                        + " that reachable from any other site they are logged into.");
    }

    @Test
    @DisplayName("the exemption names a route the extension actually declares")
    void theExemptionMatchesARealRoute() throws IOException {
        // An exemption for a route name that does not exist is inert, and reads
        // exactly like one that works.
        String extendPhp = Files.readString(SourceTree.repoRoot().resolve(EXTEND_PHP));
        List<String> declared = matches(DECLARED, extendPhp);
        for (String exempt : matches(EXEMPTED, extendPhp)) {
            assertTrue(declared.contains(exempt),
                    "'" + exempt + "' is exempted from CSRF but is not a route this extension"
                            + " declares " + declared + ". Flarum matches the exemption against"
                            + " a route NAME, so a typo here is silently inert and the endpoint"
                            + " stays refused.");
        }
    }
}
