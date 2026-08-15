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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.RequestSigner;
import dev.soulbind.protocol.Wire;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the signed transport refuses, and why.
 *
 * <p>Each of these is a property somebody would otherwise have to take on
 * trust. They run against one backend deliberately: none of them touches
 * storage semantics — the subject is the signing and replay machinery, which is
 * backend-independent. The round-trip tests carry the both-backends claim.
 */
class SignedTransportRefusalTest {

    @TempDir
    Path tempDir;

    private static final Set<Capability> GRANTED = Set.of(Capability.CODE_DISPLAY);

    private TestCore core(Clock clock) {
        // Any backend: this suite's subject is signing and replay, which is
        // backend-independent. Asking for "any" rather than naming one keeps
        // the test free of knowledge about which database is in use.
        return new TestCore(StorageBackends.any(), tempDir, GRANTED, clock);
    }

    private JsonNode read(TestCore core, HttpResponse<String> response) throws Exception {
        return core.codec.mapper().readTree(response.body());
    }

    private String errorCode(JsonNode json) {
        return json.get(Wire.ERROR).get(Wire.ERROR_CODE).asText();
    }

    @Test
    @DisplayName("a correctly signed request is accepted exactly once")
    void nonceIsSingleUse() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            String body = core.request("heartbeat", "{}");
            String nonce = UUID.randomUUID().toString();

            JsonNode first = read(core,
                    core.postSigned(body, clock.instant(), core.credential, nonce));
            assertTrue(first.get(Wire.OK).asBoolean(), first::toString);

            // Byte-for-byte the same request, which is exactly what an attacker
            // who captured it would send.
            JsonNode replay = read(core,
                    core.postSigned(body, clock.instant(), core.credential, nonce));
            assertFalse(replay.get(Wire.OK).asBoolean(), "a captured request replayed cleanly");
            assertEquals(ErrorCode.REPLAYED_NONCE.wireName(), errorCode(replay));
        }
    }

    @Test
    @DisplayName("a timestamp outside the window is refused, in BOTH directions")
    void staleAndFutureTimestamps() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            String body = core.request("heartbeat", "{}");

            for (long offset : new long[] {-301, 301, -100_000, 100_000}) {
                JsonNode json = read(core, core.postSigned(
                        body,
                        clock.instant().plusSeconds(offset),
                        core.credential,
                        UUID.randomUUID().toString()));

                assertFalse(json.get(Wire.OK).asBoolean(), () -> "accepted offset " + offset);
                assertEquals(
                        ErrorCode.STALE_TIMESTAMP.wireName(),
                        errorCode(json),
                        () -> "offset " + offset
                                + ": a timestamp far in the FUTURE must be refused too, or a "
                                + "captured request given a distant timestamp stays replayable "
                                + "indefinitely -- the window with its lid off");
            }
        }
    }

    @Test
    @DisplayName("a timestamp at the edge of the window is accepted")
    void edgeOfWindow() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            for (long offset : new long[] {-300, 0, 300}) {
                JsonNode json = read(core, core.postSigned(
                        core.request("heartbeat", "{}"),
                        clock.instant().plusSeconds(offset),
                        core.credential,
                        UUID.randomUUID().toString()));
                assertTrue(json.get(Wire.OK).asBoolean(),
                        () -> "refused offset " + offset + ", which is inside the window");
            }
        }
    }

    @Test
    @DisplayName("a signature over a different body is refused")
    void bodyTampering() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            String signedBody = core.request("heartbeat", "{}");
            String sentBody = core.request("connector.list", "{}");

            long timestamp = clock.instant().getEpochSecond();
            String nonce = UUID.randomUUID().toString();
            String signature = RequestSigner.sign(
                    core.credential.getBytes(StandardCharsets.UTF_8),
                    timestamp, nonce, signedBody);

            JsonNode json = read(core, core.postRaw(sentBody, Map.of(
                    Wire.HEADER_AUTHORIZATION, "Bearer " + core.credential,
                    Wire.HEADER_TIMESTAMP, String.valueOf(timestamp),
                    Wire.HEADER_NONCE, nonce,
                    Wire.HEADER_SIGNATURE, signature)));

            assertFalse(json.get(Wire.OK).asBoolean(), "a swapped body verified");
            assertEquals(ErrorCode.BAD_SIGNATURE.wireName(), errorCode(json));
        }
    }

    @Test
    @DisplayName("a signature made with the wrong key is refused")
    void wrongKey() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            String body = core.request("heartbeat", "{}");
            long timestamp = clock.instant().getEpochSecond();
            String nonce = UUID.randomUUID().toString();

            JsonNode json = read(core, core.postRaw(body, Map.of(
                    Wire.HEADER_AUTHORIZATION, "Bearer " + core.credential,
                    Wire.HEADER_TIMESTAMP, String.valueOf(timestamp),
                    Wire.HEADER_NONCE, nonce,
                    Wire.HEADER_SIGNATURE, RequestSigner.sign(
                            "not-the-key".getBytes(StandardCharsets.UTF_8),
                            timestamp, nonce, body))));

            assertFalse(json.get(Wire.OK).asBoolean());
            assertEquals(ErrorCode.BAD_SIGNATURE.wireName(), errorCode(json));
        }
    }

    @Test
    @DisplayName("an unregistered credential is refused before any signature work")
    void unknownCredential() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            JsonNode json = read(core, core.postSigned(
                    core.request("heartbeat", "{}"),
                    clock.instant(),
                    "a-credential-nobody-issued",
                    UUID.randomUUID().toString()));

            assertFalse(json.get(Wire.OK).asBoolean());
            assertEquals(ErrorCode.UNKNOWN_CREDENTIAL.wireName(), errorCode(json));
        }
    }

    @Test
    @DisplayName("a request with no signing headers is refused as malformed, not accepted")
    void missingHeaders() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            JsonNode json = read(core, core.postRaw(
                    core.request("heartbeat", "{}"),
                    Map.of(Wire.HEADER_AUTHORIZATION, "Bearer " + core.credential)));

            assertFalse(json.get(Wire.OK).asBoolean(), "an unsigned request was accepted");
            assertEquals(ErrorCode.MALFORMED.wireName(), errorCode(json));
        }
    }

    @Test
    @DisplayName("an operation the credential lacks the capability for is refused by name")
    void missingCapability() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            // The connector holds code-display, not config-management.
            JsonNode json = read(core, core.postSigned(
                    core.request("connector.list", "{}"), clock.instant()));

            assertFalse(json.get(Wire.OK).asBoolean());
            assertEquals(ErrorCode.MISSING_CAPABILITY.wireName(), errorCode(json));
            assertEquals(
                    Capability.CONFIG_MANAGEMENT.wireName(),
                    json.get(Wire.ERROR).get(Wire.ERROR_CAPABILITY).asText(),
                    "the refusal must name the missing capability, so an operator can grant it "
                            + "rather than guess which of several was wanted");
        }
    }

    @Test
    @DisplayName("an unknown schema version is refused rather than guessed at")
    void schemaMismatch() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            String body = "{\"" + Wire.SCHEMA + "\":9999,\"" + Wire.OP + "\":\"heartbeat\","
                    + "\"" + Wire.ID + "\":\"x\",\"" + Wire.PAYLOAD + "\":{}}";

            JsonNode json = read(core, core.postSigned(body, clock.instant()));
            assertFalse(json.get(Wire.OK).asBoolean());
            assertEquals(
                    ErrorCode.SCHEMA_MISMATCH.wireName(),
                    errorCode(json),
                    "a silent downgrade lets two peers disagree about what a message means "
                            + "while both appear to work");
        }
    }

    @Test
    @DisplayName("an unknown operation is refused only AFTER authentication")
    void unknownOperationRequiresAuth() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            // Authenticated: told the operation does not exist.
            JsonNode authenticated = read(core, core.postSigned(
                    core.request("subject.teleport", "{}"), clock.instant()));
            assertEquals(ErrorCode.UNKNOWN_OPERATION.wireName(), errorCode(authenticated));

            // Unauthenticated: told nothing about which operations exist. An
            // unknown-operation refusal handed to an anonymous caller is a free
            // oracle for probing what a build supports.
            JsonNode anonymous = read(core, core.postSigned(
                    core.request("subject.teleport", "{}"),
                    clock.instant(),
                    "not-a-credential",
                    UUID.randomUUID().toString()));
            assertEquals(ErrorCode.UNKNOWN_CREDENTIAL.wireName(), errorCode(anonymous));
        }
    }

    @Test
    @DisplayName("every refusal is HTTP 200 with the reason in the envelope")
    void refusalsAreNotTransportFailures() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            // A protocol refusal is not a transport failure. Mapping refusals
            // onto status codes gives every intermediary -- proxy, CDN,
            // corporate filter -- an opinion about them.
            HttpResponse<String> response = core.postSigned(
                    core.request("connector.list", "{}"), clock.instant());
            assertEquals(200, response.statusCode());
            assertFalse(read(core, response).get(Wire.OK).asBoolean());
        }
    }

    // A nonce containing the field separator is NOT tested here. It cannot
    // reach this transport: java.net.http refuses to build a request with a
    // newline in a header value, and so does every conformant HTTP stack --
    // header injection is the reason. The defence still matters, because the
    // socket transport and the PHP client both build the canonical form
    // themselves, so it is asserted in SignedRequestVerifierTest where it is
    // reachable. Named here so the absence reads as a decision rather than an
    // oversight.

    @Test
    @DisplayName("a non-numeric timestamp is refused as malformed")
    void nonNumericTimestamp() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            JsonNode json = read(core, core.postRaw(core.request("heartbeat", "{}"), Map.of(
                    Wire.HEADER_AUTHORIZATION, "Bearer " + core.credential,
                    Wire.HEADER_TIMESTAMP, "yesterday",
                    Wire.HEADER_NONCE, UUID.randomUUID().toString(),
                    Wire.HEADER_SIGNATURE, "0".repeat(64))));

            assertFalse(json.get(Wire.OK).asBoolean());
            assertEquals(ErrorCode.MALFORMED.wireName(), errorCode(json));
        }
    }

    @Test
    @DisplayName("a bare token without the Bearer scheme is not accepted")
    void bareTokenRejected() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(clock)) {
            // Accepting both means two parsing paths, and the lenient one
            // eventually diverges from what the other implementation sends.
            String body = core.request("heartbeat", "{}");
            long timestamp = clock.instant().getEpochSecond();
            String nonce = UUID.randomUUID().toString();

            JsonNode json = read(core, core.postRaw(body, Map.of(
                    Wire.HEADER_AUTHORIZATION, core.credential,
                    Wire.HEADER_TIMESTAMP, String.valueOf(timestamp),
                    Wire.HEADER_NONCE, nonce,
                    Wire.HEADER_SIGNATURE, RequestSigner.sign(
                            core.credential.getBytes(StandardCharsets.UTF_8),
                            timestamp, nonce, body))));

            assertFalse(json.get(Wire.OK).asBoolean());
            assertEquals(ErrorCode.UNKNOWN_CREDENTIAL.wireName(), errorCode(json));
        }
    }

    @Test
    @DisplayName("the freshness window and the nonce store agree on their bound")
    void windowAndStoreAgree() {
        // Two halves of one control. If the store forgot sooner than the window
        // allowed, a request could be replayed inside the window it was still
        // valid for -- the gap would be invisible in every test that used a
        // single fixed clock.
        Duration window = Duration.ofSeconds(300);
        NonceStore store = new NonceStore(window);
        SignedRequestVerifier verifier = new SignedRequestVerifier(window, store);
        assertEquals(window, verifier.window());

        store.recordIfNew("n", TestCore.fixedClock().instant());
        store.sweep(TestCore.fixedClock().instant().plus(window).minusSeconds(1));
        assertEquals(1, store.size(), "forgotten while still inside the window it was valid for");
    }
}
