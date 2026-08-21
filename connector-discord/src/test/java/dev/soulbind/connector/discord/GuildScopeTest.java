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

package dev.soulbind.connector.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Waiting for a configured guild to become visible.
 *
 * <p>Found live, not by reading. The connector's first run against a real
 * Discord registered its commands <em>globally</em> while {@code
 * platform.guild} named a server — the bot had not been invited to it yet, so
 * the guild was not visible, and the fallback was silent apart from one log
 * line. It left three global commands on the application: visible in every
 * server the bot is ever added to, up to an hour to propagate, and there until
 * somebody deletes them by hand.
 *
 * <p>The polling itself is what these cover. Whether the surrounding code then
 * refuses is asserted by the message it throws, which is not something a unit
 * test can reach without a JDA — so the decision was extracted to here, where
 * it can be.
 */
class GuildScopeTest {

    @Test
    @DisplayName("a guild that is already visible is not waited for")
    void alreadyVisible() {
        AtomicInteger pauses = new AtomicInteger();
        boolean found = JdaSurface.awaitGuild(() -> true, 20, pauses::incrementAndGet);

        assertTrue(found);
        assertEquals(0, pauses.get(),
                "the guild was visible on the first look and the connector slept anyway,"
                        + " which delays every start-up for nothing");
    }

    @Test
    @DisplayName("a guild that appears part way through is found, and waited for no longer")
    void appearsLater() {
        // The live case: JDA is connected, the guild arrives with a later
        // GUILD_CREATE. Three looks, then it is there.
        AtomicInteger looks = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();

        boolean found = JdaSurface.awaitGuild(
                () -> looks.incrementAndGet() > 3, 20, pauses::incrementAndGet);

        assertTrue(found, "the guild appeared within the budget and was not found");
        assertEquals(3, pauses.get(),
                "it should have paused exactly as many times as it looked and missed");
    }

    @Test
    @DisplayName("a guild that never appears gives up, having looked the stated number of times")
    void neverAppears() {
        // The typo case, and the not-invited case. Both must end here rather
        // than in a silent fallback to global registration.
        AtomicInteger looks = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();

        boolean found = JdaSurface.awaitGuild(
                () -> {
                    looks.incrementAndGet();
                    return false;
                }, 5, pauses::incrementAndGet);

        assertFalse(found, "a guild that is never visible was reported as found");
        assertEquals(5, looks.get(),
                "the budget is the number of LOOKS, and giving up early would refuse a"
                        + " start-up that was about to succeed");
        assertEquals(5, pauses.get());
    }

    @Test
    @DisplayName("a zero budget looks not at all, rather than looping forever")
    void zeroBudget() {
        AtomicInteger looks = new AtomicInteger();
        assertFalse(JdaSurface.awaitGuild(
                () -> {
                    looks.incrementAndGet();
                    return true;
                }, 0, () -> { }));
        assertEquals(0, looks.get());
    }
}
