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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The oracle self-test: does each invariant complain when core is broken?
 *
 * <p>§14 requires this <b>before</b> the harness that uses it, and the ordering
 * is the point. A simulated-user run that finds nothing is indistinguishable
 * from a set of invariants that cannot find anything, and the second is far
 * more likely — it is this repository's most-repeated defect, in a tier where
 * it would be hardest to notice, because "hundreds of actions, no violations"
 * reads like success.
 *
 * <p>So each invariant is fed the response a broken core would send, modelled
 * on the Tier 4 matrix's failure modes, and must complain. Each is also fed a
 * <b>healthy</b> core and must stay silent: an invariant that fires on
 * everything catches every fault and is worth nothing, and without the control
 * it would score identically to a good one.
 *
 * <p>No server, no socket, no fixtures on disk. {@link CoreView} makes "what
 * core said" a value a test can fabricate, which is what lets the whole file
 * run in milliseconds.
 */
class OracleSelfTest {

    private static final String GAME = "game:alex";
    private static final String CHAT = "chat:alex";
    private static final String FORUM = "forum:alex";

    /** A model in which the game and chat accounts were linked, once. */
    private static ShadowModel linkedModel() {
        ShadowModel model = new ShadowModel();
        model.linked(GAME, CHAT);
        return model;
    }

    private static List<String> check(Invariant invariant, ShadowModel model, CoreView core) {
        List<String> complaints = invariant.check(model, core);
        assertNotNull(complaints, invariant.name() + " returned null instead of a complaint list");
        return complaints;
    }

    // --- the controls --------------------------------------------------------

    @Test
    @DisplayName("every invariant stays silent against a healthy core")
    void healthyCoreProducesNoComplaints() {
        ShadowModel model = linkedModel();
        model.redeemed("BCDF2345");

        // A RULE, so `decisions-follow-the-rules` actually runs here. Without
        // one it iterates an empty map and this control says nothing about it
        // -- the invariant would stay silent against a healthy core and against
        // a broken one, and the control could not tell.
        model.ruleSet("game.join", true);

        FakeCore core = new FakeCore()
                .linked(GAME, CHAT)
                .audited("identity.linked")
                // A healthy core refuses an account it has never heard of at a
                // gate requiring linkage. Scripted rather than defaulted: the
                // default is now "could not be asked", which would make this
                // control pass by the invariant declining to look.
                .decides("game.join", "game:soulbind-sim-never-linked-probe", "deny");

        for (Invariant invariant : Invariants.all()) {
            assertEquals(List.of(), check(invariant, model, core),
                    () -> invariant.name() + " complained about a core that is behaving."
                            + " An invariant that fires on everything catches every fault"
                            + " and is worth nothing.");
        }
    }

    @Test
    @DisplayName("every invariant has a name and says what it checks")
    void everyInvariantIntroducesItself() {
        for (Invariant invariant : Invariants.all()) {
            assertFalse(invariant.name().isBlank(), "an invariant has no name");
            assertFalse(invariant.describes().isBlank(),
                    invariant.name() + " does not say what it checks, so a run that fails on"
                            + " it tells the reader only a slug");
        }
        assertEquals(Invariants.all().size(),
                Invariants.all().stream().map(Invariant::name).distinct().count(),
                "two invariants share a name, so a report cannot tell them apart");
    }

    // --- one broken core per invariant ---------------------------------------

    @Test
    @DisplayName("a link that vanished is caught")
    void linkageMirrorsModelComplains() {
        // The failure mode: an actor linked two accounts, core acknowledged, and
        // the link is not there afterwards. The most consequential thing this
        // system can get wrong and the least visible -- nobody notices until the
        // person is refused at a gate they should pass.
        FakeCore core = new FakeCore().linked(GAME, CHAT).forgot(CHAT);

        List<String> complaints =
                check(Invariants.linkageMirrorsModel(), linkedModel(), core);

        assertFalse(complaints.isEmpty(), "a vanished link was not noticed");
        assertTrue(complaints.stream().anyMatch(c -> c.contains(CHAT)),
                () -> "the complaint does not name the identity that vanished: " + complaints);
    }

    @Test
    @DisplayName("an identity nobody linked is caught")
    void coreInventsNoLinksComplains() {
        // The other direction, and the more alarming one: core has attached a
        // stranger to somebody's subject. linkage-mirrors-model alone would miss
        // it -- the accounts the model linked ARE all present.
        FakeCore core = new FakeCore().linked(GAME, CHAT).alsoOnSubjectOf(GAME, FORUM);

        List<String> complaints =
                check(Invariants.coreInventsNoLinks(), linkedModel(), core);

        assertFalse(complaints.isEmpty(), "an identity nobody linked was not noticed");
        assertTrue(complaints.stream().anyMatch(c -> c.contains(FORUM)),
                () -> "the complaint does not name the stranger: " + complaints);
    }

    @Test
    @DisplayName("a missing audit row is caught")
    void everyMutationIsAuditedComplains() {
        ShadowModel model = linkedModel();
        model.mutated("identity.unlinked");
        // Core audited the link and not the unlink.
        FakeCore core = new FakeCore().linked(GAME, CHAT).audited("identity.linked");

        List<String> complaints = check(Invariants.everyMutationIsAudited(), model, core);

        assertFalse(complaints.isEmpty(), "an unaudited mutation was not noticed");
        assertTrue(complaints.stream().anyMatch(c -> c.contains("identity.unlinked")),
                () -> "the complaint does not name the unaudited action: " + complaints);
    }

    @Test
    @DisplayName("a repeated audit sequence is caught")
    void auditSequenceComplainsOnDuplicates() {
        FakeCore core = new FakeCore()
                .auditedAtSequence(1, "identity.linked")
                .auditedAtSequence(1, "identity.linked");

        List<String> complaints =
                check(Invariants.auditSequenceStrictlyIncreases(), linkedModel(), core);

        assertFalse(complaints.isEmpty(), "two rows claiming one sequence was not noticed");
    }

    @Test
    @DisplayName("an audit log that goes backwards is caught")
    void auditSequenceComplainsOnRegression() {
        // Distinct from the duplicate case: a log that goes 1, 5, 3 has been
        // rewritten rather than merely double-written, and the duplicate check
        // alone would pass it.
        FakeCore core = new FakeCore()
                .auditedAtSequence(1, "identity.linked")
                .auditedAtSequence(5, "identity.linked")
                .auditedAtSequence(3, "identity.linked");

        List<String> complaints =
                check(Invariants.auditSequenceStrictlyIncreases(), linkedModel(), core);

        assertFalse(complaints.isEmpty(), "an audit log that went backwards was not noticed");
    }

    @Test
    @DisplayName("a code offered after redemption is caught")
    void redeemedCodesStayRedeemedComplains() {
        ShadowModel model = linkedModel();
        model.redeemed("BCDF2345");
        FakeCore core = new FakeCore().linked(GAME, CHAT).offersCode("BCDF2345");

        List<String> complaints = check(Invariants.redeemedCodesStayRedeemed(), model, core);

        assertFalse(complaints.isEmpty(), "a spent code still on offer was not noticed");
        assertTrue(complaints.stream().anyMatch(c -> c.contains("BCDF2345")),
                () -> "the complaint does not name the code: " + complaints);
    }

    @Test
    @DisplayName("a decide core CAN answer is judged, not skipped as unaskable")
    void answerableDecisionsAreJudged() {
        // `effect.isEmpty()` means core could not be asked, and the envelope
        // invariant reports that separately. Inverting the test would skip
        // every answer core DID give and judge only the silences -- so this
        // invariant would run hundreds of times, complain about nothing, and
        // look exactly like a healthy deployment.
        ShadowModel model = new ShadowModel();
        model.sawUnlinked(GAME);
        model.ruleSet("game.join", true);

        // The probe silenced, so the only thing left to complain about is the
        // account the model named -- which is the path this test is for.
        FakeCore core = new FakeCore()
                .decides("game.join", GAME, "allow")
                .decides("game.join", "game:soulbind-sim-never-linked-probe", "deny");

        List<String> complaints =
                check(Invariants.decisionsFollowTheRules(), model, core);

        assertTrue(complaints.stream().anyMatch(c -> c.contains(GAME)),
                () -> "core admitted an unlinked account the model knows about, through a"
                        + " gate that requires a link, and the invariant said nothing about"
                        + " that account: " + complaints);
    }

    @Test
    @DisplayName("an unaskable gate is NOT turned into a policy verdict")
    void unaskableGatesAreLeftAlone() {
        // The other side of the same conditional, and the reason it exists: an
        // outage must be reported once, by the invariant that watches the
        // transport, and not a second time as though core had made a decision.
        ShadowModel model = new ShadowModel();
        model.sawUnlinked(GAME);
        model.ruleSet("game.join", true);

        // BOTH questions this invariant asks have to go unanswered, or the
        // other one complains and says nothing about the branch under test:
        // the probe account it invents, and the unlinked account from the
        // model. FakeCore answers "allow" by default, which is worth knowing on
        // its own -- a test that forgets to script a decision gets a permissive
        // answer rather than a missing one.
        FakeCore silent = new FakeCore()
                .decides("game.join", GAME, "")
                .decides("game.join", "game:soulbind-sim-never-linked-probe", "");

        List<String> complaints =
                check(Invariants.decisionsFollowTheRules(), model, silent);

        assertTrue(complaints.isEmpty(),
                () -> "a gate core could not answer was reported as a policy failure: "
                        + complaints);
    }

    @Test
    @DisplayName("a 5xx is caught even when everything else agrees")
    void envelopeInvariantComplainsIndependently() {
        // The cheap oracle earns its place here. The model and core agree
        // perfectly; a 500 went past on the way; every other invariant is
        // silent, and this one is not.
        FakeCore core = new FakeCore()
                .linked(GAME, CHAT)
                .audited("identity.linked")
                .unreachable("HTTP 500 from code.redeem");

        ShadowModel model = linkedModel();
        for (Invariant invariant : Invariants.all()) {
            List<String> complaints = check(invariant, model, core);
            if (invariant.name().equals("every-response-was-an-envelope")) {
                assertFalse(complaints.isEmpty(), "a 5xx was not noticed");
                assertTrue(complaints.get(0).contains("500"),
                        () -> "the complaint does not say what went wrong: " + complaints);
            } else {
                assertEquals(List.of(), complaints,
                        () -> invariant.name() + " complained about a 5xx that is not its"
                                + " business; the cheap oracle exists so the others do not"
                                + " have to care");
            }
        }
    }

    // --- the properties §11 asks of the invariants themselves ----------------

    @Test
    @DisplayName("no invariant throws, however hostile the answers")
    void complaintsAreReturnedNotThrown() {
        // "Stackless" is the requirement. A checker that throws reports where it
        // noticed, which is never interesting, and stops at the first violation
        // when the shape of the whole set is the evidence.
        ShadowModel model = new ShadowModel();
        model.linked("weird", "no-colon-here");
        model.redeemed("");
        model.mutated("");

        FakeCore core = new FakeCore().unreachable("everything is on fire");

        for (Invariant invariant : Invariants.all()) {
            List<String> complaints = invariant.check(model, core);
            assertNotNull(complaints, invariant.name() + " returned null");
        }
    }

    @Test
    @DisplayName("the whole set answers in well under a second")
    void theSetIsFastEnoughToRunOften() {
        // §11 asks for a complaint "in about a second". The bound is generous on
        // purpose -- this is not a benchmark, it is a guard against an invariant
        // that quietly starts asking core something once per identity. A checker
        // that runs periodically through a long simulation has to be cheap or it
        // will be run less often, and then it stops being periodic.
        ShadowModel model = new ShadowModel();
        for (int i = 0; i < 500; i++) {
            model.linked("game:p" + i, "chat:p" + i);
        }
        FakeCore core = new FakeCore();
        for (int i = 0; i < 500; i++) {
            core.linked("game:p" + i, "chat:p" + i).audited("identity.linked");
        }

        long start = System.nanoTime();
        for (Invariant invariant : Invariants.all()) {
            invariant.check(model, core);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 1_000,
                () -> "the invariant set took " + millis + "ms over 500 linked pairs. It runs"
                        + " periodically during a simulation, so a checker this slow gets run"
                        + " less often and stops being periodic.");
    }
}
