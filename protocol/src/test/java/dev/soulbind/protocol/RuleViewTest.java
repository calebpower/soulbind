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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link RuleView} decides before anybody reads it.
 *
 * <p>Both invariants below were asserted only from {@code core}, through the
 * wire, which left them uncovered <b>here</b> -- and a record's own guarantees
 * are the one thing that should not depend on a caller in another module
 * happening to exercise them. The blank-description normalisation in particular
 * is load-bearing: it is what makes "absent" and "empty" the same request, and
 * therefore what stops a caller erasing a gate's documentation by sending a
 * field they left blank.
 *
 * <p>DECISIONS 10.47.
 */
class RuleViewTest {

    private static RuleView withDescription(String description) {
        return new RuleView("game.join", List.of(), false, 0L, "allow", description, null);
    }

    @Test
    @DisplayName("a blank description is the same request as no description")
    void blankDescriptionIsAbsent() {
        assertNull(withDescription(null).description());
        assertNull(
                withDescription("").description(),
                "an empty string must not reach storage as a description; it is a caller who "
                        + "sent the field and had nothing to put in it");
        assertNull(
                withDescription("   ").description(),
                "whitespace is not documentation, and storing it would overwrite a real note "
                        + "with something that renders as blank");
        assertEquals("what it is for", withDescription("what it is for").description());
        assertEquals(
                "  padded  ",
                withDescription("  padded  ").description(),
                "a description that is not blank is stored as sent -- trimming is a separate "
                        + "decision nobody has made, and making it silently here would mean the "
                        + "value read back differs from the value written");
    }

    @Test
    @DisplayName("requiredKinds is never null and never the caller's list")
    void requiredKindsIsDefensivelyCopied() {
        assertEquals(List.of(), new RuleView("g", null, false, 0L, "allow").requiredKinds());

        List<String> mutable = new ArrayList<>(List.of("game"));
        RuleView view = new RuleView("g", mutable, false, 0L, "allow");
        mutable.add("chat");
        assertEquals(
                List.of("game"),
                view.requiredKinds(),
                "a rule that changes after it was constructed is a rule nobody can reason "
                        + "about; the copy is what makes this value a value");
        assertThrows(
                UnsupportedOperationException.class,
                () -> view.requiredKinds().add("forum"),
                "and the copy handed out must not be writable either");
    }

    @Test
    @DisplayName("the short form is the rule alone, saying nothing about the gate")
    void shortFormSaysNothingAboutTheGate() {
        RuleView view = new RuleView("g", List.of("game"), true, 30L, "deny");

        // The mutant that passes "" here instead of null is EQUIVALENT, and
        // for a reason worth stating rather than rediscovering: the compact
        // constructor normalises blank to null, so the two are the same
        // request by construction. That is the point of the normalisation --
        // there is one way to say nothing, not two. DECISIONS 10.47.
        assertNull(
                view.description(),
                "a caller using the rule-only constructor has said nothing about the gate, "
                        + "and 'said nothing' must not arrive as 'set it to nothing'");
        assertNull(view.registeredBy());
        assertEquals("g", view.gate());
        assertEquals(List.of("game"), view.requiredKinds());
        assertEquals(true, view.requireLinked());
        assertEquals(30L, view.graceSeconds());
        assertEquals("deny", view.defaultEffect());
    }
}
