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

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.registry.Authenticator;
import dev.soulbind.core.registry.Authorizer;
import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.SchemaVersion;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates, authorises and executes one request.
 *
 * <p><b>Transport-agnostic on purpose.</b> Nothing here knows whether the
 * request arrived over a persistent socket or a signed HTTP request, which is
 * what lets both transports share one authorization path instead of growing two
 * that diverge. It is also what makes this directly testable: the tests call
 * {@link #dispatch} rather than standing up a server.
 *
 * <p>The order is fixed and matters: <b>schema, then credential, then
 * operation, then capability.</b> An unknown operation is only reported to a
 * caller that already authenticated — otherwise the refusal is a free oracle
 * for probing which operations a build supports.
 */
public final class Dispatcher {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(Dispatcher.class);

    /** One operation's implementation. */
    @FunctionalInterface
    public interface Handler {
        WireResponse handle(ConnectorRecord connector, JsonNode payload);
    }

    private final Authenticator authenticator;
    private final Map<Authorizer.Operation, Handler> handlers;

    public Dispatcher(Authenticator authenticator, Map<Authorizer.Operation, Handler> handlers) {
        this.authenticator = authenticator;
        this.handlers = Map.copyOf(handlers);
    }

    /**
     * Runs one request.
     *
     * @param schema the version the caller declared
     * @param opWireName the operation, in its dotted wire form
     * @param presentedToken the bearer credential, or null
     * @param payload the operation body; may be null
     */
    public WireResponse dispatch(
            int schema, String opWireName, String presentedToken, JsonNode payload) {

        if (!SchemaVersion.isSupported(schema)) {
            // Before authentication: a peer speaking a version we do not know
            // may not even be able to present a credential the way we expect,
            // and telling it the version mismatch is the only useful answer.
            return WireResponse.error(
                    ErrorCode.SCHEMA_MISMATCH,
                    "this build speaks schema " + SchemaVersion.CURRENT + ", not " + schema);
        }

        Optional<ConnectorRecord> connector = authenticator.authenticate(presentedToken);
        if (connector.isEmpty()) {
            return WireResponse.error(
                    ErrorCode.UNKNOWN_CREDENTIAL, "no registered connector for this credential");
        }

        Optional<Authorizer.Operation> operation = parseOperation(opWireName);
        if (operation.isEmpty()) {
            return WireResponse.error(
                    ErrorCode.UNKNOWN_OPERATION, "no such operation: " + opWireName);
        }

        Authorizer.Result result = Authorizer.authorize(connector.get(), operation.get());
        if (result instanceof Authorizer.Result.Denied denied) {
            return switch (denied.refusal()) {
                case UNKNOWN_CREDENTIAL -> WireResponse.error(
                        ErrorCode.UNKNOWN_CREDENTIAL, "no registered connector for this credential");
                case SUSPENDED -> WireResponse.error(
                        ErrorCode.SUSPENDED, "this connector is suspended");
                case MISSING_CAPABILITY -> WireResponse.missingCapability(denied.required());
            };
        }

        Handler handler = handlers.get(operation.get());
        if (handler == null) {
            // Declared in the table but not implemented in this build. Reported
            // as an internal fault rather than as "unknown operation": the
            // operation IS known, and telling the caller otherwise would send
            // them looking for a typo that is not there.
            return WireResponse.error(
                    ErrorCode.INTERNAL, "operation not available in this build");
        }
        try {
            return handler.handle(connector.get(), payload);
        } catch (RuntimeException e) {
            // NOTHING escapes to the transport. An exception reaching Javalin
            // becomes an HTTP 500 with an error page in the body -- which the
            // fuzz oracle forbids outright, and which hands a caller something
            // it cannot parse as a protocol response.
            //
            // Found by the T8 race against a real multi-writer backend: a
            // uniqueness violation in a handler surfaced as a 500 whose body
            // began "Server". The violation itself is fixed at its cause, but
            // the transport should never have been able to leak one.
            //
            // The message is deliberately not the exception's. An internal
            // failure that leaks its cause to a peer is an information
            // disclosure; the detail belongs in the server's own logs.
            LOG.error("operation {} failed", operation.get().wireName(), e);
            return WireResponse.error(
                    ErrorCode.INTERNAL, "this operation failed; the failure has been logged");
        }
    }

    /**
     * Resolves a wire name to an operation.
     *
     * <p>Matched against each operation's declared {@code wireName()}, so the
     * table in {@code Authorizer} stays the single source and the doc-sync guard
     * covers this path too.
     */
    private static Optional<Authorizer.Operation> parseOperation(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        String needle = wireName.strip();
        for (Authorizer.Operation op : Authorizer.Operation.values()) {
            if (op.wireName().equals(needle)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }
}
