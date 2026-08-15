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
import dev.soulbind.core.storage.Backend;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code soulbind doctor} — judge this installation and say what is wrong.
 *
 * <p>Modelled on the harness's own doctor, and for the same reason: the
 * expensive failures are configuration failures, and they are cheap to find and
 * expensive to diagnose from a stack trace at three in the morning.
 *
 * <p><b>A check that cannot run is not a pass.</b> Every check reports one of
 * {@code ok}, {@code WARN} or {@code FAIL}, and "I could not tell" is a warning
 * that says so rather than silence. Exit 0 when healthy (warnings permitted),
 * 1 when anything failed, 2 when the doctor itself could not run.
 */
public final class Doctor {

    /** What a single check concluded. */
    public enum Level {
        OK("ok  "),
        WARN("WARN"),
        FAIL("FAIL");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One finding.
     *
     * @param detail what to do about it, not merely what is wrong. A finding
     *     that names a problem without naming an action is a finding the reader
     *     has to research before they can act.
     */
    public record Finding(Level level, String check, String detail) {}

    /** Exit code meanings, so a script does not have to guess. */
    public static final int EXIT_HEALTHY = 0;

    public static final int EXIT_UNHEALTHY = 1;

    public static final int EXIT_CANNOT_RUN = 2;

    private Doctor() {
        throw new AssertionError("no instances");
    }

    /**
     * Runs every check against a configuration file.
     *
     * <p>Separated from printing and from {@link System#exit} so the tests can
     * assert the findings rather than scrape stdout — a doctor whose only
     * interface is a terminal is a doctor nobody tests.
     */
    public static List<Finding> examine(Path configFile) {
        List<Finding> findings = new ArrayList<>();

        if (!Files.isRegularFile(configFile)) {
            findings.add(new Finding(
                    Level.FAIL, "config",
                    configFile + " does not exist. Create it, or pass the path as the first "
                            + "argument."));
            return findings;
        }

        Config config;
        try {
            config = CoreConfig.load(configFile);
        } catch (ConfigException e) {
            for (String problem : e.problems()) {
                findings.add(new Finding(Level.FAIL, "config", problem));
            }
            return findings;
        } catch (RuntimeException e) {
            findings.add(new Finding(
                    Level.FAIL, "config", "cannot read " + configFile + ": " + e.getMessage()));
            return findings;
        }

        findings.add(new Finding(
                Level.OK, "config", configFile + " parses and declares no unknown keys"));

        for (String problem : CoreConfig.validate(config)) {
            findings.add(new Finding(Level.FAIL, "config", problem));
        }

        findings.addAll(examineBackend(config));
        findings.addAll(examineBinding(config));
        findings.addAll(examineSecrets(config));

        return findings;
    }

    private static List<Finding> examineBackend(Config config) {
        String name = config.getString(CoreConfig.STORAGE_BACKEND);
        if (Backend.fromConfigName(name).isEmpty()) {
            return List.of(new Finding(
                    Level.FAIL, "storage",
                    "'" + name + "' is not a storage backend this build knows. Known: "
                            + String.join(", ", backendNames()) + "."));
        }
        return List.of(new Finding(Level.OK, "storage", "backend '" + name + "' is recognised"));
    }

    private static List<String> backendNames() {
        // Read from the enum rather than written out here: a list in this file
        // would be a second copy that drifts, and it would put backend names
        // outside the storage package, which a guard forbids.
        List<String> names = new ArrayList<>();
        for (Backend backend : Backend.values()) {
            names.add(backend.configName());
        }
        return names;
    }

    private static List<Finding> examineBinding(Config config) {
        List<Finding> findings = new ArrayList<>();
        String host = CoreConfig.host(config);
        int port = config.getInt(CoreConfig.SERVER_PORT);

        if (host.equals("0.0.0.0") || host.equals("::")) {
            // A warning, not a failure: it is a legitimate deployment, and the
            // doctor's job is to make sure it was chosen rather than inherited.
            findings.add(new Finding(
                    Level.WARN, "bind",
                    "binding " + host + ":" + port + " exposes the connector transport on every "
                            + "interface. Correct behind a reverse proxy that terminates TLS; "
                            + "set server.host to a specific address otherwise."));
        } else {
            findings.add(new Finding(Level.OK, "bind", "binding " + host + ":" + port));
        }
        return findings;
    }

    private static List<Finding> examineSecrets(Config config) {
        List<Finding> findings = new ArrayList<>();

        // Whether the password came from the file or the environment, checked by
        // reading the file's own text -- because the loaded value cannot say
        // where it came from, and "it is set" is not the question.
        boolean fromEnvironment =
                System.getenv(CoreConfig.STORAGE_PASSWORD.envName()) != null;

        if (config.findString(CoreConfig.STORAGE_PASSWORD).isEmpty()) {
            findings.add(new Finding(
                    Level.WARN, "secrets",
                    "no storage password is set. Correct for a backend that does not use one; "
                            + "otherwise set " + CoreConfig.STORAGE_PASSWORD.envName() + "."));
        } else if (fromEnvironment) {
            findings.add(new Finding(
                    Level.OK, "secrets", "storage password supplied through the environment"));
        } else {
            findings.add(new Finding(
                    Level.WARN, "secrets",
                    "the storage password is written in the config file. It will be committed "
                            + "by somebody eventually. Move it to "
                            + CoreConfig.STORAGE_PASSWORD.envName() + "."));
        }

        int window = CoreConfig.signatureWindowSeconds(config);
        if (window > 900) {
            findings.add(new Finding(
                    Level.WARN, "replay",
                    "a signature window of " + window + "s is long. A captured request stays "
                            + "replayable for that long against a nonce store that has "
                            + "forgotten it."));
        } else {
            findings.add(new Finding(Level.OK, "replay", "signature window " + window + "s"));
        }

        return findings;
    }

    /** Renders findings, and returns the exit code they imply. */
    public static int report(List<Finding> findings, PrintStream out) {
        for (Finding finding : findings) {
            out.println(finding.level().label() + "  " + finding.check());
            out.println("      " + finding.detail());
        }

        long failed = findings.stream().filter(f -> f.level() == Level.FAIL).count();
        long warned = findings.stream().filter(f -> f.level() == Level.WARN).count();
        long ok = findings.size() - failed - warned;

        out.println();
        out.println(ok + " ok, " + warned + " warning(s), " + failed + " failed");
        return failed > 0 ? EXIT_UNHEALTHY : EXIT_HEALTHY;
    }

    /** Redacted, for an operator who wants to see what was loaded. */
    public static void describeConfig(Config config, PrintStream out) {
        for (Map.Entry<String, String> entry : config.describe().entrySet()) {
            out.println("  " + entry.getKey() + " = " + entry.getValue());
        }
    }
}
