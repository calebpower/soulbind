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

/**
 * The world the generator draws from.
 *
 * <p>Every method here answers "what is worth doing next", so a wrong answer
 * does not fail a run — it quietly narrows one. A world that reports everybody
 * linked stops proposing links; one that reports a spent code as outstanding
 * spends the rest of the run on an answer that cannot change. Both look like a
 * clean pass.
 *
 * <p>Nothing tested any of it directly. DECISIONS 10.32.
 */
class WorldTest {

    private static final String GAME = "game:alex";
    private static final String CHAT = "chat:alex";
    private static final String OTHER = "game:sam";

    private static final Actor ALEX = new Actor("alex", List.of(GAME, CHAT), 0);
    private static final Actor SAM = new Actor("sam", List.of(OTHER), 0);

    private static World world() {
        return new World(new java.util.ArrayList<>(List.of(ALEX, SAM)), List.of("game.join"));
    }

    @Test
    @DisplayName("unlinkedRefs answers only what is still unlinked")
    void unlinkedRefsExcludesTheLinked() {
        World world = world();
        assertEquals(List.of(GAME, CHAT, OTHER), world.unlinkedRefs());

        world.linked(GAME, CHAT);

        assertEquals(
                List.of(OTHER), world.unlinkedRefs(),
                "the world still offers linked accounts for linking, so the generator spends"
                        + " its draws re-linking what is already one subject");
    }

    @Test
    @DisplayName("a world with everything linked offers nothing, rather than offering everything")
    void everythingLinked() {
        // The inverted form of the same conditional. A world that answered
        // "all of them" here would keep the run busy and prove nothing.
        World world = world();
        world.linked(GAME, CHAT);
        world.linked(OTHER, OTHER);

        assertTrue(world.unlinkedRefs().isEmpty(), world.unlinkedRefs()::toString);
    }

    @Test
    @DisplayName("spending a code moves it from outstanding to spent")
    void codeSpentMovesIt() {
        World world = world();
        world.codeIssued("BCDFGHJK", GAME);

        world.codeSpent("BCDFGHJK");

        assertFalse(world.outstandingCodes().containsKey("BCDFGHJK"),
                "a spent code is still on offer");
        assertEquals(List.of("BCDFGHJK"), world.spentCodes(),
                "the spent code is not recorded, so the double-redeem action -- the one that"
                        + " proves core refuses a second claim -- can never be generated");
    }

    @Test
    @DisplayName("spending the same code twice records it once")
    void codeSpentIsIdempotent() {
        World world = world();
        world.codeIssued("BCDFGHJK", GAME);

        world.codeSpent("BCDFGHJK");
        world.codeSpent("BCDFGHJK");

        assertEquals(List.of("BCDFGHJK"), world.spentCodes(), world.spentCodes()::toString);
    }

    @Test
    @DisplayName("spending a code the world never issued records nothing")
    void spendingAnUnknownCode() {
        // Both halves of the guard. A world that recorded any string handed to
        // it would offer the double-redeem action codes core has never seen,
        // and core refusing those proves nothing about a SECOND claim.
        World world = world();

        world.codeSpent("NEVERSEEN");

        assertTrue(world.spentCodes().isEmpty(), world.spentCodes()::toString);
    }

    @Test
    @DisplayName("rotating an actor rotates that actor, and only that actor")
    void rotationIsTargeted() {
        World world = world();

        world.rotated(ALEX);

        Actor alex = world.actors().stream()
                .filter(a -> a.name().equals("alex")).findFirst().orElseThrow();
        Actor sam = world.actors().stream()
                .filter(a -> a.name().equals("sam")).findFirst().orElseThrow();

        assertEquals(1, alex.credentialGeneration(),
                "the actor whose credential was rotated still holds the old generation, so"
                        + " the stale-credential action can never be generated for them");
        assertEquals(0, sam.credentialGeneration(),
                "an actor nobody rotated was rotated anyway, which retires a credential the"
                        + " run then wrongly expects to be refused");
    }

    @Test
    @DisplayName("only rotated actors hold a stale credential")
    void staleCredentialsFollowRotation() {
        World world = world();
        assertTrue(world.actorsWithAStaleCredential().isEmpty(),
                "somebody held a stale credential before anything was rotated");

        world.rotated(ALEX);

        assertEquals(
                List.of("alex"),
                world.actorsWithAStaleCredential().stream().map(Actor::name).toList(),
                world.actorsWithAStaleCredential()::toString);
    }
}
