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

package dev.soulbind.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The checker's scheduling and its reporting. */
class CheckerTest {

    private static final String GAME = "game:alex";
    private static final String CHAT = "chat:alex";

    private static ShadowModel linkedModel() {
        ShadowModel model = new ShadowModel();
        model.linked(GAME, CHAT);
        return model;
    }

    private static Checker checker(int period) {
        return new Checker(Invariants.all(), period);
    }

    @Test
    @DisplayName("checks fall on the period and never on action zero")
    void periodicSchedule() {
        Checker checker = checker(25);

        assertFalse(checker.isDue(0), "checked before anything had happened");
        assertFalse(checker.isDue(24));
        assertTrue(checker.isDue(25));
        assertTrue(checker.isDue(50));
        assertFalse(checker.isDue(51));
    }

    @Test
    @DisplayName("a period below one is refused")
    void periodMustBeSane() {
        // Zero would divide by zero; a negative one would never fire and the
        // run would be checked at the end only, silently.
        assertThrows(IllegalArgumentException.class, () -> checker(0));
        assertThrows(IllegalArgumentException.class, () -> checker(-1));
    }

    @Test
    @DisplayName("a healthy run stays clean")
    void healthyRunIsClean() {
        Checker checker = checker(10);
        FakeCore core = new FakeCore().linked(GAME, CHAT).audited("identity.linked");

        assertEquals(List.of(), checker.check(10, linkedModel(), core));
        assertTrue(checker.clean(), "a healthy run reported violations");
    }

    @Test
    @DisplayName("a violation records the action it was first seen after")
    void violationsCarryTheirActionNumber() {
        // With the shrinker deferred, this number is the main thing standing
        // between a failing seed and reading a 400-action trace in full.
        Checker checker = checker(10);
        FakeCore core = new FakeCore().linked(GAME, CHAT).forgot(CHAT);

        List<Checker.Violation> fresh = checker.check(140, linkedModel(), core);

        assertFalse(fresh.isEmpty(), "a vanished link was not reported");
        assertEquals(140, fresh.get(0).afterAction(),
                "the violation does not say when it was first seen");
    }

    @Test
    @DisplayName("a persistent divergence is reported once, not at every check")
    void violationsAreDeduplicated() {
        // Otherwise the report's length measures how long the run continued
        // after the first failure, which says nothing about how many things are
        // wrong -- and buries the second, different violation under fifty copies
        // of the first.
        Checker checker = checker(10);
        FakeCore core = new FakeCore().linked(GAME, CHAT).forgot(CHAT);
        ShadowModel model = linkedModel();

        List<Checker.Violation> first = checker.check(10, model, core);
        List<Checker.Violation> second = checker.check(20, model, core);
        List<Checker.Violation> third = checker.check(30, model, core);

        assertFalse(first.isEmpty(), "the divergence was not caught at all");
        assertEquals(List.of(), second, "the same divergence was reported twice");
        assertEquals(List.of(), third, "the same divergence was reported three times");
        assertEquals(first.size(), checker.violations().size(),
                "the accumulated report grew without anything new happening");
    }

    @Test
    @DisplayName("a second, different divergence is still reported")
    void deduplicationDoesNotSwallowNewFailures() {
        // The risk the deduplication introduces, and the reason it keys on the
        // complaint rather than on the invariant: two different things going
        // wrong in one invariant must both be heard.
        Checker checker = checker(10);
        ShadowModel model = linkedModel();
        model.linked("forum:sam", "chat:sam");

        FakeCore core = new FakeCore()
                .linked(GAME, CHAT)
                .linked("forum:sam", "chat:sam")
                .forgot(CHAT);
        assertFalse(checker.check(10, model, core).isEmpty());

        core.forgot("chat:sam");
        List<Checker.Violation> later = checker.check(20, model, core);

        assertFalse(later.isEmpty(),
                "a second, different divergence was swallowed by the deduplication");
        assertEquals(20, later.get(0).afterAction());
    }

    @Test
    @DisplayName("clean() is false the moment anything has been recorded")
    void cleanTracksTheViolations() {
        // The single most consequential boolean in this module: `clean()` is
        // what every runner reads to decide whether a seed passed. A version
        // that always answered true would report every run in the tier as clean
        // -- including the ones that found real defects -- and mutation showed
        // nothing could tell the difference.
        Checker checker = new Checker(List.of(), 10);
        assertTrue(checker.clean(), "a checker that has seen nothing is not clean");

        checker.record(new Checker.Violation("made-up", "something diverged", 7));

        assertFalse(checker.clean(),
                "a checker holding a violation reported the run as clean, which is every"
                        + " defect this tier can find being reported as a pass");
        assertEquals(1, checker.violations().size());
    }

    @Test
    @DisplayName("a period of exactly one is allowed, and checks every action")
    void aPeriodOfOneIsLegal() {
        // The boundary is `< 1`, not `<= 1`. Checking after every action is
        // expensive but legitimate -- it is what a hunt narrowing down a seed
        // wants -- and refusing it would take away the sharpest setting.
        Checker checker = new Checker(List.of(), 1);

        assertTrue(checker.isDue(1), "a period of one did not check after the first action");
        assertTrue(checker.isDue(2));
    }
}
