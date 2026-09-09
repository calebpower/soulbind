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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.ConfigKey.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Schema equality, which exists for one reason and would be invisible without
 * this.
 *
 * <p>{@link ConfigKey} is a record that may hold a schema as a component, so
 * record equality reaches into one. Without a definition here it falls back to
 * identity, and {@code Config}'s "declared differently here than by the schema
 * this was loaded against" check fires on two schemas that are the same in every
 * way that matters — a failure that looks like a configuration bug and is not.
 */
class ConfigSchemaTest {

    private static final ConfigKey GATE = ConfigKey.required("gate", Type.STRING, "the gate");
    private static final ConfigKey ROLE = ConfigKey.required("role", Type.STRING, "the role");

    @Test
    @DisplayName("two schemas declaring the same keys are equal, and hash alike")
    void sameKeysAreEqual() {
        ConfigSchema one = ConfigSchema.of(GATE, ROLE);
        ConfigSchema two = ConfigSchema.of(GATE, ROLE);

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertEquals(one, one);
    }

    @Test
    @DisplayName("declaration order does not change equality")
    void orderDoesNotMatter() {
        assertEquals(ConfigSchema.of(GATE, ROLE), ConfigSchema.of(ROLE, GATE));
    }

    @Test
    @DisplayName("different keys are different schemas")
    void differentKeysDiffer() {
        assertNotEquals(ConfigSchema.of(GATE, ROLE), ConfigSchema.of(GATE));
        assertNotEquals(ConfigSchema.of(GATE),
                ConfigSchema.of(ConfigKey.optional("gate", Type.STRING, "the gate")),
                "a required key and an optional one of the same name are not the same schema");
        assertNotEquals(ConfigSchema.of(GATE), "not a schema");
        assertNotEquals(ConfigSchema.of(GATE), null);
    }

    @Test
    @DisplayName("a table-array key carrying an equal element schema is an equal key")
    void keysHoldingSchemasCompareByValue() {
        // The case the definition exists for: this is what Config's
        // "declared differently" check compares.
        ConfigKey a = ConfigKey.tables("effector.roles", ConfigSchema.of(GATE, ROLE), "bindings");
        ConfigKey b = ConfigKey.tables("effector.roles", ConfigSchema.of(GATE, ROLE), "bindings");

        assertEquals(a, b);
        assertTrue(ConfigSchema.of(a).equals(ConfigSchema.of(b)));
    }
}
