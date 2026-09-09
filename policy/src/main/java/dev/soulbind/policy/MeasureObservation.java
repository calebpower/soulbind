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

import java.time.Instant;
import java.util.Objects;

/**
 * One reported measurement, as core holds it.
 *
 * <p>Carries its own provenance because a bare number cannot be judged. The
 * window says what the value covers, so a rule can refuse one that does not
 * match; the observation time says how much to believe it, so a rule can refuse
 * one nobody has refreshed.
 *
 * @param value what was reported.
 * @param windowSeconds the window it covers, as the REPORTER understands it.
 * @param observedAt when core recorded it, from core's clock. Never the
 *     caller's: freshness a connector can assert is freshness anybody can
 *     extend, which is the reasoning that keeps {@code firstSeenAt} out of
 *     requests too.
 */
public record MeasureObservation(long value, long windowSeconds, Instant observedAt) {

    public MeasureObservation {
        Objects.requireNonNull(observedAt, "observedAt");
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
    }
}
