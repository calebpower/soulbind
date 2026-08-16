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

import java.util.Map;

/**
 * One event as a subscriber sees it.
 *
 * @param sequence its position in the stream. Monotonic and gapless per
 *     deployment, so a subscriber can tell "I have everything up to here".
 * @param idempotencyKey stable across redeliveries and across subscribers.
 *     Delivery is at-least-once; this is what makes that survivable, and a
 *     connector that ignores it will apply the same effect twice.
 */
public record EventView(
        long sequence,
        String type,
        String subjectId,
        String identityRef,
        String gate,
        Map<String, Object> payload,
        String idempotencyKey,
        long createdAtEpochSeconds) {

    public EventView {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
