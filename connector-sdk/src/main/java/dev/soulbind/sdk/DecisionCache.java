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

import dev.soulbind.policy.Decision;
import dev.soulbind.policy.Effect;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A connector's decision cache, and what it does when core is unreachable.
 *
 * <p><b>Fail-closed is the default, and it is the shipped default in every
 * reference connector.</b> When a connector cannot reach core and holds no
 * unexpired cached decision, it denies.
 *
 * <p>That is not conservatism for its own sake -- it is the only choice that
 * keeps the gate meaningful. A gate that opens whenever the dispatcher is down
 * is a gate an attacker opens by taking the dispatcher down, and "the system was
 * having trouble" is not a defence anybody will accept afterwards.
 *
 * <p>Fail-open is <em>spellable</em>, because some gates genuinely should not
 * lock a community out of its own forum over a network blip. Every departure
 * from the default is a visible configuration line, and a test asserts the
 * shipped default is closed.
 *
 * <p>The user-facing message on a fail-closed denial says <b>the system</b> is
 * at fault, not the person. Somebody refused because a server they have never
 * heard of is unreachable should not be told they are not allowed.
 */
public final class DecisionCache {

    /** What to do when core cannot be reached and nothing usable is cached. */
    public enum FailMode {
        /** Deny. The default, and the only default. */
        CLOSED,
        /**
         * Allow.
         *
         * <p>A deliberate choice for a gate whose cost of wrongly denying
         * exceeds its cost of wrongly allowing. Never arrived at by omission.
         */
        OPEN;

        /**
         * Parses a configured value.
         *
         * <p>An unreadable value becomes {@code CLOSED} -- not an exception, and
         * certainly not {@code OPEN}. A typo in a fail-mode must never be the
         * thing that opens a gate.
         */
        public static FailMode fromConfigName(String s) {
            if (s == null) {
                return CLOSED;
            }
            return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "open" -> OPEN;
                default -> CLOSED;
            };
        }
    }

    /** Where an answer came from. */
    public enum Source {
        /** Core answered. */
        FRESH,
        /** A cached answer that has not expired. */
        CACHED,
        /** Core was unreachable and the fail mode decided. */
        FAIL_MODE
    }

    /** A decision, and where it came from. */
    public record Answer(Decision decision, Source source) {}

    /**
     * The message shown when the fail mode denied.
     *
     * <p>Blames the system, by design.
     */
    public static final String FAIL_CLOSED_MESSAGE =
            "This check is temporarily unavailable, so access is on hold. "
                    + "This is a problem on our side, not yours -- please try again shortly.";

    /**
     * The cache key separator.
     *
     * <p>A unit separator. Joining with a colon would let the pair
     * {@code ("a:b", "c")} and {@code ("a", "b:c")} collide on one key — and a
     * collision here serves one subject's decision to another.
     *
     * <p>An earlier version of this comment went on to say the separator
     * "cannot appear in a gate name or a platform identifier". Nothing checked
     * that, and it is not soulbind's to guarantee: a gate name comes from an
     * operator's configuration and a platform identifier comes from whatever
     * the platform hands the connector. A rarity is not an impossibility, and
     * the whole point of the hostile corpus is that the exotic input does
     * eventually arrive.
     *
     * <p>So the key no longer relies on it — see {@link #key}. The separator
     * stays because it keeps keys readable in a heap dump, not because anything
     * depends on its absence from the inputs.
     */
    private static final String KEY_SEPARATOR = "\u001F";

    private record Entry(Decision decision, Instant expiresAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final FailMode failMode;

    /** A cache that denies when core is unreachable. */
    public DecisionCache() {
        this(FailMode.CLOSED);
    }

    public DecisionCache(FailMode failMode) {
        this.failMode = failMode == null ? FailMode.CLOSED : failMode;
    }

    public FailMode failMode() {
        return failMode;
    }

    /** Stores a fresh decision under its own TTL. */
    public void store(String gate, String identityRef, Decision decision, Instant now) {
        if (decision.ttlSeconds() <= 0) {
            // Zero TTL means "do not cache this" -- which is what a grace
            // decision at the edge of its window carries. Storing it anyway
            // would hold a gate open past the moment it should have closed.
            entries.remove(key(gate, identityRef));
            return;
        }
        entries.put(
                key(gate, identityRef),
                new Entry(decision, now.plusSeconds(decision.ttlSeconds())));
    }

    /** A cached decision, if one is still good. */
    public Optional<Decision> cached(String gate, String identityRef, Instant now) {
        String key = key(gate, identityRef);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (now.isAfter(entry.expiresAt())) {
            // Removed on read rather than swept by a timer: a decision nobody
            // asks about costs nothing to keep, and a sweeper thread is a thing
            // that can die silently and leave stale answers in force.
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.decision());
    }

    /**
     * The answer when core could not be reached.
     *
     * <p>Cache first, fail mode second. A connector holding a live answer should
     * use it rather than fall back: the cache exists precisely so a brief outage
     * is invisible.
     */
    public Answer whenUnreachable(String gate, String identityRef, Instant now) {
        Optional<Decision> cached = cached(gate, identityRef, now);
        if (cached.isPresent()) {
            return new Answer(cached.get(), Source.CACHED);
        }

        Effect effect = failMode == FailMode.OPEN ? Effect.ALLOW : Effect.DENY;
        return new Answer(
                new Decision(
                        effect,
                        Decision.Reason.DEFAULT,
                        failMode == FailMode.OPEN
                                ? "core unreachable; this gate is configured to fail open"
                                : FAIL_CLOSED_MESSAGE,
                        // TTL zero: a fail-mode answer is not a decision core
                        // made, and caching it would extend an outage's effect
                        // beyond the outage.
                        0,
                        List.of()),
                Source.FAIL_MODE);
    }

    /** Entries currently held. For tests and for a connector's own diagnostics. */
    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    /**
     * A key that cannot be made ambiguous by its inputs.
     *
     * <p>Length-prefixed rather than merely separated. With a separator alone,
     * {@code ("a\u001Fb", "c")} and {@code ("a", "b\u001Fc")} produce the same
     * string, and a collision here serves one subject's decision to another —
     * an authorization answer for the wrong identity, from a component whose
     * entire job is to answer quickly without asking.
     *
     * <p>Prefixing the gate's length makes that structurally impossible: the
     * boundary is stated rather than inferred, so no content can move it.
     *
     * <p><b>Not validation.</b> Refusing a gate name containing the separator
     * would turn an exotic-but-harmless input into a refused decision, which on
     * a fail-closed gate is an outage for whoever owns that identity. This
     * cannot fail, so it does not need a failure path.
     */
    private static String key(String gate, String identityRef) {
        return gate.length() + KEY_SEPARATOR + gate + KEY_SEPARATOR + identityRef;
    }
}
