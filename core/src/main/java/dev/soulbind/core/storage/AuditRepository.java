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

package dev.soulbind.core.storage;

import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.audit.AuditQuery;
import java.util.List;

/**
 * Audit persistence.
 *
 * <p><b>There is no update and no delete, and that is the point.</b> Audit is
 * append-only in fact rather than in policy: the capability to modify a
 * recorded event does not exist in this interface, so no caller can acquire it
 * by accident, by refactor, or under deadline pressure. A structural guard
 * asserts that no implementation grows one.
 *
 * <p>A retention policy, if one is ever wanted, is a separate deliberate
 * mechanism with its own audit trail — not a {@code delete} quietly added here.
 */
public interface AuditRepository {

    /**
     * Appends one entry and returns it with its assigned sequence number.
     *
     * <p>The sequence is monotonic and assigned by storage, not by the caller.
     * A caller-supplied sequence would let two writers claim the same position,
     * and the gap or collision would be invisible in exactly the situation
     * audit exists to explain.
     */
    AuditEntry append(AuditEntry entry);

    /** Reads entries matching a query, oldest first. */
    List<AuditEntry> query(AuditQuery query);

    /** The highest sequence number assigned so far, or 0 if the log is empty. */
    long highestSequence();
}
