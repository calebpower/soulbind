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

import dev.soulbind.core.identity.Identity;
import dev.soulbind.core.storage.IdentityRepository;
import dev.soulbind.core.storage.PolicyRepository;
import dev.soulbind.policy.Decision;
import dev.soulbind.policy.Effect;
import dev.soulbind.policy.PolicyEngine;
import dev.soulbind.policy.SubjectSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which gates a platform account satisfies, and the snapshot that decides it.
 *
 * <p>ONE implementation, used by both {@code decide} and by the code that emits
 * {@code subject.requirements-met}. That is the whole reason this class exists
 * rather than the logic sitting in the dispatcher where it started: if the two
 * ever disagreed, an effector would grant a role that {@code decide} refuses —
 * a person holding a role that does not admit them, or admitted without the
 * role that is supposed to mark it. Nothing in the system would report that as
 * wrong, because each half would be behaving correctly on its own.
 */
public final class GateEvaluator {

    private final IdentityRepository identities;

    private final PolicyRepository policy;

    private final Clock clock;

    public GateEvaluator(IdentityRepository identities, PolicyRepository policy, Clock clock) {
        this.identities = identities;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * The snapshot a decision for this platform account is made against.
     *
     * @param platformKind the platform
     * @param platformId the account on it
     * @return a snapshot, unlinked if the account belongs to no subject
     */
    public SubjectSnapshot snapshotFor(String platformKind, String platformId) {
        String ref = platformKind + ":" + platformId;
        var subject = identities.subjectOf(platformKind, platformId);
        if (subject.isEmpty()) {
            return SubjectSnapshot.unlinked(ref, clock.instant());
        }

        List<Identity> graph = identities.identitiesOf(subject.get().id());
        Set<String> verified = new TreeSet<>();
        Instant firstSeen = clock.instant();
        for (Identity identity : graph) {
            if (identity.isVerified()) {
                verified.add(identity.platformKind());
            }
            if (identity.createdAt().isBefore(firstSeen)) {
                firstSeen = identity.createdAt();
            }
        }
        // firstSeen from the graph, not from a caller: grace computed from a
        // connector-supplied time is grace anybody can extend.
        return new SubjectSnapshot(subject.get().id(), ref, verified, graph.size(), firstSeen);
    }

    /**
     * Every gate this account durably qualifies for, right now.
     *
     * <p>"Durably" is doing work, and the three exclusions are deliberate:
     *
     * <ul>
     *   <li><b>A gate with no rule</b> admits everybody, and emitting
     *       {@code requirements-met} for it would hand a standing role to every
     *       subject the moment any connector so much as asked about the gate —
     *       gates are recorded on first mention, not on configuration.
     *   <li><b>Grace</b> is an explicit temporary reprieve, and nothing in this
     *       system re-evaluates on a timer, so a role granted for grace would
     *       never be taken back when it lapsed. Not granting is the smaller
     *       wrong.
     *   <li><b>Anything that denies</b>, obviously.
     * </ul>
     *
     * <p>What remains is {@code requirements-met} and an operator's explicit
     * allow-{@code override} — two states that change only when something else
     * emits an event, which is exactly what an effector can track.
     *
     * @param platformKind the platform
     * @param platformId the account on it
     * @return the gate names, in a stable order
     */
    public Set<String> satisfiedGates(String platformKind, String platformId) {
        SubjectSnapshot snapshot = snapshotFor(platformKind, platformId);
        Set<String> satisfied = new LinkedHashSet<>();

        for (String gate : policy.gates()) {
            Optional<dev.soulbind.policy.Rule> rule = policy.rule(gate);
            if (rule.isEmpty()) {
                continue;
            }
            Decision decision = PolicyEngine.decide(
                    snapshot, rule.get(), policy.overridesFor(gate), clock.instant());
            if (decision.effect() == Effect.ALLOW
                    && (decision.reason() == Decision.Reason.REQUIREMENTS_MET
                            || decision.reason() == Decision.Reason.OVERRIDE)) {
                satisfied.add(gate);
            }
        }
        return satisfied;
    }
}
