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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How many codes one account may guess wrong before core stops listening.
 *
 * <p>There was no limit at all until Phase 10. A link code is eight characters
 * from a twenty-eight character alphabet — 3.8×10¹¹ possibilities — and
 * guessing a <em>particular</em> code is hopeless. But an attacker does not
 * need a particular one: <b>any live code links their account to a stranger's
 * subject</b>. With a hundred codes outstanding and a thousand guesses a
 * second, that is a hit every few weeks, and nothing bounded the guessing rate.
 *
 * <p><b>What counts is a code that does not exist.</b> Not an expired one, not
 * one already redeemed, not a redeem refused because both accounts were already
 * linked — those all mean the caller <em>had</em> a real code and something
 * else was wrong, and counting them would throttle people who are not guessing.
 * Only "no such code" is evidence of a guess.
 *
 * <p><b>Keyed on the account redeeming, not the connector.</b> The connector is
 * the platform; throttling it would punish everybody on that platform for one
 * abuser. The account is who is guessing, and it is the finest thing core can
 * see — core deliberately knows nothing about IP addresses or sessions, which
 * is why the specification leaves platform-appropriate limits to connectors.
 * This is the floor beneath those: an attacker spreading attempts across
 * several connectors evades every per-connector limit and still meets this one.
 *
 * <p><b>A success clears the record.</b> Somebody who mistypes twice and then
 * gets it right is not a threat, and carrying their failures forward would
 * eventually lock out a person who has done nothing but be human.
 */
public final class RedeemThrottle {

    /** Wrong guesses tolerated inside the window before refusing. */
    public static final int DEFAULT_LIMIT = 10;

    /** How far back the count looks. */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(10);

    /**
     * Accounts remembered at once.
     *
     * <p>Bounded, and when it is full the OLDEST record is dropped rather than
     * new ones refused — the opposite of {@link
     * dev.soulbind.core.transport.NonceStore}, deliberately.
     *
     * <p>That class fails closed because letting a replay through is worse than
     * refusing a request. This one must fail <em>open</em>: refusing at capacity
     * would mean an attacker who fills the map locks every legitimate person
     * out of linking, turning a guessing limit into a denial-of-service lever.
     * The cost of evicting is that a determined attacker can age their own
     * record out by making noise from other accounts, which buys them a
     * multiplier on a limit that was already generous — a far smaller harm.
     */
    public static final int MAX_ACCOUNTS = 100_000;

    private final int limit;

    private final Duration window;

    private final int maxAccounts;

    /** Access-ordered, so the eldest entry is the least recently touched. */
    private final Map<String, Deque<Instant>> failures;

    public RedeemThrottle() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW, MAX_ACCOUNTS);
    }

    public RedeemThrottle(int limit, Duration window, int maxAccounts) {
        this.limit = limit;
        this.window = window;
        this.maxAccounts = maxAccounts;
        this.failures = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > RedeemThrottle.this.maxAccounts;
            }
        };
    }

    /**
     * Whether this account may attempt a redeem right now.
     *
     * @param identityRef the account redeeming, as {@code kind:id}
     * @param now the current instant
     * @return true if the attempt should proceed
     */
    public synchronized boolean allow(String identityRef, Instant now) {
        Deque<Instant> recent = failures.get(identityRef);
        if (recent == null) {
            return true;
        }
        expire(recent, now);
        if (recent.isEmpty()) {
            failures.remove(identityRef);
            return true;
        }
        return recent.size() < limit;
    }

    /** Records a guess at a code that does not exist. */
    public synchronized void recordGuess(String identityRef, Instant now) {
        Deque<Instant> recent =
                failures.computeIfAbsent(identityRef, key -> new ArrayDeque<>());
        expire(recent, now);
        // Capped at the limit: beyond it the count says nothing more, and an
        // attacker hammering one account must not be able to grow a deque
        // without bound.
        if (recent.size() < limit) {
            recent.addLast(now);
        }
    }

    /** Forgets an account's failures, because it just succeeded. */
    public synchronized void clear(String identityRef) {
        failures.remove(identityRef);
    }

    /** How many recent wrong guesses are held against an account. */
    public synchronized int recentFailures(String identityRef, Instant now) {
        Deque<Instant> recent = failures.get(identityRef);
        if (recent == null) {
            return 0;
        }
        expire(recent, now);
        return recent.size();
    }

    /** Accounts currently remembered. For tests and for the doctor. */
    public synchronized int remembered() {
        return failures.size();
    }

    /** How long an account must wait before it is heard again. */
    public Duration window() {
        return window;
    }

    private void expire(Deque<Instant> recent, Instant now) {
        Instant cutoff = now.minus(window);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.removeFirst();
        }
    }
}
