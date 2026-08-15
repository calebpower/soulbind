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

package dev.soulbind.core.transport;

import dev.soulbind.core.registry.Authorizer.Operation;
import dev.soulbind.core.storage.ConnectorRepository;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.HelloRequest;
import dev.soulbind.protocol.HelloResponse;
import dev.soulbind.protocol.HeartbeatResponse;
import dev.soulbind.protocol.SchemaVersion;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The operations core implements at this phase. */
public final class CoreHandlers {

    private CoreHandlers() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds the handler table.
     *
     * <p>Keyed by {@link Operation}, so an operation that exists in the
     * authorization table but not here is reported as unimplemented rather than
     * as unknown — the distinction matters to whoever is debugging it.
     */
    public static Map<Operation, Dispatcher.Handler> build(
            ConnectorRepository connectors, Codec codec, Clock clock, int signatureWindowSeconds) {

        Map<Operation, Dispatcher.Handler> handlers = new LinkedHashMap<>();

        handlers.put(Operation.HELLO, (connector, payload) -> {
            var request = codec.bind(payload, HelloRequest.class);
            if (request.isEmpty()) {
                return WireResponse.error(
                        ErrorCode.MALFORMED, "hello payload could not be read");
            }

            // The intersection, not the claim. A connector saying it can do
            // something does not make it so; core answers with what the
            // credential was actually granted, so the connector learns the truth
            // at handshake rather than discovering it one refusal at a time.
            Set<Capability> granted = new TreeSet<>(request.get().parsedCapabilities());
            granted.retainAll(connector.capabilities());

            connectors.touchLastSeen(connector.id(), clock.instant());

            return WireResponse.ok(new HelloResponse(
                    SchemaVersion.CURRENT,
                    connector.id(),
                    granted.stream().map(Capability::wireName).toList(),
                    request.get().unrecognisedCapabilities(),
                    clock.instant().getEpochSecond()));
        });

        handlers.put(Operation.HEARTBEAT, (connector, payload) -> {
            // Deliberately a write to liveness and nothing else. A heartbeat
            // that touched identity would make "when did we last hear from it"
            // and "what is it allowed to do" the same row, and a flapping
            // connector would rewrite its own permissions.
            connectors.touchLastSeen(connector.id(), clock.instant());
            return WireResponse.ok(new HeartbeatResponse(
                    clock.instant().getEpochSecond(), signatureWindowSeconds));
        });

        handlers.put(Operation.CONNECTOR_LIST, (connector, payload) ->
                WireResponse.ok(Map.of("connectors", connectors.list().stream()
                        .map(c -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", c.id());
                            row.put("name", c.name());
                            row.put("status", c.status().name().toLowerCase(java.util.Locale.ROOT));
                            row.put("capabilities",
                                    c.capabilities().stream().map(Capability::wireName).sorted()
                                            .toList());
                            row.put("registeredAt", c.registeredAt().toString());
                            row.put("lastSeenAt",
                                    c.lastSeenAt() == null ? null : c.lastSeenAt().toString());
                            // No credential hash. It is not a secret in the sense
                            // the plaintext is, but it is the thing an attacker
                            // would want to confirm a guess against, and nobody
                            // reading a connector list needs it.
                            return row;
                        })
                        .toList())));

        return Map.copyOf(handlers);
    }

    /** The operations this build implements, for the doctor and for tests. */
    public static List<Operation> implemented() {
        return List.of(Operation.HELLO, Operation.HEARTBEAT, Operation.CONNECTOR_LIST);
    }
}
