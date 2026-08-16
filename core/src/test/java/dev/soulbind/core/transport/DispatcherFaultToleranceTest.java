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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.registry.Authenticator;
import dev.soulbind.core.registry.Authorizer;
import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.Wire;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nothing a handler does may reach the transport as a 5xx.
 *
 * <p>Written after the fact. A uniqueness violation inside a handler escaped to
 * Javalin and became an HTTP 500 whose body began {@code Server} — found only by
 * the T8 race against a real multi-writer backend, because the defect that
 * triggered it could not occur on the single-writer one.
 *
 * <p>The underlying race is fixed at its cause. This asserts the transport
 * property independently: whatever a handler does, the caller gets a protocol
 * response. Reproducing the original conditions would have needed MariaDB;
 * asserting the property needs only a handler that throws.
 */
class DispatcherFaultToleranceTest {

    @TempDir
    Path tempDir;

    private Dispatcher dispatcherThatThrows(TestCore core, RuntimeException failure) {
        return new Dispatcher(
                new Authenticator(core.storage.connectors()),
                Map.of(Authorizer.Operation.HEARTBEAT, (ConnectorRecord c, com.fasterxml.jackson
                        .databind.JsonNode p) -> {
                    throw failure;
                }));
    }

    @Test
    @DisplayName("a handler that throws produces a refusal, not an exception")
    void handlerThrowsIsRefusal() {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                StorageBackends.any(), tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {

            Dispatcher dispatcher =
                    dispatcherThatThrows(core, new IllegalStateException("effector exploded"));

            WireResponse response = dispatcher.dispatch(
                    dev.soulbind.protocol.SchemaVersion.CURRENT,
                    "heartbeat",
                    core.credential,
                    null);

            assertNotNull(response, "the dispatcher returned nothing at all");
            assertFalse(response.ok());
            assertEquals(ErrorCode.INTERNAL, response.code());
        }
    }

    @Test
    @DisplayName("the refusal does NOT leak the exception's message")
    void refusalDoesNotLeakTheCause() {
        // An internal failure that tells a peer why is an information
        // disclosure. The detail belongs in the server's own logs.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                StorageBackends.any(), tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {

            Dispatcher dispatcher = dispatcherThatThrows(
                    core, new IllegalStateException("jdbc://secret-host/db credentials rejected"));

            WireResponse response = dispatcher.dispatch(
                    dev.soulbind.protocol.SchemaVersion.CURRENT, "heartbeat",
                    core.credential, null);

            assertFalse(
                    response.message().contains("secret-host"),
                    () -> "the refusal leaked the cause: " + response.message());
            assertTrue(response.message().contains("logged"));
        }
    }

    @Test
    @DisplayName("every response a handler failure produces is still a well-formed envelope")
    void failureStillRendersAsAnEnvelope() throws Exception {
        // The property the fuzz oracle asserts, checked here at the codec so a
        // failure cannot produce something a peer cannot parse.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                StorageBackends.any(), tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {

            Dispatcher dispatcher = dispatcherThatThrows(core, new RuntimeException("boom"));
            WireResponse response = dispatcher.dispatch(
                    dev.soulbind.protocol.SchemaVersion.CURRENT, "heartbeat",
                    core.credential, null);

            JsonNode rendered = core.codec.mapper().readTree(
                    core.codec.renderResponse("some-id", response));

            assertEquals("some-id", rendered.get(Wire.ID).asText());
            assertFalse(rendered.get(Wire.OK).asBoolean());
            assertEquals(
                    ErrorCode.INTERNAL.wireName(),
                    rendered.get(Wire.ERROR).get(Wire.ERROR_CODE).asText());
        }
    }

    @Test
    @DisplayName("an Error is NOT swallowed")
    void errorsPropagate() {
        // OutOfMemoryError and friends are not conditions a request handler can
        // report as a refusal. Catching Throwable here would turn a dying JVM
        // into a stream of polite denials, and nobody would look at the logs
        // until it fell over anyway.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                StorageBackends.any(), tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {

            Dispatcher dispatcher = new Dispatcher(
                    new Authenticator(core.storage.connectors()),
                    Map.of(Authorizer.Operation.HEARTBEAT,
                            (ConnectorRecord c, JsonNode p) -> {
                                throw new StackOverflowError("genuinely broken");
                            }));

            org.junit.jupiter.api.Assertions.assertThrows(
                    StackOverflowError.class,
                    () -> dispatcher.dispatch(
                            dev.soulbind.protocol.SchemaVersion.CURRENT, "heartbeat",
                            core.credential, null));
        }
    }

    @Test
    @DisplayName("insert-if-absent tolerates a concurrent insert of the same key")
    void seenIsRaceTolerant() throws Exception {
        // The defect underneath the 500. Not reproducible on the single-writer
        // backend -- its executor serialises every write -- so this asserts the
        // contract rather than the race: calling `seen` twice must be
        // indistinguishable from calling it once, including when the row
        // already exists.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                StorageBackends.any(), tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {

            core.storage.platformKinds().seen("kind-a", "conn-1");
            core.storage.platformKinds().seen("kind-a", "conn-2");
            core.storage.platformKinds().seen("kind-a", "conn-1");

            assertTrue(core.storage.platformKinds().isKnown("kind-a"));
            assertEquals(
                    1,
                    core.storage.platformKinds().list().stream()
                            .filter("kind-a"::equals).count(),
                    "seen is idempotent by contract; a second call must not add a second row");

            core.storage.policy().gateSeen("gate.x", "conn-1", null);
            core.storage.policy().gateSeen("gate.x", "conn-2", "different description");
            assertEquals(
                    1, core.storage.policy().gates().stream().filter("gate.x"::equals).count());
        }
    }
}
