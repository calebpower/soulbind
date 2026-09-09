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

package dev.soulbind.protocol;

/**
 * One observation core holds, on the wire.
 *
 * <p><b>No staleness flag, deliberately.</b> Staleness is {@code maxAgeSeconds},
 * which belongs to a RULE and not to an observation — the same measurement is
 * fresh for a thirty-day rule and stale for an hourly one. Computing a boolean
 * here would put the expiry rule in a second place with its own idea of whether
 * the boundary is inclusive, which is exactly why the policy repository returns
 * expired overrides and lets the engine decide.
 *
 * @param observedAtEpochSeconds when core recorded it, by core's clock
 * @param reportedBy the connector that reported it
 */
public record MeasureView(
        String name,
        long value,
        long windowSeconds,
        long observedAtEpochSeconds,
        String reportedBy) {}
