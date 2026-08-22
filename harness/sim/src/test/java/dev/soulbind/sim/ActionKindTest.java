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
package dev.soulbind.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The three facts an action kind carries, each of which changes what a run
 * means.
 *
 * <p>{@code mustBeRefused} is the load-bearing one: the executor turns an
 * ACCEPTED action of that class into a violation without consulting the model,
 * because the expected answer is knowable without one. A version that answered
 * false for everything would silently retire that whole check — the run would
 * keep performing double redeems and retired-credential calls, keep having them
 * accepted by a broken core, and keep reporting clean.
 */
class ActionKindTest {

    /** Written out by hand, not derived, so a change to the enum fails here. */
    private static final Set<ActionKind> MUST_BE_REFUSED =
            Set.of(ActionKind.DOUBLE_REDEEM, ActionKind.STALE_CREDENTIAL);

    private static final Set<ActionKind> NEMESES = Set.of(
            ActionKind.STALE_CREDENTIAL,
            ActionKind.ACT_ON_UNLINKED,
            ActionKind.CONFIG_FLIP,
            ActionKind.ABANDON_CODE,
            ActionKind.DOUBLE_REDEEM);

    @ParameterizedTest
    @EnumSource(ActionKind.class)
    @DisplayName("mustBeRefused is true for exactly the two knowable-without-a-model classes")
    void mustBeRefusedIsExact(ActionKind kind) {
        assertEquals(
                MUST_BE_REFUSED.contains(kind),
                kind.mustBeRefused(),
                () -> kind + " disagrees with the contract. A class wrongly marked"
                        + " must-be-refused turns a legitimate acceptance into a reported"
                        + " defect; one wrongly unmarked stops the run noticing an"
                        + " acceptance that can only happen when core is broken.");
    }

    @ParameterizedTest
    @EnumSource(ActionKind.class)
    @DisplayName("every adversarial class is marked as one, and no ordinary class is")
    void nemesesAreExact(ActionKind kind) {
        // Reporting only -- it changes no behaviour -- but the report is what a
        // person reads to decide whether a run pushed on anything. "0 nemesis
        // actions" and "every action was a nemesis" are both lies that look
        // like information.
        assertEquals(NEMESES.contains(kind), kind.isNemesis(), kind::toString);
    }

    @Test
    @DisplayName("every kind has a positive weight, or it can never be chosen")
    void weightsAreUsable() {
        // A zero weight is a kind that exists, is applicable, and is never
        // drawn -- which reads in the source as covered and is not.
        for (ActionKind kind : ActionKind.values()) {
            assertTrue(kind.weight() > 0, () -> kind + " has weight " + kind.weight());
        }
    }

    @Test
    @DisplayName("the adversarial classes are a minority of the draw, not the run")
    void nemesesDoNotDominate() {
        // A run made mostly of hostile actions never builds the graph the
        // ordinary invariants need to say anything.
        int nemesis = Arrays.stream(ActionKind.values())
                .filter(ActionKind::isNemesis).mapToInt(ActionKind::weight).sum();
        int total = Arrays.stream(ActionKind.values()).mapToInt(ActionKind::weight).sum();

        assertTrue(nemesis * 2 < total,
                () -> "adversarial classes carry " + nemesis + " of " + total + " weight, so"
                        + " the run spends most of its draws attacking rather than building"
                        + " the graph the invariants read: "
                        + Arrays.stream(ActionKind.values()).filter(ActionKind::isNemesis)
                                .map(Enum::name).collect(Collectors.joining(", ")));
    }
}
