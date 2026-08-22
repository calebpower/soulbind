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

import dev.soulbind.sdk.SoulbindClient;
import java.util.UUID;

/**
 * What {@code /link} does, with no Velocity types in sight.
 *
 * <p>A player can <b>start or finish</b> here: {@code /link} with no argument
 * asks for a code to type elsewhere, and {@code /link CODE} redeems one issued
 * elsewhere. Both, because the protocol is symmetric by construction and a
 * connector that only did one half would quietly make the other platform the
 * root of identity.
 *
 * <p>Separated from the command registration so every message a player can see
 * is testable without a proxy. The messages are the product here — somebody
 * mid-link reads them and decides what to do next — and they are the part
 * hardest to check by hand.
 */
public final class LinkCommandLogic {

    /** What to say to the player, and whether it worked. */
    public record Reply(boolean success, String message) {}

    private final SoulbindClient client;
    private final String platformKind;

    public LinkCommandLogic(SoulbindClient client, String platformKind) {
        this.client = client;
        this.platformKind = platformKind;
    }

    /** {@code /link} with no argument: ask for a code. */
    public Reply issue(UUID playerId, String playerName) {
        SoulbindClient.Outcome outcome = client.call(
                "code.issue", new IssuePayload(platformKind, playerId.toString(), playerName));

        if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
            String code = ok.payload().text("code");
            long expires = ok.payload().number("expiresAtEpochSeconds");
            return new Reply(
                    true,
                    "Your link code is " + code + ". Enter it on the other platform to "
                            + "finish linking. It expires in "
                            + minutesUntil(expires) + " minutes.");
        }
        return new Reply(false, explain(outcome, "get you a code"));
    }

    /** {@code /link CODE}: redeem one issued elsewhere. */
    public Reply redeem(UUID playerId, String playerName, String typedCode) {
        if (typedCode == null || typedCode.isBlank()) {
            return new Reply(false, "Usage: /link            (or /link CODE to finish linking)");
        }

        SoulbindClient.Outcome outcome = client.call(
                "code.redeem",
                new RedeemPayload(typedCode, platformKind, playerId.toString(), playerName));

        if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
            int linked = ok.payload().size("identities");
            return new Reply(
                    true,
                    "Linked. Your account is now connected to " + (linked - 1)
                            + " other " + (linked == 2 ? "account" : "accounts") + ".");
        }

        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            // The refusal's own words, because core distinguishes expired from
            // already-used from wrong-account and the player needs to know
            // which. Flattening them to "that did not work" would send somebody
            // to ask for a new code when their real problem is that they typed
            // their own.
            return new Reply(false, humanise(refused.message()));
        }
        return new Reply(false, explain(outcome, "check that code"));
    }

    /**
     * Turns a refusal into something a player can act on.
     *
     * <p>Core's messages are prefixed with a machine-readable refusal name, for
     * connectors. A player does not want to read {@code same-account:}.
     */
    private static String humanise(String message) {
        if (message == null || message.isBlank()) {
            return "That code did not work. Ask for a new one and try again.";
        }
        int colon = message.indexOf(": ");
        // The `>= 0` mutant of this is EQUIVALENT: with a colon at position
        // zero it makes `reason` the empty string, which matches no case, and
        // the default arm then falls back to the whole message -- exactly what
        // taking the whole message as the reason produces. DECISIONS 10.37.
        String reason = colon > 0 ? message.substring(0, colon) : message;

        return switch (reason) {
            case "unknown-code" -> "That code is not one we issued. Check it and try again.";
            case "expired" -> "That code has expired. Ask for a new one.";
            case "already-redeemed" -> "That code has already been used.";
            case "same-account" -> "That code was issued for this account. Enter it on the "
                    + "OTHER platform instead.";
            case "already-linked" -> "One of those accounts is already linked to somebody.";
            default -> colon > 0 ? message.substring(colon + 2) : message;
        };
    }

    /**
     * What to say when core did not answer.
     *
     * <p>Blames the system, like every other outage message. Somebody who ran a
     * command and hit an outage should not be told they did something wrong.
     */
    private static String explain(SoulbindClient.Outcome outcome, String whatWeTried) {
        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            return humanise(refused.message());
        }
        return "We could not " + whatWeTried + " right now -- that is a problem on our side, "
                + "not yours. Please try again shortly.";
    }

    private static long minutesUntil(long epochSeconds) {
        long remaining = epochSeconds - java.time.Instant.now().getEpochSecond();
        // At least one. Telling somebody "expires in 0 minutes" is telling them
        // not to bother, when they have most of a minute.
        return Math.max(1, remaining / 60);
    }

    private record IssuePayload(String platformKind, String platformId, String display) {}

    private record RedeemPayload(
            String code, String platformKind, String platformId, String display) {}
}
