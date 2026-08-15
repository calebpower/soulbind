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
                    () -> "round " + " admitted " + winners.get() + " uses of one nonce");
        }
    }
}
