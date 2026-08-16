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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the evaluator is allowed to know about a subject.
 *
 * <p>Deliberately a <b>slice</b>, not the subject itself. Handing the evaluator
 * a live object would let it reach further than the inputs it declares, and the
 * decision would stop being reproducible from its arguments — which is the one
 * property the whole matrix depends on.
 *
 * @param subjectId null when the identity asking is not linked to anything.
 *     That is a normal state, not an error: somebody arriving for the first
 *     time has no subject, and the gate still has to answer.
 * @param askingIdentityRef {@code kind:id} of the identity making the request.
 *     Needed even when a subject exists, because an override may target the raw
 *     identity.
 * @param verifiedKinds platform kinds this subject has proven. Only verified
 *     ones — an identity bound without proof is present but has not
 *     demonstrated anything, and a gate that accepted it would accept a claim.
 * @param identityCount how many identities the subject holds, verified or not.
 *     "Linked" is about being joined to something, which is true before proof
 *     arrives.
 * @param firstSeenAt when this identity was first recorded, from audit rather
 *     than from the connector. A connector-supplied time is a time the caller
 *     controls, and grace computed from it is grace anybody can extend.
 */
public record SubjectSnapshot(
        String subjectId,
        String askingIdentityRef,
        Set<String> verifiedKinds,
        int identityCount,
        Instant firstSeenAt) {

    public SubjectSnapshot {
        Objects.requireNonNull(askingIdentityRef, "askingIdentityRef");
        verifiedKinds = verifiedKinds == null
                ? Set.of()
                : Set.copyOf(new TreeSet<>(verifiedKinds));
        if (identityCount < 0) {
            throw new IllegalArgumentException("identityCount must not be negative");
        }
    }

    /** Somebody arriving for the first time: no subject, nothing verified. */
    public static SubjectSnapshot unlinked(String identityRef, Instant firstSeenAt) {
        return new SubjectSnapshot(null, identityRef, Set.of(), 1, firstSeenAt);
    }

    /**
     * Whether the subject is joined to more than one platform account.
     *
     * <p>Two, not one. A subject with a single identity is a person known on one
     * platform — which is what an attestation produces — and calling that
     * "linked" would let a gate demanding a link be satisfied by the very
     * account asking.
     */
    public boolean isLinked() {
        return identityCount >= 2;
    }

    /** The kinds required by a rule that this subject has NOT verified. */
    public List<String> missingKinds(Set<String> required) {
        return required.stream().filter(k -> !verifiedKinds.contains(k)).sorted().toList();
    }
}
