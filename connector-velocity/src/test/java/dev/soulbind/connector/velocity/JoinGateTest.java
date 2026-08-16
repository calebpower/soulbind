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
package dev.soulbind.connector.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The join decision.
 *
 * <p>No proxy, no network. The gate is deliberately free of Velocity types, so
 * the behaviours that matter — a bounded wait, a fail mode that a timeout
 * reaches by the same path as an outage, a message that blames the right party —
 * are all testable in milliseconds.
 *
 * <p>What these do NOT prove: that the plugin wires an actual connection event
 * to this class correctly. That is the full-stack battery's claim, and it needs
 * a proxy.
 */
class JoinGateTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final String GATE = "minecraft.join";
    private static final String KICK = "Link your account first.";
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private static String envelope(String effect, String reason, int ttl, String missing) {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"" + effect
                + "\",\"reason\":\"" + reason + "\",\"detail\":\"d\",\"ttlSeconds\":"
                + ttl + ",\"missingKinds\":[" + missing + "]}}";
    }

    private JoinGate gate(InMemoryTransport transport, DecisionCache cache, Duration timeout) {
        return new JoinGate(
                new SoulbindClient(transport, "cred", CLOCK, cache),
                pool, timeout, GATE, "game", KICK);
    }

    @Test
    @DisplayName("an allowed player connects")
    void allowed() {
        JoinGate gate = gate(
                InMemoryTransport.always(envelope("allow", "requirements-met", 60, "")),
                new DecisionCache(), Duration.ofSeconds(2));

        JoinGate.Verdict verdict = gate.check(PLAYER, "Alex");
        assertTrue(verdict.allowed());
        assertEquals(DecisionCache.Source.FRESH, verdict.source());
    }

    @Test
    @DisplayName("a denied player is kicked, and told what is missing")
    void deniedNamesWhatIsMissing() {
        // A kick that says only "no" leaves the person with nothing to do.
        JoinGate gate = gate(
                InMemoryTransport.always(
                        envelope("deny", "missing-kinds", 60, "\"chat\"")),
                new DecisionCache(), Duration.ofSeconds(2));

        JoinGate.Verdict verdict = gate.check(PLAYER, "Alex");
        assertFalse(verdict.allowed());
        assertTrue(verdict.message().contains(KICK), verdict.message());
        assertTrue(verdict.message().contains("chat"), verdict.message());
    }

    @Test
    @DisplayName("no configured gate allows everybody")
    void noGateAllows() {
        // A deployment that wants /link without enforcement must be able to say
        // so. Turning enforcement on before a community has linked is how an
        // operator locks out their own players.
        JoinGate gate = new JoinGate(
                new SoulbindClient(
                        InMemoryTransport.always("unused"), "cred", CLOCK, new DecisionCache()),
                pool, Duration.ofSeconds(2), null, "game", KICK);

        assertTrue(gate.check(PLAYER, "Alex").allowed());
    }

    @Test
    @DisplayName("an unreachable core denies, by the fail mode")
    void unreachableDenies() {
        JoinGate gate = gate(
                InMemoryTransport.always(envelope("allow", "requirements-met", 60, "")).goDown(),
                new DecisionCache(), Duration.ofSeconds(2));

        JoinGate.Verdict verdict = gate.check(PLAYER, "Alex");
        assertFalse(verdict.allowed());
        assertEquals(DecisionCache.Source.FAIL_MODE, verdict.source());
        assertTrue(
                verdict.message().contains("our side, not yours"),
                () -> "the player was blamed for an outage: " + verdict.message());
    }

    @Test
    @DisplayName("fail-open lets them in when core is down")
    void failOpen() {
        JoinGate gate = gate(
                InMemoryTransport.always(envelope("allow", "requirements-met", 60, "")).goDown(),
                new DecisionCache(DecisionCache.FailMode.OPEN), Duration.ofSeconds(2));

        assertTrue(gate.check(PLAYER, "Alex").allowed());
    }

    // --- the bounded wait -------------------------------------------------------

    @Test
    @DisplayName("a slow core does not hold the join past the timeout")
    void slowCoreTimesOut() throws Exception {
        // The hazard this class exists for. A join event waiting on a network
        // round trip holds a proxy thread, and a proxy that stops accepting
        // connections because one backend is slow is worse than any single
        // decision.
        InMemoryTransport slow = new InMemoryTransport(request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return envelope("allow", "requirements-met", 60, "");
        });

        JoinGate gate = gate(slow, new DecisionCache(), Duration.ofMillis(150));

        long start = System.nanoTime();
        JoinGate.Verdict verdict = gate.check(PLAYER, "Alex");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(
                elapsedMillis < 2_000,
                () -> "the join waited " + elapsedMillis + "ms on a 150ms budget");
        assertFalse(verdict.allowed(), "a timeout must reach the fail mode, which is closed");
        assertEquals(DecisionCache.Source.FAIL_MODE, verdict.source());
    }

    @Test
    @DisplayName("a timeout reaches the fail mode by the SAME path as an outage")
    void timeoutAndOutageAgree() {
        // Giving them separate branches is how the two drift until one fails
        // open. Asserted by comparing the verdicts directly.
        InMemoryTransport slow = new InMemoryTransport(request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return envelope("allow", "requirements-met", 60, "");
        });

        JoinGate.Verdict onTimeout =
                gate(slow, new DecisionCache(), Duration.ofMillis(100)).check(PLAYER, "Alex");
        JoinGate.Verdict onOutage = gate(
                InMemoryTransport.always(envelope("allow", "requirements-met", 60, "")).goDown(),
                new DecisionCache(), Duration.ofSeconds(2)).check(PLAYER, "Alex");

        assertEquals(onOutage.allowed(), onTimeout.allowed());
        assertEquals(onOutage.source(), onTimeout.source());
        assertEquals(onOutage.message(), onTimeout.message());
    }

    @Test
    @DisplayName("a timed-out call is cancelled rather than left running")
    void timedOutCallIsCancelled() throws Exception {
        // Otherwise every join behind a slow core accumulates abandoned work,
        // and the pool fills with calls whose answers nobody will read.
        AtomicBoolean interrupted = new AtomicBoolean();
        InMemoryTransport slow = new InMemoryTransport(request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return envelope("allow", "requirements-met", 60, "");
        });

        gate(slow, new DecisionCache(), Duration.ofMillis(100)).check(PLAYER, "Alex");
        Thread.sleep(300);

        assertTrue(interrupted.get(), "the abandoned call was left running");
    }

    @Test
    @DisplayName("a fail-open timeout still lets them in")
    void failOpenTimeout() {
        InMemoryTransport slow = new InMemoryTransport(request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return envelope("allow", "requirements-met", 60, "");
        });

        assertTrue(
                gate(slow, new DecisionCache(DecisionCache.FailMode.OPEN),
                        Duration.ofMillis(100)).check(PLAYER, "Alex").allowed());
    }

    @Test
    @DisplayName("a cached decision answers without waiting on core at all")
    void cacheAnswersDuringAnOutage() {
        InMemoryTransport transport =
                InMemoryTransport.always(envelope("allow", "requirements-met", 600, ""));
        DecisionCache cache = new DecisionCache();
        JoinGate gate = gate(transport, cache, Duration.ofSeconds(2));

        assertTrue(gate.check(PLAYER, "Alex").allowed());

        transport.goDown();
        JoinGate.Verdict second = gate.check(PLAYER, "Alex");
        assertTrue(second.allowed());
        assertEquals(DecisionCache.Source.CACHED, second.source());
    }

    @Test
    @DisplayName("every denial carries a message")
    void denialsAlwaysExplain() {
        // A kick with no reason is a support ticket.
        for (DecisionCache.FailMode mode : new DecisionCache.FailMode[] {
            DecisionCache.FailMode.CLOSED
        }) {
            JoinGate.Verdict outage = gate(
                    InMemoryTransport.always(envelope("allow", "requirements-met", 60, ""))
                            .goDown(),
                    new DecisionCache(mode), Duration.ofSeconds(2)).check(PLAYER, "Alex");
            assertNotNull(outage.message());
            assertFalse(outage.message().isBlank());
        }

        JoinGate.Verdict denied = gate(
                InMemoryTransport.always(envelope("deny", "not-linked", 60, "")),
                new DecisionCache(), Duration.ofSeconds(2)).check(PLAYER, "Alex");
        assertNotNull(denied.message());
        assertFalse(denied.message().isBlank());
    }
}
