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
package dev.soulbind.protocol;

/**
 * An operator's override on the wire.
 *
 * <p>Exactly one of {@code subjectId} and {@code identityRef} is set. Naming
 * both makes it ambiguous which was followed; naming neither makes it apply to
 * everybody.
 *
 * @param reason mandatory. An override nobody can review will outlive whoever
 *     added it.
 * @param expiresAtEpochSeconds null for permanent — spellable, because some
 *     are, but it has to be chosen
 */
public record OverrideView(
        String id,
        String gate,
        String subjectId,
        String identityRef,
        String effect,
        String reason,
        Long expiresAtEpochSeconds) {}
