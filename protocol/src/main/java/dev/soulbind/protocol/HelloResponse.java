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
import java.util.Objects;

/**
 * Core's answer to {@code hello}.
 *
 * @param schema the version core speaks, echoed so a connector can log a match
 * @param connectorId the registry id this credential resolves to
 * @param granted the capabilities the credential actually holds — the
 *     intersection of what was claimed and what was granted at registration,
 *     so a connector learns what it can do rather than assuming its claim was
 *     accepted
 * @param ignored claimed capability names core did not recognise, returned
 *     rather than silently dropped: a connector built against a newer protocol
 *     should be able to see that, and an operator reading a log should not have
 *     to guess why something is inert
 * @param serverTimeSeconds core's clock, so a connector can detect skew before
 *     that skew starts refusing its signed requests as stale
 */
public record HelloResponse(
        int schema,
        String connectorId,
        List<String> granted,
        List<String> ignored,
        long serverTimeSeconds) {

    public HelloResponse {
        Objects.requireNonNull(connectorId, "connectorId");
        granted = granted == null ? List.of() : List.copyOf(granted);
        ignored = ignored == null ? List.of() : List.copyOf(ignored);
    }
}
