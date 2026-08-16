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

import dev.soulbind.core.storage.EventRepository;
import dev.soulbind.protocol.EventType;
import java.time.Clock;
import java.util.Map;

/**
 * Writing events to the outbox.
 *
 * <p>Thin on purpose. The interesting decision is <b>where</b> emission happens
 * — in the same place as the change that caused it — and putting that logic
 * here would move it away from the change it describes, which is how an event
 * and its cause drift apart.
 *
 * <p>Delivery is <b>at-least-once</b>. Exactly-once across a network does not
 * exist; what exists is at-least-once plus an idempotency key. Saying so plainly
 * is how connector authors learn they must dedup, and the SDK enforces it.
 */
public final class EventEmitter {

    private final EventRepository events;
    private final Clock clock;

    public EventEmitter(EventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public EventRecord emit(
            EventType type,
            String subjectId,
            String identityRef,
            String gate,
            Map<String, Object> payload) {
        return events.append(
                EventRecord.of(type, subjectId, identityRef, gate, payload, clock.instant()));
    }

    public EventRecord emit(EventType type, String subjectId, String identityRef) {
        return emit(type, subjectId, identityRef, null, Map.of());
    }
}
