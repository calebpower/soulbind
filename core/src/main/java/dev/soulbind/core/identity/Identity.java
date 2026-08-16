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

package dev.soulbind.core.identity;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One platform account, belonging to at most one subject.
 *
 * <p>{@code (platformKind, platformId)} is unique across the entire table, not
 * per subject: a platform account belongs to one person, and letting two
 * subjects claim the same one is how two people come to share an entitlement
 * nobody granted twice.
 *
 * @param platformKind learned at runtime from connector registration. Core has
 *     no list of these and must not acquire one — a guard enforces it.
 * @param platformId the account's identifier as the connector presents it. Core
 *     does not parse it, does not validate its shape, and does not know what
 *     any particular platform's identifiers look like.
 * @param flags connector-supplied traits. Stored and returned, never branched
 *     on: the moment core reads a flag to decide something, it has learned a
 *     platform's peculiarity and the seam is gone.
 * @param proofMethod how this identity was established, for audit and for
 *     policy that cares about strength of proof
 * @param verifiedAt when proof was established, or null if it has not been
 */
public record Identity(
        String id,
        String subjectId,
        String platformKind,
        String platformId,
        String display,
        Map<String, Object> flags,
        String proofMethod,
        Instant verifiedAt,
        Instant createdAt) {

    public Identity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(platformKind, "platformKind");
        Objects.requireNonNull(platformId, "platformId");
        Objects.requireNonNull(createdAt, "createdAt");
        flags = flags == null ? Map.of() : Map.copyOf(flags);
    }

    /** Whether proof has been established. Distinct from merely existing. */
    public boolean isVerified() {
        return verifiedAt != null;
    }

    /**
     * A flag's value, if the connector set one.
     *
     * <p>Exposed for connectors and for the admin surface to read back. Core's
     * own logic must not call this to decide anything — that is what the
     * platform-vocabulary guard is for, and this method is not an exception
     * to it.
     */
    public Optional<Object> flag(String name) {
        return Optional.ofNullable(flags.get(name));
    }

    /** {@code kind:id}, the form audit and refusals use to name an identity. */
    public String ref() {
        return platformKind + ":" + platformId;
    }
}
