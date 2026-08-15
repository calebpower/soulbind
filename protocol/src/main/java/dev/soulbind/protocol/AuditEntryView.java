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
 * One audit entry as it appears on the wire.
 *
 * <p>Separate from core's internal entry type on purpose: the wire form is a
 * contract with another implementation, and letting an internal record be
 * serialised directly means every field added to it becomes a protocol change
 * nobody decided to make.
 *
 * @param sequence its position in the log; monotonic, assigned by core
 * @param atEpochSeconds when it happened, by core's clock
 * @param actor who did it — the connector, decided by core, never self-declared
 */
public record AuditEntryView(
        long sequence,
        long atEpochSeconds,
        String actor,
        String action,
        String subjectId,
        String identityRef,
        String gate,
        Map<String, Object> detail) {

    public AuditEntryView {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
