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
 * The loop: generate, execute, record, check.
 *
 * <p>The model is updated from what core <b>actually did</b>, never from what
 * the action intended. An action that core refused did not happen, and a model
 * that recorded the intention would drift away from the truth on every refusal
 * — then report the drift as a defect in core. A tier whose false-positive rate
 * rises with the number of legitimate refusals is a tier nobody will keep
 * running.
 *
 * <p>That is also why refusals are not failures here. Most are correct: a code
 * redeemed twice, a stale credential, an unlinked identity at a gate that
 * requires one. What must hold is that they are <em>coherent</em> — the model
 * and core agree afterwards about what happened — and that is what the
 * invariants check, rather than any individual response.
 */
public final class Simulation {

    /** What a run produced. */
    public record Outcome(
            long seed,
            int actionsTaken,
            List<Checker.Violation> violations,
            List<String> trace) {

        public boolean clean() {
            return violations.isEmpty();
        }

        /** A short report, for a runner's stdout. */
        public String summary() {
            StringBuilder out = new StringBuilder();
            out.append("seed ").append(seed)
                    .append(": ").append(actionsTaken).append(" actions, ")
                    .append(violations.size()).append(" violation(s)");
            for (Checker.Violation violation : violations) {
                out.append("\n  ").append(violation);
            }
            return out.toString();
        }
    }

    private Simulation() {
        throw new AssertionError("no instances");
    }

    /**
     * Runs one seed.
     *
     * @param actions how many to attempt; the run stops early only when nothing
     *     at all is applicable
     * @param checkPeriod actions between invariant checks; the end is always
     *     checked
     */
    public static Outcome run(
            long seed,
            World world,
            CoreDriver driver,
            CoreView view,
            int actions,
            int checkPeriod) {

        Generator generator = new Generator(seed);
        ShadowModel model = new ShadowModel();
        Checker checker = new Checker(Invariants.all(), checkPeriod);
        List<String> trace = new ArrayList<>();

        int taken = 0;
        for (int i = 1; i <= actions; i++) {
            var next = generator.next(world);
            if (next.isEmpty()) {
                trace.add("(nothing applicable; stopping at " + taken + ")");
                break;
            }
            Action action = next.get();
            CoreDriver.Result result = execute(action, driver, world, model);
            taken++;
            trace.add(i + ": " + action + "  => "
                    + (result.accepted() ? "ok" : "refused: " + result.detail()));

            if (checker.isDue(i)) {
                for (Checker.Violation violation : checker.check(i, model, view)) {
                    trace.add("   !! " + violation);
                }
            }
        }

        // Always, whatever the period divided into. A run of 250 with a period
        // of 100 would otherwise never check its last fifty actions, which are
        // the ones with the most accumulated history behind them.
        checker.check(taken, model, view);

        return new Outcome(seed, taken, checker.violations(), List.copyOf(trace));
    }

    private static CoreDriver.Result execute(
            Action action, CoreDriver driver, World world, ShadowModel model) {

        String[] ref = split(action.subject());
        switch (action.kind()) {
            case ISSUE_CODE, ABANDON_CODE -> {
                CoreDriver.Result result = driver.issueCode(action.actor(), ref[0], ref[1]);
                if (result.accepted() && result.value() != null
                        && action.kind() == ActionKind.ISSUE_CODE) {
                    // ABANDON_CODE deliberately does not tell the world, so the
                    // code is never chosen for redemption and accumulates -- the
                    // half-finished work §11 asks this class to leave behind.
                    world.codeIssued(result.value(), action.subject());
                }
                return result;
            }
            case REDEEM_CODE, REDEEM_FOREIGN -> {
                String[] target = split(action.detail());
                String issuedFor = world.outstandingCodes().get(action.subject());
                CoreDriver.Result result = driver.redeemCode(
                        action.actor(), action.subject(), target[0], target[1]);
                if (result.accepted()) {
                    world.codeSpent(action.subject());
                    model.redeemed(action.subject());
                    if (issuedFor != null) {
                        world.linked(issuedFor, action.detail());
                        model.linked(issuedFor, action.detail());
                    }
                }
                return result;
            }
            case DESCRIBE -> {
                return driver.describe(action.actor(), ref[0], ref[1]);
            }
            case DECIDE, ACT_ON_UNLINKED -> {
                String gate = action.detail() == null ? firstGate(world) : action.detail();
                return driver.decide(action.actor(), gate, ref[0], ref[1]);
            }
            case SET_RULE -> {
                CoreDriver.Result result =
                        driver.setRule(action.actor(), action.subject(), true);
                if (result.accepted()) {
                    model.mutated("rule.changed");
                }
                return result;
            }
            case CONFIG_FLIP -> {
                CoreDriver.Result result = driver.setConfig(
                        action.actor(), "linking.codettlseconds", "900");
                if (result.accepted()) {
                    model.mutated("config.changed");
                    world.rotated(action.actor());
                }
                return result;
            }
            case STALE_CREDENTIAL -> {
                String[] own = split(action.actor().identities().isEmpty()
                        ? "game:nobody" : action.actor().identities().get(0));
                return driver.withRetiredCredential(action.actor(), own[0], own[1]);
            }
            default -> throw new IllegalStateException("unhandled kind: " + action.kind());
        }
    }

    private static String firstGate(World world) {
        return world.gates().isEmpty() ? "gate.unknown" : world.gates().get(0);
    }

    private static String[] split(String ref) {
        if (ref == null) {
            return new String[] {"game", "nobody"};
        }
        int colon = ref.indexOf(':');
        return colon < 0
                ? new String[] {ref, ""}
                : new String[] {ref.substring(0, colon), ref.substring(colon + 1)};
    }
}
