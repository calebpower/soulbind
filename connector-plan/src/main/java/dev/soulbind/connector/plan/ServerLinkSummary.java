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

/**
 * The server-wide picture: how many are linked, and who is not.
 *
 * <p>{@code unknown} is counted separately from {@code unlinked} and is not
 * folded into either. Adding players core could not answer for to the unlinked
 * count would make an outage look like a sudden collapse in linking, which is
 * the shape of a problem an operator would go and investigate at length.
 *
 * @param linked players core confirmed are linked
 * @param unlinked players core confirmed are not
 * @param unknown players core could not be asked about
 * @param unlinkedNames the unlinked players, for the table, sorted -- the caller's
 *     roster has no promised iteration order, and a table that reshuffles between
 *     refreshes looks like data changing when nothing has
 */
public record ServerLinkSummary(
        int linked, int unlinked, int unknown, List<String> unlinkedNames) {

    public ServerLinkSummary {
        unlinkedNames = unlinkedNames == null ? List.of() : List.copyOf(unlinkedNames);
    }

    /** Everyone asked about. */
    public int total() {
        return linked + unlinked + unknown;
    }

    /**
     * The share linked, out of those core could answer for.
     *
     * <p>Deliberately excludes {@code unknown} from the denominator. Counting
     * unanswerable players as unlinked would show a percentage falling during
     * an outage — a number that moves for a reason that has nothing to do with
     * linking is worse than no number.
     */
    public double linkedFraction() {
        int answered = linked + unlinked;
        return answered == 0 ? 0.0 : (double) linked / answered;
    }
}
