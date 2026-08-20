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

/**
 * What an actor can choose to do, and how often.
 *
 * <p><b>The nemesis classes are in this enum, not in a separate mode.</b> §11 is
 * explicit: "the nemesis is not a separate mode; it is a set of actions in the
 * same weighted pool, so faults land at arbitrary depths in an accumulated
 * history rather than against a clean fixture". A stale credential presented on
 * action three against two rows of state is a unit test. The same credential on
 * action three hundred, against a graph that has been merged, unlinked and
 * re-linked, is the thing no other tier can construct.
 *
 * <p>Two of the six classes §11 names are deferred with the shrinker — hostile
 * corpus input, which Tier 7 drives at the same endpoints, and double redeem,
 * which the Phase 2 gate proves under real concurrency. Departure 9 and
 * DECISIONS 9.1 carry the reasoning. The four here are the four whose defects
 * <em>require</em> accumulated history.
 */
public enum ActionKind {

    /** Ask for a code for an account this actor owns. */
    ISSUE_CODE(10, false),

    /** Redeem an outstanding code from another platform's account. */
    REDEEM_CODE(10, false),

    /** Ask what an identity is linked to. */
    DESCRIBE(6, false),

    /** Set a rule, which changes what a gate decides. */
    SET_RULE(3, false),

    /** Ask a gate for a decision. */
    DECIDE(8, false),

    // --- nemesis, in the same pool ------------------------------------------

    /**
     * Act with a credential this actor has already rotated away from.
     *
     * <p>A second tab holding a retired session ticket. Interesting only at
     * depth: the credential must have been rotated some actions ago, and the
     * refusal must not disturb the live session.
     */
    STALE_CREDENTIAL(2, true),

    /**
     * Act on an identity that is not linked to anything.
     *
     * <p>A page rendered before somebody else unlinked its subject. The
     * interesting case is an identity unlinked long before the connector acts.
     */
    ACT_ON_UNLINKED(2, true),

    /**
     * Change runtime configuration while a flow is in progress.
     *
     * <p>Nothing else in the battery moves the ground under an in-flight
     * operation.
     */
    CONFIG_FLIP(1, true),

    /**
     * Issue a code and never redeem it.
     *
     * <p>Half-finished work left behind. Codes accumulating unredeemed over a
     * long run is a state no other tier constructs, and expiry, purging and the
     * bounded-store behaviour all live there.
     */
    ABANDON_CODE(2, true);

    private final int weight;
    private final boolean nemesis;

    ActionKind(int weight, boolean nemesis) {
        this.weight = weight;
        this.nemesis = nemesis;
    }

    /** Relative likelihood of being chosen, among those currently applicable. */
    public int weight() {
        return weight;
    }

    /** Whether this is an adversarial class. Reporting only; it changes no behaviour. */
    public boolean isNemesis() {
        return nemesis;
    }
}
