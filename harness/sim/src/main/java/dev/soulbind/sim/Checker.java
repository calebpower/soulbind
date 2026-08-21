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

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the invariants against core, periodically and at the end.
 *
 * <p><b>Both, and they answer different questions.</b> The final check says
 * whether the run ended consistent. The periodic ones say <em>when</em> it
 * stopped being — and on a run of several hundred actions that is the
 * difference between a defect somebody can find and a trace somebody has to
 * read in full. With the shrinker deferred (DECISIONS 9.1), the action number
 * attached to a violation is the main thing standing between a failing seed and
 * an afternoon.
 *
 * <p>Every violation is recorded and the run continues. Stopping at the first
 * one throws away the shape of the failure, and a graph that has diverged has
 * usually diverged in several places — the set is the evidence, and the first
 * element of it is rarely the interesting one.
 */
public final class Checker {

    /** One invariant's complaint, and when it was first heard. */
    public record Violation(String invariant, String complaint, int afterAction) {
        @Override
        public String toString() {
            return "action " + afterAction + ": [" + invariant + "] " + complaint;
        }
    }

    private final List<Invariant> invariants;
    private final int period;
    private final List<Violation> violations = new ArrayList<>();

    /**
     * @param period how many actions between checks; the end is always checked
     */
    public Checker(List<Invariant> invariants, int period) {
        if (period < 1) {
            throw new IllegalArgumentException(
                    "a check period below 1 would check after every action or never; got "
                            + period);
        }
        this.invariants = List.copyOf(invariants);
        this.period = period;
    }

    /** Whether a check is due after this action number. */
    public boolean isDue(int actionNumber) {
        return actionNumber > 0 && actionNumber % period == 0;
    }

    /**
     * Runs every invariant and records anything new.
     *
     * <p>Deduplicated by (invariant, complaint): a divergence that persists
     * would otherwise be re-reported at every subsequent check, and a report
     * whose length is proportional to how long the run continued after the
     * first failure tells you nothing about how many things are wrong.
     */
    public List<Violation> check(int afterAction, ShadowModel model, CoreView core) {
        List<Violation> fresh = new ArrayList<>();
        // Invariants the view has declared it cannot answer are SKIPPED, not run
        // and ignored. Running one against a view that cannot answer it produces
        // either a false pass (the view answers "no" to everything) or a false
        // failure, and both are worse than not running it -- provided the skip
        // is loud, which it is: Runner prints the inert list first, before the
        // verdict, on green runs as well as red.
        List<String> inert = core.inertInvariants().stream()
                .map(reason -> reason.split(":", 2)[0].strip())
                .toList();
        for (Invariant invariant : invariants) {
            if (inert.contains(invariant.name())) {
                continue;
            }
            for (String complaint : invariant.check(model, core)) {
                Violation violation = new Violation(invariant.name(), complaint, afterAction);
                boolean alreadyKnown = violations.stream()
                        .anyMatch(v -> v.invariant().equals(violation.invariant())
                                && v.complaint().equals(violation.complaint()));
                if (!alreadyKnown) {
                    violations.add(violation);
                    fresh.add(violation);
                }
            }
        }
        return fresh;
    }

    /** Everything heard so far, in the order it was first heard. */
    /**
     * Records a violation the EXECUTOR found, not an invariant.
     *
     * <p>Some defects are visible at the moment of the action and nowhere
     * afterwards: core accepting a code it had already claimed leaves a graph
     * that looks entirely consistent, because the extra link is real. Only the
     * caller knows it asked for something that should have been refused.
     */
    public void record(Violation violation) {
        violations.add(violation);
    }

    public List<Violation> violations() {
        return List.copyOf(violations);
    }

    /** Whether the run is still consistent. */
    public boolean clean() {
        return violations.isEmpty();
    }
}
