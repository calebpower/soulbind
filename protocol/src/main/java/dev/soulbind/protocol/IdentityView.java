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

import java.util.Map;

/**
 * One identity as it appears on the wire.
 *
 * @param flags connector-supplied traits, stored and returned but never
 *     branched on by core. A connector that needs to know something about its
 *     own platform's accounts puts it here and reads it back; core carries it
 *     without understanding it.
 * @param verifiedAtEpochSeconds when proof was established, or null if it has
 *     not been. Distinct from existing: an identity can be bound without being
 *     proven, and policy is entitled to care about the difference.
 */
public record IdentityView(
        String platformKind,
        String platformId,
        String display,
        Map<String, Object> flags,
        String proofMethod,
        Long verifiedAtEpochSeconds,
        long createdAtEpochSeconds) {

    public IdentityView {
        flags = flags == null ? Map.of() : Map.copyOf(flags);
    }

    public String ref() {
        return platformKind + ":" + platformId;
    }
}
