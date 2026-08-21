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

package dev.soulbind.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The floor beneath a connector's own rate limits.
 *
 * <p>There was none until Phase 10. Guessing a <em>particular</em> eight
 * character code out of 3.8×10¹¹ is hopeless, but an attacker does not need a
 * particular one — any live code links their account to a stranger's subject,
 * and nothing bounded how fast they could try.
 */
class RedeemThrottleTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
    private static final String REF = "kind-a:guesser";

    private static RedeemThrottle throttle() {
        return new RedeemThrottle(3, Duration.ofMinutes(10), 100);
    }

    @Test
    @DisplayName("guesses are allowed up to the limit, and refused after it")
    void limitHolds() {
        RedeemThrottle t = throttle();

        for (int i = 0; i < 3; i++) {
            assertTrue(t.allow(REF, T0), "attempt " + (i + 1) + " was refused early");
            t.recordGuess(REF, T0);
        }
        assertFalse(t.allow(REF, T0), "the fourth guess was allowed past a limit of three");
    }

    @Test
    @DisplayName("only the account that guessed is affected")
    void otherAccountsAreUntouched() {
        // Keyed on the account, not the connector: throttling the connector
        // would take a whole platform offline because one person is guessing.
        RedeemThrottle t = throttle();
        for (int i = 0; i < 5; i++) {
            t.recordGuess(REF, T0);
        }

        assertFalse(t.allow(REF, T0));
        assertTrue(t.allow("kind-a:somebody-else", T0),
                "an unrelated account was refused because another was guessing");
        assertTrue(t.allow("kind-b:guesser", T0),
                "the same name on a different platform is a different account");
    }

    @Test
    @DisplayName("the window slides, so a blocked account recovers without intervention")
    void windowSlides() {
        // Nobody should have to unblock a person who mistyped. The block is a
        // pause, not a state an operator has to clear.
        RedeemThrottle t = throttle();
        for (int i = 0; i < 3; i++) {
            t.recordGuess(REF, T0);
        }
        assertFalse(t.allow(REF, T0));

        assertFalse(t.allow(REF, T0.plus(Duration.ofMinutes(9))),
                "the block lifted before the window elapsed");
        assertTrue(t.allow(REF, T0.plus(Duration.ofMinutes(11))),
                "the block outlived its window");
    }

    @Test
    @DisplayName("a success clears the record, because mistyping is not attacking")
    void successClears() {
        RedeemThrottle t = throttle();
        t.recordGuess(REF, T0);
        t.recordGuess(REF, T0);
        assertEquals(2, t.recentFailures(REF, T0));

        t.clear(REF);

        assertEquals(0, t.recentFailures(REF, T0),
                "failures survived a successful redeem, so somebody who mistypes twice and"
                        + " then gets it right carries a penalty they did not earn");
        assertTrue(t.allow(REF, T0));
    }

    @Test
    @DisplayName("at capacity it evicts rather than refusing, and that inverts NonceStore")
    void failsOpenAtCapacity() {
        // NonceStore fails CLOSED at capacity because letting a replay through
        // is worse than refusing a request. This must do the opposite:
        // refusing at capacity would let an attacker who fills the map lock
        // every legitimate person out of linking, turning a guessing limit into
        // a denial-of-service lever.
        RedeemThrottle t = new RedeemThrottle(3, Duration.ofMinutes(10), 4);

        for (int i = 0; i < 50; i++) {
            t.recordGuess("kind-a:filler-" + i, T0);
        }

        assertTrue(t.remembered() <= 4,
                "the map grew past its bound: " + t.remembered());
        assertTrue(t.allow("kind-a:a-real-person", T0),
                "a person who has guessed nothing was refused because the map was full");
    }

    @Test
    @DisplayName("a single account cannot grow its own record without bound")
    void perAccountRecordIsBounded() {
        // The count says nothing more past the limit, and an attacker hammering
        // one account must not be able to make core hold a deque per attempt.
        RedeemThrottle t = throttle();
        for (int i = 0; i < 10_000; i++) {
            t.recordGuess(REF, T0);
        }
        assertEquals(3, t.recentFailures(REF, T0),
                "the per-account record is unbounded");
    }

    @Test
    @DisplayName("an account nobody has heard of is allowed, and remembered as nothing")
    void unknownAccountsCostNothing() {
        RedeemThrottle t = throttle();
        assertTrue(t.allow("kind-a:never-seen", T0));
        assertEquals(0, t.remembered(),
                "merely asking about an account created a record for it, so reading the"
                        + " throttle would fill it");
    }
}
