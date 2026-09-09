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
 * A connector reporting one measurement about one platform account.
 *
 * <p>{@code platformKind} and {@code platformId} rather than a joined
 * {@code kind:id} reference, matching {@link DecideRequest} for the same reason:
 * a reporter knows the account in front of it, and core builds the reference.
 *
 * <p><b>There is deliberately no observation time.</b> Core stamps it from its
 * own clock. A caller-supplied one is freshness the caller controls, which would
 * make a staleness rule something any connector could opt out of by asserting it
 * had just looked.
 *
 * @param name which measure. The vocabulary is the reporter's; core stores it.
 * @param value what was measured. Core never learns the unit.
 * @param windowSeconds the window the value covers, as the reporter understands
 *     it. Stored rather than assumed, so a rule can refuse an observation that
 *     covers a different span from the one it asks about.
 */
public record MeasureReportRequest(
        String platformKind, String platformId, String name, long value, long windowSeconds) {}
