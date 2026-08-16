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

package dev.soulbind.core.cli;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigException;
import dev.soulbind.core.CoreConfig;
import dev.soulbind.core.CoreVersion;
import dev.soulbind.core.events.EventEmitter;
import dev.soulbind.core.identity.LinkingService;
import dev.soulbind.core.registry.Authenticator;
import dev.soulbind.core.storage.Storage;
import dev.soulbind.core.transport.Codec;
import dev.soulbind.core.transport.CoreHandlers;
import dev.soulbind.core.transport.Dispatcher;
import dev.soulbind.core.transport.NonceStore;
import dev.soulbind.core.transport.TransportServer;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code soulbind} command.
 *
 * <p>Three verbs, and no more than three: {@code doctor} judges an
 * installation, {@code register} mints a connector credential, {@code serve}
 * runs the dispatcher. Everything else an operator might want is an
 * <em>operation</em> reachable through the admin credential and the same
 * authorization table — not a second management surface with its own rules that
 * gradually diverge from the first.
 */
public final class Main {

    private Main() {
        throw new AssertionError("no instances");
    }

    /** Default config path, overridable by the {@code --config} argument. */
    public static final Path DEFAULT_CONFIG = Path.of("soulbind.toml");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The command, with its streams injected.
     *
     * <p>Separate from {@link #main} so the tests can assert exit codes and
     * output without a process boundary or a call to {@link System#exit} in the
     * middle of a test JVM.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> argv = Arrays.asList(args);
        if (argv.isEmpty() || argv.contains("--help") || argv.contains("-h")) {
            usage(out);
            return argv.isEmpty() ? Doctor.EXIT_CANNOT_RUN : Doctor.EXIT_HEALTHY;
        }

        Path configFile = configPathFrom(argv);
        String verb = argv.get(0);

        try {
            return switch (verb) {
                case "doctor" -> Doctor.report(Doctor.examine(configFile), out);
                case "register" -> register(argv, configFile, out, err);
                case "serve" -> serve(configFile, out, err);
                case "version" -> {
                    out.println("soulbind " + CoreVersion.VERSION);
                    yield Doctor.EXIT_HEALTHY;
                }
                default -> {
                    err.println("unknown verb: " + verb);
                    usage(err);
                    yield Doctor.EXIT_CANNOT_RUN;
                }
            };
        } catch (ConfigException e) {
            // Printed as the loader wrote it: every problem at once, because an
            // operator fixing one per restart stops reading and starts guessing.
            err.println(e.getMessage());
            return Doctor.EXIT_UNHEALTHY;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println(e.getMessage());
            return Doctor.EXIT_UNHEALTHY;
        }
    }

    private static int register(
            List<String> argv, Path configFile, PrintStream out, PrintStream err) {

        String name = valueOf(argv, "--name");
        if (name == null) {
            err.println("register needs --name <connector-name>");
            return Doctor.EXIT_CANNOT_RUN;
        }
        String capabilities = valueOf(argv, "--capabilities");
        List<String> claimed = capabilities == null || capabilities.isBlank()
                ? List.of()
                : Arrays.stream(capabilities.split(",")).map(String::strip).toList();

        // --quiet prints ONLY the credential, for scripts.
        //
        // Added because a stack script parsed the human report with awk and
        // picked up a log line that also contained the word "credential" --
        // producing a multi-line "credential" that the HTTP client then refused
        // as an invalid header. A command meant to be scripted should not need
        // fragile parsing to be scripted.
        boolean quiet = argv.contains("--quiet");

        Config config = CoreConfig.load(configFile);
        try (Storage storage = Bootstrap.open(config)) {
            Bootstrap.Registered registered = Bootstrap.register(storage, name, claimed);
            if (quiet) {
                out.println(registered.credential());
            } else {
                Bootstrap.report(registered, out);
            }
        }
        return Doctor.EXIT_HEALTHY;
    }

    private static int serve(Path configFile, PrintStream out, PrintStream err) {
        Config config = CoreConfig.load(configFile);

        // The doctor's checks run before serving, not only when asked. A
        // configuration that would fail `doctor` should not silently start:
        // the failure it names would otherwise surface as a runtime symptom
        // somewhere unrelated.
        List<String> problems = CoreConfig.validate(config);
        if (!problems.isEmpty()) {
            problems.forEach(err::println);
            return Doctor.EXIT_UNHEALTHY;
        }

        Duration window = Duration.ofSeconds(CoreConfig.signatureWindowSeconds(config));
        Clock clock = Clock.systemUTC();

        try (Storage storage = Bootstrap.open(config)) {
            Codec codec = new Codec();
            Authenticator authenticator = new Authenticator(storage.connectors());
            Dispatcher dispatcher = new Dispatcher(
                    authenticator,
                    CoreHandlers.build(
                            storage.connectors(),
                            storage.audit(),
                            storage.identities(),
                            storage.policy(),
                            storage.events(),
                            storage.runtimeConfig(),
                            new LinkingService(
                                    new EventEmitter(storage.events(), clock),
                                    storage.identities(), storage.linkCodes(),
                                    storage.platformKinds(), storage.audit(), clock,
                                    Duration.ofSeconds(
                                            CoreConfig.linkCodeTtlSeconds(config))),
                            codec,
                            clock,
                            (int) window.toSeconds()));

            try (TransportServer server = new TransportServer(
                    dispatcher, codec, authenticator, window, new NonceStore(window), clock)) {

                int port = server.start(
                        CoreConfig.host(config), config.getInt(CoreConfig.SERVER_PORT));
                out.println("soulbind " + CoreVersion.VERSION + " listening on "
                        + CoreConfig.host(config) + ":" + port);
                Doctor.describeConfig(config, out);

                // Park until the JVM is asked to stop. The server's own threads
                // do the work; this one exists only so the process does not exit.
                Thread.currentThread().join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("stopping");
        }
        return Doctor.EXIT_HEALTHY;
    }

    private static Path configPathFrom(List<String> argv) {
        String configured = valueOf(argv, "--config");
        return configured == null ? DEFAULT_CONFIG : Path.of(configured);
    }

    /** Reads {@code --flag value}. Absent returns null; a flag with no value does too. */
    private static String valueOf(List<String> argv, String flag) {
        int i = argv.indexOf(flag);
        if (i < 0 || i + 1 >= argv.size()) {
            return null;
        }
        return argv.get(i + 1);
    }

    private static void usage(PrintStream out) {
        out.println("soulbind " + CoreVersion.VERSION);
        out.println();
        out.println("  soulbind doctor   [--config <file>]");
        out.println("        Judge this installation and say what is wrong.");
        out.println("        Exit 0 healthy (warnings permitted), 1 unhealthy, 2 cannot run.");
        out.println();
        out.println("  soulbind register --name <n> [--capabilities a,b] [--quiet]");
        out.println("        Register a connector and print its credential ONCE.");
        out.println("        --quiet prints only the credential, for scripts.");
        out.println();
        out.println("  soulbind serve    [--config <file>]");
        out.println("        Run the dispatcher.");
        out.println();
        out.println("  soulbind version");
        out.println();
        out.println("Everything else an operator can do is an operation reachable through an");
        out.println("admin credential, under the same capability table -- not a second");
        out.println("management surface with rules that drift from the first.");
    }
}
