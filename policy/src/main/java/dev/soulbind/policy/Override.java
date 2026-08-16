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

package dev.soulbind.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * An operator's decision about one subject or one raw identity, beating the
 * rule.
 *
 * <p>Overrides exist because policy is never quite right for everybody, and the
 * alternative to an override is an operator editing the rule for everybody —
 * which is how a gate ends up permanently weakened for one person's benefit.
 *
 * @param subjectId the subject this applies to, or null if it targets a raw
 *     identity instead. Exactly one of the two is set.
 * @param identityRef {@code kind:id}, for a subject that does not exist yet.
 *     An operator often needs to admit somebody <em>before</em> they have
 *     linked anything, and an override that could only name a subject would be
 *     useless in exactly that case.
 * @param reason mandatory. An override with no reason is one nobody can review,
 *     and it will outlive whoever added it.
 * @param expiresAt when it stops applying, or null for permanent. Permanent is
 *     spellable, because some are — but it has to be chosen.
 */
public record Override(
        String gateName,
        String subjectId,
        String identityRef,
        Effect effect,
        String reason,
        Instant expiresAt) {

    public Override {
        Objects.requireNonNull(gateName, "gateName");
        Objects.requireNonNull(effect, "effect");
        if ((subjectId == null) == (identityRef == null)) {
            throw new IllegalArgumentException(
                    "an override names exactly one of a subject or an identity; naming both "
                            + "makes it ambiguous which one it followed, and naming neither "
                            + "makes it apply to everybody");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "an override needs a reason. One without is an override nobody can review, "
                            + "and it will outlive whoever added it.");
        }
    }

    /** Whether this override still applies at the given moment. */
    public boolean isActive(Instant at) {
        // Exclusive, matching link-code expiry: an override expiring exactly now
        // is still in force. Consistency between the two matters more than which
        // convention is chosen, because an operator who learns one expects the
        // other.
        return expiresAt == null || !at.isAfter(expiresAt);
    }

    /** Whether this override targets the given subject or identity. */
    public boolean matches(String candidateSubjectId, String candidateIdentityRef) {
        if (subjectId != null) {
            return subjectId.equals(candidateSubjectId);
        }
        return identityRef.equals(candidateIdentityRef);
    }
}
