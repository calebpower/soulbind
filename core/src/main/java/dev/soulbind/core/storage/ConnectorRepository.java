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

import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.protocol.Capability;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Connector registration and credential lookup. */
public interface ConnectorRepository {

    /** Registers a connector and its capabilities. */
    ConnectorRecord register(String name, String credentialHash, Set<Capability> capabilities);

    /**
     * Finds a connector by the hash of its presented credential.
     *
     * <p>By hash, never by the credential itself: the plaintext is shown once at
     * bootstrap and never stored, so a database disclosure does not hand over
     * working credentials.
     */
    Optional<ConnectorRecord> findByCredentialHash(String credentialHash);

    Optional<ConnectorRecord> findByName(String name);

    List<ConnectorRecord> list();

    /** Records liveness. Separate from registration so a heartbeat is not a write to identity. */
    void touchLastSeen(String connectorId, Instant at);
}
