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
package dev.soulbind.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.soulbind.policy.Decision;
import dev.soulbind.policy.Effect;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.RequestSigner;
import dev.soulbind.protocol.SchemaVersion;
import dev.soulbind.protocol.Wire;
import dev.soulbind.sdk.transport.Transport;
import dev.soulbind.sdk.transport.TransportException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A connector's view of core.
 *
 * <p>Everything here sits <b>above</b> the transport seam and is exercised
 * against {@link dev.soulbind.sdk.transport.InMemoryTransport}: envelope
 * construction, signing, the distinction between a refusal and an outage, cache
 * population, and the fail-mode fallback. None of it needs a socket to test,
 * which is the point of the seam.
 *
 * <h2>The distinction this class exists to keep</h2>
 *
 * <p>A <b>refusal</b> is core saying no. A connector tells the person.
 * An <b>outage</b> is core not answering. A connector falls back to its cache
 * and then to its fail mode. Collapsing them turns "you may not" into "try
 * again later" — and turns a genuine denial into something a retry loop
 * eventually gets past.
 */
public final class SoulbindClient implements AutoCloseable {

    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String credential;
    private final Clock clock;
    private final DecisionCache cache;

    public SoulbindClient(
            Transport transport, String credential, Clock clock, DecisionCache cache) {
        this.transport = transport;
        this.credential = credential;
        this.clock = clock;
        this.cache = cache;
    }

    /** The outcome of a call: an answer, a refusal, or an outage. */
    public sealed interface Outcome {
        /**
         * Core answered.
         *
         * <p>Carries a {@link Payload}, not a JSON tree: the SDK hands
         * connectors values, so Jackson stays an implementation detail here
         * rather than becoming part of every connector's contract.
         */
        record Ok(Payload payload) implements Outcome {}

        /** Core answered, and the answer is no. */
        record Refused(ErrorCode code, String message) implements Outcome {}

        /** Core did not answer. Distinct from being refused BY it. */
        record Unreachable(String detail) implements Outcome {}
    }

    /**
     * Calls an operation.
     *
     * <p>Signs every request, because the signed transport requires it and the
     * socket transport ignores it — one call path rather than two that drift.
     */
    public Outcome call(String operation, Object payload) {
        String body;
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put(Wire.SCHEMA, SchemaVersion.CURRENT);
            root.put(Wire.OP, operation);
            root.put(Wire.ID, UUID.randomUUID().toString());
            root.set(Wire.PAYLOAD, mapper.valueToTree(payload == null ? Map.of() : payload));
            body = mapper.writeValueAsString(root);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // A request this connector could not even build is a programming
            // error here, not an outage. Reporting it as unreachable would send
            // an operator to check the network.
            throw new IllegalStateException("cannot build a " + operation + " request", e);
        }

        String responseBody;
        try {
            responseBody = transport.send(body);
        } catch (TransportException e) {
            return new Outcome.Unreachable(e.getMessage());
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            if (root == null || !root.isObject() || !root.has(Wire.OK)) {
                // A response that is not an envelope means something between
                // here and core is answering -- a proxy error page, a captive
                // portal. That is an outage, not a refusal: core never said no.
                return new Outcome.Unreachable("the response was not a protocol envelope");
            }
            if (root.get(Wire.OK).asBoolean()) {
                return new Outcome.Ok(new Payload(root.get(Wire.PAYLOAD)));
            }
            JsonNode error = root.get(Wire.ERROR);
            ErrorCode code = error == null
                    ? ErrorCode.INTERNAL
                    : ErrorCode.fromWireName(error.path(Wire.ERROR_CODE).asText())
                            .orElse(ErrorCode.INTERNAL);
            String message = error == null ? "" : error.path(Wire.ERROR_MESSAGE).asText("");
            return new Outcome.Refused(code, message);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Outcome.Unreachable("the response could not be parsed");
        }
    }

    /**
     * Asks whether an identity may pass a gate, with the cache and fail mode
     * behind it.
     *
     * <p>The order is: ask core; on an answer, cache it and return it; on an
     * outage, use an unexpired cached decision if there is one, and otherwise
     * let the fail mode decide.
     *
     * <p>A <b>refusal</b> is not an outage and does not reach the fail mode. If
     * core says this connector lacks the capability, falling back to a cached
     * allow would be using a stale answer to work around a permissions problem.
     */
    public DecisionCache.Answer decide(String gate, String platformKind, String platformId) {
        String ref = platformKind + ":" + platformId;

        Outcome outcome = call("decide", new DecidePayload(gate, platformKind, platformId));

        if (outcome instanceof Outcome.Ok ok) {
            Decision decision = toDecision(ok.payload());
            cache.store(gate, ref, decision, clock.instant());
            return new DecisionCache.Answer(decision, DecisionCache.Source.FRESH);
        }

        if (outcome instanceof Outcome.Refused refused) {
            // Core answered. A connector refused permission to ask must not
            // quietly serve a cached allow -- that is using a stale answer to
            // route around a permissions problem, and it would keep working
            // long enough for nobody to notice the capability was revoked.
            return new DecisionCache.Answer(
                    new Decision(
                            Effect.DENY,
                            Decision.Reason.DEFAULT,
                            "this connector may not ask: " + refused.code().wireName(),
                            0,
                            List.of()),
                    DecisionCache.Source.FRESH);
        }

        return cache.whenUnreachable(gate, ref, clock.instant());
    }

    private Decision toDecision(Payload payload) {
        Effect effect = Effect.fromConfigName(payload.text("effect"))
                // An unreadable effect denies. A decision this build cannot
                // parse must not open a gate.
                .orElse(Effect.DENY);

        List<String> missing = payload.texts("missingKinds");

        Decision.Reason reason;
        try {
            String raw = payload.text("reason");
            reason = Decision.Reason.valueOf(
                    (raw.isEmpty() ? "default" : raw)
                            .toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            reason = Decision.Reason.DEFAULT;
        }

        return new Decision(
                effect,
                reason,
                payload.text("detail"),
                (int) payload.number("ttlSeconds"),
                missing);
    }

    /** The signed headers for a request body, for a transport that needs them. */
    public Map<String, String> signingHeaders(String body) {
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        return Map.of(
                Wire.HEADER_AUTHORIZATION, "Bearer " + credential,
                Wire.HEADER_TIMESTAMP, String.valueOf(timestamp),
                Wire.HEADER_NONCE, nonce,
                Wire.HEADER_SIGNATURE,
                RequestSigner.sign(
                        credential.getBytes(StandardCharsets.UTF_8), timestamp, nonce, body));
    }

    public DecisionCache cache() {
        return cache;
    }

    @Override
    public void close() {
        transport.close();
    }

    private record DecidePayload(String gate, String platformKind, String platformId) {}
}
