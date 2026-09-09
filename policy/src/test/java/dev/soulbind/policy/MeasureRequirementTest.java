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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 — a rule that requires a measured quantity, at its boundaries.
 *
 * <p>Every case here is one comparison away from its neighbour, because a
 * threshold is exactly the kind of thing that is right in the middle and wrong
 * at the edges. The engine is a pure function, so each row is a direct call with
 * no clock, no storage and no connector.
 */
class MeasureRequirementTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final String GATE = "activity.meeper";
    private static final String REF = "chat:acct-1";
    private static final String NAME = "playtime";

    /** Seven hours over seven days, refreshed at least hourly. */
    private static final MeasureRequirement SEVEN_HOURS =
            new MeasureRequirement(NAME, 25_200L, 604_800L, 3_600L);

    private static Rule rule(MeasureRequirement measure) {
        return Rule.measuring(GATE, measure);
    }

    private static SubjectSnapshot snapshotWith(MeasureObservation... observations) {
        Map<String, MeasureObservation> measures = new java.util.LinkedHashMap<>();
        for (MeasureObservation o : observations) {
            measures.put(NAME, o);
        }
        return new SubjectSnapshot("s1", REF, Set.of(), 2, NOW, measures);
    }

    private static Decision decide(SubjectSnapshot snapshot, Rule rule) {
        return PolicyEngine.decide(snapshot, rule, List.of(), NOW);
    }

    private static MeasureObservation fresh(long value) {
        return new MeasureObservation(value, 604_800L, NOW);
    }

    // --- the threshold --------------------------------------------------------

    @Test
    @DisplayName("exactly at the threshold satisfies it; one below does not")
    void thresholdIsInclusive() {
        assertEquals(Effect.ALLOW, decide(snapshotWith(fresh(25_200L)), rule(SEVEN_HOURS)).effect(),
                "a value exactly at the threshold was refused, so `atLeast` is not inclusive");

        Decision below = decide(snapshotWith(fresh(25_199L)), rule(SEVEN_HOURS));
        assertEquals(Effect.DENY, below.effect());
        assertEquals(Decision.Reason.MEASURE_BELOW_THRESHOLD, below.reason());
        assertTrue(below.detail().contains("25199") && below.detail().contains("25200"),
                () -> "the refusal must name what was measured and what was needed: "
                        + below.detail());
    }

    @Test
    @DisplayName("comfortably over is allowed, and says the requirements are met")
    void wellOverIsAllowed() {
        Decision decision = decide(snapshotWith(fresh(110_000L)), rule(SEVEN_HOURS));
        assertEquals(Effect.ALLOW, decision.effect());
        assertEquals(Decision.Reason.REQUIREMENTS_MET, decision.reason());
    }

    // --- freshness ------------------------------------------------------------

    @Test
    @DisplayName("staleness is exclusive, like every other deadline here")
    void staleIsExclusive() {
        // Exactly at the limit is still good; one second past is not. The same
        // boundary convention as grace and override expiry.
        MeasureObservation atLimit =
                new MeasureObservation(99_999L, 604_800L, NOW.minusSeconds(3_600L));
        assertEquals(Effect.ALLOW, decide(snapshotWith(atLimit), rule(SEVEN_HOURS)).effect(),
                "an observation exactly at the age limit was refused");

        MeasureObservation pastLimit =
                new MeasureObservation(99_999L, 604_800L, NOW.minusSeconds(3_601L));
        Decision stale = decide(snapshotWith(pastLimit), rule(SEVEN_HOURS));
        assertEquals(Effect.DENY, stale.effect());
        assertEquals(Decision.Reason.MEASURE_STALE, stale.reason());
    }

    @Test
    @DisplayName("a stale observation is refused even when it is well over the threshold")
    void staleBeatsValue() {
        // The interesting direction. A huge number nobody has refreshed is
        // exactly the state a reporter leaves behind when it dies, and treating
        // it as satisfaction is how a role outlives its evidence.
        MeasureObservation old =
                new MeasureObservation(9_999_999L, 604_800L, NOW.minusSeconds(86_400L));
        assertEquals(Decision.Reason.MEASURE_STALE,
                decide(snapshotWith(old), rule(SEVEN_HOURS)).reason());
    }

    // --- the window -----------------------------------------------------------

    @Test
    @DisplayName("a window that does not match exactly is refused, in both directions")
    void windowMustMatchExactly() {
        // The worst outcome this feature can produce is granting to people who
        // did not earn it, silently. A reporter set to thirty days would satisfy
        // a seven-day rule on any reading but this one -- and a reporter set to
        // one day would deny people who had earned it. Both are misconfigurations
        // and both are named.
        MeasureObservation thirtyDays = new MeasureObservation(999_999L, 2_592_000L, NOW);
        Decision longer = decide(snapshotWith(thirtyDays), rule(SEVEN_HOURS));
        assertEquals(Effect.DENY, longer.effect());
        assertEquals(Decision.Reason.MEASURE_WINDOW_MISMATCH, longer.reason());

        MeasureObservation oneDay = new MeasureObservation(999_999L, 86_400L, NOW);
        assertEquals(Decision.Reason.MEASURE_WINDOW_MISMATCH,
                decide(snapshotWith(oneDay), rule(SEVEN_HOURS)).reason());
    }

    @Test
    @DisplayName("a window mismatch is reported ahead of staleness")
    void mismatchOutranksStaleness() {
        // Ordering, asserted rather than assumed. A mismatch is a configuration
        // fault that will not fix itself; staleness may clear on the next
        // report. Naming the one that clears sends an operator to wait for a
        // problem that is not going to go away.
        MeasureObservation both =
                new MeasureObservation(999_999L, 86_400L, NOW.minusSeconds(999_999L));
        assertEquals(Decision.Reason.MEASURE_WINDOW_MISMATCH,
                decide(snapshotWith(both), rule(SEVEN_HOURS)).reason());
    }

    // --- absence --------------------------------------------------------------

    @Test
    @DisplayName("nothing reported is refused, and distinguished from below-threshold")
    void absentIsItsOwnAnswer() {
        Decision decision = decide(snapshotWith(), rule(SEVEN_HOURS));
        assertEquals(Effect.DENY, decision.effect());
        assertEquals(Decision.Reason.MEASURE_ABSENT, decision.reason());
        assertTrue(decision.detail().contains(NAME), decision::detail);
    }

    @Test
    @DisplayName("a measure reported under another name does not satisfy this rule")
    void anotherMeasureDoesNotCount() {
        SubjectSnapshot snapshot = new SubjectSnapshot("s1", REF, Set.of(), 2, NOW,
                Map.of("something-else", fresh(999_999L)));
        assertEquals(Decision.Reason.MEASURE_ABSENT, decide(snapshot, rule(SEVEN_HOURS)).reason());
    }

    // --- interaction with the rest of a rule ----------------------------------

    @Test
    @DisplayName("a measure-only rule requires something, so it does not fall through")
    void measureOnlyRuleIsNotAnEmptyRule() {
        // The single highest-value assertion here. Without the measure clause in
        // requiresSomething(), a rule whose only requirement is a threshold takes
        // the "this gate requires nothing" branch, which allows EVERYBODY
        // regardless of defaultEffect -- a wide-open gate that reads as
        // configured.
        assertTrue(rule(SEVEN_HOURS).requiresSomething());
        assertEquals(Effect.DENY, decide(snapshotWith(), rule(SEVEN_HOURS)).effect(),
                "a measure-only rule admitted a subject with no measurement at all");
    }

    @Test
    @DisplayName("kinds and linkage are reported before the measure")
    void identityFaultsComeFirst() {
        // Both unsatisfied at once. The refusal names the one a person can act
        // on now.
        Rule both = new Rule(GATE, Set.of("forum"), true, 0L, Effect.DENY, SEVEN_HOURS);
        SubjectSnapshot unverified =
                new SubjectSnapshot("s1", REF, Set.of(), 1, NOW, Map.of(NAME, fresh(0L)));

        assertEquals(Decision.Reason.MISSING_KINDS, decide(unverified, both).reason());
    }

    @Test
    @DisplayName("the measure is the last thing standing between met and unmet")
    void measureDecidesWhenIdentityIsSatisfied() {
        Rule both = new Rule(GATE, Set.of("forum"), true, 0L, Effect.DENY, SEVEN_HOURS);
        SubjectSnapshot verified =
                new SubjectSnapshot("s1", REF, Set.of("forum"), 2, NOW, Map.of(NAME, fresh(1L)));

        assertEquals(Decision.Reason.MEASURE_BELOW_THRESHOLD, decide(verified, both).reason());

        SubjectSnapshot enough = new SubjectSnapshot(
                "s1", REF, Set.of("forum"), 2, NOW, Map.of(NAME, fresh(25_200L)));
        assertEquals(Effect.ALLOW, decide(enough, both).effect());
    }

    @Test
    @DisplayName("grace still opens a gate whose measure is unmet")
    void graceAppliesToMeasures() {
        // Grace is about being new, not about which requirement is unmet, and a
        // measure is the requirement a newcomer is least able to satisfy.
        Rule graced = new Rule(GATE, Set.of(), false, 600L, Effect.DENY, SEVEN_HOURS);
        SubjectSnapshot newcomer = new SubjectSnapshot(
                "s1", REF, Set.of(), 2, NOW.minusSeconds(60L), Map.of());

        Decision decision = PolicyEngine.decide(newcomer, graced, List.of(), NOW);
        assertEquals(Effect.ALLOW, decision.effect());
        assertEquals(Decision.Reason.GRACE, decision.reason());
    }

    // --- declaration ----------------------------------------------------------

    @Test
    @DisplayName("a requirement with no freshness bound is refused at construction")
    void freshnessBoundIsMandatory() {
        // Without one the answer survives the reporter disappearing, and there
        // is no timer anywhere here to notice.
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureRequirement(NAME, 1L, 604_800L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureRequirement(NAME, 1L, 604_800L, -1L));
    }

    @Test
    @DisplayName("a nonsensical requirement is refused at construction")
    void otherInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureRequirement(" ", 1L, 604_800L, 60L), "blank name");
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureRequirement(NAME, -1L, 604_800L, 60L), "negative threshold");
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureRequirement(NAME, 1L, 0L, 60L), "zero window");
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureObservation(1L, 0L, NOW), "observation with no window");
    }

    @Test
    @DisplayName("a threshold of zero is legitimate: it means reported at all, and recently")
    void zeroThresholdIsAllowed() {
        // NOT the same as no requirement. `atLeast = 0` still demands an
        // observation that exists, covers the right window and is fresh -- which
        // is exactly "has this reporter seen them lately". Refusing it would
        // make that unspellable, and the boundary between `< 0` and `<= 0` is
        // one character.
        MeasureRequirement any = new MeasureRequirement(NAME, 0L, 604_800L, 3_600L);

        assertEquals(Effect.ALLOW, decide(snapshotWith(fresh(0L)), rule(any)).effect(),
                "a zero threshold refused a zero measurement, so it is not a threshold at all");
        assertEquals(Decision.Reason.MEASURE_ABSENT,
                decide(snapshotWith(), rule(any)).reason(),
                "a zero threshold was satisfied by nothing having been reported");
    }

    @Test
    @DisplayName("a rule written before measures existed still has none")
    void olderRulesCarryNoMeasure() {
        // The compatibility claim, asserted rather than assumed: the five-argument
        // shape must keep working and must mean "no measure requirement".
        Rule old = new Rule(GATE, Set.of("forum"), true, 0L, Effect.DENY);
        assertEquals(null, old.measure());
        assertTrue(old.requiresSomething());

        assertFalse(Rule.open(GATE).requiresSomething());
        assertEquals(null, SubjectSnapshot.unlinked(REF, NOW).measures().get(NAME));
        assertTrue(SubjectSnapshot.unlinked(REF, NOW).measures().isEmpty());
    }
}
