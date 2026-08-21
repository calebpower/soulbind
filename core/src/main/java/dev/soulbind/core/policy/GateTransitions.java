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

package dev.soulbind.core.policy;

import dev.soulbind.core.events.EventEmitter;
import dev.soulbind.core.identity.Identity;
import dev.soulbind.core.storage.IdentityRepository;
import dev.soulbind.protocol.EventType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Telling effectors what a mutation made true, or stopped being true.
 *
 * <p><b>One definition, two callers.</b> This lived inside {@code
 * LinkingService}, which meant only the operations that service owns — redeem,
 * attest, unlink — ever told anybody. Setting an operator override changes what
 * {@link GateEvaluator#satisfiedGates} answers and emitted nothing at all, so a
 * subject admitted by hand never had a role or group applied. See DECISIONS
 * 10.26.
 *
 * <p>The shape is deliberately snapshot-then-diff rather than "emit what I think
 * I just did". A caller that reasons about its own change has to get the
 * reasoning right for every rule the policy engine implements, in every call
 * site; a caller that asks the evaluator before and after only has to name the
 * accounts it touched.
 *
 * <p><b>Per identity, not per subject.</b> An effector finds its target from the
 * event's {@code identityRef} and refuses a ref whose kind is not its own, so a
 * single event per subject would leave every platform but one with nothing it
 * could act on.
 */
public final class GateTransitions {

    private final EventEmitter events;
    private final IdentityRepository identities;
    private final GateEvaluator gates;

    public GateTransitions(
            EventEmitter events, IdentityRepository identities, GateEvaluator gates) {
        this.events = events;
        this.identities = identities;
        this.gates = gates;
    }

    /**
     * What each of these refs satisfies right now, to be diffed against after.
     *
     * <p>Call this <em>before</em> the mutation. Refs without a colon are
     * skipped rather than rejected: a caller assembling refs from a code record
     * and a request has no business failing a link because one of them was
     * malformed, and a ref that is not in the map simply reads as "satisfied
     * nothing", which is the safe direction.
     */
    public Map<String, Set<String>> before(Collection<String> refs) {
        Map<String, Set<String>> before = new LinkedHashMap<>();
        for (String ref : refs) {
            int colon = ref == null ? -1 : ref.indexOf(':');
            if (colon >= 0) {
                before.put(ref, gates.satisfiedGates(
                        ref.substring(0, colon), ref.substring(colon + 1)));
            }
        }
        return before;
    }

    /** Emits one event per gate that opened, and one per gate that closed. */
    public void emit(Map<String, Set<String>> before, Collection<String> refs) {
        for (String ref : refs) {
            int colon = ref == null ? -1 : ref.indexOf(':');
            if (colon < 0) {
                continue;
            }
            Set<String> was = before.getOrDefault(ref, Set.of());
            Set<String> now = gates.satisfiedGates(
                    ref.substring(0, colon), ref.substring(colon + 1));

            String subjectId = identities
                    .subjectOf(ref.substring(0, colon), ref.substring(colon + 1))
                    .map(s -> s.id())
                    .orElse(null);

            for (String gate : now) {
                if (!was.contains(gate)) {
                    events.emit(EventType.SUBJECT_REQUIREMENTS_MET, subjectId, ref, gate, Map.of());
                }
            }
            for (String gate : was) {
                if (!now.contains(gate)) {
                    events.emit(
                            EventType.SUBJECT_REQUIREMENTS_LOST, subjectId, ref, gate, Map.of());
                }
            }
        }
    }

    /** Every identity ref on a subject, for fan-out to each platform's effector. */
    public List<String> refsOf(String subjectId) {
        if (subjectId == null) {
            return List.of();
        }
        return identities.identitiesOf(subjectId).stream().map(Identity::ref).toList();
    }

    /**
     * The refs an override touches.
     *
     * <p>An override names <em>exactly one</em> of a subject or an identity —
     * {@code PolicyOverride}'s own constructor enforces it — so this is a choice
     * between fanning out across a subject's graph and naming one account. A
     * subject-scoped override changes the answer for every platform that subject
     * is on, and each of those platforms has its own effector.
     */
    public List<String> targetsOf(String subjectId, String identityRef) {
        if (identityRef != null && !identityRef.isBlank()) {
            return List.of(identityRef);
        }
        return refsOf(subjectId);
    }
}
