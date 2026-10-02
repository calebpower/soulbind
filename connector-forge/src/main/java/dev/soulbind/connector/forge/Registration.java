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
package dev.soulbind.connector.forge;

import dev.soulbind.protocol.LinkCode;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import java.util.List;
import java.util.Optional;

/**
 * Admission to the forge, in two steps.
 *
 * <p><b>Link, then register.</b> Redeeming a code binds a forge identity to
 * whichever subject already held the code's other side; asking the gate decides
 * whether that subject may have an account. They are separate because core
 * consumes a code at redemption and a gate may refuse immediately afterwards —
 * somebody who linked and was refused for want of a second identity must be
 * able to come back and finish without a fresh code, which they could not do if
 * one call did both and failed as a unit.
 *
 * <p>Names no host type. Everything it needs of the forge is {@link
 * ForgeSurface}, so all of this runs against an in-memory transport and an
 * in-memory forge.
 */
public final class Registration {

    private final SoulbindClient client;
    private final ForgeSurface forge;
    private final String platformKind;
    private final String gate;

    /**
     * @param platformKind fixed here, never taken from a caller. A connector
     *     that accepted its kind per request could assert identities on any
     *     platform, which is the whole authority the capability model divides
     *     up.
     */
    public Registration(
            SoulbindClient client, ForgeSurface forge, String platformKind, String gate) {
        this.client = client;
        this.forge = forge;
        this.platformKind = platformKind;
        this.gate = gate;
    }

    /** What came of an attempt. */
    public sealed interface Outcome {

        /** A forge identity is now bound to a subject. */
        record Linked(String subjectId) implements Outcome {}

        /** The account exists. */
        record Created(String username) implements Outcome {}

        /**
         * Somebody was told no, and it was an answer rather than a failure.
         *
         * <p>{@code missingKinds} is what to tell them to go and do; it is
         * empty for refusals that are not about missing identities.
         */
        record Refused(String detail, List<String> missingKinds) implements Outcome {}

        /**
         * Nothing could be decided, and that is this system's fault.
         *
         * <p>Separate from {@link Refused} because collapsing the two turns
         * "you may not" into "try again later", or worse, the reverse. The
         * message blames the system rather than the person.
         */
        record Unavailable(String message) implements Outcome {}
    }

    /**
     * Redeems a code, binding this forge name to the subject that holds it.
     *
     * <p>The code is normalised here before travelling, using the same
     * {@code protocol} implementation core normalises with — not a second one.
     * It saves a round trip on input that could never match, and the shared
     * implementation is why "normalised here" and "normalised there" cannot
     * drift apart.
     */
    public Outcome link(String rawCode, String username) {
        Optional<String> code = LinkCode.normalise(rawCode);
        if (code.isEmpty()) {
            return new Outcome.Refused(
                    "that is not a link code. Check it against the one you were given -- "
                            + "they never contain a letter O or the digits 0 or 1.",
                    List.of());
        }

        Outcome taken = refuseIfNameUnavailable(username);
        if (taken != null) {
            return taken;
        }

        SoulbindClient.Outcome outcome =
                client.call("code.redeem", new RedeemBody(code.get(), platformKind, username));

        if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
            return new Outcome.Linked(ok.payload().text("subjectId"));
        }
        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            return new Outcome.Refused(refused.message(), List.of());
        }
        return new Outcome.Unavailable(DecisionCache.FAIL_CLOSED_MESSAGE);
    }

    /**
     * Asks the gate, and creates the account only if it allows.
     *
     * <p>Retryable: it consumes nothing, so somebody refused today can come
     * back once they have linked what they were missing, with no new code.
     */
    public Outcome register(ForgeSurface.Account account) {
        Outcome taken = refuseIfNameUnavailable(account.username());
        if (taken != null) {
            return taken;
        }

        DecisionCache.Answer answer = client.decide(gate, platformKind, account.username());

        if (!answer.decision().isAllowed()) {
            // The fail mode denying is not the gate denying. One means the
            // person has not met the requirements; the other means this system
            // could not find out, and telling somebody they are not allowed
            // because a server they have never heard of is unreachable is a lie.
            if (answer.source() == DecisionCache.Source.FAIL_MODE) {
                return new Outcome.Unavailable(answer.decision().detail());
            }
            return new Outcome.Refused(
                    answer.decision().detail(), answer.decision().missingKinds());
        }

        // The gate is asked BEFORE the account exists, deliberately. Creating
        // first and deleting on a refusal would leave the forge holding
        // accounts whenever the delete was the half that failed.
        return switch (forge.create(account)) {
            case CREATED -> new Outcome.Created(account.username());
            case ALREADY_EXISTS -> new Outcome.Refused(nameTaken(account.username()), List.of());
            case FAILED -> new Outcome.Unavailable(
                    "The forge could not be reached, so the account was not created. This is a "
                            + "problem on our side, not yours -- please try again shortly.");
        };
    }

    /** Null when the name may be used, an outcome when it may not. */
    private Outcome refuseIfNameUnavailable(String username) {
        return switch (forge.presence(username)) {
            case ABSENT -> null;
            case PRESENT -> new Outcome.Refused(nameTaken(username), List.of());
            // Not knowing is not the same as free. Treating it as free creates
            // an account over the top of one that may already be somebody's.
            case UNKNOWN -> new Outcome.Unavailable(
                    "The forge could not be asked whether that name is free, so nothing was "
                            + "changed. This is a problem on our side, not yours -- please try "
                            + "again shortly.");
        };
    }

    private static String nameTaken(String username) {
        return "the name '" + username + "' is already in use on the forge";
    }

    private record RedeemBody(String code, String platformKind, String platformId) {}
}
