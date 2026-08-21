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

import dev.soulbind.sdk.IdempotentApplier;
import dev.soulbind.sdk.Payload;
import dev.soulbind.sdk.SoulbindClient;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Drains core's event stream and moves LuckPerms groups to match.
 *
 * <p><b>This did not exist.</b> The proxy connector never subscribed to events
 * at all — nothing in production called {@code GroupEffector.grant} or
 * {@code revoke}, only tests did — so configuring {@code effector.group}
 * achieved precisely nothing. `VelocityConfig` even validates "effector.group
 * is set but gate.join is not, so nothing will ever grant it", which reads as
 * though the author believed something drove the grant. See DECISIONS 10.23.
 *
 * <p>Modelled on the chat connector's effector, and deliberately the same
 * shape: acknowledge only after applying, dedupe on the event's idempotency
 * key, and stop at the first event that fails so a later one is not applied
 * over a gap.
 */
final class GroupSync {

    /** The payload for {@code event.subscribe}. */
    private record PollBody(Long after, int limit) {}

    /** The payload for {@code event.ack}. */
    private record AckBody(long through) {}

    /** The payload for {@code decide}. */
    private record DecideBody(String gate, String platformKind, String platformId) {}

    /** What one drain did. */
    record Drained(int seen, int applied, boolean acknowledged) {}

    private final SoulbindClient client;
    private final GroupEffector effector;
    private final IdempotentApplier applier;
    private final String gate;
    private final String group;
    private final String platformKind;
    private final Supplier<List<UUID>> onlinePlayers;
    private final BiConsumer<String, Throwable> log;

    /**
     * Whether the last poll failed, so an outage is reported once rather than
     * every five seconds.
     */
    private boolean pollFailing;

    GroupSync(
            SoulbindClient client,
            GroupEffector effector,
            IdempotentApplier applier,
            String gate,
            String group,
            String platformKind,
            Supplier<List<UUID>> onlinePlayers,
            BiConsumer<String, Throwable> log) {
        this.client = client;
        this.effector = effector;
        this.applier = applier;
        this.gate = gate;
        this.group = group;
        this.platformKind = platformKind;
        this.onlinePlayers = onlinePlayers;
        this.log = log;
    }

    /** Whether this sync can do anything at all. */
    boolean isConfigured() {
        return effector.isAvailable()
                && group != null && !group.isBlank()
                && gate != null && !gate.isBlank();
    }

    /**
     * One drain that cannot escape, whatever it hits.
     *
     * <p>This is what the scheduler runs, and it catches {@link Throwable}
     * rather than {@code RuntimeException}. The plugin's scheduled task already
     * carried a comment saying an exception out of it "cancels all future runs
     * silently" — and then caught only {@code RuntimeException}, which leaves
     * exactly the case that comment describes wide open. A {@code
     * NoClassDefFoundError} from a shaded jar missing a class this path alone
     * touches would cancel group sync for the life of the proxy, with nothing
     * logged and every other feature still working.
     *
     * <p>Catching {@code Error} is normally wrong. It is right here because the
     * alternative is not "fail loudly" but "stop for good, in silence": there
     * is no supervisor above a cancelled scheduled task to notice.
     */
    void drainQuietly() {
        try {
            drain();
        } catch (Throwable t) {
            log.accept("group sync failed; it will be retried in five seconds", t);
        }
    }

    /** Fetches one page of events, applies what belongs here, acknowledges. */
    Drained drain() {
        if (!isConfigured()) {
            return new Drained(0, 0, false);
        }
        SoulbindClient.Outcome outcome = client.call("event.subscribe", new PollBody(null, 100));
        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            // Not acknowledged, so the cursor does not move and the next poll
            // sees the same events. An outage costs a delay, not a gap.
            //
            // SAID ONCE, and said at all. This returned in silence, and a drain
            // that cannot reach core then looks exactly like a drain with
            // nothing to do -- which is how a proxy can run for an hour
            // granting nothing and log not one word about it. Latched rather
            // than logged every cycle: at a five-second delay an outage would
            // otherwise write twelve identical lines a minute.
            if (!pollFailing) {
                pollFailing = true;
                log.accept(
                        "cannot poll core for events (" + describe(outcome) + "); no group will"
                                + " be granted or revoked until this recovers. Retrying every"
                                + " five seconds.",
                        null);
            }
            return new Drained(0, 0, false);
        }
        if (pollFailing) {
            pollFailing = false;
            log.accept("event polling recovered; resuming group sync", null);
        }

        List<Payload> events = ok.payload().items("events");
        int applied = 0;
        long lastClean = 0L;
        for (Payload event : events) {
            String key = event.text("idempotencyKey");
            long sequence = event.number("sequence");
            try {
                if (applier.applyOnce(key, () -> apply(event))) {
                    applied++;
                }
                lastClean = sequence;
            } catch (RuntimeException e) {
                // Stop here rather than continuing. Acknowledging past a
                // failure turns an event this connector could not act on into
                // one it will never see again.
                log.accept("stopping the drain at event " + sequence, e);
                break;
            }
        }

        boolean acknowledged = lastClean > 0
                && client.call("event.ack", new AckBody(lastClean))
                        instanceof SoulbindClient.Outcome.Ok;
        return new Drained(events.size(), applied, acknowledged);
    }

    private void apply(Payload event) {
        String type = event.text("type");
        boolean aboutGroups =
                "subject.requirements-met".equals(type) || "subject.requirements-lost".equals(type);

        if (!gate.equals(event.text("gate"))) {
            // Not this connector's gate. Acting on it would move a group for a
            // requirement nobody tied to it.
            //
            // Said out loud for the two types this connector exists to act on,
            // and only for those: the stream carries everything, so logging
            // every foreign event would bury the line that matters. A gate
            // configured as one string and emitted as another is otherwise a
            // silent no-op -- the drain runs, acknowledges, and grants nothing.
            if (aboutGroups) {
                log.accept(
                        "ignoring " + type + " for gate '" + event.text("gate")
                                + "': this connector is configured for gate '" + gate + "'",
                        null);
            }
            return;
        }

        if ("rule.changed".equals(type)) {
            reconcile();
            return;
        }

        UUID playerId = playerIdOf(event.text("identityRef"));
        if (playerId == null) {
            if (aboutGroups) {
                log.accept(
                        "ignoring " + type + " for '" + event.text("identityRef")
                                + "': not an account on this platform (kind '" + platformKind
                                + "')",
                        null);
            }
            return;
        }
        switch (type) {
            // Reported, not assumed. The effector answers whether it applied
            // anything, and an operator who has just linked an account needs to
            // be able to read that it happened -- the alternative is inferring
            // it from the permissions plugin's own storage, which is what this
            // connector's own harness had to resort to.
            case "subject.requirements-met" -> {
                if (effector.grant(playerId, group)) {
                    log.accept("granted group '" + group + "' to " + playerId, null);
                }
            }
            case "subject.requirements-lost" -> {
                if (effector.revoke(playerId, group)) {
                    log.accept("revoked group '" + group + "' from " + playerId, null);
                }
            }
            default -> {
                // Somebody else's event. The stream carries everything.
            }
        }
    }

    /** A poll failure in one short phrase, for the latched outage line. */
    private static String describe(SoulbindClient.Outcome outcome) {
        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            return refused.code() + ": " + refused.message();
        }
        return "core unreachable";
    }

    /**
     * Re-checks players after the rule beneath the group changed.
     *
     * <p><b>Online players only</b>, and that is the deliberate difference from
     * the chat connector. That one can enumerate a role's holders from a guild
     * list it already has in memory. Answering "who is in this group" from
     * LuckPerms means sweeping user storage — every account that has ever
     * joined — to reach players who are not connected and therefore cannot use
     * the group anyway. An offline player is re-checked when they next join,
     * which is the moment it starts mattering.
     */
    private void reconcile() {
        List<UUID> online = onlinePlayers.get();
        if (online.isEmpty()) {
            return;
        }
        int revoked = 0;
        for (UUID playerId : online) {
            Boolean allowed = allows(playerId);
            if (allowed == null) {
                // Unaskable is not a denial. An outage must not strip groups
                // from everybody online, which would turn a brief core restart
                // into a mass removal an operator then undoes by hand.
                continue;
            }
            if (!allowed && effector.revoke(playerId, group)) {
                revoked++;
            }
        }
        if (revoked > 0) {
            log.accept("rule for gate '" + gate + "' changed; removed group '" + group
                    + "' from " + revoked + " online player(s) who no longer qualify", null);
        }
    }

    /** Whether core still admits this player, or null when it could not be asked. */
    private Boolean allows(UUID playerId) {
        SoulbindClient.Outcome outcome = client.call(
                "decide", new DecideBody(gate, platformKind, playerId.toString()));
        if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
            return "allow".equals(ok.payload().text("effect"));
        }
        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            log.accept("could not ask about gate '" + gate + "': " + refused.code()
                    + ": " + refused.message()
                    + ". Reconciling after a rule change needs enforcement-point;"
                    + " without it this connector keeps every group it has granted.", null);
        }
        return null;
    }

    /**
     * The player UUID inside a {@code kind:id} reference.
     *
     * <p>The KIND is checked, not merely split off: a chat account whose id
     * happened to parse as a UUID would otherwise have a group applied to
     * whoever held that UUID here.
     */
    private UUID playerIdOf(String identityRef) {
        if (identityRef == null) {
            return null;
        }
        int colon = identityRef.indexOf(':');
        if (colon < 0 || !identityRef.substring(0, colon).equals(platformKind)) {
            return null;
        }
        try {
            return UUID.fromString(identityRef.substring(colon + 1));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
