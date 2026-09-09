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

package dev.soulbind.policy;

import java.util.Objects;

/**
 * A threshold a rule places on a reported measure.
 *
 * <p>Core never learns what the number counts. It is told a name, a value, the
 * window the value covers and when it was observed, and it compares. That the
 * name happens to mean minutes of play is a convention between the connector
 * that reports it and the operator who wrote the rule.
 *
 * @param name which measure, as the reporting connector names it.
 * @param atLeast the threshold, <b>inclusive</b>. A value exactly at it
 *     satisfies the rule — one convention per direction, so an operator who
 *     learns it once knows it everywhere.
 * @param windowSeconds the window the observation must cover, matched
 *     <b>exactly</b>. Not "at least this long": a longer window over-counts and
 *     a shorter one under-counts, and neither is a safe direction to guess. A
 *     reporter misconfigured to thirty days would otherwise satisfy a seven-day
 *     rule with nothing anywhere failing — it would simply grant to people who
 *     had not earned it, silently and forever.
 * @param maxAgeSeconds how old an observation may be and still count. Required
 *     to be positive: a requirement with no freshness bound is one whose answer
 *     survives the reporter disappearing, and there is no timer here to notice.
 */
public record MeasureRequirement(
        String name, long atLeast, long windowSeconds, long maxAgeSeconds) {

    public MeasureRequirement {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a measure requirement must name a measure");
        }
        if (atLeast < 0) {
            throw new IllegalArgumentException(
                    "atLeast must not be negative; every value satisfies a negative threshold, "
                            + "which is a rule that requires nothing while appearing to");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        if (maxAgeSeconds <= 0) {
            throw new IllegalArgumentException(
                    "maxAgeSeconds must be positive; without it an observation never goes stale "
                            + "and the rule keeps answering from evidence nobody is refreshing");
        }
    }
}
