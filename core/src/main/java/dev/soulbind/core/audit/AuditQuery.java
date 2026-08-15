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

package dev.soulbind.core.audit;

import java.time.Instant;

/**
 * A filter over the audit log.
 *
 * <p>All fields optional; null means unconstrained. {@code limit} is always
 * applied -- an unbounded audit query against a long-lived deployment is a way
 * to run the server out of memory from an authenticated endpoint.
 */
public record AuditQuery(
        Instant from, Instant to, String actor, String subjectId, String action, int limit) {

    /** The largest page any caller may request. */
    public static final int MAX_LIMIT = 1000;

    public static final int DEFAULT_LIMIT = 100;

    public AuditQuery {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    public static AuditQuery recent(int limit) {
        return new AuditQuery(null, null, null, null, null, limit);
    }
}
