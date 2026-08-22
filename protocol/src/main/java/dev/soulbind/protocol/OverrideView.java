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
// NO `id` FIELD, and its absence is deliberate rather than an omission.
//
// This record carried one until Phase 10 and `CoreHandlers.toView` passed null
// for it on every response, so `override.get` never returned one -- which is
// why removal by id was unreachable over the protocol even though the
// repository could do it. `override.remove` takes the same TARGET shape as
// `override.set` instead, for the reason `connector.rotate` takes a name: an
// operator knows the gate and who they admitted, not a uuid this system never
// showed them.
//
// A field that is never populated is a promise the protocol document makes and
// nothing keeps. Removed rather than filled, because nothing needs it.
// DECISIONS 10.34.
public record OverrideView(
        String gate,
        String subjectId,
        String identityRef,
        String effect,
        String reason,
        Long expiresAtEpochSeconds) {}
