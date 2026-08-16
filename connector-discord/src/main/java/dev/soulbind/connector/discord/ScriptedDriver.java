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

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.HttpTransport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the real connector over the scripted surface, from the command line.
 *
 * <p>The full-stack battery's chat side. It runs the <b>real</b>
 * {@link ChatConnector}, the real SDK and the real transport against a real
 * core — everything except the platform, which the scripted surface stands in
 * for.
 *
 * <p>That distinction is the point. A stack test that redeemed a code with a
 * hand-written HTTP request would prove core works and say nothing about the
 * connector; this proves the connector's own command handling, its refusal
 * wording and its privacy rules, in the same run.
 *
 * <p>Prints what the surface received, so the caller asserts on what a person
 * would have seen rather than on an exit code.
 *
 * <pre>
 *   ScriptedDriver &lt;core-url&gt; &lt;credential&gt; &lt;platform-id&gt; &lt;command&gt; [args...]
 * </pre>
 */
public final class ScriptedDriver {

    private ScriptedDriver() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println(
                    "usage: ScriptedDriver <core-url> <credential> <platform-id> <command> "
                            + "[args...]");
            System.exit(2);
        }

        String coreUrl = args[0];
        String credential = args[1];
        String platformId = args[2];
        String command = args[3];
        List<String> commandArgs = new ArrayList<>();
        for (int i = 4; i < args.length; i++) {
            commandArgs.add(args[i]);
        }

        boolean administrator = Boolean.parseBoolean(
                System.getenv().getOrDefault("SOULBIND_DRIVER_ADMIN", "false"));

        ScriptedSurface surface = new ScriptedSurface();
        try (SoulbindClient client = new SoulbindClient(
                new HttpTransport(coreUrl, credential, Clock.systemUTC()),
                credential,
                Clock.systemUTC(),
                new DecisionCache())) {

            ChatConnector connector = new ChatConnector(
                    client, surface,
                    System.getenv().getOrDefault("SOULBIND_DRIVER_KIND", "chat"));
            connector.start();

            connector.handle(new ChatSurface.Invocation(
                    command,
                    commandArgs,
                    new ChatSurface.Invoker(platformId, "Scripted", administrator)));
        }

        if (surface.sent().isEmpty()) {
            System.err.println(
                    "the connector said nothing. Every command must produce a reply -- silence "
                            + "leaves somebody staring at a prompt wondering whether it worked.");
            System.exit(1);
        }

        // stdout is the MESSAGE, one per line, and nothing else. Same discipline
        // as `soulbind register --quiet`: a driver meant to be scripted cannot
        // share a stream between its output and its commentary.
        for (ScriptedSurface.Sent sent : surface.sent()) {
            System.out.println(sent.message().replace("\n", " | "));
        }

        // Privacy is asserted HERE rather than left to the caller, because a
        // caller checking it would be a caller who might forget -- and a code
        // shown publicly is a code anybody can redeem.
        for (ScriptedSurface.Sent sent : surface.sent()) {
            if (!sent.ephemeral()) {
                System.err.println("the connector replied publicly: " + sent.message());
                System.exit(1);
            }
        }
    }
}
