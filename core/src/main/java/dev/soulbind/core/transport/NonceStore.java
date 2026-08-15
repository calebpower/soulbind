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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers nonces for as long as a signature stays fresh.
 *
 * <p>Replay protection is two halves and needs both: the timestamp window bounds
 * how long a captured request is useful, and this store makes it useful only
 * once inside that window. A nonce store without a window would have to grow
 * forever; a window without a nonce store would let a captured request be
 * replayed freely until it expired.
 *
 * <p>Entries are dropped once they are older than the window, because a
 * signature that old is already refused on its timestamp — so remembering its
 * nonce proves nothing.
 */
public final class NonceStore {

    /**
     * A hard ceiling, and the behaviour when it is reached is to <b>refuse</b>.
     *
     * <p>Fail closed, deliberately. If the store cannot hold every nonce in the
     * window then it cannot prove a nonce is new, and accepting on that basis
     * would silently turn replay protection off exactly when something abnormal
     * is happening. The ceiling is generous enough that reaching it means a
     * flood rather than ordinary traffic.
     *
     * <p>The cost is real and worth naming: a sustained flood of unique nonces
     * degrades this into refusing legitimate signed requests too. That is the
     * correct trade for an authentication control, and the alternative — evicting
     * live entries — is a replay window an attacker can open on demand.
     */
    public static final int MAX_ENTRIES = 1_000_000;

    private final Duration window;
    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();
    private final AtomicInteger sinceLastSweep = new AtomicInteger();

    /** How often insertions trigger a sweep. Amortises the cost without a timer thread. */
    private static final int SWEEP_INTERVAL = 256;

    public NonceStore(Duration window) {
        this.window = window;
    }

    /**
     * Records a nonce, reporting whether it was new.
     *
     * @return true if this nonce had not been seen inside the window
     */
    public boolean recordIfNew(String nonce, Instant now) {
        if (sinceLastSweep.incrementAndGet() >= SWEEP_INTERVAL) {
            sinceLastSweep.set(0);
            sweep(now);
        }
        if (seen.size() >= MAX_ENTRIES) {
            sweep(now);
            if (seen.size() >= MAX_ENTRIES) {
                return false; // fail closed; see MAX_ENTRIES
            }
        }
        // putIfAbsent, not containsKey-then-put: two requests carrying the same
        // nonce can arrive on different threads at the same moment, and a
        // check-then-act would let both through -- which is precisely the replay
        // this class exists to stop.
        return seen.putIfAbsent(nonce, now) == null;
    }

    /** Entries currently remembered. For tests and for `soulbind doctor`. */
    public int size() {
        return seen.size();
    }

    /** Drops everything older than the window. */
    public void sweep(Instant now) {
        Instant cutoff = now.minus(window);
        for (Map.Entry<String, Instant> entry : seen.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                seen.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
