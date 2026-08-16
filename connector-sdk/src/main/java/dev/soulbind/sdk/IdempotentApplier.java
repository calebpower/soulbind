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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Applies each event once, however many times it is delivered.
 *
 * <p>Delivery is at-least-once, so a connector that applies whatever arrives
 * will eventually grant the same role twice, send the same message twice, or
 * un-ban somebody twice. The SDK does the dedup rather than documenting that
 * connector authors should: a rule enforced by a paragraph is a rule that holds
 * until somebody is in a hurry.
 *
 * <p><b>Bounded, and bounded by eviction rather than refusal.</b> This is the
 * opposite choice from the replay-nonce store, and deliberately: there, failing
 * to remember a nonce means failing to detect a replay, which is a security
 * control, so it fails closed. Here, forgetting a key means applying an
 * idempotent effect a second time — which is by definition harmless, because
 * that is what makes it an effect worth deduping. Refusing to apply events
 * because the cache filled would be an outage caused by bookkeeping.
 *
 * <p>Eviction is oldest-first, which is right because redelivery follows a
 * reconnect and a reconnect replays the <em>recent</em> tail.
 */
public final class IdempotentApplier {

    /**
     * How many keys to remember.
     *
     * <p>Sized for the redelivery window rather than for history: a connector
     * only ever sees the tail it failed to acknowledge, and remembering further
     * back protects against nothing that happens.
     */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;

    /**
     * Access-ordered so a key seen again moves to the end.
     *
     * <p>A connector being hammered with one repeated redelivery keeps that key
     * live rather than evicting it, which is exactly the case the dedup is for.
     */
    private final LinkedHashMap<String, Boolean> seen;

    public IdempotentApplier() {
        this(DEFAULT_CAPACITY);
    }

    public IdempotentApplier(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "capacity must be at least 1; a zero-capacity applier remembers nothing and "
                            + "therefore dedups nothing, which is worse than not having one "
                            + "because it looks like it does");
        }
        this.capacity = capacity;
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > IdempotentApplier.this.capacity;
            }
        };
    }

    /**
     * Runs the effect unless this key has been applied.
     *
     * <p>The key is recorded <b>before</b> the effect runs, and is <b>removed
     * again if the effect throws</b>. Recording after would let a crash between
     * effect and record cause a re-apply; not removing on failure would mark an
     * effect as applied that never happened, and the retry would be swallowed.
     *
     * @return true if the effect ran
     */
    public synchronized boolean applyOnce(String idempotencyKey, Runnable effect) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Refused rather than applied-anyway. An event with no key cannot be
            // deduped, and applying it silently would make the guarantee this
            // class offers untrue for exactly the events that lost their key.
            throw new IllegalArgumentException(
                    "an event with no idempotency key cannot be applied safely");
        }
        if (seen.putIfAbsent(idempotencyKey, Boolean.TRUE) != null) {
            return false;
        }
        try {
            effect.run();
            return true;
        } catch (RuntimeException | Error e) {
            seen.remove(idempotencyKey);
            throw e;
        }
    }

    /** Convenience for an effect that wants the key. */
    public boolean applyOnce(String idempotencyKey, Consumer<String> effect) {
        return applyOnce(idempotencyKey, () -> effect.accept(idempotencyKey));
    }

    public synchronized boolean hasApplied(String idempotencyKey) {
        return seen.containsKey(idempotencyKey);
    }

    public synchronized int remembered() {
        return seen.size();
    }

    public synchronized void forgetAll() {
        seen.clear();
    }
}
