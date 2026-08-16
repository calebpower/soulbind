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

import dev.soulbind.core.events.EventRecord;
import java.time.Instant;
import java.util.List;

/**
 * The outbox, and where each subscriber has got to.
 *
 * <p><b>There is no delete.</b> Like audit, and for a related reason: an event
 * removed is an event a connector that was down will never receive, and "it was
 * probably fine" is not something anybody can check afterwards. Trimming, if it
 * is ever wanted, is a deliberate mechanism that must first prove every cursor
 * has passed the point it trims.
 */
public interface EventRepository {

    /** Appends an event and returns it with its assigned sequence. */
    EventRecord append(EventRecord event);

    /**
     * Events after a cursor position, oldest first.
     *
     * <p>Ordered by sequence, which is the order they were emitted. A subscriber
     * applying them out of order would see an unlink before the link it undoes.
     */
    List<EventRecord> after(long position, int limit);

    /** The highest sequence assigned, or 0. */
    long highestSequence();

    /** Where a connector has got to. Zero for one that has never acknowledged. */
    long cursorOf(String connectorId);

    /**
     * Advances a connector's cursor.
     *
     * <p>Never backwards. A cursor that could move back would re-deliver events
     * a connector already applied — survivable, since delivery is at-least-once
     * and keys exist — but it would also mean a buggy acknowledgement could
     * replay the entire history, which is a very different amount of work.
     *
     * @return the position after the call
     */
    long acknowledge(String connectorId, long position, Instant at);
}
