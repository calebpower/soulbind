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

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One configuration key: its dotted path, its type, and whether it is required.
 *
 * <p>A key is declared once, here, and that declaration is what makes both
 * strict unknown-key rejection and environment-variable overrides possible. A
 * loader that accepts whatever it finds cannot tell a typo from a setting.
 */
public record ConfigKey(
        String path,
        Type type,
        boolean required,
        boolean secret,
        String description,
        ConfigSchema elements) {

    public enum Type {
        STRING,
        INTEGER,
        BOOLEAN,

        /**
         * An array of tables, each validated against {@link #elements()}.
         *
         * <p>Exists because some settings are genuinely a list of things with
         * fields — a role binding is a gate, a role and a direction — and the
         * alternative was a delimited string parsed by hand. A mini-syntax
         * inside a string is a second configuration language with no schema, no
         * unknown-key rejection and no "did you mean", inside the one module
         * whose whole job is that configuration means what it says.
         */
        TABLE_ARRAY
    }

    /**
     * The permitted shape of a key path.
     *
     * <p>Lowercase alphanumeric segments separated by dots — <b>no underscores
     * and no hyphens</b>. That restriction is what makes the mapping to an
     * environment variable name injective: only dots become underscores, so
     * {@code a.b.c} is the only key that can produce {@code SOULBIND_A_B_C}.
     *
     * <p>Allowing underscores would make {@code a.b_c} and {@code a_b.c} collide
     * on one variable, and an operator setting a secret would silently
     * configure the wrong thing. Refusing the character is cheaper than
     * detecting the collision.
     */
    private static final Pattern VALID_PATH =
            Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*");

    public ConfigKey {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        if (!VALID_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "invalid config key '" + path + "': keys are lowercase alphanumeric "
                            + "segments separated by dots, with no underscores or hyphens, so "
                            + "that the mapping to an environment variable name is unambiguous");
        }
        if ((type == Type.TABLE_ARRAY) != (elements != null)) {
            throw new IllegalArgumentException(
                    "config key '" + path + "': an element schema is exactly what makes a "
                            + "TABLE_ARRAY checkable, and means nothing on any other type");
        }
        if (type == Type.TABLE_ARRAY && secret) {
            // A secret is redacted as one value. An array of tables is redacted
            // element by element, by the nested schema, which is the only place
            // that knows which of its fields are sensitive.
            throw new IllegalArgumentException(
                    "config key '" + path + "': mark the sensitive element field secret, "
                            + "not the array containing it");
        }
        if (elements != null) {
            for (ConfigKey element : elements.keys()) {
                if (element.type() == Type.TABLE_ARRAY) {
                    // Narrowed to one level, and the reason covers exactly that:
                    // nothing needs two, and an untested path is not a feature.
                    // The loader's recursion would carry it; its error messages
                    // and its tests have not been written for it.
                    throw new IllegalArgumentException(
                            "config key '" + path + "': element '" + element.path()
                                    + "' is itself a TABLE_ARRAY, and nesting is not supported");
                }
            }
        }
        if (description.isBlank()) {
            // A key nobody described is a key nobody can be expected to set
            // correctly, and `soulbind doctor` has nothing to print beside it.
            throw new IllegalArgumentException("config key '" + path + "' has no description");
        }
    }

    /** A required key of the given type. */
    public static ConfigKey required(String path, Type type, String description) {
        return new ConfigKey(path, type, true, false, description, null);
    }

    /** An optional key of the given type. */
    public static ConfigKey optional(String path, Type type, String description) {
        return new ConfigKey(path, type, false, false, description, null);
    }

    /**
     * A secret. Redacted everywhere the configuration is printed, and normally
     * supplied through the environment rather than written into a file.
     */
    public static ConfigKey secret(String path, boolean required, String description) {
        return new ConfigKey(path, Type.STRING, required, true, description, null);
    }

    /**
     * An array of tables, each element validated against its own schema.
     *
     * <p>Optional, and absent means an empty list rather than an error: a
     * connector with no bindings configured is a connector that changes nothing,
     * which is a reasonable posture and not a misconfiguration.
     *
     * <p>Both TOML spellings work, because tomlj resolves them to the same
     * value — the array-of-tables header and the inline form:
     *
     * <pre>
     *   [[effector.roles]]              roles = [ { gate = "g", role = "R" } ]
     *   gate = "g"
     *   role = "R"
     * </pre>
     */
    public static ConfigKey tables(String path, ConfigSchema elements, String description) {
        return new ConfigKey(path, Type.TABLE_ARRAY, false, false, description,
                java.util.Objects.requireNonNull(elements, "elements"));
    }

    /**
     * The environment variable that overrides this key.
     *
     * <p>{@code storage.password} becomes {@code SOULBIND_STORAGE_PASSWORD}.
     */
    public String envName() {
        return "SOULBIND_" + path.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}
