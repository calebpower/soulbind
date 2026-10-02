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
package dev.soulbind.connector.forge;

import dev.soulbind.sdk.IdempotentApplier;
import dev.soulbind.sdk.Payload;
import dev.soulbind.sdk.SoulbindClient;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Keeps forge accounts in step with the gate, after they exist.
 *
 * <p>Admission is decided once, at creation. This is the other half: a subject
 * whose requirements lapse has its account deactivated, and one whose
 * requirements return has it activated again. Without this the gate would be a
 * turnstile somebody passes once and never again — unlinking a game account
 * would leave the forge account standing.
 *
 * <p>Core pushes nothing, so this polls. Latency is therefore one poll interval
 * and not zero, which is stated here because it is a property of the design
 * rather than a shortcoming of this class.
 */
public final class AccountEffector {

    private static final int BATCH = 100;

    private final SoulbindClient client;
    private final ForgeSurface forge;
    private final IdempotentApplier applier;
    private final String platformKind;
    private final String gate;
    private final BiConsumer<String, Throwable> log;

    /** Whether the last poll failed, so an outage is reported once rather than every cycle. */
    private boolean pollFailing;

    /** Whether a rule change has already been reported, for the same reason. */
    private boolean ruleChangeReported;

    public AccountEffector(
            SoulbindClient client,
            ForgeSurface forge,
            IdempotentApplier applier,
            String platformKind,
            String gate,
            BiConsumer<String, Throwable> log) {
        this.client = client;
        this.forge = forge;
        this.applier = applier;
        this.platformKind = platformKind;
        this.gate = gate;
        this.log = log;
    }

    /** What one pass did. */
    public record Drained(int seen, int applied, boolean acknowledged) {}

    /** A pass that reports its own failures rather than throwing at a scheduler. */
    public void drainQuietly() {
        try {
            drain();
        } catch (RuntimeException e) {
            log.accept("event drain failed", e);
        }
    }

    public Drained drain() {
        SoulbindClient.Outcome outcome = client.call("event.subscribe", new PollBody(null, BATCH));
        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            // The cursor did not move, so the next poll sees the same events.
            // Latched rather than silent: an unreachable core and a quiet one
            // look identical otherwise, and an account that should have been
            // deactivated an hour ago would have nothing anywhere saying why.
            if (!pollFailing) {
                pollFailing = true;
                log.accept(
                        "cannot poll core for events (" + describe(outcome) + "); no account"
                                + " will be activated or deactivated until this recovers."
                                + " Still retrying.",
                        null);
            }
            return new Drained(0, 0, false);
        }
        if (pollFailing) {
            pollFailing = false;
            log.accept("event polling recovered; resuming account sync", null);
        }

        List<Payload> events = ok.payload().items("events");
        if (events.isEmpty()) {
            return new Drained(0, 0, false);
        }

        int applied = 0;
        long lastCleanSequence = 0;

        for (Payload event : events) {
            long sequence = event.number("sequence");
            try {
                if (applier.applyOnce(event.text("idempotencyKey"), () -> apply(event))) {
                    applied++;
                }
                lastCleanSequence = sequence;
            } catch (RuntimeException e) {
                // Stop at the first failure. Acknowledging past it would turn an
                // event this connector failed to act on into one it will never
                // see again, and the account would simply stay as it was with
                // nothing saying why.
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

        // A rule change names a gate and no identity -- it is a fact about
        // everybody at once -- so it is handled before the identity-shaped path
        // below, which would drop it silently.
        if ("rule.changed".equals(type) && gate.equals(event.text("gate"))) {
            // KNOWN GAP, said out loud rather than left to be discovered. The
            // chat connector reconciles here by asking the platform who holds a
            // role; this surface cannot enumerate accounts, so a rule that got
            // STRICTER leaves existing accounts active until some other event
            // moves them. Reporting it is the difference between a limitation
            // and a silence.
            if (!ruleChangeReported) {
                ruleChangeReported = true;
                log.accept(
                        "the rule for '" + gate + "' changed; existing accounts are NOT"
                                + " reconciled against it. Accounts move when a subject's"
                                + " requirements next change. Review them by hand if the rule"
                                + " was tightened.",
                        null);
            }
            return;
        }

        // An event for another gate is not this effector's business, and acting
        // on it would deactivate an account over a requirement nobody tied to
        // the forge.
        if (!gate.equals(event.text("gate"))) {
            return;
        }

        String username = usernameOf(event.text("identityRef"));
        if (username == null) {
            return;
        }

        switch (type) {
            case "subject.requirements-met" -> setActive(username, true);
            case "subject.requirements-lost" -> setActive(username, false);
            default -> {
                // Every other event type is somebody else's: identity.linked is
                // already implied by the transition that follows it, and
                // config.changed says nothing about this gate.
            }
        }
    }

    private void setActive(String username, boolean active) {
        if (!forge.setActive(username, active)) {
            // Thrown, not logged and swallowed. drain() turns this into "stop
            // here and do not acknowledge", so the event is seen again on the
            // next pass -- which is the whole reason the cursor is not advanced
            // on send.
            throw new IllegalStateException(
                    "the forge would not " + (active ? "activate" : "deactivate") + " '"
                            + username + "'");
        }
    }

    /**
     * The forge name inside an identity reference, or null if it is not one.
     *
     * <p>References are {@code kind:id}. An event about another platform's
     * identity reaches this connector because the subject spans both, and
     * acting on it would deactivate an account named after somebody's Discord
     * snowflake.
     */
    private String usernameOf(String identityRef) {
        // No null check: the only caller passes Payload.text, whose contract is
        // the empty string for an absent field, never null. A guard here would
        // be a branch no input can reach -- indistinguishable, to anybody
        // reading coverage, from one that merely has no test yet.
        int colon = identityRef.indexOf(':');
        // EQUIVALENT MUTANT, recorded rather than rediscovered: widening this to
        // `colon <= 0` changes nothing observable. A reference beginning with a
        // colon has an empty kind, which cannot equal the configured kind --
        // ForgeConfig refuses a blank one -- so it returns null either way, two
        // lines further down. Kept as written because `< 0` says "there is no
        // colon" and `<= 0` says something subtly different. The chat connector
        // carries the same note on the same expression, DECISIONS 10.28.
        if (colon < 0) {
            return null;
        }
        if (!platformKind.equals(identityRef.substring(0, colon))) {
            return null;
        }
        String username = identityRef.substring(colon + 1);
        return username.isEmpty() ? null : username;
    }

    /**
     * Why a poll did not produce events.
     *
     * <p>A switch over the sealed outcome rather than a chain of instanceof
     * checks with a fallback: the fallback was unreachable by construction --
     * {@code Ok} is handled by the caller -- and an unreachable line is one no
     * test can cover and no reader can tell from a line that merely is not
     * covered yet.
     */
    private static String describe(SoulbindClient.Outcome outcome) {
        return switch (outcome) {
            case SoulbindClient.Outcome.Refused refused ->
                    refused.code().wireName() + ": " + refused.message();
            case SoulbindClient.Outcome.Unreachable unreachable -> unreachable.detail();
            case SoulbindClient.Outcome.Ok ok -> "a success, which the caller already handled";
        };
    }

    private record PollBody(Long after, Integer limit) {}

    private record AckBody(long through) {}
}
