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
import java.util.Set;
import java.util.TreeSet;

/**
 * What a connector declares about itself at {@code hello}.
 *
 * <p>This is the whole of what core learns about a connector. There is no
 * registry of known integrations anywhere in the dispatcher — a connector says
 * which capabilities it claims, which platform kinds it speaks for, and which
 * gates it enforces, and that is enough. A new integration therefore arrives
 * without a dispatcher change, which is the property the architecture is
 * arranged to keep.
 *
 * <p>Claiming a capability is not the same as holding one. Core intersects the
 * claim with what the credential was granted at registration and answers with
 * the intersection; a connector that claims more than it was granted is not
 * refused, it is told what it actually has.
 */
public record HelloRequest(
        String connectorName,
        String connectorVersion,
        List<String> capabilities,
        List<String> platformKinds,
        List<String> gates) {

    public HelloRequest {
        Objects.requireNonNull(connectorName, "connectorName");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        platformKinds = platformKinds == null ? List.of() : List.copyOf(platformKinds);
        gates = gates == null ? List.of() : List.copyOf(gates);
    }

    /** The claimed capabilities that parse, as a set; unrecognised names are dropped here. */
    public Set<Capability> parsedCapabilities() {
        // Dropped rather than rejected: a connector built against a newer
        // protocol may claim a capability this core has never heard of, and the
        // right answer is to grant it nothing extra -- not to refuse the whole
        // handshake and take the connector offline over a name.
        Set<Capability> out = new TreeSet<>();
        for (String claimed : capabilities) {
            Capability.fromWireName(claimed).ifPresent(out::add);
        }
        return out;
    }

    /** Claimed capability names this build does not recognise. Reported back, not silently lost. */
    public List<String> unrecognisedCapabilities() {
        return capabilities.stream()
                .filter(c -> Capability.fromWireName(c).isEmpty())
                .toList();
    }
}
