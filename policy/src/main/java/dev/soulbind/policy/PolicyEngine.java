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
import java.util.Optional;

/**
 * The evaluator: a pure function of {@code (snapshot, rule, overrides, now)}.
 *
 * <p><b>No I/O, no clock of its own, no logging.</b> Every input is an argument
 * and the output depends on nothing else, which is what lets the Tier 4 matrix
 * be exhaustive rather than representative — every row calls this directly,
 * with no HTTP and no database.
 *
 * <p>The order of precedence is fixed and each step exists for a reason:
 *
 * <ol>
 *   <li><b>Overrides beat rules, and deny beats allow.</b> An operator saying
 *       "not this person" must not be undone by a rule they satisfy, and when
 *       two overrides disagree the restrictive one wins — because the cost of
 *       wrongly denying is a complaint and the cost of wrongly allowing is the
 *       thing the gate existed to prevent.
 *   <li><b>No rule means allow.</b> A gate nobody configured is a gate nobody
 *       asked for. Denying by default here would mean every new gate silently
 *       locks out everybody the moment a connector declares it.
 *   <li><b>Grace, then requirements.</b> Grace is a deliberate window before
 *       the gate closes, so it is checked before the requirements it postpones.
 * </ol>
 *
 * <p>Note the asymmetry in point 2: <em>this</em> layer allows when unconfigured
 * because there is nothing to enforce, while a <em>connector</em> that cannot
 * reach core denies, because there it is enforcement that has failed rather
 * than enforcement that is absent. Those look contradictory and are not.
 */
public final class PolicyEngine {

    /** How long a connector may cache a decision, unless configured otherwise. */
    public static final int DEFAULT_TTL_SECONDS = 60;

    private PolicyEngine() {
        throw new AssertionError("no instances");
    }

    public static Decision decide(
            SubjectSnapshot snapshot, Rule rule, List<PolicyOverride> overrides, Instant now) {
        return decide(snapshot, rule, overrides, now, DEFAULT_TTL_SECONDS);
    }

    /**
     * Evaluates one gate for one identity.
     *
     * @param rule null when no rule governs the gate
     * @param overrides every override for this gate; expired ones are ignored
     *     here rather than filtered by the caller, so a caller that forgets
     *     cannot accidentally honour a lapsed one
     */
    public static Decision decide(
            SubjectSnapshot snapshot,
            Rule rule,
            List<PolicyOverride> overrides,
            Instant now,
            int ttlSeconds) {

        Optional<PolicyOverride> applicable = strongestOverride(snapshot, overrides, now);
        if (applicable.isPresent()) {
            PolicyOverride o = applicable.get();
            return new Decision(
                    o.effect(), Decision.Reason.OVERRIDE, o.reason(), ttlSeconds, List.of());
        }

        if (rule == null) {
            return new Decision(
                    Effect.ALLOW,
                    Decision.Reason.NO_RULE,
                    "no rule governs this gate",
                    ttlSeconds,
                    List.of());
        }

        if (!rule.requiresSomething()) {
            // ALLOW regardless of defaultEffect, because defaultEffect is what
            // happens when requirements are UNMET -- and a rule requiring
            // nothing has none to be unmet. A rule that asked for nothing and
            // denied anyway would be a gate closed to everybody with no way for
            // anybody to satisfy it, which is a configuration mistake rather
            // than a policy.
            return new Decision(
                    Effect.ALLOW,
                    Decision.Reason.REQUIREMENTS_MET,
                    "this gate requires nothing",
                    ttlSeconds,
                    List.of());
        }

        List<String> missing = snapshot.missingKinds(rule.requiredKinds());
        boolean linkedEnough = !rule.requireLinked() || snapshot.isLinked();
        boolean satisfied = missing.isEmpty() && linkedEnough;

        if (satisfied) {
            return new Decision(
                    Effect.ALLOW,
                    Decision.Reason.REQUIREMENTS_MET,
                    "requirements satisfied",
                    ttlSeconds,
                    List.of());
        }

        // Grace is checked AFTER establishing that requirements are unmet: a
        // subject who already satisfies the rule should be allowed for that
        // reason, not for a grace period that happens to still be running. The
        // difference matters to whoever reads the decision log.
        if (withinGrace(snapshot, rule, now)) {
            return new Decision(
                    Effect.ALLOW,
                    Decision.Reason.GRACE,
                    "within the grace period for this gate",
                    graceTtl(snapshot, rule, now, ttlSeconds),
                    missing);
        }

        Decision.Reason reason = !linkedEnough && missing.isEmpty()
                ? Decision.Reason.NOT_LINKED
                : Decision.Reason.MISSING_KINDS;

        return new Decision(rule.defaultEffect(), reason, describe(missing, linkedEnough),
                ttlSeconds, missing);
    }

    /**
     * The override that applies, with deny winning over allow.
     *
     * <p>A subject-targeted and an identity-targeted override can both match:
     * an operator admitted somebody by identity before they linked, and later
     * banned the subject. The ban wins, which is the only safe reading.
     */
    private static Optional<PolicyOverride> strongestOverride(
            SubjectSnapshot snapshot, List<PolicyOverride> overrides, Instant now) {

        if (overrides == null || overrides.isEmpty()) {
            return Optional.empty();
        }
        Optional<PolicyOverride> allow = Optional.empty();
        for (PolicyOverride o : overrides) {
            if (!o.isActive(now)) {
                continue;
            }
            if (!o.matches(snapshot.subjectId(), snapshot.askingIdentityRef())) {
                continue;
            }
            if (o.effect() == Effect.DENY) {
                return Optional.of(o); // deny beats everything; stop looking
            }
            if (allow.isEmpty()) {
                allow = Optional.of(o);
            }
        }
        return allow;
    }

    private static boolean withinGrace(SubjectSnapshot snapshot, Rule rule, Instant now) {
        if (rule.graceSeconds() <= 0 || snapshot.firstSeenAt() == null) {
            return false;
        }
        Instant closes = snapshot.firstSeenAt().plusSeconds(rule.graceSeconds());
        // Exclusive, like every other deadline here: a gate closing exactly now
        // is still open. One convention, so an operator who learns it once knows
        // it everywhere.
        return !now.isAfter(closes);
    }

    /**
     * A grace decision must not be cached past the moment grace ends.
     *
     * <p>Otherwise a connector caches "allow, because grace" for sixty seconds,
     * grace lapses ten seconds in, and the gate stays open for the remaining
     * fifty. The gate would then be advisory rather than enforced, and only
     * intermittently — which is worse than not having it, because somebody
     * would have tested it and seen it work.
     */
    private static int graceTtl(
            SubjectSnapshot snapshot, Rule rule, Instant now, int ttlSeconds) {
        long remaining = snapshot.firstSeenAt().plusSeconds(rule.graceSeconds())
                .getEpochSecond() - now.getEpochSecond();
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(ttlSeconds, remaining);
    }

    private static String describe(List<String> missing, boolean linkedEnough) {
        if (!linkedEnough && missing.isEmpty()) {
            return "this account is not linked to any other";
        }
        if (!linkedEnough) {
            return "this account is not linked, and is missing: " + String.join(", ", missing);
        }
        return "missing verified: " + String.join(", ", missing);
    }
}
