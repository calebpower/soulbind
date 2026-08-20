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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single authorization table: which capability each operation requires.
 *
 * <p><b>This is the one place that answer exists.</b> The Tier 4 authorization
 * matrix and {@code docs/protocol.md}'s capability column are both derived from
 * it, so a rule cannot exist in two copies and drift. The admin API is the same
 * operation set under the same table — one code path, not a parallel one that
 * gradually diverges.
 */
public final class Authorizer {

    /** An operation a credential can attempt. */
    public enum Operation {
        // Available to any registered credential.
        HELLO(null),
        HEARTBEAT(null),
        EVENT_SUBSCRIBE(null),

        /**
         * Acknowledge events applied.
         *
         * <p>Unprivileged like the subscribe it pairs with. A connector can only
         * move its OWN cursor -- the id comes from the credential, never from
         * the payload -- so there is nothing here another capability would
         * protect.
         */
        EVENT_ACK(null),

        ATTEST(Capability.IDENTITY_PROVIDER),
        CODE_ISSUE(Capability.CODE_DISPLAY),

        /**
         * Describe the link state of an account this connector vouches for.
         *
         * <p>Distinct from {@code subject.inspect}, which is an ADMIN operation
         * over anybody. This answers "what is this account linked to" for a
         * connector asking on behalf of the person in front of it -- a
         * self-service question every chat surface needs and no operator should
         * have to grant `config-management` for.
         *
         * <p>Requires {@code code-display}: a connector that may mint a link
         * code for an account already vouches for it, and already learns the
         * graph the moment a link completes. This grants no reach it did not
         * have; it removes the need to obtain far more.
         */
        IDENTITY_DESCRIBE(Capability.LINK_STATE_READER),
        CODE_REDEEM(Capability.CODE_ENTRY),
        DECIDE(Capability.ENFORCEMENT_POINT),
        AUDIT_PUSH(Capability.AUDIT_SOURCE),

        RULE_GET(Capability.CONFIG_MANAGEMENT),
        RULE_SET(Capability.CONFIG_MANAGEMENT),
        OVERRIDE_GET(Capability.CONFIG_MANAGEMENT),
        OVERRIDE_SET(Capability.CONFIG_MANAGEMENT),
        CONFIG_GET(Capability.CONFIG_MANAGEMENT),
        CONFIG_SET(Capability.CONFIG_MANAGEMENT),
        CONNECTOR_LIST(Capability.CONFIG_MANAGEMENT),
        SUBJECT_INSPECT(Capability.CONFIG_MANAGEMENT),
        IDENTITY_UNLINK(Capability.CONFIG_MANAGEMENT),
        AUDIT_QUERY(Capability.CONFIG_MANAGEMENT);

        private final Capability required;

        Operation(Capability required) {
            this.required = required;
        }

        /**
         * The capability this operation requires, or empty when any registered
         * credential may perform it.
         *
         * <p>Empty means "any <em>registered</em>", never "anyone": an
         * unregistered caller has no credential and never reaches this table.
         */
        public Optional<Capability> required() {
            return Optional.ofNullable(required);
        }

        /** The wire name, stated rather than derived, for the same reason as {@link Capability}. */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
        }
    }

    private Authorizer() {
        throw new AssertionError("no instances");
    }

    /** Why a request was refused. Carried into the response, because a refusal states its reason. */
    public enum Refusal {
        /** No credential, or one that matches no registered connector. */
        UNKNOWN_CREDENTIAL,
        /** Registered, but suspended. */
        SUSPENDED,
        /** Registered and active, but lacking the capability this operation needs. */
        MISSING_CAPABILITY
    }

    /** The outcome of an authorization check. */
    public sealed interface Result {
        record Allowed(ConnectorRecord connector, Operation operation) implements Result {}

        record Denied(Refusal refusal, Operation operation, Capability required)
                implements Result {}
    }

    /**
     * Decides whether a connector may perform an operation.
     *
     * <p>Deliberately a pure function of {@code (connector, operation)}: no I/O,
     * no clock, no database. That is what makes the Tier 4 matrix possible —
     * every row asserts this function directly rather than through HTTP.
     */
    public static Result authorize(ConnectorRecord connector, Operation operation) {
        if (connector == null) {
            return new Result.Denied(Refusal.UNKNOWN_CREDENTIAL, operation, null);
        }
        if (connector.status() != ConnectorRecord.Status.ACTIVE) {
            return new Result.Denied(Refusal.SUSPENDED, operation, null);
        }
        Optional<Capability> required = operation.required();
        if (required.isEmpty()) {
            return new Result.Allowed(connector, operation);
        }
        if (connector.capabilities().contains(required.get())) {
            return new Result.Allowed(connector, operation);
        }
        return new Result.Denied(Refusal.MISSING_CAPABILITY, operation, required.get());
    }

    /**
     * The whole table, for the matrix test and for documentation generation.
     *
     * <p>Exposed so both consumers read the same source. The matrix test then
     * re-states the expected mapping independently — asserting against the
     * source, not a re-export of it, so a table edited in error is caught rather
     * than agreed with.
     */
    public static Map<Operation, Optional<Capability>> table() {
        Map<Operation, Optional<Capability>> out = new EnumMap<>(Operation.class);
        for (Operation op : Operation.values()) {
            out.put(op, op.required());
        }
        return out;
    }

    /** Operations any registered credential may perform. */
    public static Set<Operation> unprivilegedOperations() {
        EnumSet<Operation> out = EnumSet.noneOf(Operation.class);
        for (Operation op : Operation.values()) {
            if (op.required().isEmpty()) {
                out.add(op);
            }
        }
        return out;
    }
}
