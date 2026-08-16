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
 * Ask for events after a position.
 *
 * @param after the last sequence this connector ACKNOWLEDGED, or null to
 *     continue from core's record of its cursor. Sending a position is how a
 *     connector that keeps its own bookkeeping stays authoritative about what it
 *     applied; omitting it is how one that does not lets core remember.
 * @param limit how many at most. Bounded server-side regardless.
 */
public record EventPollRequest(Long after, Integer limit) {}
