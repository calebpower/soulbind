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
 * The write side: turning an {@link Action} into something core is asked to do.
 *
 * <p>An interface for the same reason {@link CoreView} is one. The simulation
 * loop — generate, execute, update the model, check — is the part most likely to
 * be subtly wrong, and it is testable in milliseconds against an in-memory core
 * or not testable at all until a session is available. Those are the only two
 * options and the first is better.
 *
 * <p>It also makes the acceptance test possible without reverting anything: an
 * in-memory core with a defect deliberately switched on must be caught by a
 * hunting run, and that is the same question §14's gate asks of the real one.
 */
public interface CoreDriver {

    /**
     * What core did with an attempt.
     *
     * @param accepted whether core allowed it
     * @param value the meaningful result — a code, a subject id, an effect —
     *     or null when there is none
     * @param detail core's own words, for the trace
     */
    record Result(boolean accepted, String value, String detail, boolean codeConsumed) {

        public static Result ok(String value) {
            return new Result(true, value, "ok", true);
        }

        public static Result refused(String detail) {
            return new Result(false, null, detail, false);
        }

        /**
         * Refused, and the code was spent anyway.
         *
         * <p>Core claims a link code even when it declines the link: "it was
         * used, and re-offering it would let the same collision be retried
         * indefinitely". A caller that treats a refusal as leaving the code
         * outstanding will offer it again forever — measured at 132 of 234
         * refusals in one run being `already-redeemed` for codes the tier kept
         * proposing.
         */
        public static Result refusedAndSpent(String detail) {
            return new Result(false, null, detail, true);
        }
    }

    /**
     * The display name this driver writes for an actor.
     *
     * <p>Asked rather than assumed. The model has to record exactly what went on
     * the wire, and a second copy of the rule in the executor would be a second
     * chance to disagree — the round-trip invariant would then be comparing the
     * model's idea of the name against core's, with the actual sent value in
     * neither.
     */
    String displayFor(Actor actor);

    /** Mint a code for an account this actor vouches for. Value is the code. */
    Result issueCode(Actor actor, String platformKind, String platformId);

    /** Redeem a code as another account. Value is the subject id. */
    Result redeemCode(Actor actor, String code, String platformKind, String platformId);

    /** Ask what an identity is linked to. */
    Result describe(Actor actor, String platformKind, String platformId);

    /** Ask a gate. Value is the effect. */
    Result decide(Actor actor, String gate, String platformKind, String platformId);

    /** Write a rule for a gate. */
    Result setRule(Actor actor, String gate, boolean requireLinked);

    /** Change a runtime configuration value. */
    Result setConfig(Actor actor, String key, String value);

    /**
     * Attempt something with a credential this actor should no longer be using.
     *
     * <p><b>A known approximation, and it is stated rather than glossed.</b> §11
     * describes this class as "a second tab holding a retired session ticket".
     * soulbind has no credential rotation — it is a Phase 10 deliverable that
     * does not exist yet — so nothing can actually retire a credential, and what
     * this sends is a credential that was never valid.
     *
     * <p>That still tests the property §11 asks for: the attempt must produce a
     * <em>refusal, not a crash</em>, and must not disturb the live session. What
     * it does not test is the retirement path specifically — whether a
     * previously-good credential stops working at the moment it should. That
     * gap closes when rotation lands, and this method is where it will land.
     */
    Result withRetiredCredential(Actor actor, String platformKind, String platformId);
}
