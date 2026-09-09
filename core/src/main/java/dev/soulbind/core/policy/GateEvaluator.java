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
import dev.soulbind.core.storage.MeasureRecord;
import dev.soulbind.core.storage.MeasureRepository;
import dev.soulbind.policy.MeasureObservation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private final MeasureRepository measures;

    public GateEvaluator(
            IdentityRepository identities,
            PolicyRepository policy,
            MeasureRepository measures,
            Clock clock) {
        this.identities = identities;
        this.policy = policy;
        this.measures = measures;
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
            // An account belonging to no subject can still have been measured --
            // a reporter sees the game account before anybody links anything --
            // so the unlinked snapshot carries its own measures rather than none.
            return new SubjectSnapshot(
                    null, ref, Set.of(), 1, clock.instant(), strongestByName(List.of(ref)));
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
        List<String> refs = new ArrayList<>();
        for (Identity identity : graph) {
            refs.add(identity.platformKind() + ":" + identity.platformId());
        }
        return new SubjectSnapshot(subject.get().id(), ref, verified, graph.size(), firstSeen,
                strongestByName(refs));
    }

    /**
     * The strongest observation of each measure across a subject's identities.
     *
     * <p><b>Strongest, not summed</b>, and the whole record travels together
     * rather than a maximum value being paired with somebody else's window.
     *
     * <p>Summing would let one person's two accounts on the same platform add up
     * to an entitlement neither earned -- an alt farm, which is the shape the
     * unique (platform_kind, platform_id) constraint exists to prevent
     * elsewhere. It would also produce a meaningless number across platforms:
     * forum minutes and game minutes are not the same quantity, and their total
     * measures nothing. "The strongest evidence any platform has" is the only
     * reading that stays true whichever platforms happen to report.
     */
    private Map<String, MeasureObservation> strongestByName(List<String> refs) {
        Map<String, MeasureObservation> out = new LinkedHashMap<>();
        for (MeasureRecord record : measures.forRefs(refs, null)) {
            MeasureObservation candidate = new MeasureObservation(
                    record.value(), record.windowSeconds(), record.observedAt());
            MeasureObservation existing = out.get(record.name());
            // EQUIVALENT MUTANT at the boundary, recorded rather than
            // rediscovered: `>` and `>=` differ only when two identities report
            // the SAME value, and then they differ only in which equally-strong
            // observation's window and timestamp survive. Neither answer is more
            // correct, so asserting one would be pinning an arbitrary tie-break
            // and calling it a requirement. Same treatment as the reference-parsing
            // boundary a connector-side effector records, per DECISIONS 10.28.
            if (existing == null || candidate.value() > existing.value()) {
                out.put(record.name(), candidate);
            }
        }
        return out;
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
     *   <li><b>An override that expires.</b> Same reasoning as grace, and it
     *       was missed the first time: nothing re-evaluates on a timer, so a
     *       group granted for a one-hour override would still be there in
     *       March. A <em>permanent</em> override changes only when an operator
     *       changes it, and that now emits — which is what makes it safe to
     *       count and was not true when this list was first written.
     * </ul>
     *
     * <p>What remains is {@code requirements-met} and an operator's explicit
     * allow-{@code override} — two states that change only when something else
     * emits an event, which is exactly what an effector can track.
     *
     * <p><b>The sentence above needs one qualification since measures landed.</b>
     * "Nothing re-evaluates on a timer" is still true of core, and is still the
     * reason grace and expiring overrides are excluded. A measure requirement is
     * not an exception to it: a reported measure changes only when a connector
     * reports one, and reporting is a mutation that re-evaluates inside its own
     * request, so a measure-satisfied gate is as durable as a linked identity.
     * The gap is narrower and is recorded as a narrowing in {@code STATUS.md} —
     * an observation that goes STALE stops satisfying {@code decide} the instant
     * it lapses, but emits nothing, because nothing is watching the clock. A
     * role granted on a measure therefore outlives its evidence until the
     * reporter comes back and reports a low value.
     *
     * @param platformKind the platform
     * @param platformId the account on it
     * @return the gate names, in a stable order
     */
    private static boolean isPermanent(
            SubjectSnapshot snapshot,
            List<dev.soulbind.policy.PolicyOverride> overrides,
            Instant now) {

        // The engine's own notion of "strongest", never a second one: deny
        // beats allow, and a caller re-deriving that would get exactly the
        // rule that matters wrong.
        return PolicyEngine.strongestOverride(snapshot, overrides, now)
                .map(o -> o.expiresAt() == null)
                .orElse(false);
    }

    public Set<String> satisfiedGates(String platformKind, String platformId) {
        SubjectSnapshot snapshot = snapshotFor(platformKind, platformId);
        Set<String> satisfied = new LinkedHashSet<>();

        Instant now = clock.instant();
        for (String gate : policy.gates()) {
            Optional<dev.soulbind.policy.Rule> rule = policy.rule(gate);
            if (rule.isEmpty()) {
                continue;
            }
            List<dev.soulbind.policy.PolicyOverride> overrides = policy.overridesFor(gate);
            Decision decision = PolicyEngine.decide(snapshot, rule.get(), overrides, now);
            if (decision.effect() != Effect.ALLOW) {
                continue;
            }
            if (decision.reason() == Decision.Reason.REQUIREMENTS_MET) {
                satisfied.add(gate);
            } else if (decision.reason() == Decision.Reason.OVERRIDE
                    && isPermanent(snapshot, overrides, now)) {
                satisfied.add(gate);
            }
        }
        return satisfied;
    }
}
