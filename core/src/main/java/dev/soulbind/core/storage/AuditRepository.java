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
import dev.soulbind.core.audit.AuditPage;
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

    /**
     * Reads entries matching a query, oldest first.
     *
     * <p>Convenience over {@link #page(AuditQuery)} for callers that genuinely
     * want the bounded answer -- an internal check, a test. Anything reading on
     * behalf of somebody who might want the whole log wants the page, because
     * only the page can say it stopped early.
     */
    default List<AuditEntry> query(AuditQuery query) {
        return page(query).entries();
    }

    /**
     * Reads one page matching a query, oldest first, saying whether more remain.
     *
     * <p>The single implementation, so the bounded read and the paged read
     * cannot come to disagree about what matches.
     */
    AuditPage page(AuditQuery query);

    /** The highest sequence number assigned so far, or 0 if the log is empty. */
    long highestSequence();
}
