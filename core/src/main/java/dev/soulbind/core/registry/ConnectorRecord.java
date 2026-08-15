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

package dev.soulbind.core.registry;

import dev.soulbind.protocol.Capability;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * A registered connector.
 *
 * <p>Carries the credential HASH, never the credential. The plaintext is
 * displayed once at bootstrap and is not recoverable afterwards, so a database
 * disclosure does not hand an attacker working credentials.
 */
public record ConnectorRecord(
        String id,
        String name,
        String credentialHash,
        Status status,
        Set<Capability> capabilities,
        Instant registeredAt,
        Instant lastSeenAt) {

    public enum Status {
        ACTIVE,
        /** Registered but refused: retained so audit can still name it. */
        SUSPENDED
    }

    public ConnectorRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(credentialHash, "credentialHash");
        Objects.requireNonNull(status, "status");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /** Whether this connector may perform an operation requiring the given capability. */
    public boolean has(Capability capability) {
        return status == Status.ACTIVE && capabilities.contains(capability);
    }
}
