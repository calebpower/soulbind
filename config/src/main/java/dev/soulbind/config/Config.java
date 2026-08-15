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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A loaded configuration: resolved values, addressed by the keys that declared
 * them.
 *
 * <p>Accessors take a {@link ConfigKey} rather than a string. A caller cannot
 * therefore read a key the schema does not declare, which is the same rule the
 * loader enforces on the file — applied to the code as well, so the two cannot
 * disagree about what exists.
 */
public final class Config {

    private final String source;
    private final ConfigSchema schema;
    private final Map<String, Object> values;

    Config(String source, ConfigSchema schema, Map<String, Object> values) {
        this.source = source;
        this.schema = schema;
        this.values = Map.copyOf(values);
    }

    /** Where this came from, for error messages. */
    public String source() {
        return source;
    }

    public ConfigSchema schema() {
        return schema;
    }

    public String getString(ConfigKey key) {
        return get(key, String.class, ConfigKey.Type.STRING);
    }

    public int getInt(ConfigKey key) {
        return get(key, Long.class, ConfigKey.Type.INTEGER).intValue();
    }

    public boolean getBoolean(ConfigKey key) {
        return get(key, Boolean.class, ConfigKey.Type.BOOLEAN);
    }

    public Optional<String> findString(ConfigKey key) {
        return find(key, String.class, ConfigKey.Type.STRING);
    }

    public Optional<Integer> findInt(ConfigKey key) {
        return find(key, Long.class, ConfigKey.Type.INTEGER).map(Long::intValue);
    }

    public Optional<Boolean> findBoolean(ConfigKey key) {
        return find(key, Boolean.class, ConfigKey.Type.BOOLEAN);
    }

    private <T> T get(ConfigKey key, Class<T> as, ConfigKey.Type expected) {
        return find(key, as, expected).orElseThrow(() -> new IllegalStateException(
                "no value for '" + key.path() + "'. It is declared optional, so read it with "
                        + "find" + capitalise(expected) + "() and decide what absence means "
                        + "rather than being handed a default nobody chose."));
    }

    private <T> Optional<T> find(ConfigKey key, Class<T> as, ConfigKey.Type expected) {
        requireDeclared(key);
        if (key.type() != expected) {
            throw new IllegalArgumentException(
                    "'" + key.path() + "' is declared " + key.type() + ", read as " + expected);
        }
        return Optional.ofNullable(values.get(key.path())).map(as::cast);
    }

    private void requireDeclared(ConfigKey key) {
        ConfigKey declared = schema.get(key.path());
        if (declared == null) {
            throw new IllegalArgumentException(
                    "'" + key.path() + "' is not in this configuration's schema");
        }
        if (!declared.equals(key)) {
            // Same path, different declaration: two components disagree about
            // what the key means, and whichever loaded first would win silently.
            throw new IllegalArgumentException(
                    "'" + key.path() + "' is declared differently here than by the schema this "
                            + "configuration was loaded against");
        }
    }

    private static String capitalise(ConfigKey.Type type) {
        return switch (type) {
            case STRING -> "String";
            case INTEGER -> "Int";
            case BOOLEAN -> "Boolean";
        };
    }

    /**
     * Every resolved value, with secrets redacted.
     *
     * <p>The redaction is why this exists: {@code soulbind doctor} and start-up
     * logging both want to show an operator what was loaded, and the natural
     * implementation — printing the map — writes credentials to a log file
     * somebody will later paste into an issue.
     */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ConfigKey key : schema.keys()) {
            Object value = values.get(key.path());
            if (value == null) {
                out.put(key.path(), "(unset)");
            } else if (key.secret()) {
                out.put(key.path(), "(redacted)");
            } else {
                out.put(key.path(), String.valueOf(value));
            }
        }
        return out;
    }

    @Override
    public String toString() {
        // Routed through describe() on purpose. A toString that dumped `values`
        // would leak a credential the first time this object appeared in a log
        // line or a debugger transcript.
        return "Config[" + source + "]" + describe();
    }
}
