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

package dev.soulbind.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One recorded event.
 *
 * <p>A record, so an entry cannot be mutated after construction. Combined with
 * a repository that offers no update, the immutability is structural rather
 * than a convention someone has to remember.
 *
 * @param sequence monotonic, assigned by storage; 0 means "not yet appended"
 * @param at when it happened
 * @param actor who did it -- a connector id, or an admin credential id
 * @param action what they did
 * @param subjectId affected subject, if any
 * @param identityRef affected identity, if any, as "kind:id"
 * @param gate affected gate, if any
 * @param detail free-form structured context
 */
public record AuditEntry(
        long sequence,
        Instant at,
        String actor,
        String action,
        String subjectId,
        String identityRef,
        String gate,
        Map<String, Object> detail) {

    public AuditEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        // Defensive copy: a caller retaining a reference to a mutable map could
        // otherwise change what an appended entry says after the fact, which
        // defeats the whole point of an append-only log.
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    /** A new entry, not yet appended. Storage assigns the sequence. */
    public static AuditEntry of(
            Instant at, String actor, String action, Map<String, Object> detail) {
        return new AuditEntry(0L, at, actor, action, null, null, null, detail);
    }

    /** This entry with a storage-assigned sequence. */
    public AuditEntry withSequence(long seq) {
        return new AuditEntry(seq, at, actor, action, subjectId, identityRef, gate, detail);
    }
}
