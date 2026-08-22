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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The set of keys a component understands.
 *
 * <p>Declaring the schema up front is what lets the loader reject a key it does
 * not recognise. A configuration file with a misspelt key is the failure mode
 * that costs the most to diagnose: the setting appears to be there, the
 * software uses its default, and the symptom shows up somewhere unrelated.
 */
public final class ConfigSchema {

    private final Map<String, ConfigKey> byPath;

    private ConfigSchema(Map<String, ConfigKey> byPath) {
        this.byPath = byPath;
    }

    /**
     * Builds a schema, refusing anything ambiguous.
     *
     * <p>Two checks run here rather than at load time, because a broken schema
     * is a programming error and should fail the tests that construct it, not a
     * production start-up.
     */
    public static ConfigSchema of(Collection<ConfigKey> keys) {
        Map<String, ConfigKey> byPath = new LinkedHashMap<>();
        Map<String, String> byEnv = new TreeMap<>();
        List<String> problems = new ArrayList<>();

        for (ConfigKey key : keys) {
            ConfigKey previous = byPath.put(key.path(), key);
            if (previous != null) {
                problems.add("duplicate key '" + key.path() + "'");
            }
            String clash = byEnv.put(key.envName(), key.path());
            if (clash != null && !clash.equals(key.path())) {
                // Unreachable while ConfigKey's path rule holds, and asserted
                // anyway: this is the check that would catch a future relaxation
                // of that rule turning two keys into one environment variable.
                //
                // So the `!clash.equals(...)` mutant is EQUIVALENT by design,
                // not by oversight -- there is no input that reaches it with
                // the rule in force. Recorded here so a sweep skips it rather
                // than deleting a guard that exists for a future change.
                // DECISIONS 10.40.
                problems.add("keys '" + clash + "' and '" + key.path()
                        + "' both map to " + key.envName());
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid config schema: " + String.join("; ", problems));
        }
        return new ConfigSchema(byPath);
    }

    public static ConfigSchema of(ConfigKey... keys) {
        return of(List.of(keys));
    }

    /** Every declared key, in declaration order. */
    public Collection<ConfigKey> keys() {
        return byPath.values();
    }

    public Set<String> paths() {
        return byPath.keySet();
    }

    public ConfigKey get(String path) {
        return byPath.get(path);
    }

    public boolean contains(String path) {
        return byPath.containsKey(path);
    }

    /**
     * A schema containing every key of both, for a component that composes
     * another's configuration.
     */
    public ConfigSchema merge(ConfigSchema other) {
        List<ConfigKey> all = new ArrayList<>(byPath.values());
        all.addAll(other.byPath.values());
        return of(all);
    }
}
