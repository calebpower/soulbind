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
package dev.soulbind.connector.discord;

import dev.soulbind.sdk.IdempotentApplier;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.Payload;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Applies role changes from the event stream.
 *
 * <p>Two layers of idempotence, and both earn their place. The
 * {@link IdempotentApplier} stops the same EVENT being applied twice, which
 * at-least-once delivery guarantees will happen. The connector's own
 * {@code hasRole} check stops the platform being asked to grant a role somebody
 * already holds — which happens for reasons that have nothing to do with
 * delivery, such as an operator granting it by hand.
 *
 * <p>No platform types here either: this drives {@link ChatConnector}, which
 * drives {@link ChatSurface}. The whole redelivery story is therefore testable
 * against the scripted surface.
 */
public final class RoleEffector {

    private final SoulbindClient client;
    private final ChatConnector connector;
    private final IdempotentApplier applier;
    private final String gate;
    private final String role;
    private final String platformKind;
    private final BiConsumer<String, Throwable> log;

    /**
     * Whether the last poll failed, so an outage is reported once rather than
     * on every cycle.
     */
    private boolean pollFailing;

    public RoleEffector(
            SoulbindClient client,
            ChatConnector connector,
            IdempotentApplier applier,
            String gate,
            String role,
            String platformKind,
            BiConsumer<String, Throwable> log) {
        this.client = client;
        this.connector = connector;
        this.applier = applier;
        this.gate = gate;
        this.role = role;
        this.platformKind = platformKind;
        this.log = log;
    }

    /** How many events were applied. */
    public record Drained(int seen, int applied, boolean acknowledged) {}

    /**
     * One drain that cannot escape, whatever it hits.
     *
     * <p>What the scheduler runs, and it catches {@link Throwable} rather than
     * {@code RuntimeException}. The scheduled task carried a comment saying
     * "nothing escapes into the scheduler" and then caught only {@code
     * RuntimeException} — leaving open exactly the case it described, where an
     * {@code Error} cancels the task for the life of the process with nothing
     * logged.
     */
    public void drainQuietly() {
        try {
            drain();
        } catch (Throwable t) {
            log.accept("event drain failed; it will be retried", t);
        }
    }

    /**
     * Polls, applies, and acknowledges.
     *
     * <p>Acknowledges only after applying, and only up to the last event that
     * applied cleanly. Acknowledging first would turn a delivery this connector
     * failed to act on into an event it will never see again — and the role
     * would simply never appear, with nothing anywhere saying why.
     */
    public Drained drain() {
        SoulbindClient.Outcome outcome = client.call("event.subscribe", new PollBody(null, 100));
        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            // An outage is not a failure worth shouting about: the cursor did
            // not move, so the next poll gets the same events.
            //
            // Not worth shouting EVERY CYCLE, which is not the same as not
            // worth saying. Returning in silence makes an unreachable core
            // indistinguishable from a quiet one, and a connector that has
            // granted nothing for an hour then has no log line explaining it.
            // Latched, so an outage costs one line and its end costs one more.
            if (!pollFailing) {
                pollFailing = true;
                log.accept(
                        "cannot poll core for events (" + describe(outcome) + "); no role will"
                                + " be granted or revoked until this recovers. Still retrying.",
                        null);
            }
            return new Drained(0, 0, false);
        }
        if (pollFailing) {
            pollFailing = false;
            log.accept("event polling recovered; resuming role sync", null);
        }

        List<Payload> events = ok.payload().items("events");
        if (events.isEmpty()) {
            return new Drained(0, 0, false);
        }

        int applied = 0;
        long lastCleanSequence = 0;

        for (Payload event : events) {
            String key = event.text("idempotencyKey");
            long sequence = event.number("sequence");
            try {
                if (applier.applyOnce(key, () -> apply(event))) {
                    applied++;
                }
                lastCleanSequence = sequence;
            } catch (RuntimeException e) {
                // Stop at the first failure. Acknowledging past it would skip
                // it forever, and continuing past it would apply LATER events
                // whose meaning may depend on this one -- a role revoked before
                // the grant it undoes.
                log.accept("could not apply event " + sequence + "; stopping here", e);
                break;
            }
        }

        boolean acknowledged = false;
        if (lastCleanSequence > 0) {
            acknowledged = client.call("event.ack", new AckBody(lastCleanSequence))
                    instanceof SoulbindClient.Outcome.Ok;
        }
        return new Drained(events.size(), applied, acknowledged);
    }

    private void apply(Payload event) {
        String type = event.text("type");
        String eventGate = event.text("gate");

        // Only this connector's gate. An event for another gate is not this
        // effector's business, and acting on it would grant a role for a
        // requirement nobody tied to it.
        if (gate != null && !gate.isBlank() && !gate.equals(eventGate)) {
            return;
        }

        // BEFORE the identity check, because a rule change names a gate and no
        // identity at all -- it is a fact about everybody at once, and the
        // identity-shaped path below would drop it silently.
        if ("rule.changed".equals(type)) {
            reconcile();
            return;
        }

        String platformId = platformIdOf(event.text("identityRef"));
        if (platformId == null) {
            return;
        }

        switch (type) {
            case "subject.requirements-met" -> connector.applyRole(platformId, role);
            case "subject.requirements-lost" -> connector.removeRole(platformId, role);
            default -> {
                // Every other event type is somebody else's. Ignored rather
                // than logged: this stream carries everything, and a line per
                // uninteresting event is a log nobody reads.
            }
        }
    }

    /**
     * Re-checks everybody holding the role after the rule beneath it changed.
     *
     * <p>Core emits {@code rule.changed} and nothing consumed it until now, so
     * editing a rule left every existing grant standing: somebody who no longer
     * qualified kept the role until they happened to link or unlink something
     * else. Core cannot fix this on its own — a rule change can flip every
     * subject in a deployment at once, and fanning that out inside the request
     * that changed the rule would hold a connection open across the whole
     * graph.
     *
     * <p>The connector can, because its population is bounded and it already
     * knows it: the accounts on <em>this</em> platform holding <em>this</em>
     * role. Each is asked afresh, and the role comes off where the answer
     * changed.
     *
     * <p><b>Revocations only.</b> Finding people who NEWLY qualify would mean
     * enumerating every member of the platform and asking core about each,
     * which is unbounded in the one direction that does not matter: nobody is
     * wrongly holding anything, they simply get it on their next
     * {@code requirements-met}. Taking a role away is the half that cannot
     * wait, because until it happens somebody has access a rule says they
     * should not.
     */
    private void reconcile() {
        if (role == null || role.isBlank() || gate == null || gate.isBlank()) {
            return;
        }
        List<String> holders = connector.holdersOf(role);
        if (holders.isEmpty()) {
            return;
        }

        int revoked = 0;
        for (String platformId : holders) {
            Boolean stillAllowed = connector.allowsGate(platformId, gate, log);
            if (stillAllowed == null) {
                // Unaskable. NOT a revocation: an outage must not strip roles
                // from everybody who holds one, which would turn a brief core
                // restart into a mass removal that then has to be undone by
                // hand.
                continue;
            }
            if (!stillAllowed && connector.removeRole(platformId, role)) {
                revoked++;
            }
        }
        if (revoked > 0) {
            log.accept("rule for gate '" + gate + "' changed; removed '" + role
                    + "' from " + revoked + " account(s) that no longer qualify", null);
        }
    }

    /**
     * The platform id inside a {@code kind:id} reference.
     *
     * <p>Returns null when the reference names another platform, because a role
     * on this platform cannot be granted to an account on a different one — and
     * doing it by string coincidence would grant it to whoever happened to share
     * an identifier.
     */
    private String platformIdOf(String identityRef) {
        if (identityRef == null || identityRef.isBlank()) {
            return null;
        }
        int colon = identityRef.indexOf(':');
        // `< 0`, and the `<= 0` mutant of it is EQUIVALENT rather than
        // surviving through inattention. A colon at position zero leaves an
        // empty kind, and an empty kind never equals this connector's, so the
        // check below returns null for exactly the inputs `<= 0` would catch
        // here. Recorded so a later sweep skips it rather than rediscovering
        // it -- DECISIONS 10.28, same treatment as policy's two in 10.19.
        if (colon < 0) {
            return null;
        }
        // The KIND is checked, not just split off. An earlier version returned
        // the identifier from any reference -- so a game account whose id
        // happened to match a chat account's would have had this platform's
        // role granted to whoever held it. The javadoc claimed the check
        // existed; the code did not have it.
        if (!identityRef.substring(0, colon).equals(platformKind)) {
            return null;
        }
        return identityRef.substring(colon + 1);
    }

    private record PollBody(Long after, Integer limit) {}

    private record AckBody(long through) {}

    /** A poll failure in one short phrase, for the latched outage line. */
    private static String describe(SoulbindClient.Outcome outcome) {
        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            // reportedCode(), which is what core actually sent -- not this
            // build's parse of it. A code this connector has no constant for
            // parses to INTERNAL, and printing that would hide a version skew
            // behind the least informative word available. DECISIONS 10.34.
            return refused.reportedCode() + ": " + refused.message();
        }
        return "core unreachable";
    }
}
