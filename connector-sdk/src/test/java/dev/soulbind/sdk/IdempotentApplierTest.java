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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SDK's dedup, which is what makes at-least-once survivable. */
class IdempotentApplierTest {

    @Test
    @DisplayName("an effect runs once however many times its event is delivered")
    void appliesOnce() {
        IdempotentApplier applier = new IdempotentApplier();
        AtomicInteger applied = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            applier.applyOnce("key-1", applied::incrementAndGet);
        }
        assertEquals(1, applied.get());
    }

    @Test
    @DisplayName("distinct events both run")
    void distinctKeysBothRun() {
        IdempotentApplier applier = new IdempotentApplier();
        AtomicInteger applied = new AtomicInteger();

        applier.applyOnce("a", applied::incrementAndGet);
        applier.applyOnce("b", applied::incrementAndGet);
        assertEquals(2, applied.get());
    }

    @Test
    @DisplayName("a failing effect is NOT marked applied, so the retry works")
    void failureIsRetryable() {
        // Marking it applied would swallow the retry: the effect never happened
        // and never will, and the connector's state silently diverges from what
        // core believes it did.
        IdempotentApplier applier = new IdempotentApplier();
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> applier.applyOnce("key-1", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("effector down");
        }));
        assertFalse(applier.hasApplied("key-1"));

        assertTrue(applier.applyOnce("key-1", attempts::incrementAndGet));
        assertEquals(2, attempts.get(), "the retry must actually run");
    }

    @Test
    @DisplayName("an event with no key is refused rather than applied unsafely")
    void keyIsRequired() {
        // Applying it would make the guarantee untrue for exactly the events
        // that lost their key -- which is the worst set to make an exception for.
        IdempotentApplier applier = new IdempotentApplier();
        for (String key : new String[] {null, "", "   "}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> applier.applyOnce(key, () -> { }));
        }
    }

    @Test
    @DisplayName("a zero capacity is refused, because it would dedup nothing")
    void capacityMustBeUsable() {
        // Worse than not having an applier, because it looks like it does.
        assertThrows(IllegalArgumentException.class, () -> new IdempotentApplier(0));
        assertThrows(IllegalArgumentException.class, () -> new IdempotentApplier(-1));
    }

    @Test
    @DisplayName("the cache is bounded, and EVICTS rather than refusing")
    void boundedByEviction() {
        // The opposite choice from the replay-nonce store, deliberately: there,
        // forgetting means failing to detect a replay, so it fails closed. Here,
        // forgetting means applying an idempotent effect twice -- harmless by
        // definition, since that is what makes it worth deduping. Refusing to
        // apply events because a cache filled would be an outage caused by
        // bookkeeping.
        IdempotentApplier applier = new IdempotentApplier(3);
        for (int i = 0; i < 10; i++) {
            assertTrue(
                    applier.applyOnce("key-" + i, () -> { }),
                    "the applier stopped applying events once its cache filled");
        }
        assertEquals(3, applier.remembered());
    }

    @Test
    @DisplayName("eviction is oldest-first, and a repeated key stays live")
    void evictionIsAccessOrdered() {
        // Redelivery follows a reconnect, and a reconnect replays the RECENT
        // tail -- so the recent keys are the ones worth keeping. A connector
        // being hammered with one repeated redelivery keeps that key live.
        IdempotentApplier applier = new IdempotentApplier(3);
        applier.applyOnce("old", () -> { });
        applier.applyOnce("b", () -> { });
        applier.applyOnce("c", () -> { });

        // Touch "old" so it is no longer eldest.
        assertFalse(applier.applyOnce("old", () -> { }));

        applier.applyOnce("d", () -> { });
        assertTrue(applier.hasApplied("old"), "the repeatedly-seen key was evicted");
        assertFalse(applier.hasApplied("b"), "the genuinely-oldest key should have gone");
    }

    @Test
    @DisplayName("concurrent deliveries of one event apply it exactly once")
    void concurrentDeliveries() throws Exception {
        // Two transports, or a poll racing a socket push, can deliver the same
        // event at the same moment. A check-then-act would let both through.
        int threads = 16;
        for (int round = 0; round < 20; round++) {
            IdempotentApplier applier = new IdempotentApplier();
            AtomicInteger applied = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        go.await(5, TimeUnit.SECONDS);
                        applier.applyOnce("racy", applied::incrementAndGet);
                        return null;
                    });
                }
                go.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, applied.get(), "one event was applied more than once");
        }
    }

    @Test
    @DisplayName("the Consumer overload hands the effect its own key")
    void consumerOverloadReceivesTheKey() {
        // NO_COVERAGE in a mutation sweep: this overload is used nowhere, in
        // production or in tests. It stays because it is published SDK surface
        // a connector author may reasonably reach for -- and its entire value
        // is that the effect is told which key it is running under. An effect
        // handed the wrong string would key its own bookkeeping wrongly, which
        // is the one mistake this class exists to prevent.
        IdempotentApplier applier = new IdempotentApplier();
        java.util.List<String> received = new java.util.ArrayList<>();

        assertTrue(applier.applyOnce("event-7", (java.util.function.Consumer<String>) received::add));
        assertEquals(java.util.List.of("event-7"), received,
                "the consumer was not given the idempotency key it is running under");

        // And it dedupes on the same key as the other form.
        assertFalse(applier.applyOnce("event-7", (java.util.function.Consumer<String>) received::add));
        assertEquals(1, received.size(), "a repeated key ran the effect twice");
    }
}
