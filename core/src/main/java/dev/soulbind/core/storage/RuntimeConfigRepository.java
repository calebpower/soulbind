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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Operational knobs an operator may change without a restart.
 *
 * <p><b>Deliberately not policy.</b> Rules and overrides live in their own
 * tables with their own audit trail; this holds things like code TTL and
 * decision-log verbosity. Keeping them apart means "who changed the policy" and
 * "who turned the logging down" are different questions with different answers.
 */
public interface RuntimeConfigRepository {

    Optional<String> get(String key);

    /** Every key and value. */
    Map<String, String> all();

    void set(String key, String value, Instant at, String updatedVia);

    /** Removes a key, so it falls back to its compiled default. */
    boolean clear(String key);
}
