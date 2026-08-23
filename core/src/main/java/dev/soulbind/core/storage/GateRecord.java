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

/**
 * A gate as it is recorded, rather than as it is governed.
 *
 * <p>Distinct from {@link dev.soulbind.policy.Rule}, which is the policy
 * applied AT a gate. This is the gate's own row: that it exists, who first
 * declared it, and what an operator wrote down about what it is for. A gate can
 * have one of these and no rule -- that is the normal state of a gate a
 * connector has asked about and nobody has configured.
 *
 * @param name the gate's name, e.g. {@code game.join}
 * @param registeredBy the connector that FIRST declared this gate. First, not
 *     most recent: overwriting it on every later declaration would turn "who
 *     introduced this" into "who asked last", which is a different fact and a
 *     less useful one.
 * @param description what the gate is for, in a sentence, or {@code null} when
 *     nobody has said. Written by an operator through {@code rule.set}; nothing
 *     derives it.
 * @param firstSeenAt when the gate was first declared
 */
public record GateRecord(
        String name, String registeredBy, String description, Instant firstSeenAt) {}
