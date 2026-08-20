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

package dev.soulbind.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the committed seed set against a real core and reports.
 *
 * <p>Invoked by the full-stack harness, once per storage backend, which is what
 * §14's gate asks for: "three fixed seeds × both backends green in a reaper
 * session".
 */
public final class Runner {

    private static final int DEFAULT_ACTIONS = 400;
    private static final int DEFAULT_CHECK_PERIOD = 50;

    private Runner() {
        throw new AssertionError("no instances");
    }

    /**
     * The report, as a string, so it can be asserted without a network.
     *
     * <p>The inert-invariant list is printed FIRST and unconditionally. It is
     * the one part of this output that describes what the run did <b>not</b>
     * check, and burying it under a green result is how a narrowing becomes
     * invisible.
     */
    public static String report(List<Simulation.Outcome> outcomes, CoreView view) {
        StringBuilder out = new StringBuilder();

        List<String> inert = view.inertInvariants();
        if (inert.isEmpty()) {
            out.append("[sim] every invariant is answerable against this core\n");
        } else {
            out.append("[sim] NOT CHECKED against this core, ")
                    .append(inert.size()).append(" invariant(s):\n");
            for (String reason : inert) {
                out.append("[sim]   - ").append(reason).append('\n');
            }
        }

        int failed = 0;
        for (Simulation.Outcome outcome : outcomes) {
            out.append("[sim] ").append(outcome.summary().replace("\n", "\n[sim] ")).append('\n');
            if (!outcome.clean()) {
                failed++;
                out.append("[sim]   promote this seed -- add to harness/sim/src/main/"
                                + "resources/seeds.txt:\n[sim]     ")
                        .append(outcome.seed()).append("  found ")
                        .append(outcome.violations().get(0).invariant())
                        .append(" on <date>\n");
            }
        }

        out.append("[sim] ").append(outcomes.size() - failed).append(" of ")
                .append(outcomes.size()).append(" seeds clean");
        return out.toString();
    }

    /** Reads {@code name=credential} lines; {@code admin} and {@code retired} are reserved. */
    static Map<String, String> readCredentials(Path file) throws IOException {
        Map<String, String> credentials = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals < 0) {
                throw new IllegalStateException(
                        "credential file line is not name=value: " + trimmed);
            }
            credentials.put(trimmed.substring(0, equals).strip(),
                    trimmed.substring(equals + 1).strip());
        }
        for (String required : List.of("admin", "retired")) {
            if (!credentials.containsKey(required)) {
                throw new IllegalStateException(
                        "no '" + required + "' credential. Without it the run would either"
                                + " skip the classes that need it or quietly use an actor's,"
                                + " and a run that silently drops a nemesis class reports"
                                + " green for less work.");
            }
        }
        if (credentials.size() <= 2) {
            throw new IllegalStateException(
                    "no actor credentials. Every actor is a separate principal; a run with"
                            + " none would exercise nothing.");
        }
        return credentials;
    }

    /**
     * Builds the cast from whatever credentials were supplied.
     *
     * <p>Each actor spans platforms, per §11 — the defects worth finding live in
     * the cross-platform graph, and an actor confined to one platform exercises
     * one connector, which every other tier does better.
     */
    static World worldFor(Map<String, String> credentials, String runTag) {
        List<Actor> actors = new ArrayList<>();
        for (String name : credentials.keySet()) {
            if (name.equals("admin") || name.equals("retired")) {
                continue;
            }
            actors.add(new Actor(name, List.of(
                    "game:" + name + runTag,
                    "chat:" + name + runTag,
                    "forum:" + name + runTag), 0));
        }
        return new World(actors, List.of("game.join", "forum.post"));
    }

    public static void main(String[] args) throws Exception {
        String coreUrl = require("SOULBIND_SIM_CORE_URL");
        Path credentialFile = Path.of(require("SOULBIND_SIM_CREDENTIALS"));
        int actions = Integer.parseInt(
                System.getenv().getOrDefault("SOULBIND_SIM_ACTIONS",
                        String.valueOf(DEFAULT_ACTIONS)));

        // The run tag, drawn OUTSIDE the seeded stream -- §11's subtlest
        // requirement. It distinguishes this run's rows from an earlier run's
        // against the same database; if it came from the seed, replaying a seed
        // would collide with the original run's data.
        String runTag = "-" + System.getenv().getOrDefault("SOULBIND_SIM_TAG", "local");

        Map<String, String> credentials = readCredentials(credentialFile);
        SdkCore core = new SdkCore(
                coreUrl,
                stripReserved(credentials),
                credentials.get("admin"),
                credentials.get("retired"));

        List<Simulation.Outcome> outcomes = new ArrayList<>();
        for (Seeds.Seed seed : Seeds.fixed()) {
            outcomes.add(Simulation.run(
                    seed.value(), worldFor(credentials, runTag), core, core,
                    actions, DEFAULT_CHECK_PERIOD));
        }

        System.out.println(report(outcomes, core));
        boolean clean = outcomes.stream().allMatch(Simulation.Outcome::clean);
        if (!clean) {
            for (Simulation.Outcome outcome : outcomes) {
                if (!outcome.clean()) {
                    System.out.println("[sim] trace for seed " + outcome.seed() + ":");
                    outcome.trace().forEach(line -> System.out.println("[sim]   " + line));
                }
            }
        }
        System.exit(clean ? 0 : 1);
    }

    private static Map<String, String> stripReserved(Map<String, String> credentials) {
        Map<String, String> actors = new LinkedHashMap<>(credentials);
        actors.remove("admin");
        actors.remove("retired");
        return actors;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
