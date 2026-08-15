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

package dev.soulbind.core.storage;

import java.util.Locale;
import java.util.Optional;

/**
 * Which relational backend is in use.
 *
 * <p>Two exist, and both are first-class: every storage test runs against both
 * by parameterisation. A test naming one backend is the exception that needs a
 * stated reason — the single-writer scenarios are the expected such case,
 * because there the backend's concurrency model is the thing under test.
 *
 * <p>This enum is the <em>only</em> place backend-conditional logic is
 * permitted to be visible outside the two implementations, and even here it is
 * a selector rather than a branch. A guard asserts that no SQL string and no
 * JDBC type escapes this package.
 */
public enum Backend {

    /**
     * Embedded, single-file, single-writer.
     *
     * <p>Chosen as a real deployment option rather than a test fixture: a small
     * community running one connector should not have to operate a database
     * server. The single-writer reality is handled inside the implementation —
     * WAL mode, a busy timeout, and a serialising executor — rather than left
     * for callers to discover under load.
     */
    SQLITE("sqlite"),

    /** Networked, multi-writer. */
    MARIADB("mariadb");

    private final String configName;

    Backend(String configName) {
        this.configName = configName;
    }

    /** The name that appears in configuration and in the environment override. */
    public String configName() {
        return configName;
    }

    /** Parses a configured name; empty rather than a default, so a typo is loud. */
    public static Optional<Backend> fromConfigName(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String needle = s.strip().toLowerCase(Locale.ROOT);
        for (Backend b : values()) {
            if (b.configName.equals(needle)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }
}
