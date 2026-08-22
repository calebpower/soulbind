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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generator's two promises: reproducible, and never proposing the
 * impossible.
 */
class GeneratorTest {

    private static World worldTagged(String tag) {
        return new World(
                List.of(
                        new Actor("alex", List.of("game:alex" + tag, "chat:alex" + tag), 0),
                        new Actor("sam", List.of("forum:sam" + tag, "chat:sam" + tag), 0)),
                List.of("game.join", "forum.post"));
    }

    /** Drives the generator, feeding results back so the world actually moves. */
    private static List<Action> drive(long seed, World world, int steps) {
        Generator generator = new Generator(seed);
        List<Action> taken = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            var next = generator.next(world);
            if (next.isEmpty()) {
                break;
            }
            Action action = next.get();
            taken.add(action);
            apply(world, action, i);
        }
        return taken;
    }

    /** A stand-in executor: moves the world the way a real one would. */
    private static void apply(World world, Action action, int step) {
        switch (action.kind()) {
            case ISSUE_CODE, ABANDON_CODE ->
                    world.codeIssued("CODE" + step, action.subject());
            case REDEEM_CODE, REDEEM_FOREIGN -> {
                String issuedFor = world.outstandingCodes().get(action.subject());
                world.codeSpent(action.subject());
                if (issuedFor != null && action.detail() != null) {
                    world.linked(issuedFor, action.detail());
                }
            }
            case CONFIG_FLIP -> world.rotated(action.actor());
            default -> { }
        }
    }

    @Test
    @DisplayName("the same seed produces the same run, exactly")
    void sameSeedSameRun() {
        // The whole point of a seed. Without this a failing run is a story about
        // something that happened once, and nobody can be handed it.
        List<Action> first = drive(20260820L, worldTagged("-a"), 200);
        List<Action> second = drive(20260820L, worldTagged("-a"), 200);

        assertEquals(first.toString(), second.toString(),
                "one seed produced two different runs");
        assertTrue(first.size() > 100, "the run stopped early and proved little");
    }

    @Test
    @DisplayName("a different seed produces a different run")
    void differentSeedDifferentRun() {
        // Otherwise the seed is decoration and every run explores one path.
        List<Action> first = drive(1L, worldTagged("-a"), 200);
        List<Action> second = drive(2L, worldTagged("-a"), 200);

        assertNotEquals(first.toString(), second.toString(),
                "two seeds produced identical runs, so the seed does nothing");
    }

    @Test
    @DisplayName("the per-run tag does not disturb the sequence")
    void theRunTagIsOutsideTheSeededStream() {
        // §11's subtlest requirement: "anything that must vary between runs -- a
        // tag distinguishing this process's data from an earlier run's -- is
        // drawn OUTSIDE the seeded stream, so replay reproduces the action
        // sequence exactly".
        //
        // If the tag came from the seeded PRNG, replaying a seed would either
        // collide with the original run's rows or shift every subsequent draw.
        // This is the most common way a "reproducible" generator turns out not
        // to be, and it is invisible until somebody tries to replay one.
        //
        // Asserted on the KINDS, because the refs necessarily embed the tag.
        List<ActionKind> withOneTag = drive(99L, worldTagged("-run1"), 200)
                .stream().map(Action::kind).toList();
        List<ActionKind> withAnother = drive(99L, worldTagged("-run2"), 200)
                .stream().map(Action::kind).toList();

        assertEquals(withOneTag, withAnother,
                "changing the per-run tag changed the action sequence, so the tag is being"
                        + " drawn from the seeded stream and no seed can be replayed against"
                        + " a fresh database");
    }

    @Test
    @DisplayName("nothing impossible is ever proposed")
    void onlyApplicableActions() {
        World world = worldTagged("-c");
        Generator generator = new Generator(4242L);

        for (int i = 0; i < 400; i++) {
            var next = generator.next(world);
            if (next.isEmpty()) {
                break;
            }
            Action action = next.get();

            if (action.kind() == ActionKind.REDEEM_CODE) {
                assertTrue(world.outstandingCodes().containsKey(action.subject()),
                        () -> "proposed redeeming " + action.subject() + ", which is not"
                                + " outstanding. A draw spent on something the executor must"
                                + " refuse makes the weights stop meaning what they say.");
            }
            if (action.kind() == ActionKind.STALE_CREDENTIAL) {
                assertFalse(world.actorsWithAStaleCredential().isEmpty(),
                        "proposed using a stale credential when nobody has rotated");
            }
            if (action.kind() == ActionKind.ACT_ON_UNLINKED) {
                assertTrue(world.unlinkedRefs().contains(action.subject()),
                        () -> "proposed acting on " + action.subject() + " as unlinked when"
                                + " it is linked");
            }
            apply(world, action, i);
        }
    }

    @Test
    @DisplayName("an ordinary redeem links one person's own accounts")
    void redeemsAreSamePerson() {
        // The tier's whole subject is a cross-platform identity GRAPH, and an
        // earlier generator let any actor redeem any actor's code. Every redeem
        // then merged two arbitrary components, the graph closed transitively,
        // and every simulated person became the same person -- measured at seven
        // of nine identities on one subject by action 50, and thirty-two of
        // thirty-six with twelve actors.
        //
        // A collapsed graph is not a smaller test, it is a different one: there
        // are no distinct subjects left, so nothing can exercise two people
        // colliding over one account, and every defect becomes reachable on the
        // first link.
        //
        // Linking your own accounts is also simply what linking IS. Somebody
        // else redeeming your code is REDEEM_FOREIGN, weighted low, and it is a
        // conflict rather than a link.
        World world = worldTagged("-shape");
        Generator generator = new Generator(20260820L);
        int checked = 0;

        for (int i = 0; i < 400; i++) {
            var next = generator.next(world);
            if (next.isEmpty()) {
                break;
            }
            Action action = next.get();
            if (action.kind() == ActionKind.REDEEM_CODE) {
                String issuedFor = world.outstandingCodes().get(action.subject());
                assertNotNull(issuedFor, "a redeem was proposed for a code nobody issued");
                Actor owner = null;
                for (Actor candidate : world.actors()) {
                    if (candidate.identities().contains(issuedFor)) {
                        owner = candidate;
                    }
                }
                assertNotNull(owner, "nobody owns " + issuedFor);
                final Actor finalOwner = owner;
                assertTrue(finalOwner.identities().contains(action.detail()),
                        () -> "a plain redeem links " + issuedFor + " to " + action.detail()
                                + ", which belongs to somebody else. That merges two people"
                                + " into one subject; the graph closes transitively and there"
                                + " is nothing left to explore. Somebody else's redeem is"
                                + " REDEEM_FOREIGN.");
                checked++;
            }
            apply(world, action, i);
        }

        final int redeemsChecked = checked;
        assertTrue(redeemsChecked > 5,
                () -> "only " + redeemsChecked + " ordinary redeems occurred, so this"
                        + " asserted almost nothing");
    }

    @Test
    @DisplayName("nemesis actions actually land, at depth")
    void theNemesisIsNotDecorative() {
        // A pool containing adversarial classes that never get drawn is a pool
        // that documents an intention. §11 puts them in the same weighted pool
        // precisely so they arrive deep in an accumulated history, so the test
        // is both that they occur AND that they occur late.
        List<Action> run = drive(7L, worldTagged("-d"), 400);

        EnumSet<ActionKind> seenLate = EnumSet.noneOf(ActionKind.class);
        for (int i = run.size() / 2; i < run.size(); i++) {
            if (run.get(i).kind().isNemesis()) {
                seenLate.add(run.get(i).kind());
            }
        }

        assertFalse(seenLate.isEmpty(),
                "no nemesis action occurred in the second half of a 400-action run, so the"
                        + " adversarial classes are documenting an intention rather than"
                        + " landing at depth");
    }

    @Test
    @DisplayName("an actor holding nothing is never asked to act on one of its accounts")
    void anActorWithNoIdentities() {
        // DECIDE and DOUBLE_REDEEM both index into the actor's identities. An
        // actor can hold none -- the sim creates them, and a rotation can
        // arrive before a link does -- and proposing either would end the run
        // on an index out of bounds rather than on a finding.
        World world = new World(
                new java.util.ArrayList<>(List.of(new Actor("ghost", List.of(), 0))),
                List.of("game.join"));
        world.codeIssued("SPENTONE", "game:someone");
        world.codeSpent("SPENTONE");

        Generator generator = new Generator(20260820L);
        for (int i = 0; i < 200; i++) {
            var next = generator.next(world);
            if (next.isEmpty()) {
                break;
            }
            ActionKind kind = next.get().kind();
            assertTrue(
                    kind != ActionKind.DECIDE && kind != ActionKind.DOUBLE_REDEEM,
                    () -> kind + " was proposed for an actor that holds no identity at all");
        }
    }

    @Test
    @DisplayName("a double redeem is never proposed when no code has been spent")
    void noDoubleRedeemWithoutASpentCode() {
        // The action means "claim a code core has already claimed". With no
        // spent code there is nothing to claim twice, and proposing it anyway
        // would have the run send an unknown code, be refused for the wrong
        // reason, and count that refusal as the nemesis working.
        World world = new World(
                new java.util.ArrayList<>(List.of(
                        new Actor("alex", List.of("game:alex", "chat:alex"), 0))),
                List.of("game.join"));

        Generator generator = new Generator(20260820L);
        for (int i = 0; i < 200; i++) {
            var next = generator.next(world);
            if (next.isEmpty()) {
                break;
            }
            assertTrue(next.get().kind() != ActionKind.DOUBLE_REDEEM,
                    "a double redeem was proposed in a world where no code has been spent");
        }
    }

    @Test
    @DisplayName("a redeem never offers the account the code was issued for")
    void redeemNeverTargetsItsOwnAccount() {
        // Core refuses an account linking to itself, so proposing it spends a
        // draw on an answer that is known in advance -- and the refusal then
        // pads the refusal count with something that proves nothing.
        World world = new World(
                new java.util.ArrayList<>(List.of(
                        new Actor("alex", List.of("game:alex", "chat:alex"), 0))),
                List.of("game.join"));
        world.codeIssued("BCDFGHJK", "game:alex");

        Generator generator = new Generator(20260820L);
        for (int i = 0; i < 300; i++) {
            var next = generator.next(world);
            if (next.isEmpty()) {
                break;
            }
            Action action = next.get();
            if (action.kind() == ActionKind.REDEEM_CODE) {
                assertNotEquals("game:alex", action.detail(),
                        "a code issued for game:alex was offered back to game:alex");
            }
        }
    }
}
