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

package dev.soulbind.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The one place soulbind reads a configuration file.
 *
 * <p>TOML everywhere soulbind owns the file — never YAML, INI or JSON. Two
 * formats means two loaders, two sets of parsing edge cases, and a question at
 * every new file. A guard asserts no YAML parser enters any module's dependency
 * graph, and this module is the only one that declares a TOML parser at all.
 *
 * <p>Three properties, each chosen against a specific failure:
 *
 * <ul>
 *   <li><b>Unknown keys are rejected.</b> A misspelt key that is silently
 *       ignored is the most expensive configuration bug there is: the setting
 *       looks present, the default is used, and the symptom appears somewhere
 *       else entirely.
 *   <li><b>Every problem is reported at once.</b> Fixing one error per restart
 *       teaches an operator to stop reading and start guessing.
 *   <li><b>The environment overrides the file.</b> Secrets belong in the
 *       environment, and a deployment that supplies one should not also have to
 *       template a file around it.
 * </ul>
 */
public final class ConfigLoader {

    private ConfigLoader() {
        throw new AssertionError("no instances");
    }

    /** Loads from a file, with {@link System#getenv()} supplying overrides. */
    public static Config load(Path file, ConfigSchema schema) {
        return load(file, schema, System.getenv());
    }

    /**
     * Loads from a file, with an explicit environment.
     *
     * <p>The environment is a parameter rather than read directly so the tests
     * can drive override behaviour without mutating the process environment —
     * which Java cannot do portably anyway, and which would make the tests
     * order-dependent if it could.
     */
    public static Config load(Path file, ConfigSchema schema, Map<String, String> env) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config file " + file, e);
        }
        return parse(text, file.toString(), schema, env);
    }

    /**
     * Loads from TOML text.
     *
     * <p>Separate from the file overload so a caller with configuration from
     * somewhere else — a test, an admin API, a host platform's own store — gets
     * identical parsing, unknown-key rejection and override behaviour. A second
     * path that "just parses a string" is how the two diverge.
     */
    public static Config parse(
            String toml, String source, ConfigSchema schema, Map<String, String> env) {
        List<String> problems = new ArrayList<>();
        TomlParseResult parsed = Toml.parse(toml);

        parsed.errors().forEach(e ->
                problems.add("line " + e.position().line() + ": " + e.getMessage()));

        // Unknown keys first: if the file is misspelt, a "missing required key"
        // that follows is usually the same mistake seen from the other side, and
        // reporting both without connecting them sends the operator the wrong way.
        if (problems.isEmpty()) {
            for (String present : new TreeSet<>(parsed.dottedKeySet())) {
                if (!schema.contains(present)) {
                    problems.add("unknown key '" + present + "'" + suggest(present, schema));
                }
            }
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (ConfigKey key : schema.keys()) {
            String override = env.get(key.envName());
            boolean supplied = override != null || parsed.contains(key.path());

            Object value;
            if (override != null) {
                value = coerce(key, override, problems);
            } else {
                value = fromToml(key, parsed, problems);
            }

            if (value != null) {
                values.put(key.path(), value);
            } else if (key.required() && !supplied) {
                // Only when genuinely absent. A key that WAS supplied but failed
                // to parse has already been reported against its real fault;
                // adding "missing required key" on top tells the operator to add
                // something they can see in the file, and sends them looking in
                // the wrong place.
                problems.add("missing required key '" + key.path() + "' (" + key.description()
                        + "); set it in the file or as " + key.envName());
            }
        }

        if (!problems.isEmpty()) {
            throw new ConfigException(source, problems);
        }
        return new Config(source, schema, values);
    }

    private static Object fromToml(ConfigKey key, TomlParseResult parsed, List<String> problems) {
        if (!parsed.contains(key.path())) {
            return null;
        }
        Object raw = parsed.get(key.path());
        return switch (key.type()) {
            case STRING -> raw instanceof String s ? s : typeError(key, raw, problems);
            case INTEGER -> raw instanceof Long l ? l : typeError(key, raw, problems);
            case BOOLEAN -> raw instanceof Boolean b ? b : typeError(key, raw, problems);
        };
    }

    private static Object typeError(ConfigKey key, Object raw, List<String> problems) {
        problems.add("'" + key.path() + "' must be " + article(key.type()) + " "
                + key.type().name().toLowerCase(java.util.Locale.ROOT)
                + ", found " + describeType(raw));
        return null;
    }

    /**
     * Coerces an environment-variable value, which is always a string.
     *
     * <p>Strictly: {@code "yes"}, {@code "1"} and {@code "TRUE"} are not
     * booleans. {@link Boolean#parseBoolean} treats every non-"true" string as
     * false, which would turn a typo into a silently disabled feature — the
     * exact class of bug this loader exists to prevent.
     */
    private static Object coerce(ConfigKey key, String raw, List<String> problems) {
        switch (key.type()) {
            case STRING:
                return raw;
            case INTEGER:
                try {
                    return Long.valueOf(raw.strip());
                } catch (NumberFormatException e) {
                    problems.add(key.envName() + "='" + raw + "' is not an integer");
                    return null;
                }
            case BOOLEAN:
                String v = raw.strip().toLowerCase(java.util.Locale.ROOT);
                if (v.equals("true")) {
                    return Boolean.TRUE;
                }
                if (v.equals("false")) {
                    return Boolean.FALSE;
                }
                problems.add(key.envName() + "='" + raw
                        + "' is not a boolean; write exactly true or false");
                return null;
            default:
                throw new IllegalStateException("unhandled type: " + key.type());
        }
    }

    /**
     * Names the closest declared key, when there is an obviously close one.
     *
     * <p>Edit distance 2, because that covers a transposition or a doubled
     * character — the mistakes actually made — without confidently suggesting
     * an unrelated key, which is worse than suggesting nothing.
     */
    private static String suggest(String unknown, ConfigSchema schema) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : schema.paths()) {
            int d = editDistance(unknown, candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best == null ? "" : "; did you mean '" + best + "'?";
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String article(ConfigKey.Type type) {
        return type == ConfigKey.Type.INTEGER ? "an" : "a";
    }

    private static String describeType(Object raw) {
        if (raw instanceof String) {
            return "a string";
        }
        if (raw instanceof Long) {
            return "an integer";
        }
        if (raw instanceof Boolean) {
            return "a boolean";
        }
        if (raw instanceof Double) {
            return "a float";
        }
        return raw == null ? "nothing" : raw.getClass().getSimpleName();
    }
}
