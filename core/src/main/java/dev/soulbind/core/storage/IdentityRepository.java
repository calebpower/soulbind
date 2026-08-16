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

import dev.soulbind.core.identity.Identity;
import dev.soulbind.core.identity.Subject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The identity graph: subjects, and the platform accounts that belong to them. */
public interface IdentityRepository {

    /** Creates a subject with no identities yet. */
    Subject createSubject(Instant at);

    Optional<Subject> findSubject(String subjectId);

    /**
     * Binds a platform account to a subject.
     *
     * @throws Jdbc.StorageException if the account already belongs to someone.
     *     A platform account belongs to at most one person, and the uniqueness
     *     is a database constraint rather than a check here — a check would
     *     race, and the race is exactly the case that matters.
     */
    Identity bind(
            String subjectId,
            String platformKind,
            String platformId,
            String display,
            Map<String, Object> flags,
            String proofMethod,
            Instant verifiedAt,
            Instant at);

    Optional<Identity> findIdentity(String platformKind, String platformId);

    /** Every identity belonging to a subject, oldest first. */
    List<Identity> identitiesOf(String subjectId);

    /**
     * Removes an identity.
     *
     * <p><b>Hard with respect to policy, soft with respect to audit.</b> The row
     * is deleted, so a decision asked one transaction later sees it gone — an
     * unlink that left policy unchanged would not be an unlink. The audit rows
     * naming it remain forever, because what happened still happened.
     *
     * <p>Re-linking the same platform account later creates a NEW identity, with
     * a new id and a new creation time. History lives in audit, not in a
     * resurrected row that would silently carry its old verification date.
     *
     * @return true if an identity was removed
     */
    boolean unlink(String platformKind, String platformId);

    // THERE IS NO MERGE, deliberately. An operation folding two subjects into
    // one needs a rule for every conflicting field, and the first time it ran on
    // the wrong pair it would be unrecoverable: the identities would afterwards
    // be indistinguishable from ones legitimately linked. Unlink and re-link is
    // the supported path, and it leaves a trail.

    /**
     * Records proof for an existing identity.
     *
     * <p>Separate from {@link #bind} because proof can arrive later than the
     * binding, and often does: an account is linked by code and verified by a
     * stronger method afterwards. Overwriting the proof method on every bind
     * would erase that.
     *
     * @return true if an identity was updated
     */
    boolean markVerified(
            String platformKind, String platformId, String proofMethod, Instant verifiedAt);

    /** The subject owning a platform account, if it is linked. */
    Optional<Subject> subjectOf(String platformKind, String platformId);
}
