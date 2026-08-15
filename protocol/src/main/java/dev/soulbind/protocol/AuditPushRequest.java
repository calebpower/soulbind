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
 * A connector appending an event to the audit stream.
 *
 * <p><b>There is no actor field, deliberately.</b> The actor is the connector
 * whose credential signed the request, decided by core and never by the caller.
 * A connector able to name its own actor could attribute its actions to another
 * connector — or to a person — and an audit log whose attribution the subject
 * controls is not evidence of anything.
 *
 * @param action what happened, in the same dotted vocabulary as the operations
 * @param subjectId the subject it concerns, if any
 * @param identityRef the platform identity it concerns, if any
 * @param gate the gate it concerns, if any
 * @param detail structured context; the reader's, not the schema's, to interpret
 */
public record AuditPushRequest(
        String action,
        String subjectId,
        String identityRef,
        String gate,
        Map<String, Object> detail) {

    public AuditPushRequest {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
