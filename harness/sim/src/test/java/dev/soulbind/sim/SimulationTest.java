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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The acceptance test, in process.
 *
 * <p>§14's Phase 9 gate asks that "a deliberately reverted Phase-2-or-later fix
 * is rediscovered by a hunting run". That has to be executed against real core
 * to satisfy the gate, and it is — but the same question is asked here of the
 * whole loop, in milliseconds, against a small core whose defects can be
 * switched on one at a time.
 *
 * <p>The two are complementary. The session run proves the tier finds a real
 * defect in real code. This one proves the tier finds <b>every</b> defect it
 * claims to, including the five nobody has written yet, and it runs on every
 * build rather than once a session.
 */
class SimulationTest {

    private static World world() {
        return new World(
                List.of(
                        new Actor("alex", List.of("game:alex", "chat:alex"), 0),
                        new Actor("sam", List.of("forum:sam", "chat:sam"), 0),
                        new Actor("rey", List.of("game:rey", "forum:rey"), 0)),
                List.of("game.join", "forum.post"));
    }

    @Test
    @DisplayName("a correct core survives a long run untouched")
    void theControl() {
        // The half that is easy to skip and impossible to do without. A tier
        // that catches a defective core proves nothing if it also catches a
        // correct one -- and the only way to find out is to write a correct one
        // and run it.
        InMemoryCore core = new InMemoryCore();
        Simulation.Outcome outcome =
                Simulation.run(20260820L, world(), core, core, 400, 50);

        assertTrue(outcome.clean(),
                () -> "a correct core was reported as broken:\n" + outcome.summary());
        assertTrue(outcome.actionsTaken() > 300,
                () -> "the run stopped after " + outcome.actionsTaken() + " actions and"
                        + " proved little");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InMemoryCore.Defect.class)
    @DisplayName("every defect is rediscovered by a hunting run")
    void everyDefectIsCaught(InMemoryCore.Defect defect) {
        // Parameterised over the enum rather than one test per defect, so a
        // defect added without a test is impossible -- the case appears the
        // moment the constant does.
        InMemoryCore core = new InMemoryCore().with(defect);
        Simulation.Outcome outcome =
                Simulation.run(20260820L, world(), core, core, 400, 50);

        assertFalse(outcome.clean(),
                () -> defect + " was switched on and a 400-action run did not notice.\n"
                        + "Last actions:\n"
                        + String.join("\n", outcome.trace().subList(
                                Math.max(0, outcome.trace().size() - 10),
                                outcome.trace().size())));
    }

    @Test
    @DisplayName("a run reports how much work it actually did")
    void theRunReportsItsOwnWork() {
        // "400 actions" counts attempts, and an attempt core refused did not
        // happen. Two seeds sharing an identity namespace with a third linked
        // NOTHING and reported clean, because there is nothing for an invariant
        // to disagree about when nothing changed.
        InMemoryCore core = new InMemoryCore();
        Simulation.Outcome outcome = Simulation.run(20260820L, world(), core, core, 400, 50);

        assertTrue(outcome.didWork(),
                () -> "the run linked nothing: " + outcome.summary());
        // Three, not more. Three actors of three platforms each is a graph that
        // COMPLETES after about six successful redeems, and every redeem after
        // that is correctly refused as already-linked. So a healthy run does its
        // linking early and spends the rest of its actions on decisions, audit,
        // rules and the nemesis classes.
        //
        // Worth knowing when reading a summary: a low link count late in a long
        // run is saturation, not failure. What would be failure is ZERO, which
        // didWork() covers.
        assertTrue(outcome.linksMade() >= 3,
                () -> "only " + outcome.linksMade() + " links, so barely any of the graph"
                        + " was built: " + outcome.summary());
        assertTrue(outcome.summary().contains("links made"),
                () -> "the summary does not say how much work was done, so a run that did"
                        + " none reads exactly like one that did: " + outcome.summary());
    }

    @Test
    @DisplayName("a run that is not exhausted takes EXACTLY the actions it was asked for")
    void theRunTakesExactlyWhatItWasAsked() {
        // `actionsTaken() > 300` was the assertion here, and a loop running one
        // short of its bound satisfies it. Exactness is what pins the boundary,
        // and the trace has to agree: one line per action, or the record of the
        // run is not a record of the run.
        InMemoryCore core = new InMemoryCore();
        Simulation.Outcome outcome = Simulation.run(20260820L, world(), core, core, 120, 40);

        assertEquals(120, outcome.actionsTaken(),
                "the run did not take the number of actions it was given, and nothing but"
                        + " this says so: " + outcome.summary());
        assertEquals(120, outcome.trace().size(),
                "the trace does not have one line per action, so it cannot be read back as"
                        + " what happened");
    }

    @Test
    @DisplayName("every action is counted as accepted or refused, and nothing falls between")
    void everyActionIsAccountedFor() {
        // The counters are how a person decides whether a clean run meant
        // anything. A refusal miscounted as an acceptance -- or as neither --
        // makes "400 actions, 0 violations" unreadable.
        InMemoryCore core = new InMemoryCore();
        Simulation.Outcome outcome = Simulation.run(20260820L, world(), core, core, 200, 50);

        long ok = outcome.trace().stream().filter(line -> line.endsWith("=> ok")).count();
        long refused = outcome.trace().stream().filter(line -> line.contains("=> refused:"))
                .count();

        assertEquals(outcome.actionsTaken(), ok + refused,
                "an action was neither accepted nor refused in the trace: " + outcome.trace()
                        .stream().filter(l -> !l.endsWith("=> ok") && !l.contains("=> refused:"))
                        .toList());
        assertEquals(outcome.refusals(), refused,
                "the refusal counter disagrees with the trace it was counted from");
        assertTrue(refused > 0,
                "nothing was refused in two hundred actions, so the refusal path is not being"
                        + " exercised and its counter proves nothing");
    }

    @Test
    @DisplayName("a run that linked nothing does not claim to have worked")
    void noLinksIsNotWork() {
        // The specific fault this flag exists for: three seeds sharing one
        // identity namespace meant seeds two and three linked nothing at all,
        // and both reported clean. Zero successful links is a harness fault.
        // `linksMade > 0`, not `>= 0`.
        Simulation.Outcome nothing = new Simulation.Outcome(
                1L, 400, 0, 400, List.of(), List.of());
        Simulation.Outcome something = new Simulation.Outcome(
                1L, 400, 1, 399, List.of(), List.of());

        assertFalse(nothing.didWork(),
                "a run that made no links at all reported that it had done work");
        assertTrue(something.didWork(),
                "a run that made a link reported that it had not");
    }

    @Test
    @DisplayName("the run says WHEN it first diverged, not just that it did")
    void violationsAreLocatedInTheRun() {
        // With the shrinker deferred (DECISIONS 9.1) this is what makes a
        // failing seed tractable. A violation with no action number means
        // reading the whole trace.
        InMemoryCore core = new InMemoryCore().with(InMemoryCore.Defect.REDEEM_DOES_NOT_LINK);
        Simulation.Outcome outcome =
                Simulation.run(20260820L, world(), core, core, 400, 25);

        assertFalse(outcome.violations().isEmpty());
        int first = outcome.violations().get(0).afterAction();
        assertTrue(first > 0 && first <= 400,
                () -> "the first violation reports action " + first + ", which is not a"
                        + " position in this run");
    }

    @Test
    @DisplayName("a run is reproducible from its seed, defects and all")
    void runsAreReproducible() {
        InMemoryCore first = new InMemoryCore().with(InMemoryCore.Defect.CODE_STAYS_REDEEMABLE);
        InMemoryCore second = new InMemoryCore().with(InMemoryCore.Defect.CODE_STAYS_REDEEMABLE);

        Simulation.Outcome a = Simulation.run(4242L, world(), first, first, 300, 50);
        Simulation.Outcome b = Simulation.run(4242L, world(), second, second, 300, 50);

        assertEquals(a.trace(), b.trace(), "one seed produced two different traces");
        assertEquals(a.violations().toString(), b.violations().toString(),
                "one seed produced two different verdicts");
    }

    @Test
    @DisplayName("the final check runs even when the period does not divide the length")
    void theEndIsAlwaysChecked() {
        // A run of 210 with a period of 100 would otherwise leave its last ten
        // actions unchecked -- and those are the ones with the most accumulated
        // history behind them, which is where this tier's value is.
        InMemoryCore core = new InMemoryCore().with(InMemoryCore.Defect.REDEEM_DOES_NOT_LINK);
        Simulation.Outcome outcome =
                Simulation.run(31337L, world(), core, core, 210, 100_000);

        assertFalse(outcome.clean(),
                "a defect present at the end of the run was never checked for, because the"
                        + " period never came round");
    }
}
