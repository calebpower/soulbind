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
package dev.soulbind.core.events;

import dev.soulbind.protocol.EventType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * An event, as stored in the outbox.
 *
 * @param sequence assigned by storage. Zero on a record being appended.
 * @param idempotencyKey assigned by core at emission and carried to every
 *     subscriber unchanged. Two subscribers see the same key for the same
 *     event, and a redelivery carries the key it had the first time — which is
 *     what lets a connector recognise what it already applied.
 */
public record EventRecord(
        long sequence,
        EventType type,
        String subjectId,
        String identityRef,
        String gate,
        Map<String, Object> payload,
        String idempotencyKey,
        Instant createdAt) {

    public EventRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** A new event, with its key derived from what makes it unique. */
    public static EventRecord of(
            EventType type,
            String subjectId,
            String identityRef,
            String gate,
            Map<String, Object> payload,
            Instant at) {
        return new EventRecord(
                0L, type, subjectId, identityRef, gate, payload,
                java.util.UUID.randomUUID().toString(), at);
    }

    /**
     * The same event with its assigned sequence.
     *
     * <p>Public because storage lives in another package. Not a setter: the
     * record is immutable, and an event whose sequence could change after
     * emission is an event two subscribers could see at different positions.
     */
    public EventRecord withSequence(long assigned) {
        return new EventRecord(
                assigned, type, subjectId, identityRef, gate, payload, idempotencyKey, createdAt);
    }
}
