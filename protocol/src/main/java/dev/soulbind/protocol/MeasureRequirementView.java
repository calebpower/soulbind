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
 * A rule's threshold on a measure, on the wire.
 *
 * <p>Rendered only when a rule has one. Every rule written before measures
 * existed has none, and a null component that serialized as {@code
 * "measure":null} would change the bytes of every existing {@code rule.get}
 * response — so the field carries its own inclusion annotation where it is used
 * rather than the mapper being told to drop nulls globally, which would quietly
 * change a dozen unrelated responses at the same time.
 *
 * @param name which measure, as the reporting connector names it
 * @param atLeast the threshold, inclusive
 * @param windowSeconds the window an observation must cover, matched exactly
 * @param maxAgeSeconds how old an observation may be and still count
 */
public record MeasureRequirementView(
        String name, long atLeast, long windowSeconds, long maxAgeSeconds) {}
