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
package dev.soulbind.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.policy.Decision;
import dev.soulbind.policy.Effect;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SDK's behaviour when core cannot be reached.
 *
 * <p>The first test in this file is the gate item: <b>the shipped default is
 * fail-closed.</b> Everything else here exists to make sure that default cannot
 * be reached around.
 */
class DecisionCacheTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final String GATE = "gate.x";
    private static final String REF = "kind-a:acct-1";

    private static Decision allow(int ttl) {
        return new Decision(
                Effect.ALLOW, Decision.Reason.REQUIREMENTS_MET, "ok", ttl, List.of());
    }

    // --- the gate item ----------------------------------------------------------

    @Test
    @DisplayName("GATE: the shipped default is fail-CLOSED")
    void defaultIsFailClosed() {
        // A gate that opens whenever the dispatcher is down is a gate an
        // attacker opens by taking the dispatcher down.
        assertEquals(DecisionCache.FailMode.CLOSED, new DecisionCache().failMode());

        DecisionCache.Answer answer = new DecisionCache().whenUnreachable(GATE, REF, NOW);
        assertEquals(Effect.DENY, answer.decision().effect());
        assertEquals(DecisionCache.Source.FAIL_MODE, answer.source());
    }

    @Test
    @DisplayName("a null fail mode is CLOSED, not a crash and not OPEN")
    void nullFailModeIsClosed() {
        assertEquals(
                DecisionCache.FailMode.CLOSED,
                new DecisionCache(null).failMode(),
                "a caller that meant to configure something and did not must not get the "
                        + "permissive answer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "yes", "true", "1", "opne", "Open ", "CLOSED", "nonsense"})
    @DisplayName("only the exact word 'open' opens a gate")
    void onlyOpenOpens(String configured) {
        // A typo in a fail-mode must never be the thing that opens a gate.
        DecisionCache.FailMode mode = DecisionCache.FailMode.fromConfigName(configured);
        if (configured.strip().equalsIgnoreCase("open")) {
            assertEquals(DecisionCache.FailMode.OPEN, mode);
        } else {
            assertEquals(
                    DecisionCache.FailMode.CLOSED,
                    mode,
                    () -> "'" + configured + "' opened a gate");
        }
    }

    @Test
    @DisplayName("a null configuration value is CLOSED")
    void nullConfigIsClosed() {
        assertEquals(DecisionCache.FailMode.CLOSED, DecisionCache.FailMode.fromConfigName(null));
    }

    @Test
    @DisplayName("fail-open is reachable, but only deliberately")
    void failOpenIsSpellable() {
        // Some gates genuinely should not lock a community out of its own forum
        // over a network blip. The point is that it is chosen, never inherited.
        DecisionCache cache = new DecisionCache(DecisionCache.FailMode.OPEN);
        assertEquals(Effect.ALLOW, cache.whenUnreachable(GATE, REF, NOW).decision().effect());
    }

    @Test
    @DisplayName("the fail-closed message blames the system, not the person")
    void messageBlamesTheSystem() {
        // Somebody refused because a server they have never heard of is
        // unreachable should not be told they are not allowed.
        String message = new DecisionCache().whenUnreachable(GATE, REF, NOW).decision().detail();
        assertTrue(message.contains("our side, not yours"), message);
        assertFalse(
                message.toLowerCase(java.util.Locale.ROOT).contains("denied"),
                () -> "the message reads as a refusal of the person: " + message);
    }

    // --- the cache --------------------------------------------------------------

    @Test
    @DisplayName("a cached decision survives an outage")
    void cacheCoversAnOutage() {
        DecisionCache cache = new DecisionCache();
        cache.store(GATE, REF, allow(60), NOW);

        DecisionCache.Answer answer = cache.whenUnreachable(GATE, REF, NOW.plusSeconds(30));
        assertEquals(Effect.ALLOW, answer.decision().effect());
        assertEquals(DecisionCache.Source.CACHED, answer.source());
    }

    @Test
    @DisplayName("an expired cache entry does NOT cover an outage")
    void expiredCacheDoesNotCover() {
        DecisionCache cache = new DecisionCache();
        cache.store(GATE, REF, allow(60), NOW);

        DecisionCache.Answer answer = cache.whenUnreachable(GATE, REF, NOW.plusSeconds(61));
        assertEquals(Effect.DENY, answer.decision().effect());
        assertEquals(DecisionCache.Source.FAIL_MODE, answer.source());
    }

    @Test
    @DisplayName("a TTL of zero is not cached at all")
    void zeroTtlIsNotCached() {
        // A grace decision at the edge of its window carries TTL zero. Caching
        // it would hold a gate open past the moment it should have closed.
        DecisionCache cache = new DecisionCache();
        cache.store(GATE, REF, allow(0), NOW);
        assertEquals(0, cache.size());
        assertTrue(cache.cached(GATE, REF, NOW).isEmpty());
    }

    @Test
    @DisplayName("storing a zero-TTL decision evicts an existing entry")
    void zeroTtlEvicts() {
        // Otherwise a gate that starts returning "do not cache this" would keep
        // serving the previous answer for its full lifetime -- which is exactly
        // when a grace window has just closed.
        DecisionCache cache = new DecisionCache();
        cache.store(GATE, REF, allow(60), NOW);
        cache.store(GATE, REF, allow(0), NOW);
        assertTrue(cache.cached(GATE, REF, NOW).isEmpty());
    }

    @Test
    @DisplayName("a fail-mode answer is never itself cached")
    void failModeAnswerIsNotCacheable() {
        // Caching it would extend an outage's effect beyond the outage.
        DecisionCache cache = new DecisionCache();
        assertEquals(0, cache.whenUnreachable(GATE, REF, NOW).decision().ttlSeconds());
    }

    @Test
    @DisplayName("a cache entry expiring exactly now is still good")
    void cacheBoundaryIsExclusive() {
        // The same convention as every other deadline in the system.
        DecisionCache cache = new DecisionCache();
        cache.store(GATE, REF, allow(60), NOW);
        assertTrue(cache.cached(GATE, REF, NOW.plusSeconds(60)).isPresent());
        assertTrue(cache.cached(GATE, REF, NOW.plusSeconds(60).plusMillis(1)).isEmpty());
    }

    @Test
    @DisplayName("two gates, or two identities, never share a cache entry")
    void keysDoNotCollide() {
        // Joining with a colon would let ("a:b","c") and ("a","b:c") collide --
        // and a collision here serves one subject's decision to another.
        DecisionCache cache = new DecisionCache();
        cache.store("a:b", "c", allow(60), NOW);

        assertTrue(
                cache.cached("a", "b:c", NOW).isEmpty(),
                "a different (gate, identity) pair read another's decision");
        assertTrue(cache.cached("a:b", "c", NOW).isPresent());
    }

    @Test
    @DisplayName("one identity's decision is not served to another")
    void identitiesAreSeparate() {
        DecisionCache cache = new DecisionCache();
        cache.store(GATE, "kind-a:alice", allow(60), NOW);
        assertTrue(cache.cached(GATE, "kind-a:bob", NOW).isEmpty());
    }

    @Test
    @DisplayName("an expired entry is dropped rather than accumulating")
    void expiredEntriesAreDropped() {
        DecisionCache cache = new DecisionCache();
        cache.store(GATE, REF, allow(60), NOW);
        assertEquals(1, cache.size());

        cache.cached(GATE, REF, NOW.plusSeconds(61));
        assertEquals(
                0, cache.size(),
                "an unbounded cache of expired decisions is a slow leak in a long-running "
                        + "proxy");
    }

    @Test
    @DisplayName("a denial is cached too, and denies during an outage")
    void deniedDecisionsAreCached() {
        // Caching only the allows would mean an outage silently upgrades every
        // recent denial to whatever the fail mode says -- and under fail-open
        // that is an upgrade to allow.
        DecisionCache cache = new DecisionCache(DecisionCache.FailMode.OPEN);
        cache.store(GATE, REF, new Decision(
                Effect.DENY, Decision.Reason.MISSING_KINDS, "no", 60, List.of("kind-b")), NOW);

        DecisionCache.Answer answer = cache.whenUnreachable(GATE, REF, NOW.plusSeconds(1));
        assertEquals(
                Effect.DENY, answer.decision().effect(),
                "a fresh denial was replaced by the fail-open answer");
        assertNotEquals(DecisionCache.Source.FAIL_MODE, answer.source());
    }
}
