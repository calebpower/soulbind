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

package dev.soulbind.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.protocol.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Replay protection: the nonce half. */
class NonceStoreTest {

    private static final Duration WINDOW = Duration.ofSeconds(300);
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);

    @Test
    @DisplayName("a new nonce is accepted once and refused thereafter")
    void singleUse() {
        NonceStore store = new NonceStore(WINDOW);
        assertTrue(store.recordIfNew("n1", NOW));
        assertFalse(store.recordIfNew("n1", NOW), "the second use is the replay");
    }

    @Test
    @DisplayName("distinct nonces do not interfere")
    void distinctNonces() {
        NonceStore store = new NonceStore(WINDOW);
        assertTrue(store.recordIfNew("a", NOW));
        assertTrue(store.recordIfNew("b", NOW));
    }

    @Test
    @DisplayName("entries older than the window are forgotten")
    void expiry() {
        // Remembering a nonce beyond the window proves nothing: a signature that
        // old is already refused on its timestamp. Keeping it would only grow
        // the store forever.
        NonceStore store = new NonceStore(WINDOW);
        store.recordIfNew("n1", NOW);

        store.sweep(NOW.plus(WINDOW).plusSeconds(1));
        assertEquals(0, store.size());
        assertTrue(store.recordIfNew("n1", NOW.plus(WINDOW).plusSeconds(1)));
    }

    @Test
    @DisplayName("an entry still inside the window survives a sweep")
    void sweepKeepsFresh() {
        NonceStore store = new NonceStore(WINDOW);
        store.recordIfNew("n1", NOW);
        store.sweep(NOW.plusSeconds(1));
        assertEquals(1, store.size());
        assertFalse(store.recordIfNew("n1", NOW.plusSeconds(1)));
    }

    // --- the two thresholds -------------------------------------------------
    //
    // Everything above calls sweep() by hand. Nothing reached the code that
    // decides whether a sweep happens on its own, or the refusal when the store
    // is full -- 256 and 1,000,000 insertions away respectively in production.
    // Mutation coverage found all six: negating either conditional, moving
    // either boundary, deleting the sweep call and deleting the counter reset
    // all left every assertion in this file green.

    @Test
    @DisplayName("insertions eventually sweep on their own, without anyone calling sweep")
    void sweepsItselfAfterTheInterval() {
        NonceStore store = new NonceStore(WINDOW, 1_000, 4);
        assertTrue(store.recordIfNew("old", NOW));

        // Past the window, so `old` is now expired. Three more insertions take
        // the counter to the interval and the sweep must happen unprompted --
        // this is the only reclamation a long-running core gets.
        Instant later = NOW.plus(WINDOW).plusSeconds(1);
        assertTrue(store.recordIfNew("a", later));
        assertTrue(store.recordIfNew("b", later));
        assertTrue(store.recordIfNew("c", later));

        assertEquals(3, store.size(),
                "the expired entry is still there, so no automatic sweep ran");
        assertTrue(store.sweepCount() >= 1, "no sweep was triggered by insertion");
    }

    @Test
    @DisplayName("the sweep counter resets, so sweeps stay amortised rather than constant")
    void sweepStaysAmortised() {
        NonceStore store = new NonceStore(WINDOW, 1_000, 4);
        for (int i = 0; i < 4; i++) {
            store.recordIfNew("n" + i, NOW);
        }
        assertEquals(1, store.sweepCount(), "the first interval did not sweep exactly once");

        // Three more must NOT sweep. Without the counter reset every insertion
        // past the first threshold sweeps forever: correct answers, and the cost
        // of the store goes quadratic in a way no nonce assertion can see.
        for (int i = 4; i < 7; i++) {
            store.recordIfNew("n" + i, NOW);
        }
        assertEquals(1, store.sweepCount(),
                "sweeping on every insertion after the first threshold -- the counter is"
                        + " not being reset");

        store.recordIfNew("n7", NOW);
        assertEquals(2, store.sweepCount(), "the second interval did not sweep");
    }

    @Test
    @DisplayName("a full store refuses rather than growing, and does not record the refused nonce")
    void failsClosedWhenFull() {
        // Fail CLOSED: refusing a legitimate request beats accepting a replay,
        // and an unbounded store is a memory-exhaustion path an attacker
        // controls. Every entry here is fresh, so the sweep frees nothing.
        NonceStore store = new NonceStore(WINDOW, 4, 1_000);
        for (int i = 0; i < 4; i++) {
            assertTrue(store.recordIfNew("n" + i, NOW));
        }
        assertFalse(store.recordIfNew("overflow", NOW),
                "a full store accepted another nonce instead of failing closed");
        assertEquals(4, store.size(), "the refused nonce was recorded anyway");

        // And the refusal must not be sticky in the wrong direction: once the
        // window passes, the store recovers on its own.
        Instant later = NOW.plus(WINDOW).plusSeconds(1);
        assertTrue(store.recordIfNew("after-expiry", later),
                "the store never recovered after its entries expired");
    }

    @Test
    @DisplayName("two threads racing the same nonce: exactly one wins")
    void concurrentSameNonce() throws Exception {
        // The reason recordIfNew is putIfAbsent rather than containsKey-then-put.
        // A check-then-act would let both requests through, which is precisely
        // the replay this class exists to stop -- and it would only happen under
        // load, which is when somebody is most likely to be trying.
        int threads = 32;
        for (int round = 0; round < 50; round++) {
            NonceStore store = new NonceStore(WINDOW);
            String nonce = "race-" + round;
            int finalRound = round;
            AtomicInteger winners = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (store.recordIfNew(nonce, NOW)) {
                            winners.incrementAndGet();
                        }
                    });
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            assertEquals(
                    1, winners.get(),
                    () -> "round " + finalRound + " admitted " + winners.get()
                            + " uses of one nonce");
        }
    }
}
