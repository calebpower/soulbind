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

package dev.soulbind.core.cli;

import dev.soulbind.config.Config;
import dev.soulbind.core.CoreConfig;
import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.core.registry.Credentials;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.Storage;
import dev.soulbind.protocol.Capability;
import java.io.PrintStream;
import dev.soulbind.core.audit.AuditEntry;
import java.time.Clock;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registering a connector and minting its credential.
 *
 * <p>The credential is printed <b>once</b>, here, and never again. Only its
 * hash is stored, so there is no "show it to me again" — not as a policy that
 * could be relaxed, but because the plaintext genuinely no longer exists
 * anywhere once this returns.
 */
public final class Bootstrap {

    private Bootstrap() {
        throw new AssertionError("no instances");
    }

    /** A registration that succeeded, with the one and only copy of the credential. */
    public record Registered(ConnectorRecord connector, String credential) {}

    /**
     * Registers a connector.
     *
     * @throws IllegalArgumentException if the name is taken or a capability is
     *     unrecognised. Both are refusals rather than warnings: registering a
     *     second connector under an existing name would make audit attribution
     *     ambiguous, and silently dropping an unrecognised capability would hand
     *     back a credential that cannot do what the operator asked for.
     */
    public static Registered register(
            Storage storage, String name, List<String> capabilityNames) {
        return register(storage, name, capabilityNames, Clock.systemUTC());
    }

    /** Registers a connector against an explicit clock, for tests. */
    public static Registered register(
            Storage storage, String name, List<String> capabilityNames, Clock clock) {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a connector needs a name");
        }
        if (storage.connectors().findByName(name).isPresent()) {
            throw new IllegalArgumentException(
                    "a connector named '" + name + "' is already registered. Audit attributes "
                            + "events to a connector, so two with one name would make the log "
                            + "ambiguous exactly where it is being read to explain something.");
        }

        Set<Capability> capabilities = new TreeSet<>();
        List<String> unknown = new ArrayList<>();
        for (String claimed : capabilityNames) {
            Optional<Capability> parsed = Capability.fromWireName(claimed);
            if (parsed.isPresent()) {
                capabilities.add(parsed.get());
            } else {
                unknown.add(claimed);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "unrecognised capabilit" + (unknown.size() == 1 ? "y: " : "ies: ")
                            + String.join(", ", unknown) + ". Known: "
                            + String.join(", ", knownCapabilities()) + ".");
        }

        Credentials.Minted minted = Credentials.mint();
        ConnectorRecord connector =
                storage.connectors().register(name, minted.hash(), capabilities);

        // Minting a credential is audited, and it was not until Phase 10 --
        // while `connector.rotate` was, which made the log say a credential had
        // been REPLACED with no record of it ever having been created. The
        // javadoc below had promised this row since Phase 1: a claim about the
        // system in a comment nothing read, which is the shape of failure this
        // project keeps finding in its own documentation.
        //
        // The actor is "cli" rather than "connector:<id>". No connector is
        // involved: this runs on the machine, against the database, as whoever
        // has a shell there -- and recording a connector id would attribute an
        // operator's action to something that did not take it, which is the one
        // property audit attribution exists to protect.
        storage.audit().append(new AuditEntry(
                0L, clock.instant(),
                "cli",
                "connector.registered",
                null, null, null,
                Map.of(
                        "connector", name,
                        "connectorId", connector.id(),
                        "capabilities", capabilities.stream()
                                .map(Capability::wireName)
                                .collect(java.util.stream.Collectors.joining(",")))));

        return new Registered(connector, minted.plaintext());
    }

    private static List<String> knownCapabilities() {
        List<String> names = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            names.add(capability.wireName());
        }
        return names;
    }

    /**
     * Prints a registration.
     *
     * <p>The credential goes to the stream once and is not logged. An operator
     * who loses it rotates the connector -- {@code connector.rotate}, which
     * replaces the credential immediately and audits doing so -- rather than
     * recovering a secret the system promised not to keep. Before rotation
     * existed the only recourse was registering a second connector, and this
     * paragraph said so.
     */
    public static void report(Registered registered, PrintStream out) {
        out.println("registered " + registered.connector().name());
        out.println("  id           " + registered.connector().id());
        out.println("  capabilities " + String.join(", ",
                registered.connector().capabilities().stream()
                        .map(Capability::wireName).sorted().toList()));
        out.println();
        out.println("  credential   " + registered.credential());
        out.println();
        out.println("This is the only time the credential is shown. Only its hash is stored,");
        out.println("so it cannot be recovered -- not by policy, but because the plaintext no");
        out.println("longer exists anywhere. Losing it means registering again.");
    }

    /** Opens storage from a loaded configuration. */
    public static Storage open(Config config) {
        Backend backend = Backend.fromConfigName(config.getString(CoreConfig.STORAGE_BACKEND))
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + config.getString(CoreConfig.STORAGE_BACKEND)
                                + "' is not a storage backend this build knows"));
        return Storage.open(
                backend,
                config.getString(CoreConfig.STORAGE_URL),
                config.findString(CoreConfig.STORAGE_USER).orElse(null),
                config.findString(CoreConfig.STORAGE_PASSWORD).orElse(null));
    }
}
