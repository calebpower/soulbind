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

import dev.soulbind.sdk.SoulbindClient;
import java.util.List;
import java.util.Optional;

/**
 * Everything this connector does, above the surface seam.
 *
 * <p>No platform types anywhere in this file. It receives an
 * {@link ChatSurface.Invocation}, calls core, and replies — so the whole of it
 * runs against a scripted surface, and the battery exercises the real logic,
 * the real SDK, the real transport and a real core without the platform.
 *
 * <p>Two gates on every administrative command, and they are not the same gate:
 * the <b>capability</b> says what this connector's credential may ask core for,
 * and the <b>platform permission</b> says which humans may ask this connector.
 * A connector holding {@code config-management} would otherwise let any member
 * of a chat server rewrite policy, which is the capability model being correct
 * and the deployment being wrong.
 */
public final class ChatConnector {

    /** The commands this connector answers. */
    public static final List<String> COMMANDS = List.of("link", "whoami", "soulbind");

    private final SoulbindClient client;
    private final ChatSurface surface;
    private final String platformKind;

    public ChatConnector(SoulbindClient client, ChatSurface surface, String platformKind) {
        this.client = client;
        this.surface = surface;
        this.platformKind = platformKind;
    }

    /** Registers what this connector answers. */
    public void start() {
        surface.registerCommands(COMMANDS);
    }

    /** Routes one invocation. */
    public void handle(ChatSurface.Invocation invocation) {
        switch (invocation.command()) {
            case "link" -> handleLink(invocation);
            case "whoami" -> handleWhoami(invocation);
            case "soulbind" -> handleAdmin(invocation);
            default -> surface.reply(
                    invocation, "I do not know that command.", true);
        }
    }

    /**
     * One command, argument-dependent: {@code /link} issues, {@code /link CODE}
     * redeems.
     *
     * <p>Both halves, because the protocol is symmetric and a connector offering
     * only one would quietly make the other platform the root of identity.
     */
    private void handleLink(ChatSurface.Invocation invocation) {
        Optional<String> code = invocation.firstArgument();

        if (code.isEmpty()) {
            SoulbindClient.Outcome outcome = client.call("code.issue", new IssueBody(
                    platformKind, invocation.invoker().platformId(),
                    invocation.invoker().displayName()));

            if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
                // EPHEMERAL, always. A code in a public channel is a code
                // anybody can redeem -- and the person who asked for it would
                // have no idea somebody else took it.
                surface.reply(
                        invocation,
                        "Your link code is " + ok.payload().text("code")
                                + ". Enter it on the other platform to finish linking.",
                        true);
            } else {
                surface.reply(invocation, explain(outcome, "get you a code"), true);
            }
            return;
        }

        SoulbindClient.Outcome outcome = client.call("code.redeem", new RedeemBody(
                code.get(), platformKind, invocation.invoker().platformId(),
                invocation.invoker().displayName()));

        if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
            int linked = ok.payload().size("identities");
            surface.reply(
                    invocation,
                    "Linked. Your account is now connected to " + (linked - 1)
                            + " other " + (linked == 2 ? "account" : "accounts") + ".",
                    true);
        } else {
            surface.reply(invocation, explain(outcome, "check that code"), true);
        }
    }

    private void handleWhoami(ChatSurface.Invocation invocation) {
        SoulbindClient.Outcome outcome = client.call("identity.describe", new InspectBody(
                platformKind, invocation.invoker().platformId()));

        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            surface.reply(invocation, explain(outcome, "look that up"), true);
            return;
        }
        if (!ok.payload().flag("linked")) {
            surface.reply(
                    invocation,
                    "This account is not linked to anything yet. Use /link to start.",
                    true);
            return;
        }

        StringBuilder sb = new StringBuilder("This account is linked to:");
        for (var identity : ok.payload().items("identities")) {
            sb.append("\n  ").append(identity.text("platformKind"));
            String display = identity.text("display");
            if (!display.isBlank()) {
                sb.append(" (").append(display).append(")");
            }
            // Whether it is PROVEN, not merely present. A person reading this
            // wants to know which of their accounts still needs verifying, and
            // "linked" alone does not answer that.
            sb.append(identity.has("verifiedAtEpochSeconds")
                    && identity.number("verifiedAtEpochSeconds") > 0
                    ? " -- verified" : " -- not yet verified");
        }
        surface.reply(invocation, sb.toString(), true);
    }

    /**
     * Administrative commands, gated twice.
     *
     * <p>The platform permission is checked HERE, before core is asked. Asking
     * first and refusing on the answer would mean an unprivileged member could
     * probe policy by reading refusals — and would spend a round trip doing it.
     */
    private void handleAdmin(ChatSurface.Invocation invocation) {
        if (!invocation.invoker().isAdministrator()) {
            surface.reply(
                    invocation,
                    "That command is for server administrators.",
                    true);
            return;
        }
        if (invocation.arguments().isEmpty()) {
            surface.reply(invocation, "Usage: /soulbind <rules|connectors>", true);
            return;
        }

        switch (invocation.arguments().get(0)) {
            case "connectors" -> {
                SoulbindClient.Outcome outcome = client.call("connector.list", null);
                if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
                    StringBuilder sb = new StringBuilder("Registered connectors:");
                    for (var connector : ok.payload().items("connectors")) {
                        sb.append("\n  ").append(connector.text("name"))
                                .append(" (").append(connector.text("status")).append(")");
                    }
                    surface.reply(invocation, sb.toString(), true);
                } else {
                    surface.reply(invocation, explain(outcome, "list connectors"), true);
                }
            }
            default -> surface.reply(
                    invocation, "Usage: /soulbind <rules|connectors>", true);
        }
    }

    /**
     * Applies a role for a subject that satisfied a gate.
     *
     * <p>Idempotent by asking first. Delivery is at-least-once, so this runs
     * again for events already applied — and a platform that logs an audit entry
     * per grant would otherwise fill with duplicates of a thing that did not
     * change.
     *
     * @return true if the role was applied by this call
     */
    public boolean applyRole(String platformId, String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        if (surface.hasRole(platformId, role)) {
            return false;
        }
        return surface.grantRole(platformId, role);
    }

    /** Removes a role for a subject that stopped satisfying a gate. */
    public boolean removeRole(String platformId, String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        if (!surface.hasRole(platformId, role)) {
            return false;
        }
        return surface.revokeRole(platformId, role);
    }

    /**
     * Turns a refusal into something a person can act on.
     *
     * <p>Shared with the game connector in shape but not in code, deliberately:
     * the wording differs per platform, and a shared string table would make
     * every message a compromise between two audiences.
     */
    private static String explain(SoulbindClient.Outcome outcome, String whatWeTried) {
        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            String message = refused.message();
            int colon = message == null ? -1 : message.indexOf(": ");
            String reason = colon > 0 ? message.substring(0, colon) : message;

            return switch (reason == null ? "" : reason) {
                case "unknown-code" -> "That code is not one we issued. Check it and try again.";
                case "expired" -> "That code has expired. Ask for a new one.";
                case "already-redeemed" -> "That code has already been used.";
                case "same-account" -> "That code was issued for this account. Enter it on the "
                        + "other platform instead.";
                case "already-linked" -> "One of those accounts is already linked to somebody.";
                default -> colon > 0 ? message.substring(colon + 2)
                        : "That did not work: " + reason;
            };
        }
        return "I could not " + whatWeTried + " right now -- that is a problem on our side, "
                + "not yours. Please try again shortly.";
    }

    private record IssueBody(String platformKind, String platformId, String display) {}

    private record RedeemBody(
            String code, String platformKind, String platformId, String display) {}

    private record InspectBody(String platformKind, String platformId) {}
}
