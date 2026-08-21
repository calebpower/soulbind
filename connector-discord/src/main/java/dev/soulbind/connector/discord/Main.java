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

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigException;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.IdempotentApplier;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.HttpTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connector daemon.
 *
 * <p>Thin, like the proxy plugin and for the same reason: everything with a
 * decision in it is in a class with no platform types. This starts things and
 * connects them.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args.length > 0 ? args[0] : "soulbind-discord.toml");

        if (!Files.isRegularFile(configFile)) {
            // Refuse to start rather than run on defaults. A connector that
            // starts and enforces nothing looks exactly like one that works.
            LOG.error("no configuration at {}", configFile);
            System.exit(2);
        }

        Config config;
        try {
            config = DiscordConfig.load(configFile);
        } catch (ConfigException e) {
            LOG.error("{}", e.getMessage());
            System.exit(1);
            return;
        }

        List<String> problems = DiscordConfig.validate(config);
        if (!problems.isEmpty()) {
            problems.forEach(LOG::error);
            System.exit(1);
        }

        String credential = config.findString(DiscordConfig.CREDENTIAL).orElse("");
        SoulbindClient client = new SoulbindClient(
                new HttpTransport(
                        config.getString(DiscordConfig.CORE_URL), credential, Clock.systemUTC()),
                credential,
                Clock.systemUTC(),
                new DecisionCache(DiscordConfig.failMode(config)));

        var jda = JDABuilder.createLight(
                        config.getString(DiscordConfig.BOT_TOKEN),
                        // The minimum. A bot asking for more than it uses is a
                        // bot an operator has to justify to their members, and
                        // this one reads nothing but its own commands.
                        GatewayIntent.GUILD_MEMBERS)
                .build();
        jda.awaitReady();

        JdaSurface surface = new JdaSurface(
                jda,
                config.findString(DiscordConfig.GUILD_ID).orElse(null),
                (message, cause) -> {
                    if (cause == null) {
                        LOG.info("{}", message);
                    } else {
                        LOG.warn("{}", message, cause);
                    }
                });

        ChatConnector connector = new ChatConnector(
                client, surface, DiscordConfig.platformKind(config));
        connector.start();

        jda.addEventListener(new ListenerAdapter() {
            @Override
            public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                // Adopted, then handled. The surface remembers where the reply
                // goes; the connector never learns there was an event.
                connector.handle(surface.adopt(event));
            }
        });

        String role = config.findString(DiscordConfig.LINKED_ROLE).orElse(null);
        String gate = config.findString(DiscordConfig.GATE).orElse(null);

        if (role != null && gate != null) {
            RoleEffector effector = new RoleEffector(
                    client, connector, new IdempotentApplier(), gate, role,
                    DiscordConfig.platformKind(config),
                    (message, cause) -> LOG.warn("{}", message, cause));

            ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "soulbind-events");
                t.setDaemon(true);
                return t;
            });

            int seconds = DiscordConfig.pollSeconds(config);
            // drainQuietly, not drain: the containment lives in RoleEffector so
            // a test can watch it work, and it catches Throwable rather than
            // RuntimeException -- this was an inline catch that did not hold the
            // guarantee its own comment claimed.
            //
            // scheduleWithFixedDelay, not AtFixedRate: a drain that takes longer
            // than the interval must not have another queued behind it, or a
            // slow core turns into a backlog of pollers.
            poller.scheduleWithFixedDelay(
                    effector::drainQuietly, 0, seconds, TimeUnit.SECONDS);

            LOG.info("polling for events every {}s, granting '{}' on '{}'", seconds, role, gate);
        } else {
            LOG.info("no effector configured; commands only");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down");
            jda.shutdown();
            client.close();
        }));
    }
}
