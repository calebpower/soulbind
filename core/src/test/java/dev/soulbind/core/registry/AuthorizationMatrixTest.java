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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.registry.Authorizer.Operation;
import dev.soulbind.core.registry.Authorizer.Refusal;
import dev.soulbind.core.registry.Authorizer.Result;
import dev.soulbind.protocol.Capability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tier 4 — the authorization matrix. Every operation × every capability.
 *
 * <p>This is the centrepiece test. When authorization is refactored, these rows
 * passing unchanged is the gate on the refactor.
 *
 * <p>The expected mapping below is <b>restated independently</b> rather than
 * read from {@link Authorizer#table()}. Deriving expectations from the thing
 * under test would assert only that the code agrees with itself; a capability
 * changed in error would be agreed with rather than caught. This duplication is
 * deliberate and is the methodology's "assert against the source, not a
 * re-export".
 */
class AuthorizationMatrixTest {

    /** The contract, written out by hand. Changing production code must not change this. */
    private static final Map<Operation, Optional<Capability>> EXPECTED =
            new EnumMap<>(Operation.class) {{
                put(Operation.HELLO, Optional.empty());
                put(Operation.HEARTBEAT, Optional.empty());
                put(Operation.EVENT_SUBSCRIBE, Optional.empty());
                // Unprivileged, and deliberately so: a connector can only move
                // its OWN cursor, because the id comes from the credential and
                // never from the payload. There is nothing here a capability
                // would protect.
                put(Operation.EVENT_ACK, Optional.empty());

                put(Operation.ATTEST, Optional.of(Capability.IDENTITY_PROVIDER));
                put(Operation.CODE_ISSUE, Optional.of(Capability.CODE_DISPLAY));
                // Self-service, not administrative. A connector that may mint a
                // code for an account already vouches for it; requiring
                // config-management to answer "what am I linked to" would mean
                // every chat surface could rewrite policy.
                put(Operation.IDENTITY_DESCRIBE, Optional.of(Capability.LINK_STATE_READER));
                put(Operation.CODE_REDEEM, Optional.of(Capability.CODE_ENTRY));
                put(Operation.DECIDE, Optional.of(Capability.ENFORCEMENT_POINT));
                put(Operation.AUDIT_PUSH, Optional.of(Capability.AUDIT_SOURCE));

                put(Operation.RULE_GET, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.RULE_SET, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.OVERRIDE_GET, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.OVERRIDE_SET, Optional.of(Capability.CONFIG_MANAGEMENT));
                // Removing an override is as administrative as setting one, and
                // deliberately not weaker: a connector that could take back an
                // operator's deny-override could admit whoever that override
                // was keeping out.
                put(Operation.OVERRIDE_REMOVE, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.CONFIG_GET, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.CONFIG_SET, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.CONNECTOR_LIST, Optional.of(Capability.CONFIG_MANAGEMENT));
                // Administrative, and it has to be: a connector that could
                // rotate its own credential could rotate somebody else's, and
                // the case rotation exists for is a credential in the hands of
                // whoever would be calling.
                put(Operation.CONNECTOR_ROTATE, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.SUBJECT_INSPECT, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.IDENTITY_UNLINK, Optional.of(Capability.CONFIG_MANAGEMENT));
                put(Operation.AUDIT_QUERY, Optional.of(Capability.CONFIG_MANAGEMENT));
            }};

    @Test
    @DisplayName("every operation is in the expected table -- no operation escapes the matrix")
    void everyOperationIsCovered() {
        List<Operation> uncovered = new ArrayList<>();
        for (Operation op : Operation.values()) {
            if (!EXPECTED.containsKey(op)) {
                uncovered.add(op);
            }
        }
        assertTrue(
                uncovered.isEmpty(),
                () -> "a new operation was added without a matrix row: " + uncovered
                        + ". An operation nobody wrote an expectation for is an operation whose "
                        + "authorization nobody decided.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    @DisplayName("the declared requirement matches the contract")
    void declaredRequirementMatchesContract(Operation op) {
        assertEquals(
                EXPECTED.get(op),
                op.required(),
                () -> op + " requires a different capability than the contract states. If this "
                        + "change is intended, change the contract in this test deliberately -- "
                        + "that is the point at which somebody decides.");
    }

    // --- the matrix proper: every operation against every capability set -----

    static Stream<Arguments> operationsAndCapabilities() {
        List<Arguments> rows = new ArrayList<>();
        for (Operation op : Operation.values()) {
            for (Capability held : Capability.values()) {
                rows.add(Arguments.of(op, held));
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} holding only {1}")
    @MethodSource("operationsAndCapabilities")
    @DisplayName("a connector holding exactly one capability")
    void singleCapability(Operation op, Capability held) {
        ConnectorRecord connector = active(Set.of(held));
        Result result = Authorizer.authorize(connector, op);

        Optional<Capability> required = EXPECTED.get(op);
        boolean shouldAllow = required.isEmpty() || required.get() == held;

        if (shouldAllow) {
            assertInstanceOf(
                    Result.Allowed.class,
                    result,
                    () -> op + " should be allowed for a connector holding " + held);
        } else {
            Result.Denied denied = assertInstanceOf(
                    Result.Denied.class,
                    result,
                    () -> op + " must NOT be allowed for a connector holding only " + held);
            assertEquals(Refusal.MISSING_CAPABILITY, denied.refusal());
            assertEquals(
                    required.get(),
                    denied.required(),
                    "the refusal must name the capability that was missing, so the operator "
                            + "can act on it rather than guess");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    @DisplayName("a connector holding NO capabilities gets only the unprivileged operations")
    void noCapabilities(Operation op) {
        Result result = Authorizer.authorize(active(Set.of()), op);
        if (EXPECTED.get(op).isEmpty()) {
            assertInstanceOf(Result.Allowed.class, result);
        } else {
            assertInstanceOf(Result.Denied.class, result);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    @DisplayName("a connector holding EVERY capability is allowed everything")
    void allCapabilities(Operation op) {
        assertInstanceOf(
                Result.Allowed.class,
                Authorizer.authorize(active(Set.of(Capability.values())), op),
                () -> op + " was refused to a connector holding every capability");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    @DisplayName("a SUSPENDED connector is refused everything, including unprivileged operations")
    void suspendedIsRefusedEverything(Operation op) {
        ConnectorRecord suspended = new ConnectorRecord(
                "id", "name", "hash", ConnectorRecord.Status.SUSPENDED,
                Set.of(Capability.values()), Instant.now(), null);

        Result.Denied denied = assertInstanceOf(
                Result.Denied.class,
                Authorizer.authorize(suspended, op),
                () -> op + " was permitted to a suspended connector. Suspension that still "
                        + "allows heartbeat is suspension in name only.");
        assertEquals(Refusal.SUSPENDED, denied.refusal());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    @DisplayName("an unknown credential is refused everything")
    void unknownCredentialIsRefusedEverything(Operation op) {
        Result.Denied denied = assertInstanceOf(
                Result.Denied.class,
                Authorizer.authorize(null, op),
                () -> op + " was permitted with no credential at all");
        assertEquals(Refusal.UNKNOWN_CREDENTIAL, denied.refusal());
    }

    @Test
    @DisplayName("the exposed table agrees with each operation's own declaration")
    void tableAgreesWithOperations() {
        // Two views of one fact. If they ever disagree, one of them is being
        // built by hand somewhere and has drifted.
        assertEquals(EXPECTED, Authorizer.table());
    }

    @Test
    @DisplayName("exactly the intended operations are unprivileged")
    void unprivilegedSetIsExact() {
        assertEquals(
                Set.of(Operation.HELLO, Operation.HEARTBEAT, Operation.EVENT_SUBSCRIBE,
                        Operation.EVENT_ACK),
                Authorizer.unprivilegedOperations(),
                "an operation silently becoming unprivileged is the most expensive possible "
                        + "authorization mistake");
    }

    /**
     * Capabilities that deliberately gate no request operation.
     *
     * <p>{@code effector} is held by a connector that <em>consumes</em> events
     * and acknowledges them; the specification's capability table lists it with
     * no request operation of its own. Leaving that implicit would make an
     * inert capability indistinguishable from one somebody forgot to wire, so it
     * is stated here. When the event-acknowledgement operation lands, this set
     * shrinks by a deliberate edit rather than silently continuing to pass.
     */
    private static final Set<Capability> INTENTIONALLY_UNGATING = Set.of(Capability.EFFECTOR);

    @Test
    @DisplayName("every capability either gates an operation or is listed as deliberately inert")
    void everyCapabilityIsAccountedFor() {
        Set<Capability> gating = EnumSet.noneOf(Capability.class);
        for (Optional<Capability> required : EXPECTED.values()) {
            required.ifPresent(gating::add);
        }

        List<Capability> unaccounted = new ArrayList<>();
        for (Capability cap : Capability.values()) {
            if (!gating.contains(cap) && !INTENTIONALLY_UNGATING.contains(cap)) {
                unaccounted.add(cap);
            }
        }
        assertTrue(
                unaccounted.isEmpty(),
                () -> "capability grants nothing and was not declared inert: " + unaccounted
                        + ". A capability an operator can grant, that permits nothing, is a "
                        + "promise the system does not keep.");

        List<Capability> wronglyDeclaredInert = new ArrayList<>();
        for (Capability cap : INTENTIONALLY_UNGATING) {
            if (gating.contains(cap)) {
                wronglyDeclaredInert.add(cap);
            }
        }
        assertTrue(
                wronglyDeclaredInert.isEmpty(),
                () -> "declared inert but now gates an operation: " + wronglyDeclaredInert
                        + ". Remove it from INTENTIONALLY_UNGATING -- the note above it has "
                        + "stopped being true.");
    }

    private static ConnectorRecord active(Set<Capability> caps) {
        return new ConnectorRecord(
                "id", "name", "hash", ConnectorRecord.Status.ACTIVE, caps, Instant.now(), null);
    }
}
