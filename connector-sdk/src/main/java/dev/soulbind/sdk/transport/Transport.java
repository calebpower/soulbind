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
package dev.soulbind.sdk.transport;

/**
 * How a connector reaches core.
 *
 * <p>Deliberately tiny: one method, taking a request body and returning a
 * response body. Everything interesting — signing, retry, decision caching,
 * idempotent application — lives <b>above</b> this interface and is therefore
 * testable against {@link InMemoryTransport} with no socket in the room.
 *
 * <p>That is the transport seam, and a guard asserts it: no HTTP or WebSocket
 * type may appear outside this package. The failure it prevents is protocol
 * logic that can only be exercised by standing up a server — such logic still
 * works, it just gets tested less, so it is where the bugs go.
 *
 * <p>The door is open a crack for a third compiled-in transport and no wider:
 * no dynamic loading, no service discovery, no ABI. A transport is a class in
 * this package, chosen at construction.
 */
public interface Transport extends AutoCloseable {

    /**
     * Sends a request and returns the response body.
     *
     * <p>Synchronous, and that is a deliberate simplification rather than an
     * oversight. Asynchrony belongs to the caller — a proxy plugin must not
     * block its event thread, and the way it avoids that is by deciding when to
     * hand work to a pool, not by every transport implementing its own idea of
     * a future.
     *
     * @throws TransportException when the request could not be completed. A
     *     refusal from core is NOT this: a refusal is a well-formed response
     *     that says no, and confusing the two is how a connector treats "you
     *     may not" as "try again later".
     */
    String send(String requestBody) throws TransportException;

    /** Whether the transport believes it can currently reach core. */
    boolean isConnected();

    @Override
    void close();
}
