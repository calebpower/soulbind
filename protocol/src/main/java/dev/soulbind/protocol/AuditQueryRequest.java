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
 * A filter over the audit log.
 *
 * <p>Every field optional; absent means unconstrained. The limit is bounded
 * server-side whatever is asked for — an unbounded audit query against a
 * long-lived deployment is a way to exhaust the server's memory from an
 * authenticated endpoint, and "the caller asked nicely" is not a defence.
 *
 * <p>Times are seconds since the epoch rather than formatted strings: the other
 * implementation of this protocol is in another language, and a shared integer
 * has no timezone, no locale and no format to disagree about.
 */
public record AuditQueryRequest(
        Long fromEpochSeconds,
        Long toEpochSeconds,
        String actor,
        String subjectId,
        String action,
        Integer limit,
        Long afterSequence) {}
