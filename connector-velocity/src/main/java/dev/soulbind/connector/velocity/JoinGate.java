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

import dev.soulbind.policy.Decision;
import dev.soulbind.policy.Effect;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The join decision, with a bounded wait.
 *
 * <p><b>No core round trip happens on the proxy's event thread.</b> A join event
 * that waits on a network call holds a proxy thread, and a proxy that stops
 * accepting connections because one backend service is slow is a worse outcome
 * than any single decision could be. The call goes to a pool; the event thread
 * waits, briefly, for a result.
 *
 * <p>When the wait expires the <b>fail mode decides</b>, exactly as if core were
 * down — because from this player's point of view it was. That is the same code
 * path as an outage rather than a separate one, so a timeout cannot behave
 * differently from an unreachable core by accident.
 *
 * <p>Deliberately free of Velocity types, so every one of these behaviours is
 * testable without a proxy. The plugin adapts an event to a call and a result to
 * a kick; this decides.
 */
public final class JoinGate {

    /** What the proxy should do. */
    public record Verdict(boolean allowed, String message, DecisionCache.Source source) {}

    private final SoulbindClient client;
    private final ExecutorService pool;
    private final Duration timeout;
    private final String gate;
    private final String platformKind;
    private final String kickMessage;

    public JoinGate(
            SoulbindClient client,
            ExecutorService pool,
            Duration timeout,
            String gate,
            String platformKind,
            String kickMessage) {
        this.client = client;
        this.pool = pool;
        this.timeout = timeout;
        this.gate = gate;
        this.platformKind = platformKind;
        this.kickMessage = kickMessage;
    }

    /**
     * Decides whether a player may connect.
     *
     * @param gateName null when no gate is configured, which allows everybody —
     *     a deployment that wants {@code /link} without enforcement must be able
     *     to say so, because turning enforcement on before a community has
     *     linked is how an operator locks out their own players
     */
    public Verdict check(UUID playerId, String playerName) {
        if (gate == null || gate.isBlank()) {
            return new Verdict(true, null, DecisionCache.Source.FRESH);
        }

        Callable<DecisionCache.Answer> work =
                () -> client.decide(gate, platformKind, playerId.toString());

        Future<DecisionCache.Answer> future = pool.submit(work);
        DecisionCache.Answer answer;
        try {
            answer = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Cancelled, so a slow core does not accumulate abandoned work
            // behind every join. `true` interrupts it: the call is a round trip
            // with no side effect on this side, so interrupting loses nothing.
            future.cancel(true);
            return timedOut();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return timedOut();
        } catch (java.util.concurrent.ExecutionException e) {
            // The SDK reports an unreachable core as an ANSWER, not an
            // exception, so reaching here means something unexpected broke. It
            // is still a join, and it still has to be decided -- falling through
            // to the fail mode is the only safe reading.
            return timedOut();
        }

        Decision decision = answer.decision();
        if (decision.effect() == Effect.ALLOW) {
            return new Verdict(true, null, answer.source());
        }
        return new Verdict(false, message(decision), answer.source());
    }

    /**
     * The fail mode's verdict, reached through the SAME path as an outage.
     *
     * <p>Not a separate branch with its own idea of what to do. A timeout and an
     * unreachable core are the same situation to the player, and giving them
     * separate code is how the two drift until one of them fails open.
     */
    private Verdict timedOut() {
        DecisionCache.Answer answer = client.cache().whenUnreachable(
                gate, platformKind + ":timeout", java.time.Instant.now());

        return new Verdict(
                answer.decision().effect() == Effect.ALLOW,
                answer.decision().effect() == Effect.ALLOW ? null : answer.decision().detail(),
                DecisionCache.Source.FAIL_MODE);
    }

    /**
     * What the player sees.
     *
     * <p>A denial from policy shows the operator's configured message plus what
     * is missing. A denial from the fail mode shows the SDK's message, which
     * blames the system — because somebody refused for an outage should not be
     * told they are not allowed.
     */
    private String message(Decision decision) {
        if (decision.reason() == Decision.Reason.DEFAULT
                && decision.detail().contains("our side")) {
            return decision.detail();
        }
        List<String> missing = decision.missingKinds();
        if (missing.isEmpty()) {
            return kickMessage;
        }
        return kickMessage + " (missing: " + String.join(", ", missing) + ")";
    }
}
