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
import dev.soulbind.core.storage.Backend;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.SchemaVersion;
import dev.soulbind.protocol.Wire;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Phase 1 gate: a registered connector can {@code hello} and
 * {@code heartbeat} <b>over both transports</b>.
 *
 * <p>Both, on every available backend, because the claim being made is that one
 * dispatcher serves two transports — and a test that exercised one would prove
 * the dispatcher works while asserting nothing about the property that matters.
 *
 * <p>What these do NOT prove: that the two transports produce byte-identical
 * responses for every operation. They share one codec and one dispatcher, which
 * is a structural argument rather than an asserted one; the wire-conformance
 * tests pin the shapes themselves.
 */
class TransportRoundTripTest {

    @TempDir
    Path tempDir;

    private static final Set<Capability> GRANTED =
            Set.of(Capability.CODE_DISPLAY, Capability.CONFIG_MANAGEMENT);

    // --- signed request-lifecycle transport ----------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("hello over the signed transport returns the granted capabilities")
    void helloOverSignedTransport(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, GRANTED, clock)) {
            String body = core.request("hello", """
                    {"connectorName":"test-connector","connectorVersion":"0.1.0",
                     "capabilities":["code-display","config-management","not-a-capability"],
                     "platformKinds":["kind-a"],"gates":["gate.x"]}
                    """);

            HttpResponse<String> response = core.postSigned(body, clock.instant());
            assertEquals(200, response.statusCode(), response.body());

            JsonNode json = core.codec.mapper().readTree(response.body());
            assertTrue(json.get(Wire.OK).asBoolean(), response.body());
            assertEquals(SchemaVersion.CURRENT, json.get(Wire.SCHEMA).asInt());

            JsonNode payload = json.get(Wire.PAYLOAD);
            assertEquals(core.connector.id(), payload.get("connectorId").asText());

            // The INTERSECTION, not the claim. Claiming a capability does not
            // grant it; core answers with what the credential actually holds so
            // the connector learns the truth at handshake rather than one
            // refusal at a time.
            List<String> granted = new ArrayList<>();
            payload.get("granted").forEach(n -> granted.add(n.asText()));
            assertEquals(List.of("code-display", "config-management"), granted.stream().sorted().toList());

            // And the unrecognised name comes back rather than vanishing.
            List<String> ignored = new ArrayList<>();
            payload.get("ignored").forEach(n -> ignored.add(n.asText()));
            assertEquals(List.of("not-a-capability"), ignored);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("claiming a capability it was not granted does not grant it")
    void claimingMoreThanGranted(Backend backend) throws Exception {
        // The case that matters, and the one the test above cannot see: there,
        // the connector claimed exactly what it held, so "the claim" and "the
        // intersection" were the same list and a mutation returning the claim
        // passed unnoticed. Here the claim is strictly larger.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {
            HttpResponse<String> response = core.postSigned(core.request("hello", """
                    {"connectorName":"greedy",
                     "capabilities":["code-display","config-management","enforcement-point"]}
                    """), clock.instant());

            JsonNode payload = core.codec.mapper().readTree(response.body()).get(Wire.PAYLOAD);
            List<String> granted = new ArrayList<>();
            payload.get("granted").forEach(n -> granted.add(n.asText()));

            assertEquals(
                    List.of("code-display"),
                    granted,
                    "a connector was told it holds capabilities nobody granted it. Claiming a "
                            + "capability is not holding one; core answers with the "
                            + "intersection so the connector learns the truth at handshake "
                            + "rather than one refusal at a time.");

            // And the claim genuinely does not work, which is the property the
            // returned list is describing.
            JsonNode refused = core.codec.mapper().readTree(
                    core.postSigned(core.request("connector.list", "{}"), clock.instant()).body());
            assertFalse(refused.get(Wire.OK).asBoolean(),
                    "the ungranted capability was not merely mis-reported, it worked");
            assertEquals(
                    ErrorCode.MISSING_CAPABILITY.wireName(),
                    refused.get(Wire.ERROR).get(Wire.ERROR_CODE).asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("heartbeat over the signed transport records liveness")
    void heartbeatOverSignedTransport(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, GRANTED, clock)) {
            assertNull(core.storage.connectors().findByName("test-connector").orElseThrow()
                    .lastSeenAt(), "a freshly registered connector has not been seen");

            HttpResponse<String> response =
                    core.postSigned(core.request("heartbeat", "{}"), clock.instant());

            JsonNode json = core.codec.mapper().readTree(response.body());
            assertTrue(json.get(Wire.OK).asBoolean(), response.body());
            assertEquals(
                    clock.instant().getEpochSecond(),
                    json.get(Wire.PAYLOAD).get("serverTimeSeconds").asLong(),
                    "the server clock is returned so a connector can spot skew before that "
                            + "skew starts refusing its signed requests");

            assertNotNull(
                    core.storage.connectors().findByName("test-connector").orElseThrow()
                            .lastSeenAt(),
                    "a heartbeat that does not record liveness is a heartbeat that tells "
                            + "nobody anything");
        }
    }

    // --- socket transport -----------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("hello and heartbeat over the socket transport")
    void helloAndHeartbeatOverSocket(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, GRANTED, clock)) {
            Collector collector = new Collector(2);
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .header(Wire.HEADER_AUTHORIZATION, "Bearer " + core.credential)
                    .buildAsync(URI.create(core.socketUrl()), collector)
                    .get(20, TimeUnit.SECONDS);

            // Each send is awaited before the next begins. java.net.http.WebSocket
            // forbids invoking sendText again before the previous one completes,
            // and violating it silently loses a message rather than throwing.
            //
            // Found in a session, not here: the unchained version passed on the
            // workstation for months of wall-clock and failed on the first Linux
            // run. A timing-dependent misuse that happens to work is the kind of
            // defect that only ever shows up on somebody else's machine.
            socket.sendText(core.request("hello", """
                    {"connectorName":"test-connector","capabilities":["code-display"]}
                    """), true).get(20, TimeUnit.SECONDS);
            socket.sendText(core.request("heartbeat", "{}"), true).get(20, TimeUnit.SECONDS);

            assertTrue(
                    collector.await(20),
                    () -> "expected 2 responses over the socket, got "
                            + collector.received());

            List<JsonNode> messages = collector.parsed(core.codec);
            assertEquals(2, messages.size());
            for (JsonNode message : messages) {
                assertTrue(message.get(Wire.OK).asBoolean(), message::toString);
                assertEquals(SchemaVersion.CURRENT, message.get(Wire.SCHEMA).asInt());
            }
            assertEquals(
                    core.connector.id(),
                    messages.get(0).get(Wire.PAYLOAD).get("connectorId").asText());

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a socket presenting no credential is told why and closed")
    void socketWithoutCredential(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, GRANTED, clock)) {
            Collector collector = new Collector(1);
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(core.socketUrl()), collector)
                    .get(20, TimeUnit.SECONDS);

            assertTrue(collector.await(20), "an unauthenticated socket was left silent");

            JsonNode message = collector.parsed(core.codec).get(0);
            assertFalse(message.get(Wire.OK).asBoolean());
            assertEquals(
                    ErrorCode.UNKNOWN_CREDENTIAL.wireName(),
                    message.get(Wire.ERROR).get(Wire.ERROR_CODE).asText());

            // Closed, not left open: an open socket is a resource, and one that
            // can never do anything is a resource an unauthenticated peer holds.
            assertTrue(collector.awaitClose(20), "the socket was left open");
        }
    }

    private static void assertNull(Object o, String message) {
        org.junit.jupiter.api.Assertions.assertNull(o, message);
    }

    /** Collects socket messages until the expected count arrives. */
    private static final class Collector implements WebSocket.Listener {
        private final List<String> messages = new ArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private final CountDownLatch expected;
        private final CountDownLatch closed = new CountDownLatch(1);

        Collector(int expectedMessages) {
            this.expected = new CountDownLatch(expectedMessages);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                synchronized (messages) {
                    messages.add(partial.toString());
                }
                partial.setLength(0);
                expected.countDown();
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }

        boolean await(int seconds) throws InterruptedException {
            return expected.await(seconds, TimeUnit.SECONDS);
        }

        /** How many arrived, so a timeout says what it actually saw. */
        int received() {
            synchronized (messages) {
                return messages.size();
            }
        }

        boolean awaitClose(int seconds) throws InterruptedException {
            return closed.await(seconds, TimeUnit.SECONDS);
        }

        List<JsonNode> parsed(Codec codec) throws Exception {
            List<JsonNode> out = new ArrayList<>();
            synchronized (messages) {
                for (String message : messages) {
                    out.add(codec.mapper().readTree(message));
                }
            }
            return out;
        }
    }
}
