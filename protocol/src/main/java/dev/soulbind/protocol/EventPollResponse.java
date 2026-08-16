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

import java.util.List;

/**
 * A page of events.
 *
 * @param events oldest first. Applying them out of order would let a subscriber
 *     see an unlink before the link it undoes.
 * @param cursor where this connector stood when the page was built. Unchanged
 *     by polling: the cursor advances on ACKNOWLEDGEMENT, never on send, because
 *     advancing on send turns a delivery lost in flight into an event nobody
 *     will ever receive.
 * @param highest the newest sequence in the outbox, so a connector can tell how
 *     far behind it is without polling to exhaustion
 */
public record EventPollResponse(List<EventView> events, long cursor, long highest) {

    public EventPollResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
