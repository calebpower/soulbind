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
            return new Drained(0, 0, false);
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
}
