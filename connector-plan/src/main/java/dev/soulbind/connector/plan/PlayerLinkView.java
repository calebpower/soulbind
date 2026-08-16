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

package dev.soulbind.connector.plan;

import java.util.List;
import java.util.Optional;

/**
 * Everything a dashboard shows about one player's links.
 *
 * <p>A record rather than a set of provider calls, because Plan asks for each
 * value separately and a provider per question would be a round trip per
 * question. This is gathered once and read many times.
 *
 * <p><b>Unknown is a state, not a default.</b> {@code known == false} means
 * core could not be reached, and it is deliberately distinct from a player who
 * is genuinely unlinked. A dashboard that renders "not linked" during an outage
 * tells an operator to go and chase somebody who may be linked perfectly well —
 * the same distinction the gates keep, for the same reason.
 *
 * @param known whether core answered at all
 * @param linked whether this account belongs to a subject with links
 * @param subjectId shown only when the operator has opted in; see the config
 * @param kinds the platform kinds this subject is verified on, sorted
 * @param proofMethods how each link was proven, sorted and deduplicated
 * @param verifiedAtEpochSeconds the EARLIEST verification, or empty
 */
public record PlayerLinkView(
        boolean known,
        boolean linked,
        Optional<String> subjectId,
        List<String> kinds,
        List<String> proofMethods,
        Optional<Long> verifiedAtEpochSeconds) {

    public PlayerLinkView {
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        proofMethods = proofMethods == null ? List.of() : List.copyOf(proofMethods);
    }

    /** Core did not answer. Not the same as "this player has no links". */
    public static PlayerLinkView unknown() {
        return new PlayerLinkView(
                false, false, Optional.empty(), List.of(), List.of(), Optional.empty());
    }

    /** Core answered, and this account belongs to no subject. */
    public static PlayerLinkView unlinked() {
        return new PlayerLinkView(
                true, false, Optional.empty(), List.of(), List.of(), Optional.empty());
    }

    /**
     * What a dashboard should print for the link state.
     *
     * <p>Three words for three states. "No" for an outage would be a lie, and
     * the lie is the expensive one: it sends somebody to fix a link that is not
     * broken.
     */
    public String describe() {
        if (!known) {
            return "unknown (core unreachable)";
        }
        return linked ? "linked" : "not linked";
    }
}
