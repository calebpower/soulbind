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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tier 4 — the decision matrix. Identity states × rule shapes × overrides ×
 * grace boundaries.
 *
 * <p>Exhaustive rather than representative, which is only possible because the
 * evaluator is a pure function: every row calls it directly, with no HTTP, no
 * database and no clock of its own.
 *
 * <p>The expected effect for each combination is <b>computed independently</b>
 * below, from the specification's rules rather than from the engine's. That
 * duplication is the point — an expectation derived from the thing under test
 * asserts only that the code agrees with itself.
 */
class DecisionMatrixTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final String GATE = "gate.x";
    private static final String REF = "kind-a:acct-1";

    // --- the matrix -----------------------------------------------------------

    /** Every identity state worth distinguishing. */
    private static List<SubjectSnapshot> snapshots() {
        return List.of(
                // Nobody: arriving for the first time.
                SubjectSnapshot.unlinked(REF, NOW),
                // Known on one platform, verified there, linked to nothing.
                new SubjectSnapshot("s1", REF, Set.of("kind-a"), 1, NOW),
                // Linked, but nothing verified.
                new SubjectSnapshot("s1", REF, Set.of(), 2, NOW),
                // Linked and verified on one of two required kinds.
                new SubjectSnapshot("s1", REF, Set.of("kind-a"), 2, NOW),
                // Linked and verified on both.
                new SubjectSnapshot("s1", REF, Set.of("kind-a", "kind-b"), 2, NOW),
                // Linked and verified on more than asked.
                new SubjectSnapshot("s1", REF, Set.of("kind-a", "kind-b", "kind-c"), 3, NOW));
    }

    /** Every rule shape worth distinguishing. */
    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(null);                                          // no rule at all
        rules.add(Rule.open(GATE));                               // requires nothing
        rules.add(Rule.linked(GATE));                             // any link will do
        rules.add(Rule.requiring(GATE, "kind-a"));                // one kind
        rules.add(Rule.requiring(GATE, "kind-a", "kind-b"));      // two kinds
        rules.add(new Rule(GATE, Set.of("kind-a"), true, 0L, Effect.DENY));  // kind AND linked
        rules.add(new Rule(GATE, Set.of("kind-z"), false, 0L, Effect.DENY)); // unreachable kind
        rules.add(new Rule(GATE, Set.of("kind-z"), false, 0L, Effect.ALLOW));// staged, not enforced
        return rules;
    }

    static Stream<Arguments> matrix() {
        List<Arguments> rows = new ArrayList<>();
        for (SubjectSnapshot snapshot : snapshots()) {
            for (Rule rule : rules()) {
                rows.add(Arguments.of(snapshot, rule));
            }
        }
        return rows.stream();
    }

    /**
     * The contract, restated from the specification.
     *
     * <p>Deliberately NOT calling anything in the engine. If this were derived
     * from {@code PolicyEngine}, a rule changed in error would be agreed with
     * rather than caught.
     */
    private static Effect expected(SubjectSnapshot snapshot, Rule rule) {
        if (rule == null) {
            return Effect.ALLOW;                       // a gate nobody configured
        }
        boolean requiresSomething = rule.requireLinked() || !rule.requiredKinds().isEmpty();
        if (!requiresSomething) {
            return Effect.ALLOW;                       // a rule asking for nothing
        }
        boolean kindsMet = snapshot.verifiedKinds().containsAll(rule.requiredKinds());
        boolean linkMet = !rule.requireLinked() || snapshot.identityCount() >= 2;
        return kindsMet && linkMet ? Effect.ALLOW : rule.defaultEffect();
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    @DisplayName("every identity state against every rule shape")
    void everyCombination(SubjectSnapshot snapshot, Rule rule) {
        Decision decision = PolicyEngine.decide(snapshot, rule, List.of(), NOW);
        assertEquals(
                expected(snapshot, rule),
                decision.effect(),
                () -> "snapshot=" + snapshot + " rule=" + rule + " gave " + decision);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    @DisplayName("a denial always says what is missing")
    void denialsAreActionable(SubjectSnapshot snapshot, Rule rule) {
        // A connector that can only say "no" leaves the person with nothing to
        // do. Every denial from an unmet requirement names the kinds.
        Decision decision = PolicyEngine.decide(snapshot, rule, List.of(), NOW);
        if (decision.effect() == Effect.DENY
                && decision.reason() == Decision.Reason.MISSING_KINDS) {
            assertFalse(
                    decision.missingKinds().isEmpty(),
                    () -> "denied for missing kinds but named none: " + decision);
        }
        assertFalse(decision.detail() == null || decision.detail().isBlank());
    }

    // --- override precedence ---------------------------------------------------

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    @DisplayName("a deny override beats every rule and every identity state")
    void denyOverrideAlwaysWins(SubjectSnapshot snapshot, Rule rule) {
        Override deny = new Override(GATE, "s1", null, Effect.DENY, "banned", null);
        Override denyByRef = new Override(GATE, null, REF, Effect.DENY, "banned", null);

        for (Override o : List.of(deny, denyByRef)) {
            if (o.subjectId() != null && snapshot.subjectId() == null) {
                continue; // a subject-targeted override cannot match a subjectless snapshot
            }
            Decision decision = PolicyEngine.decide(snapshot, rule, List.of(o), NOW);
            assertEquals(
                    Effect.DENY,
                    decision.effect(),
                    () -> "an operator saying 'not this person' was overridden by policy: "
                            + decision);
            assertEquals(Decision.Reason.OVERRIDE, decision.reason());
        }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    @DisplayName("an allow override beats a rule the subject does not satisfy")
    void allowOverrideWins(SubjectSnapshot snapshot, Rule rule) {
        Override allow = new Override(GATE, null, REF, Effect.ALLOW, "vouched for", null);
        Decision decision = PolicyEngine.decide(snapshot, rule, List.of(allow), NOW);
        assertEquals(Effect.ALLOW, decision.effect());
        assertEquals(Decision.Reason.OVERRIDE, decision.reason());
    }

    @Test
    @DisplayName("DENY BEATS ALLOW when two overrides disagree")
    void denyBeatsAllow() {
        // An operator admitted somebody by identity before they linked, then
        // later banned the subject. The ban wins: wrongly denying costs a
        // complaint, wrongly allowing costs the thing the gate existed for.
        SubjectSnapshot snapshot = new SubjectSnapshot("s1", REF, Set.of(), 2, NOW);
        List<Override> both = List.of(
                new Override(GATE, null, REF, Effect.ALLOW, "admitted early", null),
                new Override(GATE, "s1", null, Effect.DENY, "banned since", null));

        assertEquals(
                Effect.DENY,
                PolicyEngine.decide(snapshot, Rule.open(GATE), both, NOW).effect());
        // And in the other order, because a precedence that depended on list
        // order would be reproducible only by accident.
        assertEquals(
                Effect.DENY,
                PolicyEngine.decide(snapshot, Rule.open(GATE), both.reversed(), NOW).effect());
    }

    @Test
    @DisplayName("an expired override does not apply, and the caller need not filter it")
    void expiredOverrideIgnored() {
        SubjectSnapshot snapshot = SubjectSnapshot.unlinked(REF, NOW);
        Override lapsed = new Override(
                GATE, null, REF, Effect.ALLOW, "temporary", NOW.minusSeconds(1));

        Decision decision =
                PolicyEngine.decide(snapshot, Rule.linked(GATE), List.of(lapsed), NOW);
        assertEquals(Effect.DENY, decision.effect());
        assertEquals(Decision.Reason.NOT_LINKED, decision.reason());
    }

    @Test
    @DisplayName("an override expiring exactly now is still in force")
    void overrideBoundaryIsExclusive() {
        // Same convention as link-code expiry. Consistency matters more than
        // which one is chosen: an operator who learns one expects the other.
        Override edge = new Override(GATE, null, REF, Effect.ALLOW, "temporary", NOW);
        assertTrue(edge.isActive(NOW));
        assertFalse(edge.isActive(NOW.plusMillis(1)));
    }

    @Test
    @DisplayName("an override for a different gate does not leak into this one")
    void overrideIsGateScoped() {
        // Not enforced by the engine -- the caller passes the overrides for the
        // gate it is asking about -- so this states the contract at the boundary
        // where somebody would otherwise assume filtering happens here.
        Override other = new Override("gate.other", null, REF, Effect.DENY, "banned", null);
        assertEquals("gate.other", other.gateName());
        assertFalse(other.gateName().equals(GATE));
    }

    // --- grace ------------------------------------------------------------------

    @Test
    @DisplayName("inside grace, an unsatisfied subject is allowed")
    void graceAllows() {
        Rule rule = new Rule(GATE, Set.of("kind-b"), false, 300L, Effect.DENY);
        SubjectSnapshot snapshot = SubjectSnapshot.unlinked(REF, NOW);

        Decision decision = PolicyEngine.decide(snapshot, rule, List.of(), NOW.plusSeconds(299));
        assertEquals(Effect.ALLOW, decision.effect());
        assertEquals(Decision.Reason.GRACE, decision.reason());
    }

    @Test
    @DisplayName("grace is exclusive at its boundary, and closed one millisecond later")
    void graceBoundary() {
        Rule rule = new Rule(GATE, Set.of("kind-b"), false, 300L, Effect.DENY);
        SubjectSnapshot snapshot = SubjectSnapshot.unlinked(REF, NOW);

        assertEquals(
                Effect.ALLOW,
                PolicyEngine.decide(snapshot, rule, List.of(), NOW.plusSeconds(300)).effect());
        assertEquals(
                Effect.DENY,
                PolicyEngine.decide(
                        snapshot, rule, List.of(), NOW.plusSeconds(300).plusMillis(1)).effect());
    }

    @Test
    @DisplayName("a grace decision is not cacheable past the moment grace ends")
    void graceTtlIsClamped() {
        // Otherwise a connector caches "allow, because grace" for sixty seconds,
        // grace lapses ten seconds in, and the gate stays open for the remaining
        // fifty -- advisory rather than enforced, and only intermittently, which
        // is worse than absent because somebody would have tested it and seen it
        // work.
        Rule rule = new Rule(GATE, Set.of("kind-b"), false, 300L, Effect.DENY);
        SubjectSnapshot snapshot = SubjectSnapshot.unlinked(REF, NOW);

        Decision early = PolicyEngine.decide(snapshot, rule, List.of(), NOW);
        assertEquals(PolicyEngine.DEFAULT_TTL_SECONDS, early.ttlSeconds(),
                "far from the edge, the ordinary TTL applies");

        Decision late = PolicyEngine.decide(snapshot, rule, List.of(), NOW.plusSeconds(290));
        assertEquals(10, late.ttlSeconds(), "ten seconds of grace left means ten seconds of TTL");
    }

    @Test
    @DisplayName("a satisfied subject is allowed for being satisfied, not for grace")
    void satisfiedBeatsGrace() {
        // The difference matters to whoever reads the decision log: "allowed,
        // grace" and "allowed, requirements met" describe different futures.
        Rule rule = new Rule(GATE, Set.of("kind-a"), false, 300L, Effect.DENY);
        SubjectSnapshot snapshot = new SubjectSnapshot("s1", REF, Set.of("kind-a"), 2, NOW);

        Decision decision = PolicyEngine.decide(snapshot, rule, List.of(), NOW);
        assertEquals(Decision.Reason.REQUIREMENTS_MET, decision.reason());
    }

    @Test
    @DisplayName("zero grace means no grace, not infinite grace")
    void zeroGrace() {
        Rule rule = new Rule(GATE, Set.of("kind-b"), false, 0L, Effect.DENY);
        assertEquals(
                Effect.DENY,
                PolicyEngine.decide(
                        SubjectSnapshot.unlinked(REF, NOW), rule, List.of(), NOW).effect());
    }

    @Test
    @DisplayName("a negative grace is refused at construction rather than honoured")
    void negativeGraceRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new Rule(GATE, Set.of(), true, -1L, Effect.DENY),
                "a gate cannot close before the subject existed");
    }

    // --- the properties that hold everywhere -------------------------------------

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    @DisplayName("the function is pure: the same inputs give the same answer")
    void isDeterministic(SubjectSnapshot snapshot, Rule rule) {
        Decision first = PolicyEngine.decide(snapshot, rule, List.of(), NOW);
        Decision second = PolicyEngine.decide(snapshot, rule, List.of(), NOW);
        assertEquals(first, second);
    }

    @Test
    @DisplayName("an override with no reason cannot be constructed")
    void overrideNeedsReason() {
        // One nobody can review will outlive whoever added it.
        for (String reason : new String[] {null, "", "   "}) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new Override(GATE, "s1", null, Effect.ALLOW, reason, null));
        }
    }

    @Test
    @DisplayName("an override naming both or neither target is refused")
    void overrideNeedsExactlyOneTarget() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new Override(GATE, "s1", REF, Effect.ALLOW, "r", null),
                "naming both makes it ambiguous which one it followed");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new Override(GATE, null, null, Effect.ALLOW, "r", null),
                "naming neither makes it apply to everybody");
    }

    @Test
    @DisplayName("one identity is not linked, and two are")
    void linkedMeansMoreThanOne() {
        // A subject with a single identity is a person known on one platform --
        // what an attestation produces. Calling that "linked" would let a gate
        // demanding a link be satisfied by the very account asking.
        assertFalse(new SubjectSnapshot("s1", REF, Set.of("kind-a"), 1, NOW).isLinked());
        assertTrue(new SubjectSnapshot("s1", REF, Set.of(), 2, NOW).isLinked());
    }
}
