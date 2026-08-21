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

package dev.soulbind.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An absent collection on the wire becomes an empty one, never a crash.
 *
 * <p>Every wire DTO defends the same way — {@code x == null ? empty :
 * copyOf(x)} — and a mutation sweep reported every one of those lines as
 * NO_COVERAGE. That is not the trivia it looks like. These records are what the
 * codec binds JSON into, so a field a connector simply <em>omits</em> arrives
 * as {@code null}: negate that condition and an optional field left out of a
 * request throws {@code NullPointerException} inside the dispatcher instead of
 * defaulting.
 *
 * <p>Which fields are optional is a protocol promise. A connector that sends no
 * flags, no detail, no missing kinds is not malformed — it has nothing to say
 * about them.
 *
 * <p>The copies are also defensive in the other direction: a caller that
 * retains the collection it passed must not be able to change what a bound
 * request says afterwards.
 */
class AbsentFieldTest {

    @Test
    @DisplayName("absent collections default to empty rather than throwing")
    void absentCollectionsDefault() {
        assertTrue(new IdentityView("k", "i", "d", null, "p", null, 0L).flags().isEmpty(),
                "IdentityView flags");
        assertTrue(new RuleView("g", null, false, 0L, "deny").requiredKinds().isEmpty(),
                "RuleView requiredKinds");
        assertTrue(new AuditPushRequest("a", null, null, null, null).detail().isEmpty(),
                "AuditPushRequest detail");
        assertTrue(new EventPollResponse(null, 0L, 0L).events().isEmpty(),
                "EventPollResponse events");
        assertTrue(new EventView(1L, "t", null, null, null, null, "idem", 0L).payload().isEmpty(),
                "EventView payload");
        assertTrue(new AuditEntryView(1L, 0L, "a", "act", null, null, null, null)
                        .detail().isEmpty(),
                "AuditEntryView detail");
        assertTrue(new DecideResponse("deny", "not-linked", "d", 60, null)
                        .missingKinds().isEmpty(),
                "DecideResponse missingKinds");
        assertTrue(new CodeRedeemResponse("s", null).identities().isEmpty(),
                "CodeRedeemResponse identities");
    }

    @Test
    @DisplayName("a present collection is copied, so the sender cannot change it afterwards")
    void collectionsAreCopied() {
        // The mirror of the above and the reason copyOf is there rather than a
        // bare assignment: a caller holding the list it passed could otherwise
        // rewrite a request after core had accepted it.
        List<String> mutable = new java.util.ArrayList<>(List.of("kind-a"));
        DecideResponse response = new DecideResponse("deny", "missing-kinds", "d", 60, mutable);

        mutable.add("kind-b");

        assertEquals(List.of("kind-a"), response.missingKinds(),
                "the response changed after the caller edited the list it passed in");
        assertThrows(UnsupportedOperationException.class,
                () -> response.missingKinds().add("kind-c"),
                "the response's own collection is mutable");
    }

    @Test
    @DisplayName("an identity renders as kind:id, which is what audit and effectors read")
    void identityRefRenders() {
        // Not cosmetic: an effector splits this to find its target, and refuses
        // a ref whose kind is not its own.
        // A neutral kind, not a real platform's name: core and protocol learn
        // platform kinds at runtime from connector registration, and a name
        // compiled in here would mean that is no longer true. The guard caught
        // this file doing it.
        IdentityView view = new IdentityView(
                "kind-a", "315290836042645505", "Alex", Map.of(), "link-code", 1L, 2L);

        assertNotNull(view.ref());
        assertEquals("kind-a:315290836042645505", view.ref());
    }
}
