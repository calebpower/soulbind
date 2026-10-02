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

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigException;
import dev.soulbind.connector.forge.transport.ForgejoAdminSurface;
import dev.soulbind.connector.forge.transport.SignupServer;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.IdempotentApplier;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.HttpTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connector daemon.
 *
 * <p>Thin, like the other connectors' entry points and for the same reason:
 * everything with a decision in it is in a class that names no host type. This
 * starts things and connects them.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        Path configFile = Path.of(args.length > 0 ? args[0] : "soulbind-forge.toml");

        if (!Files.isRegularFile(configFile)) {
            // Refuse to start rather than run on defaults. A connector that
            // starts and gates nothing looks exactly like one that works, and
            // in this connector's case it would be the only door to the forge
            // standing open.
            LOG.error("no configuration at {}", configFile);
            System.exit(2);
            return;
        }

        Config config;
        try {
            config = ForgeConfig.load(configFile);
        } catch (ConfigException e) {
            LOG.error("{}", e.getMessage());
            System.exit(1);
            return;
        }

        List<String> problems = ForgeConfig.validate(config);
        if (!problems.isEmpty()) {
            problems.forEach(LOG::error);
            System.exit(1);
            return;
        }

        BiConsumer<String, Throwable> log = (message, cause) -> {
            if (cause == null) {
                LOG.info("{}", message);
            } else {
                LOG.warn("{}", message, cause);
            }
        };

        String credential = config.findString(ForgeConfig.CREDENTIAL).orElse("");
        String kind = ForgeConfig.platformKind(config);
        String gate = ForgeConfig.gate(config);

        SoulbindClient client = new SoulbindClient(
                new HttpTransport(
                        config.getString(ForgeConfig.CORE_URL), credential, Clock.systemUTC()),
                credential,
                Clock.systemUTC(),
                new DecisionCache(ForgeConfig.failMode(config)));

        ForgejoAdminSurface forge = new ForgejoAdminSurface(
                config.getString(ForgeConfig.HOST_URL),
                config.findString(ForgeConfig.HOST_TOKEN).orElse(""),
                Duration.ofMillis(ForgeConfig.timeoutMs(config)),
                log);

        Registration registration = new Registration(client, forge, kind, gate);

        SignupServer server = new SignupServer(registration, ForgeConfig.signupPath(config), log)
                .start(ForgeConfig.signupBind(config), ForgeConfig.signupPort(config));
        LOG.info("signup form on {}:{}{}",
                ForgeConfig.signupBind(config), server.port(), ForgeConfig.signupPath(config));

        AccountEffector effector = new AccountEffector(
                client, forge, new IdempotentApplier(), kind, gate, log);

        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "soulbind-forge-events");
            t.setDaemon(true);
            return t;
        });

        int seconds = ForgeConfig.pollSeconds(config);
        // drainQuietly, not drain: the containment lives in AccountEffector so a
        // test can watch it work, and a scheduled task that throws is one that
        // stops running -- in some executors without a word.
        //
        // scheduleWithFixedDelay, not AtFixedRate: a drain that takes longer
        // than the interval must not have another queued behind it.
        poller.scheduleWithFixedDelay(
                effector::drainQuietly, seconds, seconds, TimeUnit.SECONDS);
        LOG.info("polling core for '{}' events every {}s", gate, seconds);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // The listener is the half that holds a port. Releasing it on the
            // way out is what lets a restart bind again rather than failing on
            // an address still in use.
            poller.shutdownNow();
            server.close();
        }, "soulbind-forge-shutdown"));
    }
}
