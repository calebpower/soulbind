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

import dev.soulbind.core.registry.Authenticator;
import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.core.registry.Credentials;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.Storage;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.RequestSigner;
import dev.soulbind.protocol.SchemaVersion;
import dev.soulbind.protocol.Wire;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A running core, for tests that need one.
 *
 * <p>Owns the whole stack — storage, registry, dispatcher, both transports —
 * and hands back a registered connector's plaintext credential, which exists
 * only at this moment and nowhere else afterwards.
 */
final class TestCore implements AutoCloseable {

    final Storage storage;
    private final Dispatcher dispatcher;
    final TransportServer server;
    final Codec codec;
    final int port;
    final String credential;
    final ConnectorRecord connector;

    private final HttpClient http = HttpClient.newHttpClient();

    TestCore(Backend backend, Path tempDir, Set<Capability> capabilities, Clock clock) {
        this.storage = StorageBackends.open(backend, tempDir);

        Credentials.Minted minted = Credentials.mint();
        this.credential = minted.plaintext();
        this.connector = storage.connectors()
                .register("test-connector", minted.hash(), capabilities);

        this.codec = new Codec();
        Authenticator authenticator = new Authenticator(storage.connectors());
        Duration window = Duration.ofSeconds(300);

        this.dispatcher = new Dispatcher(
                authenticator,
                CoreHandlers.build(
                        storage.connectors(),
                        storage.audit(),
                        storage.identities(),
                        new dev.soulbind.core.identity.LinkingService(
                                storage.identities(), storage.linkCodes(),
                                storage.platformKinds(), storage.audit(), clock,
                                Duration.ofMinutes(10)),
                        codec,
                        clock,
                        (int) window.toSeconds()));

        this.server = new TransportServer(
                dispatcher, codec, authenticator, window, new NonceStore(window), clock);
        this.port = server.start("127.0.0.1", 0);
    }

    /** The dispatcher, for tests that want it without a socket in the way. */
    Dispatcher dispatcher() {
        return dispatcher;
    }

    static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    }

    String rpcUrl() {
        return "http://127.0.0.1:" + port + TransportServer.RPC_PATH;
    }

    String socketUrl() {
        return "ws://127.0.0.1:" + port + TransportServer.SOCKET_PATH;
    }

    /** Builds a protocol request body. */
    String request(String op, String payloadJson) {
        return "{\"" + Wire.SCHEMA + "\":" + SchemaVersion.CURRENT
                + ",\"" + Wire.OP + "\":\"" + op + "\""
                + ",\"" + Wire.ID + "\":\"" + UUID.randomUUID() + "\""
                + ",\"" + Wire.PAYLOAD + "\":" + payloadJson + "}";
    }

    /**
     * Registers a SECOND connector on this core, with its own credential.
     *
     * <p>The gate asks for two connectors completing a link. One credential
     * holding both capabilities would prove the linking logic works while
     * asserting nothing about the property the capability model exists for.
     */
    String registerAnother(String name, Set<Capability> capabilities) {
        Credentials.Minted minted = Credentials.mint();
        storage.connectors().register(name, minted.hash(), capabilities);
        return minted.plaintext();
    }

    /** Posts a signed request as some other connector's credential. */
    HttpResponse<String> postSignedAs(String token, String body, Instant now) throws Exception {
        return postSigned(body, now, token, UUID.randomUUID().toString());
    }

    /** Posts a correctly signed request and returns the raw response body. */
    HttpResponse<String> postSigned(String body, Instant now) throws Exception {
        return postSigned(body, now, credential, UUID.randomUUID().toString());
    }

    /** Posts with explicit credential and nonce, so a test can replay or forge. */
    HttpResponse<String> postSigned(String body, Instant now, String token, String nonce)
            throws Exception {
        long timestamp = now.getEpochSecond();
        String signature = RequestSigner.sign(
                token.getBytes(StandardCharsets.UTF_8), timestamp, nonce, body);
        return postRaw(body, Map.of(
                Wire.HEADER_AUTHORIZATION, "Bearer " + token,
                Wire.HEADER_TIMESTAMP, String.valueOf(timestamp),
                Wire.HEADER_NONCE, nonce,
                Wire.HEADER_SIGNATURE, signature));
    }

    /** Posts with whatever headers the test wants, including none. */
    HttpResponse<String> postRaw(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(rpcUrl()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        server.close();
        storage.close();
    }
}
