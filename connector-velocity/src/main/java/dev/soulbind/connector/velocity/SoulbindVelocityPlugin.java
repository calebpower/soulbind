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

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.soulbind.config.Config;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.Transport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * The plugin: adapts proxy events to the logic beside it.
 *
 * <p><b>Deliberately thin.</b> Every decision this file could make is made in a
 * class with no Velocity types — {@link JoinGate}, {@link LinkCommandLogic},
 * {@link GroupEffector}, {@link BedrockIdentity} — because those are testable in
 * milliseconds and this is not. What is left here is wiring: an event becomes a
 * call, a verdict becomes a kick, a command becomes a message.
 *
 * <p>If a behaviour worth asserting appears in this file, it is in the wrong
 * file.
 */
@Plugin(
        id = "soulbind",
        name = "soulbind",
        version = "0.1.0",
        description = "Cross-platform account linking",
        authors = {"Caleb L. Power"})
public final class SoulbindVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Config config;
    private SoulbindClient client;
    private JoinGate joinGate;
    private LinkCommandLogic linkCommand;
    private GroupEffector effector;
    private GroupSync groupSync;
    private java.util.concurrent.ScheduledExecutorService drains;
    private ExecutorService pool;

    @Inject
    public SoulbindVelocityPlugin(
            ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        Path configFile = dataDirectory.resolve("soulbind.toml");
        if (!Files.isRegularFile(configFile)) {
            // Refuse to run half-configured. A plugin that starts with defaults
            // and enforces nothing looks exactly like a plugin that is working.
            logger.error(
                    "no configuration at {}. soulbind will not enforce anything until it "
                            + "exists -- see the module README for a minimal file.",
                    configFile);
            return;
        }

        try {
            config = VelocityConfig.load(configFile);
        } catch (RuntimeException e) {
            logger.error("configuration could not be read: {}", e.getMessage());
            return;
        }

        List<String> problems = VelocityConfig.validate(config);
        if (!problems.isEmpty()) {
            problems.forEach(logger::error);
            return;
        }

        // One thread per available core, bounded. The pool exists so no core
        // round trip happens on a proxy event thread; making it unbounded would
        // trade a blocked event thread for an unbounded thread count, which
        // fails later and worse.
        pool = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r, "soulbind-connector");
                    t.setDaemon(true);
                    return t;
                });

        Transport transport = buildTransport(config);
        client = new SoulbindClient(
                transport,
                config.findString(VelocityConfig.CREDENTIAL).orElse(""),
                Clock.systemUTC(),
                new DecisionCache(VelocityConfig.failMode(config)));

        joinGate = new JoinGate(
                client,
                pool,
                VelocityConfig.decisionTimeout(config),
                config.findString(VelocityConfig.JOIN_GATE).orElse(null),
                VelocityConfig.platformKind(config),
                VelocityConfig.kickMessage(config));

        linkCommand = new LinkCommandLogic(client, VelocityConfig.platformKind(config));

        effector = GroupEffector.discover(
                "net.luckperms.api.LuckPermsProvider",
                // The real resolver. This was `Optional::empty` -- a
                // placeholder that made the effector fall through to absent()
                // even with LuckPerms installed, so every group grant was a
                // no-op. DECISIONS 10.23.
                () -> LuckPermsGroups.resolve((message, cause) -> {
                    if (cause == null) {
                        logger.info(message);
                    } else {
                        logger.warn(message, cause);
                    }
                }),
                (message, cause) -> {
                    if (cause == null) {
                        logger.info(message);
                    } else {
                        logger.warn(message, cause);
                    }
                });

        // The event drain, which did not exist: nothing here ever called the
        // effector, so effector.group achieved nothing however it was
        // configured. DECISIONS 10.23.
        groupSync = new GroupSync(
                client,
                effector,
                new dev.soulbind.sdk.IdempotentApplier(),
                config.findString(VelocityConfig.JOIN_GATE).orElse(null),
                config.findString(VelocityConfig.EFFECTOR_GROUP).orElse(null),
                VelocityConfig.platformKind(config),
                () -> proxy.getAllPlayers().stream().map(Player::getUniqueId).toList(),
                (message, cause) -> {
                    if (cause == null) {
                        logger.info(message);
                    } else {
                        logger.warn(message, cause);
                    }
                });

        if (groupSync.isConfigured()) {
            // On the connector's own pool, never a proxy event thread, and at a
            // fixed delay rather than a fixed rate: a slow core must not queue
            // drains behind each other until the pool is full of them.
            drains = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "soulbind-groups");
                t.setDaemon(true);
                return t;
            });
            drains.scheduleWithFixedDelay(() -> {
                try {
                    groupSync.drain();
                } catch (RuntimeException e) {
                    // Never propagate: an exception out of a scheduled task
                    // cancels all future runs silently, and the group effector
                    // would simply stop with nothing said.
                    logger.warn("group sync failed; it will be retried", e);
                }
            }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
            logger.info("group sync active: '{}' on gate '{}'",
                    config.findString(VelocityConfig.EFFECTOR_GROUP).orElse(""),
                    config.findString(VelocityConfig.JOIN_GATE).orElse(""));
        }

        CommandManager commands = proxy.getCommandManager();
        commands.register(
                commands.metaBuilder("link").plugin(this).build(), new LinkCommand());

        logger.info(
                "soulbind ready; join gate {}",
                config.findString(VelocityConfig.JOIN_GATE)
                        .map(g -> "enforcing " + g)
                        .orElse("disabled"));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (drains != null) {
            drains.shutdownNow();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        if (client != null) {
            client.close();
        }
    }

    /**
     * The join gate.
     *
     * <p>{@code LoginEvent} rather than a later one: a player denied here never
     * reaches a backend, so no backend has to know about the gate. Denying after
     * a server connection would mean every backend needs the same check.
     */
    @Subscribe
    public void onLogin(LoginEvent event) {
        if (joinGate == null) {
            // Misconfigured at start-up. Already logged as an error there; not
            // repeated per join, because a log line per connection is how the
            // one that mattered gets buried.
            return;
        }
        Player player = event.getPlayer();
        JoinGate.Verdict verdict = joinGate.check(player.getUniqueId(), player.getUsername());

        if (!verdict.allowed()) {
            event.setResult(
                    com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(
                            Component.text(verdict.message())));
        }
    }

    private Transport buildTransport(Config config) {
        // The HTTP transport lives in the SDK's transport package, which is the
        // only place an HTTP type may appear. Constructed here because the URL
        // is configuration.
        return new dev.soulbind.sdk.transport.HttpTransport(
                config.getString(VelocityConfig.CORE_URL),
                config.findString(VelocityConfig.CREDENTIAL).orElse(""),
                Clock.systemUTC());
    }

    /** {@code /link} and {@code /link CODE}. */
    private final class LinkCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("/link is for players."));
                return;
            }
            if (linkCommand == null) {
                source.sendMessage(
                        Component.text("Linking is not configured on this proxy."));
                return;
            }

            String[] args = invocation.arguments();
            UUID id = player.getUniqueId();
            String display = BedrockIdentity.displayFor(
                    player.getUsername(),
                    config.findString(VelocityConfig.BEDROCK_PREFIX).orElse(null),
                    id);

            // Off the event thread. A command handler that blocks on a network
            // call blocks the same thread pool a join would use.
            pool.submit(() -> {
                LinkCommandLogic.Reply reply = args.length == 0
                        ? linkCommand.issue(id, display)
                        : linkCommand.redeem(id, display, args[0]);
                player.sendMessage(Component.text(reply.message()));
            });
        }
    }
}
