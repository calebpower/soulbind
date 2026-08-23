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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Does the executor keep the ORACLE in step with what it just did?
 *
 * <p>{@code OracleSelfTest} proves each invariant complains when core is broken.
 * This is the other half, and until Phase 10 nothing asserted it: the invariants
 * compare the shadow model against core, so a fact the executor forgets to
 * record is a fact **missing from both sides of the comparison**. Every
 * invariant then stays silent, the run reports hundreds of actions and no
 * violations, and that reads exactly like success.
 *
 * <p>Mutation put a number on it — nine of the executor's model and world
 * updates could be deleted outright with no test failing. Each one of those is
 * a way the simulated-user tier could agree with a broken product. DECISIONS
 * 10.32.
 */
class ExecutorBookkeepingTest {

    private static final String GAME = "game:alex";
    private static final String CHAT = "chat:alex";

    private static final Actor ALEX = new Actor("alex", List.of(GAME, CHAT), 0);

    /** A driver that answers as told and records what it was asked. */
    private static final class ScriptedDriver implements CoreDriver {
        private final CoreDriver.Result answer;
        private final List<String> calls = new ArrayList<>();

        ScriptedDriver(CoreDriver.Result answer) {
            this.answer = answer;
        }

        @Override
        public String displayFor(Actor actor) {
            return actor.name() + "-display";
        }

        @Override
        public Result issueCode(Actor actor, String platformKind, String platformId) {
            calls.add("issue " + platformKind + ":" + platformId);
            return answer;
        }

        @Override
        public Result redeemCode(Actor actor, String code, String kind, String id) {
            calls.add("redeem " + code + " as " + kind + ":" + id);
            return answer;
        }

        @Override
        public Result describe(Actor actor, String platformKind, String platformId) {
            calls.add("describe");
            return answer;
        }

        @Override
        public Result decide(Actor actor, String gate, String kind, String id) {
            calls.add("decide " + gate);
            return answer;
        }

        @Override
        public Result setRule(Actor actor, String gate, boolean requireLinked) {
            calls.add("rule " + gate);
            return answer;
        }

        @Override
        public Result setConfig(Actor actor, String key, String value) {
            calls.add("config " + key);
            return answer;
        }

        @Override
        public Result withRetiredCredential(Actor actor, String kind, String id) {
            calls.add("retired");
            return answer;
        }
    }

    private static World world() {
        return new World(List.of(ALEX), List.of("game.join"));
    }

    // --- issuing --------------------------------------------------------------

    @Test
    @DisplayName("issuing a code teaches the model the account exists, linked or not")
    void issueTeachesTheModel() {
        // Without this the policy invariant cannot ask about accounts linked to
        // nothing -- it would not know they were there.
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.ISSUE_CODE, ALEX, GAME, null),
                new ScriptedDriver(CoreDriver.Result.ok("BCDFGHJK")), world, model);

        // neverLinked, NOT knownIdentities, and the separation is load-bearing:
        // an account that has only had a code issued for it has no subject in
        // core, so putting it in the link graph would have the linkage
        // invariant report "core does not know it" about an account core is
        // correct not to know. Both halves asserted, so a change that merges
        // the two sets fails here rather than in a session.
        assertTrue(model.neverLinked().contains(GAME),
                "the model does not know the account exists, so every invariant that asks"
                        + " about accounts linked to nothing will find nothing and say"
                        + " nothing: " + model.neverLinked());
        assertFalse(model.knownIdentities().contains(GAME),
                "an account with only a code issued for it was put in the link graph, which"
                        + " makes the linkage invariant complain about core being right");
        assertEquals("alex-display", model.displayFor(GAME).orElse(null),
                "the display core was told was not recorded, so an invariant comparing what"
                        + " core shows against what was sent has nothing to compare");
        assertEquals(GAME, world.outstandingCodes().get("BCDFGHJK"),
                "the code is not in the world, so nothing will ever try to redeem it");
    }

    @Test
    @DisplayName("abandoning a code teaches the model but leaves the code unusable")
    void abandonLeavesTheCodeBehind() {
        // The half-finished work the specification asks this class to leave
        // behind: core holds a code nobody will ever redeem.
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.ABANDON_CODE, ALEX, GAME, null),
                new ScriptedDriver(CoreDriver.Result.ok("BCDFGHJK")), world, model);

        assertTrue(model.neverLinked().contains(GAME));
        assertTrue(world.outstandingCodes().isEmpty(),
                "an abandoned code was offered to the world, so it will be redeemed and the"
                        + " scenario stops being abandonment: " + world.outstandingCodes());
    }

    @Test
    @DisplayName("a refused issue still teaches the model the account exists")
    void refusedIssueStillTeaches() {
        // The account was named, so it is real whatever core said about the
        // code. Recording it only on success would leave the model blind to
        // every account a refusal touched.
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.ISSUE_CODE, ALEX, GAME, null),
                new ScriptedDriver(CoreDriver.Result.refused("nope")), world, model);

        assertTrue(model.neverLinked().contains(GAME));
        assertTrue(world.outstandingCodes().isEmpty(),
                "a code core refused to issue was added to the world");
    }

    // --- redeeming ------------------------------------------------------------

    @Test
    @DisplayName("a redeem that links records the link in BOTH the model and the world")
    void redeemRecordsTheLink() {
        World world = world();
        ShadowModel model = new ShadowModel();
        world.codeIssued("BCDFGHJK", GAME);

        Simulation.execute(
                new Action(ActionKind.REDEEM_CODE, ALEX, "BCDFGHJK", CHAT),
                new ScriptedDriver(CoreDriver.Result.ok(null)), world, model);

        assertTrue(model.groupContaining(GAME).contains(CHAT),
                "the model does not know the two accounts are one subject, so the invariant"
                        + " that checks core agrees has nothing to disagree with: "
                        + model.groupContaining(GAME));
        assertTrue(world.linkedRefs().contains(GAME) && world.linkedRefs().contains(CHAT),
                "the world still thinks these are unlinked, so the generator will keep"
                        + " proposing them for linking: " + world.linkedRefs());
        assertTrue(model.isRedeemed("BCDFGHJK"),
                "the model does not know the code was spent, so the invariant that catches a"
                        + " code offered twice cannot fire");
        assertFalse(world.outstandingCodes().containsKey("BCDFGHJK"),
                "a spent code stayed in the world, so the rest of the run spends its draws"
                        + " on an answer that cannot change");
    }

    @Test
    @DisplayName("a code core CONSUMED is spent even when the link was refused")
    void consumedCodeIsSpentEvenOnRefusal() {
        // codeConsumed is deliberately separate from accepted: core claims a
        // code before it decides whether the link is allowed, and a code it has
        // claimed is gone whatever it then answered.
        World world = world();
        ShadowModel model = new ShadowModel();
        world.codeIssued("BCDFGHJK", GAME);

        Simulation.execute(
                new Action(ActionKind.REDEEM_CODE, ALEX, "BCDFGHJK", CHAT),
                new ScriptedDriver(new CoreDriver.Result(false, null, "already linked", true)),
                world, model);

        assertFalse(world.outstandingCodes().containsKey("BCDFGHJK"),
                "core said it had claimed the code and the world kept offering it");
        assertFalse(model.isRedeemed("BCDFGHJK"),
                "the model recorded a redemption core refused, which would make the run's"
                        + " later assertions agree with a link that never happened");
        assertTrue(world.linkedRefs().isEmpty(), world.linkedRefs()::toString);
    }

    @Test
    @DisplayName("a display is recorded only when the redeem was accepted AND named a target")
    void displayIsRecordedOnlyWhenBothHold() {
        // Both halves of the guard. Recording on a refusal would have the model
        // expecting core to hold a display it never accepted; recording with no
        // target named would attribute it to nothing.
        World refused = world();
        ShadowModel refusedModel = new ShadowModel();
        Simulation.execute(
                new Action(ActionKind.REDEEM_CODE, ALEX, "BCDFGHJK", CHAT),
                new ScriptedDriver(CoreDriver.Result.refused("no")), refused, refusedModel);
        assertTrue(refusedModel.displayFor(CHAT).isEmpty(),
                "a display core refused to accept was recorded as sent");

        // And the case that must record: accepted, with a target named. Only
        // asserting the two refusals leaves "records nothing, ever" passing.
        World both = world();
        ShadowModel bothModel = new ShadowModel();
        Simulation.execute(
                new Action(ActionKind.REDEEM_CODE, ALEX, "BCDFGHJK", CHAT),
                new ScriptedDriver(CoreDriver.Result.ok(null)), both, bothModel);
        assertEquals("alex-display", bothModel.displayFor(CHAT).orElse(null),
                "an accepted redeem that named its target recorded no display, so the"
                        + " invariant comparing what core shows against what was sent has"
                        + " nothing to compare");

        World noTarget = world();
        ShadowModel noTargetModel = new ShadowModel();
        Simulation.execute(
                new Action(ActionKind.REDEEM_CODE, ALEX, "BCDFGHJK", null),
                new ScriptedDriver(CoreDriver.Result.ok(null)), noTarget, noTargetModel);
        assertTrue(noTargetModel.displayFor(CHAT).isEmpty(), "a display was attributed to"
                + " an account the action never named");
    }

    @Test
    @DisplayName("an actor with no identities still names something core can parse")
    void staleCredentialWithNoIdentities() {
        // An actor can be rotated before it has linked anything. Splitting an
        // empty list would put a null through the driver and the run would die
        // on the one action written to prove core stays up.
        ScriptedDriver driver = new ScriptedDriver(CoreDriver.Result.refused("retired"));

        Simulation.execute(
                new Action(ActionKind.STALE_CREDENTIAL,
                        new Actor("nobody", List.of(), 1), null, null),
                driver, world(), new ShadowModel());

        assertTrue(driver.calls.contains("retired"), driver.calls::toString);
    }

    @Test
    @DisplayName("a reference with no colon, or none at all, still reaches core as something")
    void malformedReferencesAreSplitSafely() {
        // split() is the last thing between a generator bug and a
        // NullPointerException that ends the run. A run that dies is a run that
        // reports nothing, which is worse than one that asks core a silly
        // question and is refused.
        ScriptedDriver noColon = new ScriptedDriver(CoreDriver.Result.ok(null));
        Simulation.execute(
                new Action(ActionKind.DESCRIBE, ALEX, "no-colon-here", null),
                noColon, world(), new ShadowModel());
        assertTrue(noColon.calls.contains("describe"), noColon.calls::toString);

        ScriptedDriver nullSubject = new ScriptedDriver(CoreDriver.Result.ok(null));
        Simulation.execute(
                new Action(ActionKind.ISSUE_CODE, ALEX, null, null),
                nullSubject, world(), new ShadowModel());
        assertTrue(nullSubject.calls.contains("issue game:nobody"),
                "a null subject did not fall back to a parseable reference: "
                        + nullSubject.calls);
    }

    // --- the mutating actions -------------------------------------------------

    @Test
    @DisplayName("a rule change is recorded as both an audit expectation and a rule")
    void ruleChangeIsRecordedTwice() {
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.SET_RULE, ALEX, "game.join", null),
                new ScriptedDriver(CoreDriver.Result.ok(null)), world, model);

        assertTrue(model.expectedAuditActions().contains("rule.changed"),
                "the model does not expect the audit row, so the invariant that catches a"
                        + " missing one cannot fire: " + model.expectedAuditActions());
        assertEquals(Boolean.TRUE, model.rules().get("game.join"),
                "the rule the run just set is not in the model, so no decision can be"
                        + " checked against it");
    }

    @Test
    @DisplayName("a refused rule change is recorded as nothing at all")
    void refusedRuleChangeRecordsNothing() {
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.SET_RULE, ALEX, "game.join", null),
                new ScriptedDriver(CoreDriver.Result.refused("no capability")), world, model);

        assertFalse(model.expectedAuditActions().contains("rule.changed"),
                "the model expects an audit row for a change core refused, so the run will"
                        + " report a missing row that was never supposed to exist");
        assertTrue(model.rules().isEmpty(), model.rules()::toString);
    }

    @Test
    @DisplayName("a config flip rotates the actor's credential in the world")
    void configFlipRotates() {
        // The world has to know, or the stale-credential action can never be
        // generated -- and that action is how the run proves a retired
        // credential is refused.
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.CONFIG_FLIP, ALEX, null, null),
                new ScriptedDriver(CoreDriver.Result.ok(null)), world, model);

        assertTrue(model.expectedAuditActions().contains("config.changed"),
                model.expectedAuditActions()::toString);
        assertFalse(world.actorsWithAStaleCredential().isEmpty(),
                "nobody holds a stale credential after a rotation, so the run can never"
                        + " generate the action that proves core refuses one");
    }

    // --- the two that must record NOTHING -------------------------------------

    @Test
    @DisplayName("a double redeem that succeeds writes nothing into the model")
    void doubleRedeemRecordsNothing() {
        // Success here IS the defect. Writing it into the model would make the
        // run's later assertions agree with the corruption -- the oracle would
        // adopt the bug and then confirm it.
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.DOUBLE_REDEEM, ALEX, "BCDFGHJK", CHAT),
                new ScriptedDriver(CoreDriver.Result.ok(null)), world, model);

        assertTrue(model.knownIdentities().isEmpty() && model.neverLinked().isEmpty(),
                "the model adopted something that only happened because core was broken: "
                        + model.knownIdentities() + " / " + model.neverLinked());
        assertTrue(model.redeemedCodes().isEmpty(), model.redeemedCodes()::toString);
        assertTrue(world.linkedRefs().isEmpty(), world.linkedRefs()::toString);
    }

    @Test
    @DisplayName("a stale-credential attempt writes nothing, whatever core says")
    void staleCredentialRecordsNothing() {
        World world = world();
        ShadowModel model = new ShadowModel();

        Simulation.execute(
                new Action(ActionKind.STALE_CREDENTIAL, ALEX, GAME, null),
                new ScriptedDriver(CoreDriver.Result.ok(null)), world, model);

        assertTrue(model.knownIdentities().isEmpty() && model.neverLinked().isEmpty(),
                model.neverLinked()::toString);
        assertTrue(model.expectedAuditActions().isEmpty(),
                model.expectedAuditActions()::toString);
    }

    // --- the read-only ones ---------------------------------------------------

    @Test
    @DisplayName("describe and decide change nothing, because they change nothing in core")
    void readsRecordNothing() {
        for (ActionKind kind : List.of(ActionKind.DESCRIBE, ActionKind.DECIDE)) {
            World world = world();
            ShadowModel model = new ShadowModel();

            Simulation.execute(
                    new Action(kind, ALEX, GAME, null),
                    new ScriptedDriver(CoreDriver.Result.ok(null)), world, model);

            assertTrue(model.knownIdentities().isEmpty() && model.neverLinked().isEmpty(),
                    kind + " wrote to the model; a read that teaches the oracle something"
                            + " makes the oracle agree with whatever core just said");
            assertTrue(model.expectedAuditActions().isEmpty(), kind::toString);
        }
    }

    @Test
    @DisplayName("a decide with no gate named falls back to one the world knows")
    void decideFallsBackToAKnownGate() {
        ScriptedDriver driver = new ScriptedDriver(CoreDriver.Result.ok(null));

        Simulation.execute(
                new Action(ActionKind.DECIDE, ALEX, GAME, null),
                driver, world(), new ShadowModel());

        assertTrue(driver.calls.contains("decide game.join"),
                "the gate asked about is not one this world has, so the answer says nothing"
                        + " about the deployment under test: " + driver.calls);
    }

    @Test
    @DisplayName("a world with no gates still asks about something, rather than crashing")
    void decideWithNoGatesAtAll() {
        ScriptedDriver driver = new ScriptedDriver(CoreDriver.Result.ok(null));

        Simulation.execute(
                new Action(ActionKind.DECIDE, ALEX, GAME, null),
                driver, new World(List.of(ALEX), List.of()), new ShadowModel());

        assertTrue(driver.calls.contains("decide gate.unknown"), driver.calls::toString);
    }
}
